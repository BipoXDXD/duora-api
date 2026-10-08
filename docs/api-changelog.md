# Changelog da API

Mudanças no contrato publicado em [`openapi.json`](openapi.json), da mais recente para a mais
antiga ([ADR 0012](adr/0012-contrato-openapi.md)).

Toda **breaking change** entra aqui no mesmo PR que a introduz: sem a entrada, o CI
(`tools/contract/check-breaking.sh`) recusa a mudança. Diga o que quebra, por quê e o que o
`duora-web` precisa mudar. Mudanças compatíveis podem entrar também, mas não são obrigatórias.

## Não publicado

Mudança compatível ([ADR 0015](adr/0015-bloqueio-e-denuncia.md)):

- `POST /api/reports` (`fileReport`): a cota de denúncias passa a usar o mesmo limite por conta das outras
  operações. Status, `Retry-After` do `429` e corpo continuam os mesmos, mas o `detail` do `429` passa de
  "report quota exceeded; try again later" para "rate limit exceeded; try again later", e o `503` de cota
  não contável ganha `Retry-After: 1`. O `duora-web` não deve depender do texto do `detail`.

Mudança compatível ([ADR 0016](adr/0016-eventos-e-inscricoes.md), [ADR 0017](adr/0017-pareamento.md)):

- Limite por conta nas operações que travam o evento ou disputam a rodada: `PUT` e `DELETE
  /api/events/{eventId}/registration` dividem 60 chamadas por hora (`registerForEvent` e
  `cancelMyRegistration`), e `PUT /api/admin/events/{eventId}/rounds/{number}` dá 30 por hora a cada conta
  ADMIN (`startRound`). Acima do limite, as três respondem 429 com `Retry-After` (segundos, no máximo
  86400) e ProblemDetail. `cancelMyRegistration` ganha também o 503 com `Retry-After: 1` para quando o
  limite não pôde ser contado (nada é feito); o 503 de `registerForEvent` e de `startRound` ganha essa causa
  além do evento ocupado ou da rodada em disputa. Repetições idempotentes (200 da mesma inscrição ou rodada)
  também gastam o limite. O `duora-web` deve respeitar o `Retry-After` e não repetir chamadas em laço.

Mudanças compatíveis ([ADR 0019](adr/0019-decisao-privada-e-conexoes.md)):

- Decisão privada depois da rodada: `PUT /api/events/{eventId}/rounds/{number}/decision` com
  `{"interested": true|false}` (só booleano JSON; `1` ou `"true"` são `400` `INVALID_FORMAT`). `201` com
  `Location` na primeira vez, `200` repetindo a mesma escolha, `409` com a outra escolha (a decisão é
  final), `404` para quem não formou par na rodada, `503` com `Retry-After` se a decisão do par na mesma
  rodada demorar além do teto. A resposta (`DecisionResponse`) só fala de quem chama, nunca do par.
- Limite por conta na decisão: `PUT /api/events/{eventId}/rounds/{number}/decision`
  (`decideAboutMyPartner`) aceita 120 chamadas por hora por conta, repostas aos poucos. Acima do limite,
  responde 429 com `Retry-After` (segundos, no máximo 86400) e ProblemDetail, sem gravar nada. O 503 da
  operação ganha a causa de o limite não ter podido ser contado, além da espera pela decisão do par
  (`Retry-After: 1` nas duas). Repetições idempotentes (200 da mesma escolha) também gastam o limite. O
  `duora-web` deve respeitar o `Retry-After` e não repetir a chamada em laço.
- `GET /api/events/{eventId}/rounds/{number}/decision`: a própria decisão, ou `404`.
- `GET /api/me/connections`: as próprias conexões, `{items: [{accountId, connectedAt}], nextPageToken}`,
  com `maxPageSize` de 1 a 100 (padrão 20) e `pageToken`. Uma conexão aparece quando as duas pessoas dizem
  sim e nenhum bloqueio as separa.

Mudanças compatíveis ([ADR 0020](adr/0020-motivo-das-recusas-no-problem-detail.md) e
[ADR 0017](adr/0017-pareamento.md)):

- Todo `409` e o `403` de regra da inscrição (`PUT /api/events/{eventId}/registration`) passam a usar o
  schema `RefusalProblemDetail`: o `ProblemDetail` com o membro opcional `reason`, de uma lista fechada
  (`EVENT_NOT_PUBLISHED`, `EVENT_ALREADY_PUBLISHED`, `EVENT_CANCELLED`, `EVENT_STARTED`, `EVENT_ENDED`,
  `EVENT_FULL`, `EVENT_NOT_UNDERWAY`, `ROUND_OUT_OF_SEQUENCE`, `PROFILE_INCOMPLETE`, `UNDERAGE`,
  `BIRTH_DATE_ALREADY_SET`, `DECISION_ALREADY_MADE`). O status e o `detail` continuam os mesmos, exceto num caso: inscrever-se,
  sair ou publicar depois do **fim** do evento responde `detail` "the event has already ended" (antes,
  "already started"), com `reason` `EVENT_ENDED`. O `duora-web` pode escolher a mensagem pelo `reason`,
  tratando motivo desconhecido ou ausente como recusa genérica do status.
- `EventResponse` ganha `currentRound` (inteiro de 1 a 100 ou `null`, sempre presente): o número da última
  rodada iniciada. Em `GET /api/events` é sempre `null`, porque a lista só traz eventos que ainda não
  começaram. O front pode deixar de pedir o número da rodada à pessoa e ler o par em
  `GET /api/events/{eventId}/rounds/{currentRound}/pairing`.

Mudança compatível ([ADR 0018](adr/0018-erros-de-campo-no-problem-detail.md)):

- O 400 de validação do corpo (`POST /api/waitlist`, `PATCH /api/me/profile`, `POST /api/reports`,
  `POST /api/admin/events`) ganha o membro `errors`, uma lista de `{field, code}` (schemas
  `ValidationProblemDetail` e `FieldError`). `code` é de uma lista fechada; `field` é o nome da propriedade
  JSON e fica ausente quando o corpo inteiro não pôde ser lido (`MALFORMED_BODY`). O `detail` continua
  igual. O `duora-web` pode trocar a leitura do texto do `detail` pelo `field` e pelo `code`, tratando
  code desconhecido como erro genérico do campo.

Mudança compatível ([ADR 0002](adr/0002-front-web-com-bff.md)):

- `POST /logout` entra na spec (`operationId` `logout`, tag `session`): 200 com `{"logoutUrl": "..."}`,
  a URL de logout do Entra para o front navegar até ela, ou 403 sem o token CSRF. O comportamento já
  existia (rota do Spring Security); só passou a ser documentado, com o schema `LogoutResponse`.

Mudanças compatíveis ([ADR 0015](adr/0015-bloqueio-e-denuncia.md)):

- Bloqueio entre contas: `POST /api/accounts/{accountId}:block` e `:unblock` (204, idempotentes) e
  `GET /api/me/blocked-accounts`, paginada por `maxPageSize` e `pageToken`.
- Denúncia: `POST /api/reports` (201 com `Location`; 429 com `Retry-After` acima da cota diária) e
  `GET /api/reports/{id}`, só para quem denunciou.
- Path ou query que não converte para o tipo do parâmetro responde 400 com `detail` sem o valor
  recebido; query string malformada (`?=null`) deixa de responder 500.

Mudanças compatíveis ([ADR 0016](adr/0016-eventos-e-inscricoes.md)):

- Eventos para o ADMIN: `POST /api/admin/events` (201 com `Location`; o evento nasce rascunho),
  `GET /api/admin/events/{id}` e as ações `:publish` e `:cancel` (409 para estado inválido).
- Eventos para quem está logado: `GET /api/events`, paginada por `maxPageSize` (1 a 50) e `pageToken`, como
  `GET /api/me/blocked-accounts`, e `GET /api/events/{id}` (rascunho responde 404).
- Inscrição: `PUT /api/events/{eventId}/registration` (201 com `Location` na primeira vez, 200 nas
  repetições; 403 com perfil incompleto; 409 com evento cancelado, começado ou lotado; 503 com
  `Retry-After`), `GET` e `DELETE` (204, idempotente) na mesma rota, e `GET /api/me/registrations`.

Mudanças compatíveis ([ADR 0017](adr/0017-pareamento.md)):

- Rodadas de pareamento para o ADMIN: `PUT /api/admin/events/{eventId}/rounds/{number}` (sem corpo; 201
  com `Location` na primeira vez, 200 com a mesma rodada nas repetições; 409 com o evento fora do horário,
  cancelado ou rascunho, ou sem a rodada anterior; 503 com `Retry-After`) e `GET` na mesma rota. As duas
  devolvem só contagens (`pairCount`, `sittingOutCount`), nunca quem formou par com quem.
- O próprio par: `GET /api/events/{eventId}/rounds/{number}/pairing`, com `partnerAccountId` (`null` para
  quem ficou de fora); 404 igual para rodada inexistente e para quem não estava no sorteio.

## 0.1.0 (2026-10-05)

Primeira versão publicada da spec: `POST /api/waitlist`, `GET /api/me`,
`GET /api/admin/waitlist/stats` e `GET`/`PATCH /api/me/profile` (edição com `If-Match`).
