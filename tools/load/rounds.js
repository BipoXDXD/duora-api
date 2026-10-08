// Sorteio da rodada (ADR 0017): o ADMIN inicia a rodada em 50 eventos em andamento ao mesmo tempo, cada um
// com 4 inscritos (200 contas no total). Cada evento recebe o pedido DUAS vezes em paralelo (duas abas do
// ADMIN, ou um clique duplo): exatamente um pedido cria a rodada (201) e sorteia, e o outro recebe a mesma
// rodada (200), sem segundo sorteio. A segunda onda repete o desenho com a rodada 2, que depende da 1 e
// não pode repetir nenhum par.
//
// Os eventos já em andamento vêm do preparo por SQL (sql/in-progress-events.sql), porque a API não
// permite criar evento que já começou. As invariantes finais também são conferidas no banco (run.sh).
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';
import {
  users, data, adminToken, call, bodyOf, hasExactlyTheKeys, isDocumentedBusy, logBusy, MAX_ATTEMPTS,
} from './lib.js';

const EVENTS = data.events.length;
const REQUESTS_PER_EVENT = 2;
const PEOPLE_PER_EVENT = 4;
const WAVE_TWO_START = '20s';

const created = new Counter('rounds_created'); // resposta final 201, uma por (evento, número)
const replayed = new Counter('rounds_replayed'); // resposta final 200: a rodada já existia
const busy = new Counter('rounds_busy_503');
const unexpected = new Counter('unexpected_responses');
const invariants = new Rate('invariants_ok');

function wave(number, startTime) {
  return {
    executor: 'per-vu-iterations',
    vus: EVENTS * REQUESTS_PER_EVENT,
    iterations: 1,
    startTime,
    maxDuration: '15s',
    exec: 'startRound',
    env: { ROUND: String(number) },
  };
}

export const options = {
  scenarios: {
    round1: wave(1, '0s'),
    round2: wave(2, WAVE_TWO_START),
  },
  thresholds: {
    // Valor inicial (tools/load/RESULTS.md): o pior p95 medido com a JVM fria foi 0,59 s (0,90 s numa execução extra).
    // O teto de espera pela chave da rodada é 5 s (JdbcRoundRepository.LOCK_TIMEOUT), e o sorteio de 4
    // pessoas é barato: um p95 de 1,5 s já diria que a fila do pool, e não o sorteio, passou a mandar.
    'http_req_duration{name:PUT round}': ['p(95)<1500'],
    // Os 503 documentados são aceitos, mas só como exceção: até 10% dos pedidos.
    rounds_busy_503: [`count<=${EVENTS * REQUESTS_PER_EVENT * 2 / 10}`],
    'rounds_created{round:1}': [`count==${EVENTS}`],
    'rounds_created{round:2}': [`count==${EVENTS}`],
    'rounds_replayed{round:1}': [`count==${EVENTS}`],
    'rounds_replayed{round:2}': [`count==${EVENTS}`],
    unexpected_responses: ['count==0'],
    invariants_ok: ['rate==1'],
  },
};

function putRound(eventId, number) {
  let response;
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    response = call('PUT', `/api/admin/events/${eventId}/rounds/${number}`, adminToken, null, 'PUT round');
    if (!isDocumentedBusy(response)) {
      return response;
    }
    busy.add(1, { round: String(number) });
    logBusy('PUT round', response);
    sleep(1);
  }
  return response;
}

export function startRound() {
  const number = Number(__ENV.ROUND);
  const event = data.events[Math.floor(exec.scenario.iterationInTest / REQUESTS_PER_EVENT)];
  const response = putRound(event.id, number);
  const tags = { round: String(number) };

  if (response.status === 201) {
    created.add(1, tags);
  } else if (response.status === 200) {
    replayed.add(1, tags);
  } else {
    unexpected.add(1);
    console.error(`resposta inesperada na rodada ${number}: ${response.status} ${response.body}`);
    return;
  }
  const body = bodyOf(response);
  invariants.add(check(response, {
    'resposta só com contagens': () => hasExactlyTheKeys(body, ['eventId', 'number', 'startedAt', 'pairCount', 'sittingOutCount']),
    'todos formaram par (4 pessoas = 2 pares)': () => body.pairCount === PEOPLE_PER_EVENT / 2 && body.sittingOutCount === 0,
    'o número é o pedido': () => body.number === number && body.eventId === event.id,
  }));
}

export function teardown() {
  const subjectOf = {};
  for (const account of data.users) {
    subjectOf[account.accountId] = account.subject;
  }
  const tokenOf = {};
  for (const user of users) {
    tokenOf[user.subject] = user.token;
  }

  // O que cada pessoa vê do próprio sorteio: o par existe, é recíproco e não se repete na rodada 2.
  const partnerIn = {}; // `${rodada}|${subject}` -> subject do par
  for (const event of data.events) {
    for (const round of [1, 2]) {
      const admin = bodyOf(call('GET', `/api/admin/events/${event.id}/rounds/${round}`, adminToken, null, 'teardown GET round'));
      invariants.add(check(admin, { 'o ADMIN lê a rodada com 2 pares': (r) => r !== null && r.pairCount === 2 }));
    }
    for (const accountId of event.registrants) {
      const subject = subjectOf[accountId];
      for (const round of [1, 2]) {
        const pairing = call('GET', `/api/events/${event.id}/rounds/${round}/pairing`, tokenOf[subject], null, 'teardown GET pairing');
        const body = bodyOf(pairing);
        invariants.add(check(pairing, {
          'a pessoa lê o próprio par': (r) => r.status === 200 && hasExactlyTheKeys(body, ['eventId', 'roundNumber', 'partnerAccountId']),
          'o par não é nulo nem a própria pessoa': () => body.partnerAccountId !== null && body.partnerAccountId !== accountId,
        }));
        if (body && body.partnerAccountId) {
          partnerIn[`${round}|${subject}`] = subjectOf[body.partnerAccountId];
        }
      }
    }
  }
  for (const [key, partner] of Object.entries(partnerIn)) {
    const [round, subject] = key.split('|');
    invariants.add(check(partner, {
      'o par é recíproco': (p) => partnerIn[`${round}|${p}`] === subject,
      'o par da rodada 2 não repete o da rodada 1': (p) => round === '1' || partnerIn[`1|${subject}`] !== p,
    }));
  }
}
