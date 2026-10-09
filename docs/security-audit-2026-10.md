# Auditoria de segurança da API (2026-10)

Data: 2026-10-08. Base: `main` em `22abc92` (PR #30). Módulos auditados: identity, profiles, trustsafety, events,
matching, connections, waitlist e config. O módulo `chat` (em desenvolvimento noutra branch) ficou fora.

Régua: a seção "Segurança e design de APIs" do `~/.claude/CLAUDE.md` e o `~/.claude/knowledge/api-security.md`,
com os itens 9 a 12 de Java/Spring. Regra sem teste não conta como cumprida: cada linha abaixo aponta o teste que
a garante, ou diz **LACUNA**. As lacunas claras foram fechadas nesta branch (`test/security-audit`); o que pede
decisão está em [Pendências de decisão](#pendências-de-decisão).

## Superfície auditada

| Módulo | Operação | Rota | Quem |
|---|---|---|---|
| profiles | `getCurrentUser` | `GET /api/me` | autenticado |
| profiles | `getMyProfile` / `editMyProfile` | `GET`, `PATCH /api/me/profile` | dono (rota sem id) |
| trustsafety | `blockAccount` / `unblockAccount` | `POST /api/accounts/{accountId}:block`, `:unblock` | autenticado |
| trustsafety | `listMyBlockedAccounts` | `GET /api/me/blocked-accounts` | dono |
| trustsafety | `fileReport` / `getReport` | `POST /api/reports`, `GET /api/reports/{id}` | autenticado / autor |
| events | catálogo | `GET /api/events`, `GET /api/events/{id}` | autenticado |
| events | inscrição | `PUT`, `GET`, `DELETE /api/events/{eventId}/registration` | dono (sub-recurso singular) |
| events | `listMyRegistrations` | `GET /api/me/registrations` | dono |
| events | admin | `POST /api/admin/events`, `GET /api/admin/events/{id}`, `POST …/{id}:publish`, `:cancel` | ADMIN |
| matching | rodada | `PUT`, `GET /api/admin/events/{eventId}/rounds/{number}` | ADMIN |
| matching | `getMyPairing` | `GET /api/events/{eventId}/rounds/{number}/pairing` | quem está na rodada |
| connections | decisão | `PUT`, `GET /api/events/{eventId}/rounds/{number}/decision` | dono (sub-recurso singular) |
| connections | `listMyConnections` | `GET /api/me/connections` | dono |
| waitlist | `joinWaitlist` | `POST /api/waitlist` | **pública** |
| waitlist | `getWaitlistStats` | `GET /api/admin/waitlist/stats` | ADMIN |
| config | probes | `GET /actuator/health`, `/liveness`, `/readiness` | **pública** |
| config | login e logout do BFF | `/oauth2/authorization/entra`, `/login/oauth2/code/entra`, `POST /logout` | filtros do Spring Security |
| config | documentação | `/api/admin/openapi`, `/api/admin/swagger-ui.html` | ADMIN, só com o perfil `api-docs` |

## Regra → rotas → teste

| Regra do checklist | Rotas | Teste que a garante |
|---|---|---|
| Nega por padrão: toda rota exige credencial, públicas em allowlist | todas as registradas no MVC (varredura) | `DenyByDefaultIT.everyRouteOutsideAllowlistRejectsRequestsWithoutCredentials` (sem credencial e com bearer inválido → 401 sem redirect); `enumerationFindsControllerActuatorAndPublicRoutes`; `WaitlistSecurityIT.anonymousIsRejectedOnRoutesOutsideAllowlist`; `OpenApiContractIT.everyOperationDeclaresTheAuthenticationTheApiEnforces` |
| BOLA: B nas rotas de A → 404, corpo sem dado de A | `GET /api/reports/{id}` | `ReportIT.nobodyReadsSomeoneElsesReport` (igual a id inexistente) |
| | inscrição `GET`/`DELETE`, `GET /api/me/registrations` | `RegistrationIT.anotherUserNeitherSeesNorCancelsTheRegistration` (A intacta após o `DELETE` de B) |
| | decisão `GET` | `ConnectionIT.thePartnerCannotReadTheDecision` |
| | decisão `PUT` (recurso de A intacto) | `ConnectionIT.onlyWhoFormedThePairDecides`; **novo** `ConnectionIT.anOutsiderLeavesTheDecisionOfThePairIntact` |
| | `GET …/pairing` | `RoundIT.someoneOutsideTheRoundCannotSeeAnyPairOfIt` |
| | `:unblock` | `BlockIT.unblockingRemovesOnlyTheCallersBlock`, `unblockingOnePairKeepsEveryOtherBlock` |
| | `GET`/`PATCH /api/me/profile` | `ProfileIT.anotherUserNeitherSeesNorChangesTheProfile`, `editByAnotherUserChangesOnlyTheirOwnProfile` |
| | listas `/api/me/*` | `BlockIT.listShowsOnlyTheCallersOwnBlocks`, `ConnectionIT.nobodySeesTheConnectionsOfOthers`, `RegistrationIT.anotherUserNeitherSeesNorCancelsTheRegistration` |
| | `pageToken` de outra conta | `BlockIT.pageTokenFromAnotherAccountOnlyPagesTheCallersOwnList`; **novos** `ConnectionIT.aPageTokenFromAnotherAccountOnlyPagesTheCallersOwnConnections`, `RegistrationIT.pageTokenFromAnotherAccountOnlyPagesTheCallersOwnRegistrations` (antes: LACUNA) |
| | rascunho de evento | `EventCatalogIT.draftLooksExactlyLikeAnEventThatDoesNotExist`, `RegistrationIT.draftLooksLikeAnEventThatDoesNotExist` |
| Papel: rota ADMIN com papel inferior → 403 | todas sob `/api/admin/**` (varredura) | **novo** `AdminRoleOnEveryAdminRouteIT.everyAdminRouteIsForbiddenToOtherRoles` (sem papel e com `ROLE_MODERATOR`, corpo de recusa exato); por rota, com banco inalterado: `AdminEventIT.userWithoutTheAdminRoleIsForbiddenAndChangesNothing`, `anotherRoleIsForbidden`, `RoundIT.aUserWithoutTheAdminRoleCannotStartNorReadARound`, `RoundRateLimitIT.callsRefusedByTheRoleDoNotSpendTheLimit`, `WaitlistSecurityIT.regularUserCannotReadStats`, `ApiDocsAccessIT.regularUserCannotReadTheDocs`, `BearerTokenValidationIT.forbidsValidTokenWithoutAdminRole` |
| Entrada: DTO estrito, campo desconhecido → 400 e banco inalterado | `PATCH /api/me/profile` | `ProfileIT.unknownFieldIsRejectedWithoutWriting` (`complete`, `version`, `accountId`, `adult`; **novo** caso `role`) |
| | `POST /api/reports` | `ReportIT.unknownFieldIsRejectedWithoutWriting` (`status`, `reporterAccountId`, `id`, `createdAt`) |
| | `POST /api/admin/events` | `AdminEventIT.serverOwnedFieldIsRejectedWithoutWriting` (`status`, `id`, `registrationCount`, `createdAt`, `version`) |
| | `PUT …/decision` | `ConnectionFieldErrorsIT.invalidBodyNamesTheFieldAndTheReason` (`partnerAccountId`, banco inalterado) |
| | `POST /api/waitlist` | `JoinWaitlistIT.joiningWithUnknownFieldIsRejectedWithoutWriting` |
| | `PUT` inscrição, `PUT` rodada, `:block`, `:unblock`, `:publish`, `:cancel` | sem corpo: nada é lido do corpo, então não há mass assignment (`OpenApiContractIT` documenta a ausência de corpo) |
| Transição de estado por ação dedicada | evento | `:publish` e `:cancel` (`AdminEventIT`, `CustomActionSecurityIT`, `OpenApiCustomActionIT`) |
| Limites em todo input (tamanho, faixa, página) | perfil, denúncia, evento, decisão, waitlist | `ProfileIT.invalidInputIsRejectedWithoutWriting`, `ProfileFieldErrorsIT`, `ReportIT.invalidInputIsRejectedWithoutWriting`, `descriptionOfAThousandCharactersIsAccepted`, `AdminEventIT.invalidInputIsRejectedWithoutWriting`, `acceptsValuesOnTheBorder`, `EventFieldErrorsIT`, `WaitlistFieldErrorsIT` |
| | paginação | `EventCatalogIT.invalidPageSizeIsABadRequest`, `invalidPageTokenIsABadRequest`, `BlockIT.pageSizeOutsideTheLimitsIsRejected`, `malformedPageTokenIsRejected`, `ConnectionIT.anInvalidPageSizeIsABadRequest`, `aPageTokenTheApiDidNotIssueIsABadRequest`, `RegistrationIT.invalidPageOfOwnRegistrationsIsABadRequest` |
| | path (id, número da rodada) | `ConnectionIT.anInvalidRoundNumberIsABadRequestAndWritesNothing`, `RoundIT.anInvalidRoundNumberIsABadRequestAndWritesNothing`, `AdminEventIT.invalidEventIdIsABadRequest`, `MalformedRequestInputIT` |
| NUL e controle → 4xx, nunca 500 | corpos | `ProfileIT`/`ProfileFieldErrorsIT` (nome e bio), `ReportIT`/`ReportFieldErrorsIT` (descrição), `AdminEventIT` (título), `JoinWaitlistIT.joiningWithControlOrInvisibleCharacterIsRejectedWithoutWriting`; descrição do evento só tinha NUL em `EventFieldErrorsIT.rejectedValueIsNotEchoed` → **novo** em `AdminEventIT.invalidInputIsRejectedWithoutWriting` (NUL, U+202E, U+200B, banco inalterado) |
| | path e query de todas as rotas | **novo** `ControlCharactersInRequestIT.controlCharacterInAPathVariableIsAClientError` (varredura, `%00 %01 %1B %7F`) e `controlCharacterInAPagingParameterIsABadRequest` (4 listas × `maxPageSize`/`pageToken`) (antes: LACUNA fora dos corpos) |
| Injeção de SQL tratada como dado | perfil, evento | `ProfileIT.sqlInTheNameIsStoredAsPlainText`, `AdminEventIT.sqlInTheTitleIsStoredAsPlainText`, `invalidInputIsRejectedWithoutWriting` ("horário com injeção de SQL") |
| Saída: conjunto exato de chaves (JSON estrito) | `GET /api/me` | `BearerTokenValidationIT.currentUserFromBearerTokenExposesOnlyDisplayNameAndProfileStatus`, `WebLoginIT.currentUserExposesOnlyDisplayNameAndProfileStatus` |
| | perfil | `ProfileIT` (`JsonCompareMode.STRICT`) |
| | denúncia `POST`/`GET` | `ReportIT.filingAReportRecordsItOpenForModeration` (corpo inteiro), `reporterReadsTheirOwnReport` |
| | bloqueios | `BlockIT.blockingAnotherAccountRecordsTheBlockWithoutBody`, `blockedAccountsAreListedNewestFirstAcrossPages` |
| | eventos (catálogo e admin) | `EventCatalogIT.listsOnlyPublishedEventsThatHaveNotStartedInStartOrder`, `readsAPublishedEvent`, `AdminEventIT.createsADraftAndAnswersWithItAndItsLocation`, `adminReadsTheDraft`, `publishingADraftMakesItPublished`, `cancellingAPublishedEvent` |
| | inscrições | `RegistrationIT.registeringCreatesTheRegistration`, `readsTheOwnRegistration`, `listsOwnRegistrationsOfEventsThatHaveNotEndedInStartOrder` |
| | rodada e pareamento | `RoundIT.startingTheFirstRoundPairsEveryRegistrant`, `theAdminReadsTheCountsOfARoundButNotWhoIsInIt`, `aPairedPersonSeesOnlyTheirPartner`, `whoSatOutSeesNoPartner` |
| | decisão e conexões | `ConnectionIT.aDecisionIsRecordedAndOnlyTellsAboutTheCaller`, `twoYesesFormOneConnectionThatBothSee` |
| | waitlist | `WaitlistSecurityIT.adminReadsStats`; `POST` → **novo** `JoinWaitlistIT.joiningWithAnEmailAlreadyListedLooksLikeJoiningWithANewOne` (corpo vazio) |
| | recusas (401/403/4xx) | `BearerTokenValidationIT.rejectsInvalidToken`, `WebLoginIT.logoutWithoutCsrfTokenIsRejectedAndKeepsSession`, `*FieldErrorsIT` |
| Erro sem stack, SQL, classe ou versão | 500 inesperado | `UnexpectedErrorIT.unexpectedFailureOnPublicRouteAnswersServerErrorAsProblemDetail`, `unexpectedFailureIsLoggedWithStackTraceUnderTheRequestId`; `MalformedRequestInputIT`; `MultipartContentTypeIT` |
| Valor recusado não é ecoado | corpos | `ProfileFieldErrorsIT`, `ReportFieldErrorsIT`, `EventFieldErrorsIT`, `ConnectionFieldErrorsIT`, `WaitlistFieldErrorsIT` (`rejectedValueIsNotEchoed`) |
| Log sem PII (canário) | waitlist, credenciais, cookies, query, tracing, code OAuth, perfil, denúncia | `SensitiveDataLoggingIT` (13 cenários anteriores) |
| | `GET /api/me` | **novo** `SensitiveDataLoggingIT.currentUserNameNeverReachesTheLog` → **achou bug 2** |
| | decisão `PUT`/`GET` | **novo** `DecisionLoggingIT.theDecisionNeverReachesTheLog` → **achou bug 1** |
| | rodadas, eventos, inscrições, conexões | sem dado pessoal em texto livre: ids, datas, título do evento (conteúdo do ADMIN). Ids de outra pessoa nos logs de DEBUG: ver pendência 2 |
| Nenhum dado sensível em path ou query | todas | por desenho: path só com UUID e número da rodada; query só `maxPageSize` e `pageToken`. `OpenApiContractIT.noOperationAsksTheClientForTheCallersAccount` |
| Rate limit por operação, 429 + `Retry-After` | `POST /api/waitlist` (IP) | `JoinWaitlistIT.joiningAboveRateLimitIsRejectedWithoutWriting`, `alternativeSpellingsOfRouteDoNotBypassRateLimit`, `ForwardedClientAddressIT` |
| | `POST /api/reports` (conta) | `ReportIT.reportingAboveTheDailyQuotaIsRejectedWithRetryAfterAndWithoutWriting` e vizinhos |
| | inscrição `PUT`/`DELETE` (conta) | `RegistrationRateLimitIT` |
| | `PUT` rodada (conta ADMIN) | `RoundRateLimitIT` |
| | `PUT` decisão (conta) | `DecisionRateLimitIT` |
| | limite que cai com o banco falha fechado | `AccountRateLimitIT.rejectsWhenTheStoreIsDown` e os `…WhenTheLimitCannotBeCounted` |
| | login, reset, envio de código | delegados ao Entra External ID (a API não tem login próprio) |
| | `PATCH /api/me/profile`, `:block`, `:unblock` | **LACUNA de política**: ver pendência 1 |
| JWT: algoritmo fixo, assinatura, `exp`, `iss`, `aud` | porta bearer | `BearerTokenValidationIT.rejectsInvalidToken`: expirado, sem `exp`, `aud` de outro app, `iss` de outro tenant, sem `oid`, outra chave com o mesmo `kid`, payload adulterado, `alg none`, `alg nonE`, HS256 com a chave pública, lixo; `RequiredAuthenticationSettingsIT` |
| CSRF em toda mutação da sessão web | todas as mutações do MVC (varredura) | **novo** `CsrfOnEveryMutationIT.everyMutationOfTheWebSessionRequiresTheCsrfToken` (sem token → 403 de segurança; com token → passa da segurança). Antes só havia teste por rota em perfil, `:block`, denúncia, inscrição, decisão, rodada e criação de evento: `:unblock`, `:publish` e `:cancel` eram LACUNA. Logout: `WebLoginIT.logoutWithoutCsrfTokenIsRejectedAndKeepsSession` |
| | dispensa da waitlist | decisão registrada em `SecurityConfiguration` (rota anônima); a varredura a mantém na allowlist explícita |
| Cookie `HttpOnly`, `Secure`, `SameSite` | sessão | `WebLoginIT.sessionCookieIsHostOnlyHttpOnlySecureAndLax`; `MalformedSessionCookieIT` |
| Sessão nova no login, logout invalida | sessão | `WebLoginIT.loginRedirectsToFrontAndRotatesSessionId`, `preLoginSessionIsUselessAfterLogin`, `sessionCookieIsUselessAfterLogout`, `logoutEndsSessionHereAndAnswersTheEntraLogoutUrl` |
| OAuth: PKCE, `state`, sem refresh token | login | `WebLoginIT.rejectsLoginWithInvalidTokens`, `loginRequestsNoRefreshToken` |
| Headers de proteção e CORS | respostas com dado pessoal e recusas | **novo** `SecurityHeadersIT` (`Cache-Control: no-store`, `nosniff`, `X-Frame-Options: DENY`, HSTS em HTTPS; nenhuma origem de fora recebe `Access-Control-Allow-*`) (antes: LACUNA) |
| Docs e actuator fechados | `/api/admin/openapi`, swagger, actuator | `ApiDocsDisabledByDefaultIT`, `ApiDocsAccessIT`, `HealthProbesIT.probeAnswersWithoutCredentialsAndShowsOnlyTheStatus` |
| Segredos: boot falha sem eles; scanner no CI | config | `RequiredAuthenticationSettingsIT`, `RequiredRateLimitSettingsIT`, `RequiredTrustedProxySettingsIT`; gitleaks em `.github/workflows/ci.yml` |
| Contrato: Spectral OWASP, Schemathesis, `oasdiff` | spec | `.github/workflows/ci.yml`, `contract-fuzz.yml`; `OpenApiContractIT.committedSpecMatchesTheGeneratedOne` |
| Concorrência e idempotência | inscrição | `RegistrationIT.concurrentRegistrationsNeverExceedTheCapacity`, `concurrentRepeatedRegistrationsCreateOnlyOne`, `registrationRacingTheEventCancellationEndsConsistent`, `registrationThatWaitsTooLongForTheEventLockIsRefused` |
| | rodada | `RoundIT.concurrentStartsOfTheSameRoundCreateASingleRound`, `aRoundStartedTogetherWithThePreviousOneNeverExistsWithoutIt`, `aRoundStartIsRefusedWhenAnotherRequestHoldsItTooLong` |
| | decisão | `ConnectionIT.simultaneousYesesCreateExactlyOneConnection`, `simultaneousRepeatsOfTheSameDecisionRecordItOnce`, `aDecisionIsRefusedWhenThePartnersTakesTooLong` |
| | bloqueio, perfil, publicação, conta | `BlockIT.concurrentBlocksOfTheSamePairKeepASingleBlock`, `ProfileIT.concurrentEditsFromTheSameVersionKeepOnlyOne`, `AdminEventIT.concurrentPublishesPublishOnce`, `AccountProvisioningIT.concurrentFirstRequestsOpenASingleAccount` |
| | waitlist | **novo** `JoinWaitlistIT.simultaneousJoinsWithTheSameEmailKeepSingleEntry` (antes: só repetição sequencial) |
| PUT/PATCH com `If-Match` | perfil | `ProfileIT.editWithoutIfMatchIsRejectedWithoutWriting`, `editBasedOnAnOutdatedReadIsRejectedAndKeepsTheNewerEdit`; `PUT` de inscrição e de rodada são idempotentes pela chave, sem estado a sobrescrever (ADRs 0016 e 0017) |
| Fluxo com passo anterior exigido | inscrição exige perfil completo e 18 anos | `RegistrationIT.emptyProfileCannotRegister`, `profileWithoutRegionCannotRegister`, `minorCannotRegisterEvenWithAFilledProfile` |
| | rodada N exige N-1 e evento em andamento | `RoundIT.aRoundNeedsThePreviousOneAndWritesNothingWithoutIt`, `roundsDoNotStartBeforeTheEvent`, `roundsDoNotStartInADraft`, `roundsDoNotStartInACancelledEvent`; `MatchingSchemaIT.aRoundNeedsThePreviousOne` |
| | decisão exige ter formado o par | `ConnectionIT.onlyWhoFormedThePairDecides` |
| | conexão exige dois "sim" e nenhum bloqueio | `ConnectionIT.twoYesesFormOneConnectionThatBothSee`, `aBlockEitherWayPreventsTheConnection`; `RoundIT.peopleSeparatedByABlockAreNeverPaired` |
| | janela de tempo da decisão | **LACUNA de política**: ver pendência 3 |
| Resposta igual exista ou não (enumeração) | `POST /api/waitlist` | **novo** `JoinWaitlistIT.joiningWithAnEmailAlreadyListedLooksLikeJoiningWithANewOne` |
| | `:block` e denúncia de conta inexistente → 404 | conhecido e aceito até aqui (ver pendência 4) |
| Idempotency key em dinheiro | — | não se aplica: a API não move dinheiro |
| SSRF | — | não se aplica: nenhuma rota recebe URL |

## Bugs achados e corrigidos

1. **A decisão privada ia para o log em DEBUG.** O Spring MVC registra o corpo lido e o escrito pelo `toString`
   do record, e `DecideRequest`/`DecisionResponse` usavam o padrão: `Read … to [DecideRequest[interested=true]]`.
   A ADR 0019 trata a decisão como dado pessoal sensível ("interesse de uma pessoa por outra"), e o STRIDE dela
   não listava o log. Teste: `DecisionLoggingIT.theDecisionNeverReachesTheLog`. Correção:
   `fix(connections): redact the decision from the request and response toString`.
2. **O nome do Entra ia para o log em DEBUG em `GET /api/me`.** Mesmo mecanismo, em `CurrentUserResponse`. Teste:
   `SensitiveDataLoggingIT.currentUserNameNeverReachesTheLog`. Correção:
   `fix(profiles): redact the display name from the CurrentUserResponse toString`.

Os dois só aparecem com `org.springframework.web` em DEBUG, o nível que alguém liga para investigar um incidente
(o mesmo critério que `SensitiveDataLoggingIT` já usava).

## Pendências de decisão

1. **Rate limit em `PATCH /api/me/profile`, `:block` e `:unblock`.** Hoje não têm limite por conta. O `:block`
   responde 404 para conta inexistente e 204 para existente: um oráculo de existência de conta, mitigado só pelos
   74 bits aleatórios do UUIDv7. Opções: (a) limite por conta nos três, como a denúncia (ADR 0006/0015);
   (b) só em `:block`/`:unblock`; (c) aceitar o risco e registrar. Recomendação: (b), algo como 60 por hora,
   porque é o único com oráculo e com efeito em outra pessoa; custo: mais um bucket e um `RateLimitIT`.
2. **Ids de outra pessoa nos logs de DEBUG.** `ConnectionsResponse` (com quem houve interesse mútuo),
   `PairingResponse` (o par da rodada) e `BlockedAccountsResponse` (quem foi bloqueado) saem pelo `toString` padrão;
   `FileReportRequest` mantém `reportedAccountId` de propósito. Ids são pseudônimos, mas ligam duas pessoas.
   Recomendação: redigir ao menos em `ConnectionsResponse`, pelo mesmo motivo do bug 1, e registrar a regra
   ("relação entre pessoas não vai para o log") na ADR 0013.
3. **Janela de tempo da decisão.** Hoje dá para decidir sobre o par de uma rodada a qualquer momento depois dela,
   inclusive com o evento encerrado ou cancelado depois. É regra de negócio: até quando a decisão vale?
4. **404 de conta inexistente em `:block` e `POST /api/reports`.** Coerente com "id inexistente = 404", mas é o
   oráculo da pendência 1. A denúncia já tem cota; aceitar e registrar, ou responder igual ao caso existente
   (o que mudaria o contrato e a ADR 0015).
5. **Expiração absoluta da sessão web.** `spring.session.timeout=30m` é ociosa; não há teto absoluto. Decidir se
   vale um (por exemplo 12 h) ou se a sessão do Entra basta.
6. **Brute force e lockout do login** ficam no Entra External ID: conferir no tenant (Smart Lockout) e registrar;
   não é testável aqui.
7. **STRIDE da ADR 0019** não tem a linha "Information disclosure: a decisão no log". Sugestão: acrescentar a linha
   apontando `DecisionLoggingIT`.

## Testes adicionados

| Arquivo | Testes (casos executados) |
|---|---|
| `config/CsrfOnEveryMutationIT` (novo) | 1 + fábrica com 22 casos (11 mutações × sem/com token) |
| `config/AdminRoleOnEveryAdminRouteIT` (novo) | 1 + fábrica com 14 casos (7 rotas × sem papel/outro papel) |
| `config/ControlCharactersInRequestIT` (novo) | fábrica com 60 casos (15 rotas com variável × 4 caracteres) + 8 parametrizados |
| `config/SecurityHeadersIT` (novo) | 4 |
| `connections/DecisionLoggingIT` (novo) | 2 (sim e não) |
| `config/SensitiveDataLoggingIT` | +1 |
| `connections/ConnectionIT` | +2 |
| `events/RegistrationIT` | +1 |
| `events/AdminEventIT` | +3 casos em `invalidInputIsRejectedWithoutWriting` |
| `waitlist/JoinWaitlistIT` | +2 |
| `profiles/ProfileIT` | +1 caso em `unknownFieldIsRejectedWithoutWriting` (`role`) |

A enumeração de rotas saiu de `DenyByDefaultIT` para `config/RegisteredRoutes`, usada pelas quatro varreduras.
