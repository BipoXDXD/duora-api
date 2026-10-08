// Inscrição concorrente (ADR 0016): 100 contas com perfil completo se inscrevem ao mesmo tempo num
// evento de capacidade 50. Esperado: exatamente 50 inscritas, as outras 50 recebem 409 (lotado), e nenhuma
// resposta de erro além do 503 documentado (espera pelo lock acima de 2 s, com Retry-After: 1).
//
// Cada conta, depois de ter a resposta final, repete o mesmo PUT (o clique duplo): a inscrição que existe
// responde 200 com a mesma data, e a recusa continua sendo 409.
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';
import {
  users, adminToken, call, bodyOf, hasExactlyTheKeys, isDocumentedBusy, logBusy, MAX_ATTEMPTS,
} from './lib.js';

// Os padrões são os do plano (100 contas, capacidade 50); run.sh repassa REGISTRATION_ACCOUNTS e
// REGISTRATION_CAPACITY para testar o que acontece com mais contas ou um evento maior.
const ACCOUNTS = Number(__ENV.REGISTRATION_ACCOUNTS || 100);
const CAPACITY = Number(__ENV.REGISTRATION_CAPACITY || 50);

const created = new Counter('registrations_created'); // resposta final 201
const full = new Counter('registrations_full'); // resposta final 409
const busy = new Counter('registrations_busy_503'); // tentativas com o 503 documentado (foram repetidas)
const unexpected = new Counter('unexpected_responses'); // qualquer outra coisa
const invariants = new Rate('invariants_ok');

export const options = {
  scenarios: {
    register: { executor: 'per-vu-iterations', vus: ACCOUNTS, iterations: 1, maxDuration: '2m' },
  },
  thresholds: {
    // Valor inicial (tools/load/RESULTS.md): o pior p95 medido com a JVM fria foi 1,7 s (100 contas, uma
    // vCPU), e 2,5 s dá cerca de 40% de folga sem passar do teto de 2 s do lock somado à fila do pool.
    // Acima disso o 503 deixou de ser exceção. Com a JVM aquecida o p95 cai para 0,3 a 0,6 s.
    'http_req_duration{name:PUT registration}': ['p(95)<2500'],
    // Os 503 documentados são aceitos, mas só como exceção: até 10% das contas.
    registrations_busy_503: [`count<=${ACCOUNTS / 10}`],
    registrations_created: [`count==${CAPACITY}`],
    registrations_full: [`count==${ACCOUNTS - CAPACITY}`],
    unexpected_responses: ['count==0'],
    invariants_ok: ['rate==1'],
  },
};

export function setup() {
  const soon = Date.now() + 60 * 60 * 1000;
  const eventResponse = call('POST', '/api/admin/events', adminToken, {
    title: 'Carga: inscrição concorrente',
    description: 'Evento sintético do teste de carga.',
    startsAt: new Date(soon).toISOString(),
    endsAt: new Date(soon + 2 * 60 * 60 * 1000).toISOString(),
    capacity: CAPACITY,
  }, 'setup POST event');
  if (eventResponse.status !== 201) {
    throw new Error(`não foi possível criar o evento: ${eventResponse.status} ${eventResponse.body}`);
  }
  const eventId = bodyOf(eventResponse).id;
  const published = call('POST', `/api/admin/events/${eventId}:publish`, adminToken, null, 'setup publish event');
  if (published.status !== 200) {
    throw new Error(`não foi possível publicar o evento: ${published.status} ${published.body}`);
  }
  return { eventId };
}

function putRegistration(eventId, token, name) {
  let response;
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    response = call('PUT', `/api/events/${eventId}/registration`, token, null, name);
    if (!isDocumentedBusy(response)) {
      return response;
    }
    busy.add(1);
    logBusy('PUT registration', response);
    sleep(1); // o Retry-After documentado
  }
  return response;
}

export default function (state) {
  const user = users[exec.vu.idInTest - 1];
  const response = putRegistration(state.eventId, user.token, 'PUT registration');

  if (response.status === 201) {
    created.add(1);
    const body = bodyOf(response);
    invariants.add(check(response, {
      '201 traz Location': (r) => (r.headers['Location'] || '').endsWith(`/api/events/${state.eventId}/registration`),
      '201 traz só eventId e registeredAt': () => hasExactlyTheKeys(body, ['eventId', 'registeredAt']),
    }));
    const again = putRegistration(state.eventId, user.token, 'PUT registration repeat');
    invariants.add(check(again, {
      'repetir devolve a mesma inscrição (200)': (r) => r.status === 200 && (bodyOf(r) || {}).registeredAt === body.registeredAt,
    }));
  } else if (response.status === 409) {
    full.add(1);
    const again = putRegistration(state.eventId, user.token, 'PUT registration repeat');
    invariants.add(check(again, { 'lotado continua 409': (r) => r.status === 409 }));
  } else {
    unexpected.add(1);
    console.error(`resposta inesperada: ${response.status} ${response.body}`);
  }
}

export function teardown(state) {
  const event = bodyOf(call('GET', `/api/admin/events/${state.eventId}`, adminToken, null, 'teardown GET event'));
  invariants.add(check(event, {
    'o ADMIN conta exatamente a capacidade': (e) => e.registrationCount === CAPACITY,
  }));

  // A mesma conta, vista de fora: cada conta lê a própria inscrição.
  let registered = 0;
  for (const user of users.slice(0, ACCOUNTS)) {
    const mine = call('GET', `/api/events/${state.eventId}/registration`, user.token, null, 'teardown GET registration');
    if (mine.status === 200) {
      registered += 1;
    } else if (mine.status !== 404) {
      unexpected.add(1);
    }
  }
  invariants.add(check(registered, { 'exatamente a capacidade em contas lê a própria inscrição': (n) => n === CAPACITY }));
}
