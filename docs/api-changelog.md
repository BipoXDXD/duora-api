# Changelog da API

Mudanças no contrato publicado em [`openapi.json`](openapi.json), da mais recente para a mais
antiga ([ADR 0012](adr/0012-contrato-openapi.md)).

Toda **breaking change** entra aqui no mesmo PR que a introduz: sem a entrada, o CI
(`tools/contract/check-breaking.sh`) recusa a mudança. Diga o que quebra, por quê e o que o
`duora-web` precisa mudar. Mudanças compatíveis podem entrar também, mas não são obrigatórias.

## Não publicado

Denúncia de mensagem do chat ([ADR 0021](adr/0021-chat-temporario-e-reconexao.md), fatia 3). Mudança compatível,
só acréscimos:

- `POST /api/events/{eventId}/rounds/{number}/chat/messages/{seq}:report` (`reportRoundChatMessage`) com
  `{"reason": "...", "description": "..."}`: os mesmos motivos e regras do relato de `fileReport` (`OTHER` exige
  descrição; até 1000 caracteres, sem invisíveis). Cria uma denúncia contra o par da rodada e guarda, para a
  moderação, uma cópia da mensagem que sobrevive ao expurgo do chat. `201` com `Location` em `/api/reports/{id}`
  (legível por `getMyReport`) e o corpo `ChatMessageReport`, com os mesmos campos de `getMyReport`
  (`{id, reportedAccountId, reason, description, status, createdAt}`), sem o texto da mensagem. `400` para a
  própria mensagem, posição fora de 1 a 300 ou corpo inválido; `404` para posição sem mensagem ou para quem não
  formou par na rodada (o mesmo `detail` das rotas do chat); `429` e `503` da cota de denúncias, que é a mesma de
  `fileReport` (10 por dia, somando as duas rotas). Vale com o chat fechado e depois de bloquear o par, até o
  expurgo, 24 h depois do fim do evento; depois dele, a posição responde `404`.
- `GET .../chat/messages/{seq}:report` e outros métodos na ação respondem `405`; a leitura da mensagem continua em
  `GET .../chat/messages/{seq}`.

Expurgo do chat (fatia 4): sem mudança no contrato. 24 h depois do fim agendado do evento, o chat e as mensagens
são apagados; daí em diante a lista de mensagens volta vazia, a mensagem e a denúncia de mensagem respondem
`404`, e `getMyRoundChat` mostra um chat vazio e fechado, com outro `chatId`.

Chat temporário da rodada ([ADR 0021](adr/0021-chat-temporario-e-reconexao.md), fatias 1 e 2):

- **Quebra apontada pelo oasdiff, compatível pela [ADR 0020](adr/0020-motivo-das-recusas-no-problem-detail.md):**
  o `reason` do `RefusalProblemDetail` ganha `CHAT_CLOSED` e `IDEMPOTENCY_KEY_REUSED`. Como o schema é o mesmo
  em todo `409`, o oasdiff acusa `response-property-enum-value-added` em todas as operações com `409`, mas
  só as rotas novas do chat devolvem esses valores. O `duora-web` já deve tratar `reason` desconhecido como
  recusa genérica do status; para usar o chat, precisa regenerar os tipos.
- `GET /api/events/{eventId}/rounds/{number}/chat` (`getMyRoundChat`): `{chatId, open, lastSeq}`. O chat do par
  da rodada existe desde o sorteio; `open` diz se ele aceita mensagens agora (fecha quando a rodada seguinte
  começa, quando o evento acaba, com 300 mensagens ou com um bloqueio entre os dois, sem dizer qual) e
  `lastSeq` é a posição da última mensagem. `404` igual para quem não formou par, rodada ou evento inexistente.
- `GET .../chat/messages?afterSeq=&maxPageSize=` (`listMyRoundChatMessages`): `{items: [{seq, fromMe, text,
  sentAt}], nextAfterSeq}`, em ordem crescente de `seq`, `afterSeq` de 0 a 300 (padrão 0), `maxPageSize` de 1 a
  100 (padrão 50). O cursor é a posição, transparente, e não um `pageToken` opaco: a sequência não tem lacunas,
  então lacuna no cliente quer dizer mensagem perdida. O cliente faz polling com `afterSeq` igual à maior
  posição vista (a cada 2 s com a aba visível) até `nextAfterSeq` vir `null`.
- `GET .../chat/messages/{seq}` (`getMyRoundChatMessage`): uma mensagem, ou `404`.
- `POST .../chat/messages` (`sendRoundChatMessage`) com o header `Idempotency-Key` (UUID, obrigatório) e
  `{"text": "..."}` (1 a 500 caracteres depois de NFC e sem espaço nas pontas; controle e invisíveis são `400`
  com `errors`). `201` com `Location` na primeira vez; `200` com a mesma mensagem ao repetir a chave com o mesmo
  texto, mesmo depois de o chat fechar; `409` com `reason` `IDEMPOTENCY_KEY_REUSED` para a chave com outro texto
  e `CHAT_CLOSED` com o chat fechado. Limite de 20 envios por minuto por conta, repetições incluídas (`429`
  com `Retry-After`); `503` com `Retry-After: 1` se outro envio segurar o chat além do teto ou se o limite não
  puder ser contado. O `duora-web` gera a chave ao criar o rascunho e a reutiliza em todo reenvio.

Mudança compatível ([ADR 0002](adr/0002-front-web-com-bff.md)):

- `GET /api/me` (`getCurrentUser`): a resposta ganha `roles`, lista dos papéis de quem está logado, sempre presente
  (`[]` para o usuário comum, `["ADMIN"]` para o administrador; hoje o único valor possível é `ADMIN`). Vem das
  mesmas authorities que a segurança aplica nas rotas, pela sessão web e pelo bearer; papel que o Entra emita e a
  API não conheça não aparece. O `duora-web` pode usar `roles` para mostrar ou esconder a área administrativa, sem
  deixar de tratar o `403` das rotas, e deve ignorar valor de papel que não conheça.

Mudança compatível ([ADR 0016](adr/0016-eventos-e-inscricoes.md)):

- `GET /api/admin/events` (`listAdminEvents`, tag `admin-events`, só ADMIN): a lista de todos os eventos, rascunhos
  incluídos, do início mais distante ao mais antigo, no envelope `{items, nextPageToken}` com itens no formato do
  `AdminEventResponse` (`status` e `registrationCount`, nunca quem se inscreveu). Parâmetros opcionais: `maxPageSize`
  de 1 a 50 (padrão 20), `pageToken` e `status` (`DRAFT`, `PUBLISHED` ou `CANCELLED`; sem ele, todos). Valor fora
  disso, ou token que a API não gerou, é `400`; sem o papel, `403`. Evento encerrado continua na lista. O `duora-web`
  pode trocar a leitura de evento a evento por esta rota na área administrativa.

Mudança compatível ([ADR 0015](adr/0015-bloqueio-e-denuncia.md), [ADR 0011](adr/0011-conta-e-perfil.md)):

- Limite por conta no bloqueio e na edição do perfil: `POST /api/accounts/{accountId}:block` (`blockAccount`) e
  `:unblock` (`unblockAccount`) dividem 60 chamadas por hora, e `PATCH /api/me/profile` (`editMyProfile`) aceita
  120 por hora, repostas aos poucos. Acima do limite, as três respondem 429 com `Retry-After` (segundos, no
  máximo 86400) e ProblemDetail, sem gravar nada, e ganham o 503 com `Retry-After: 1` para quando o limite não
  pôde ser contado. Repetições idempotentes e o `404` de `:block` para conta inexistente também gastam o limite;
  o `GET` do perfil e a lista de bloqueios não gastam. O `duora-web` deve respeitar o `Retry-After` e não
  repetir chamadas em laço.

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
