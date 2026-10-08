// Utilidades comuns aos cenários k6 (docs/adr/0022). Roda dentro do container do k6, onde run.sh copia
// os scripts e os dois arquivos gerados por execução para /work:
//   tokens.json  {admin, users: [{subject, token}]}   tokens JWT de teste, assinados com a chave da execução
//   data.json    {users: [{subject, accountId}], events: [{id}]}   ids lidos do banco depois do preparo
import http from 'k6/http';
import { SharedArray } from 'k6/data';

export const BASE_URL = __ENV.BASE_URL;

export const adminToken = JSON.parse(open('/work/tokens.json')).admin;
export const users = new SharedArray('users', () => JSON.parse(open('/work/tokens.json')).users);
export const data = JSON.parse(open('/work/data.json'));

// O servidor responde 503 com "Retry-After: 1" quando a espera por lock passa do teto (ADR 0016, 0017,
// 0019) e diz que repetir é seguro. O cliente de carga faz o mesmo que o front deve fazer.
export const MAX_ATTEMPTS = 5;

// Os cenários medem o tempo do pedido por nome de operação (tag "name"), para os thresholds e o
// relatório separarem as rotas sem depender da URL com o id.
export function call(method, path, token, body, name, extraHeaders) {
  const headers = Object.assign({ Authorization: `Bearer ${token}` }, extraHeaders || {});
  let payload = null;
  if (body !== undefined && body !== null) {
    payload = JSON.stringify(body);
    headers['Content-Type'] = 'application/json';
  }
  return http.request(method, `${BASE_URL}${path}`, payload, {
    headers,
    tags: { name },
    // Os 4xx e o 503 esperados fazem parte do cenário; quem decide se um status é falha é o cenário.
    responseCallback: http.expectedStatuses({ min: 200, max: 599 }),
  });
}

export function bodyOf(response) {
  try {
    return response.json();
  } catch (e) {
    return null;
  }
}

export function hasExactlyTheKeys(object, expected) {
  if (object === null || typeof object !== 'object') {
    return false;
  }
  const actual = Object.keys(object).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length && actual.every((key, i) => key === wanted[i]);
}

// 503 documentado: com "Retry-After: 1". Qualquer outro 503 é defeito.
export function isDocumentedBusy(response) {
  return response.status === 503 && response.headers['Retry-After'] === '1';
}

// Registra um 503 documentado com o tempo que o pedido levou e o corpo, para o relatório dizer se foi o
// teto de espera pelo lock (perto do teto) ou outra causa (limite que não pôde ser contado).
export function logBusy(operation, response) {
  console.warn(`503 em ${operation} após ${Math.round(response.timings.duration)} ms: ${response.body}`);
}

export function isDocumentedTooManyRequests(response) {
  return response.status === 429 && /^[0-9]+$/.test(response.headers['Retry-After'] || '');
}
