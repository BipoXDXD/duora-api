// Chat temporário da rodada (ADR 0021, fatia 7): 50 eventos em andamento, cada um com 2 inscritos e a rodada 1
// sorteada, dão 50 pares e 100 participantes. Cada participante se comporta como o front: lê o chat ao abrir a
// tela, faz polling de `GET .../chat/messages?afterSeq=<maior seq visto>` a cada 2 s e envia uma mensagem a
// cada 10 a 20 s (com jitter) com uma `Idempotency-Key` nova; em ~5% dos envios repete o POST com a mesma
// chave e o mesmo texto, como um retry depois de um timeout (esperado: 200 e a mesma mensagem).
//
// O sorteio da rodada 1 é preparo (setup), feito pela API do ADMIN. Os eventos em andamento vêm do preparo
// por SQL (sql/in-progress-events.sql, 2 inscritos por evento), porque a API não cria evento que já começou.
//
// Cada participante termina com uma linha `chatvu|<subject>|<maior seq lido>|<envios aceitos>` no log do k6;
// o run.sh compara essas linhas com o banco (sql/invariants-chat.sql): ninguém perde mensagem e o total
// gravado é o total de envios únicos aceitos.
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';
import {
  users, data, adminToken, call, bodyOf, hasExactlyTheKeys, isDocumentedBusy, logBusy, MAX_ATTEMPTS,
} from './lib.js';

const DURATION_SECONDS = Number(__ENV.CHAT_SECONDS || 300);
const POLL_PERIOD_MS = Number(__ENV.CHAT_POLL_MS || 2000); // 2 s é o polling do front (ADR 0021); menor = teste de folga
const SEND_MIN_MS = 10000;
const SEND_MAX_MS = 20000;
const STOP_SENDING_BEFORE_END_MS = 20000; // sobra tempo para todos lerem a última mensagem antes do fim
const RETRY_SHARE = 0.05;
const PARTICIPANTS = data.events.length * 2;

const polls = new Counter('chat_polls');
const sends = new Counter('chat_sends'); // POSTs de mensagens novas (as repetições deliberadas não entram)
const created = new Counter('chat_messages_created'); // resposta final 201
const replayed = new Counter('chat_messages_replayed'); // repetição deliberada: 200 com a mesma mensagem
const busy = new Counter('chat_busy_503');
const busyRate = new Rate('chat_send_busy');
const unexpected = new Counter('unexpected_responses');
const invariants = new Rate('invariants_ok');

export const options = {
  scenarios: {
    participants: {
      executor: 'per-vu-iterations',
      vus: PARTICIPANTS,
      iterations: 1,
      maxDuration: `${DURATION_SECONDS + 120}s`,
    },
  },
  thresholds: {
    // Valores iniciais (tools/load/RESULTS.md): 40% acima do pior p95 medido com a JVM fria em 3 subidas (105 ms
    // no polling e 209 ms no envio, 100 participantes a cada 2 s). Com a JVM aquecida o p95 fica em 7 a 18 ms e
    // 14 a 42 ms. Um p95 acima disso na carga do front já diria que a API ficou sem CPU, e não que a JVM esquentou.
    'http_req_duration{name:GET chat messages}': ['p(95)<150'],
    'http_req_duration{name:POST chat message}': ['p(95)<300'],
    // Os 503 documentados são aceitos, mas só como exceção: até 5% dos envios.
    chat_send_busy: ['rate<=0.05'],
    unexpected_responses: ['count==0'],
    invariants_ok: ['rate==1'],
  },
};

export function setup() {
  for (const event of data.events) {
    const round = call('PUT', `/api/admin/events/${event.id}/rounds/1`, adminToken, null, 'setup PUT round');
    if (round.status !== 201) {
      throw new Error(`rodada 1 do evento ${event.id}: ${round.status} ${round.body}`);
    }
  }
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : (r % 4) + 8).toString(16);
  });
}

function between(min, max) {
  return min + Math.random() * (max - min);
}

function eventOf(subject) {
  const accountId = data.users.find((account) => account.subject === subject).accountId;
  return data.events.find((event) => event.registrants.includes(accountId));
}

// Lê tudo depois de `seen` e devolve a nova maior posição. A posição de cada mensagem tem de ser a seguinte à
// anterior (sem lacuna), e `fromMe` só vale para o que esta conta enviou.
function poll(base, token, seen, mine) {
  let position = seen;
  for (;;) {
    const response = call('GET', `${base}/messages?afterSeq=${position}`, token, null, 'GET chat messages');
    polls.add(1);
    const body = bodyOf(response);
    if (response.status !== 200 || body === null) {
      unexpected.add(1);
      console.error(`polling inesperado: ${response.status} ${response.body}`);
      return position;
    }
    let contiguous = true;
    let authorship = true;
    for (const item of body.items) {
      contiguous = contiguous && item.seq === position + 1;
      authorship = authorship && item.fromMe === mine.has(item.seq) && (!item.fromMe || mine.get(item.seq) === item.text);
      position = item.seq;
    }
    invariants.add(check(body, {
      'a página tem só items e nextAfterSeq': (b) => hasExactlyTheKeys(b, ['items', 'nextAfterSeq']),
      'as posições lidas seguem sem lacuna': () => contiguous,
      'fromMe e o texto batem com o que a conta enviou': () => authorship,
    }));
    if (body.nextAfterSeq === null) {
      return position;
    }
  }
}

// POST com a mesma chave até sair do 503 documentado, como o front deve fazer.
function postMessage(base, token, key, text, name) {
  let response;
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    response = call('POST', `${base}/messages`, token, { text }, name, { 'Idempotency-Key': key });
    const isBusy = isDocumentedBusy(response);
    busyRate.add(isBusy);
    if (!isBusy) {
      return response;
    }
    busy.add(1);
    logBusy(name, response);
    sleep(1);
  }
  return response;
}

// Envia uma mensagem nova e, em ~5% dos casos, repete o POST. Devolve a posição gravada, ou null se a
// resposta final não foi o 201 esperado.
function send(base, token, subject, number, mine) {
  const key = uuid();
  const text = `${subject} mensagem ${number}`;
  sends.add(1);
  const response = postMessage(base, token, key, text, 'POST chat message');
  const body = bodyOf(response);
  if (response.status !== 201 || body === null) {
    unexpected.add(1);
    console.error(`envio inesperado: ${response.status} ${response.body}`);
    return null;
  }
  created.add(1);
  mine.set(body.seq, text);
  invariants.add(check(response, {
    'o envio devolve só a mensagem': () => hasExactlyTheKeys(body, ['seq', 'fromMe', 'text', 'sentAt']),
    'a mensagem é minha e tem o texto enviado': () => body.fromMe === true && body.text === text,
    'o 201 traz o Location da mensagem': (r) => (r.headers['Location'] || '').endsWith(`/chat/messages/${body.seq}`),
  }));

  if (Math.random() < RETRY_SHARE) {
    const again = postMessage(base, token, key, text, 'POST chat message retry');
    const same = bodyOf(again);
    if (again.status === 200 && same !== null) {
      replayed.add(1);
    } else {
      unexpected.add(1);
      console.error(`repetição inesperada: ${again.status} ${again.body}`);
    }
    invariants.add(check(same, {
      'a repetição devolve a mesma mensagem': (m) => m !== null && m.seq === body.seq && m.text === text && m.fromMe === true,
    }));
  }
  return body.seq;
}

export default function () {
  const user = users[exec.vu.idInTest - 1];
  const base = `/api/events/${eventOf(user.subject).id}/rounds/1/chat`;
  const mine = new Map(); // posição -> texto, do que esta conta enviou
  let seen = 0;
  let sent = 0;

  // As pessoas não abrem a tela no mesmo instante.
  sleep(between(0, POLL_PERIOD_MS) / 1000);
  const startedAt = Date.now();
  const endAt = startedAt + DURATION_SECONDS * 1000;
  const stopSendingAt = endAt - STOP_SENDING_BEFORE_END_MS;
  let nextSendAt = startedAt + between(0, SEND_MAX_MS);

  const opened = call('GET', base, user.token, null, 'GET chat');
  invariants.add(check(opened, {
    'o chat abre para o par da rodada sorteada': (r) => r.status === 200 && (bodyOf(r) || {}).open === true,
  }));

  while (Date.now() < endAt) {
    const tickStart = Date.now();
    seen = poll(base, user.token, seen, mine);
    if (tickStart >= nextSendAt && tickStart < stopSendingAt) {
      if (send(base, user.token, user.subject, sent + 1, mine) !== null) {
        sent += 1;
      }
      nextSendAt = tickStart + between(SEND_MIN_MS, SEND_MAX_MS);
    }
    sleep(Math.max(0, POLL_PERIOD_MS - (Date.now() - tickStart)) / 1000);
  }

  // Quando todos pararam de enviar, quem leu tudo está na última posição do chat.
  seen = poll(base, user.token, seen, mine);
  const closing = call('GET', base, user.token, null, 'GET chat');
  const lastSeq = (bodyOf(closing) || {}).lastSeq;
  invariants.add(check(closing, {
    'li até a última mensagem do chat': () => lastSeq === seen,
  }));
  console.log(`chatvu|${user.subject}|${seen}|${sent}`);
}
