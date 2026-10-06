# Changelog da API

Mudanças no contrato publicado em [`openapi.json`](openapi.json), da mais recente para a mais
antiga ([ADR 0012](adr/0012-contrato-openapi.md)).

Toda **breaking change** entra aqui no mesmo PR que a introduz: sem a entrada, o CI
(`tools/contract/check-breaking.sh`) recusa a mudança. Diga o que quebra, por quê e o que o
`duora-web` precisa mudar. Mudanças compatíveis podem entrar também, mas não são obrigatórias.

## 0.1.0 (2026-10-05)

Primeira versão publicada da spec: `POST /api/waitlist`, `GET /api/me`,
`GET /api/admin/waitlist/stats` e `GET`/`PATCH /api/me/profile` (edição com `If-Match`).
