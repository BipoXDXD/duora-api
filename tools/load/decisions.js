// Decisão privada (ADR 0019): depois da rodada 1 de 50 eventos (200 contas, 100 pares), as 200 contas
// decidem ao mesmo tempo. Os pares são divididos em quatro grupos iguais, para a disputa do advisory lock
// acontecer em cada combinação possível de respostas:
//   0  os dois dizem sim            -> exatamente uma conexão
//   1  só o primeiro diz sim        -> nenhuma conexão
//   2  só o segundo diz sim         -> nenhuma conexão
//   3  os dois dizem não            -> nenhuma conexão
// ("primeiro" é a conta de menor id.) Esperado: tantas conexões quantos pares do grupo 0, cada uma vista
// pelas duas contas e por mais ninguém; nenhuma resposta de erro além do 503 documentado.
//
// O sorteio da rodada 1 é preparo (setup), feito pela API do ADMIN: não entra nas medições da decisão.
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';
import {
  users, data, adminToken, call, bodyOf, hasExactlyTheKeys, isDocumentedBusy, logBusy, MAX_ATTEMPTS,
} from './lib.js';

const PAIR_GROUPS = 4;

const decided = new Counter('decisions_created'); // resposta final 201
const busy = new Counter('decisions_busy_503');
const unexpected = new Counter('unexpected_responses');
const invariants = new Rate('invariants_ok');

export const options = {
  scenarios: {
    decide: { executor: 'per-vu-iterations', vus: users.length, iterations: 1, maxDuration: '2m' },
  },
  thresholds: {
    // Valor inicial (tools/load/RESULTS.md): o pior p95 medido com a JVM fria foi 1,75 s (200 pedidos de
    // uma vez, uma vCPU), e 2,5 s dá cerca de 40% de folga. O teto de espera pelo advisory lock é 2 s
    // (JdbcDecisionRepository.LOCK_TIMEOUT), e a disputa de verdade é só entre os dois lados de um par.
    // Com a JVM aquecida o p95 cai para 0,5 a 0,9 s.
    'http_req_duration{name:PUT decision}': ['p(95)<2500'],
    // Os 503 documentados são aceitos, mas só como exceção: até 10% das contas.
    decisions_busy_503: [`count<=${users.length / 10}`],
    decisions_created: [`count==${users.length}`],
    unexpected_responses: ['count==0'],
    invariants_ok: ['rate==1'],
  },
};

export function setup() {
  const subjectOf = {};
  for (const account of data.users) {
    subjectOf[account.accountId] = account.subject;
  }
  const tokenOf = {};
  for (const user of users) {
    tokenOf[user.subject] = user.token;
  }

  for (const event of data.events) {
    const round = call('PUT', `/api/admin/events/${event.id}/rounds/1`, adminToken, null, 'setup PUT round');
    if (round.status !== 201) {
      throw new Error(`rodada 1 do evento ${event.id}: ${round.status} ${round.body}`);
    }
  }

  // Quem formou par com quem, lido por cada conta.
  const pairs = {}; // `${menor id}|${maior id}` -> {first, second} (subjects)
  const eventOf = {};
  for (const event of data.events) {
    for (const accountId of event.registrants) {
      const subject = subjectOf[accountId];
      const pairing = call('GET', `/api/events/${event.id}/rounds/1/pairing`, tokenOf[subject], null, 'setup GET pairing');
      const partnerId = (bodyOf(pairing) || {}).partnerAccountId;
      if (pairing.status !== 200 || !partnerId) {
        throw new Error(`sem par para ${subject} no evento ${event.id}: ${pairing.status} ${pairing.body}`);
      }
      eventOf[subject] = event.id;
      const [lower, upper] = accountId < partnerId ? [accountId, partnerId] : [partnerId, accountId];
      pairs[`${lower}|${upper}`] = { lower, upper };
    }
  }

  const plan = {}; // subject -> {eventId, interested, partner, connects}
  let expectedConnections = 0;
  Object.keys(pairs).sort().forEach((key, index) => {
    const { lower, upper } = pairs[key];
    const group = index % PAIR_GROUPS;
    const lowerYes = group === 0 || group === 1;
    const upperYes = group === 0 || group === 2;
    const connects = lowerYes && upperYes;
    expectedConnections += connects ? 1 : 0;
    plan[subjectOf[lower]] = { eventId: eventOf[subjectOf[lower]], interested: lowerYes, partner: upper, connects };
    plan[subjectOf[upper]] = { eventId: eventOf[subjectOf[upper]], interested: upperYes, partner: lower, connects };
  });
  return { plan, expectedConnections, pairCount: Object.keys(pairs).length };
}

function putDecision(eventId, token, interested, name) {
  let response;
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    response = call('PUT', `/api/events/${eventId}/rounds/1/decision`, token, { interested }, name);
    if (!isDocumentedBusy(response)) {
      return response;
    }
    busy.add(1);
    logBusy('PUT decision', response);
    sleep(1);
  }
  return response;
}

export default function (state) {
  const user = users[exec.vu.idInTest - 1];
  const mine = state.plan[user.subject];
  const response = putDecision(mine.eventId, user.token, mine.interested, 'PUT decision');

  if (response.status !== 201) {
    unexpected.add(1);
    console.error(`resposta inesperada: ${response.status} ${response.body}`);
    return;
  }
  decided.add(1);
  const body = bodyOf(response);
  invariants.add(check(response, {
    // A resposta só conta a decisão de quem chamou, igual em qualquer grupo (ADR 0019, privacidade).
    '201 só com a própria decisão': () => hasExactlyTheKeys(body, ['eventId', 'roundNumber', 'interested', 'decidedAt']),
    '201 devolve a escolha enviada': () => body.interested === mine.interested && body.roundNumber === 1,
  }));

  const same = putDecision(mine.eventId, user.token, mine.interested, 'PUT decision repeat');
  invariants.add(check(same, {
    'repetir a mesma escolha devolve a mesma decisão (200)': (r) => r.status === 200 && (bodyOf(r) || {}).decidedAt === body.decidedAt,
  }));
  const other = putDecision(mine.eventId, user.token, !mine.interested, 'PUT decision other');
  invariants.add(check(other, { 'a outra escolha é recusada (409)': (r) => r.status === 409 }));

  const read = call('GET', `/api/events/${mine.eventId}/rounds/1/decision`, user.token, null, 'GET decision');
  invariants.add(check(read, {
    'a própria decisão se lê de volta': (r) => r.status === 200 && (bodyOf(r) || {}).interested === mine.interested,
  }));
}

export function teardown(state) {
  let seen = 0;
  for (const user of users) {
    const mine = state.plan[user.subject];
    const list = call('GET', '/api/me/connections', user.token, null, 'teardown GET connections');
    const items = (bodyOf(list) || {}).items || [];
    seen += items.length;
    invariants.add(check(list, {
      'conexão só quando os dois disseram sim': () => items.length === (mine.connects ? 1 : 0),
      'a conexão é com o par': () => !mine.connects || items[0].accountId === mine.partner,
    }));
  }
  invariants.add(check(seen, {
    'cada conexão aparece para as duas contas e mais ninguém': (n) => n === 2 * state.expectedConnections,
  }));
  console.log(`pares: ${state.pairCount}; conexões esperadas: ${state.expectedConnections}`);
}
