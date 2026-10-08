// Prepara as contas sintéticas pela própria API: o primeiro acesso autenticado abre a conta (ADR 0011) e
// o PATCH deixa o perfil completo (nome, região e maior de idade). Não mede nada: roda uma vez por
// execução, antes dos cenários, e só confere que o preparo deu certo.
import { check } from 'k6';
import exec from 'k6/execution';
import { users, adminToken, call, bodyOf } from './lib.js';

export const options = {
  scenarios: {
    seed: { executor: 'shared-iterations', vus: 20, iterations: users.length, maxDuration: '3m' },
  },
  thresholds: { checks: ['rate==1'] },
};

export function setup() {
  // A conta do ADMIN também nasce no primeiro acesso.
  const me = call('GET', '/api/me', adminToken, null, 'seed GET me admin');
  check(me, { 'ADMIN autenticado': (r) => r.status === 200 });
}

export default function () {
  const index = exec.scenario.iterationInTest;
  const user = users[index];
  const current = call('GET', '/api/me/profile', user.token, null, 'seed GET profile');
  const edited = call('PATCH', '/api/me/profile', user.token, {
    displayName: `Carga ${String(index + 1).padStart(3, '0')}`,
    birthDate: '1990-05-17',
    region: 'BR-SP',
  }, 'seed PATCH profile', { 'If-Match': current.headers['Etag'] });
  check(edited, {
    'perfil editado': (r) => r.status === 200,
    'perfil completo': (r) => (bodyOf(r) || {}).complete === true,
  });
}
