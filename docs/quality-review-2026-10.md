# Revisão de qualidade, outubro de 2026

- **Data:** 2026-10-08
- **Branch:** `refactor/quality-pass`
- **Escopo:** `matching`, `connections`, `events`, `profiles`, `trustsafety` (sem denúncia), `identity` e
  `config`, código e testes.
- **Fora do escopo:** o módulo `chat`, os arquivos de denúncia do `trustsafety` (`Report*`, `NewReport`,
  `report_message_evidence`) e os testes que o PR #40 altera (`OpenApiContractIT`, `DenyByDefaultIT`).
- **Seção 5:** o módulo `chat` e as APIs publicadas que ele usa (`EventCalendar`, `Pairings`, `Reports`,
  `Blocking`), revisados depois, em 2026-10-09, na branch `refactor/chat-quality-pass`.

Os módulos cresceram rápido, com vários agentes em paralelo. A revisão procurou conhecimento duplicado
(Rule of Three; DRY só quando é a mesma regra), smells de função e de classe, desvios da ADR 0007 e da regra
da dependência, e smells de teste.

Para cada achado houve uma de duas decisões:

- **Refatorado agora:** em passos pequenos, sem mudar o comportamento, com os testes verdes. O contrato não
  mudou (`docs/openapi.json` intacto, `OpenApiContractIT` verde).
- **Registrado aqui:** quando o custo, o risco ou a mudança de comportamento não compensavam nesta passada.

## 1. Refatorado nesta branch

| Smell | Onde estava | Refactoring |
|---|---|---|
| Duplicate Code: o `maxPageSize` era lido em 3 cópias | `BlockController.pageSizeOf`, `ConnectionController.pageSizeOf`, `events/api/PageSize` | `config.MaxPageSize.parse`. O 400 é tratado num handler só, em `MalformedRequestInputHandler` |
| Duplicate Code: o mesmo `pageToken` keyset em 2 cópias idênticas | `events/api/PageToken`, `connections/api/ConnectionPageToken` | `config.KeysetPageToken`. Cada módulo mantém o próprio teto de tamanho e o próprio tipo de cursor |
| Duplicate Code e comentário que mente: as convenções da spec | `ApiSchemas` de 4 módulos, mais constantes privadas em `BlockController` e `ProfileController`. O comentário do `trustsafety` dizia 30 para um valor de 35 | `config.ApiSchemaConventions`, com o `PROBLEM_SCHEMA` derivado do `OpenApiConfiguration`. Os `ApiSchemas` dos módulos guardam apelidos curtos, para não tocar nas anotações nem nos arquivos do PR #40 |
| Duplicate Code: o teto de texto do ProblemDetail | `MalformedRequestInputHandler` e `OpenApiConfiguration` | Uma constante só, em `OpenApiConfiguration` |
| Parâmetro morto | `OpenApiConfiguration.problem(HttpStatus, String)` | Remove Parameter |
| Ciclo de fonte que o ArchUnit não vê | `identity/api` importava `config.WebLoginConfiguration.OBJECT_ID_CLAIM`, e `config` importa `identity.AccountId`. O javac inlina a constante, por isso `modulesAreFreeOfCycles` não pega | Move Field para `identity.IdentityClaims.OBJECT_ID`. `WebLoginConfiguration` virou package-private |
| Dead Code | `Connection.otherThan` e `ConnectionPair.otherThan`, só alcançados pelos próprios testes | Removidos, junto com os testes |
| Temporal Coupling e detalhe de infraestrutura na porta | `RoundRepository.limitLockWait` e `DecisionRepository.limitLockWait`. Os serviços tinham de chamá-los antes de `addIfAbsent` ou `lockPair` | O adapter define o `lock_timeout` dentro da própria operação (pull complexity down). As portas só prometem a espera com teto |
| Duplicate Code: a ordem dos ids de conta em 3 cópias (4 com o chat) | `comesBefore` em `Pair`, `ConnectionPair` e `BlockedPair` | `AccountId implements Comparable` pela ordem do texto, a mesma do `uuid` no PostgreSQL, com o porquê documentado (Move Function) |
| Duplicate Code em teste: o emissor do token copiado | `ISSUER` em 19 classes de teste, mais 2 literais soltos | `TestIdentities.ISSUER` |

Testes de caracterização novos:

- `BlockIT.pageSizeOutsideTheLimitsNamesTheLimitOfThisList` e as duas novas de `ConnectionIT` fixam o
  ProblemDetail inteiro (STRICT) das listas paginadas. Os três rodaram verdes sobre o código antigo antes da
  troca.
- `config.KeysetPageTokenTest` e `config.MaxPageSizeTest` herdaram os casos de `events/api/PageTokenTest` e
  `PageSizeTest`. Os de `events` ficaram só com o que é da lista de eventos.

## 2. Bug corrigido

**`fix(trustsafety)`: o `pageToken` da lista de bloqueios aceitava data implausível.**

Um token bem codificado com o ano +200000 era aceito e devolvia 200 com uma página. O contrato diz que um
token que a API não gerou recebe 400. Os tokens de `events` e `connections` já limitavam o instante a
1970–9999.

O teste veio antes: o caso novo em `BlockIT.malformedPageTokens` falhou no código antigo com
`Status expected:<400> but was:<200>`.

## 3. Registrado, não feito

### Duplicação

1. **`BlockPageToken` fora do `KeysetPageToken`.** Migrar mudaria três coisas, todas mudança de
   comportamento:
   - o separador do token (`|` para espaço), o que invalida os tokens em circulação no deploy;
   - o detail do 400 (`pageToken is not valid` para `pageToken is invalid`);
   - a aceitação de UUID não canônico.

   É barato, mas tem de ser um commit `fix`/`change` próprio, com aviso no changelog. Recomendo fazer.
2. **Os sete `*RateLimitConfiguration` e `*RateLimitProperties` quase idênticos.** O que se repete é
   boilerplate de binding. O `@ConfigurationProperties` com record exige um tipo por prefixo, e o que varia
   é conhecimento de cada módulo: o prefixo da chave, o nome do bean e o Javadoc do limite. Unificar
   exigiria registrar os limites em `config`, que passaria a conhecer cada módulo. Fica como está.
3. **Os handlers 503 de lock em 3 módulos (4 com o chat).** A estrutura (503 + `Retry-After: 1`) se repete,
   mas o detail e o motivo do teto são de cada módulo. Um helper pouparia três linhas por módulo. Não
   compensa.
4. **Os handlers `exceção → ProblemDetail(404/400, mensagem)` em `Matching`, `Connections` e
   `EventsExceptionHandler` e no `BlockController`.** Uma superclasse `NotFoundException` na raiz, com um
   handler em `config`, apagaria uns 10 métodos. Mexe em cerca de 12 exceções e na ordem dos advices: vale
   um PR próprio.

   Na mesma família, há dois nomes para o mesmo conceito: `matching.UnknownEventException` e
   `events.EventNotFoundException`.
5. **`lock_timeout` + SQLSTATE 55P03 em 3 adapters (4 com o chat).** `JdbcRoundRepository`,
   `JdbcDecisionRepository` e `RegistrationRepository` repetem o `set_config` e a tradução do 55P03. Há
   ainda três jeitos de ler erro do PG: `violates()` no matching, `SqlStates` no trustsafety e a comparação
   inline. O próximo passo, depois da mudança desta branch, é um helper em `config`, por exemplo
   `PostgresLocks`.

   **Feito** (branch `refactor/locks-and-spec-numbers`): `config.PostgresLocks` tem `limitWait` (o
   `set_config('lock_timeout', ...)`) e `translatingTimeout` (55P03 vira `CannotAcquireLockException`). Os
   quatro adapters (`JdbcRoundRepository`, `JdbcDecisionRepository`, `JdbcChatRepository` e
   `RegistrationRepository`) o usam e mantêm o próprio teto (5 s, 2 s, 2 s, 2 s) e a própria mensagem; os
   handlers 503 não mudaram. O `PostgresLocksTest` cobre a tradução. Sobra a leitura de constraint
   (`violates()` e `SqlStates`), que lê FK e unicidade, não lock, e fica como está.
6. **A paginação "limit + 1" em `ConnectionService` e `BlockService`.** O events já tem `ResultPage`. Dá
   para generalizar quando o chat ganhar lista.
7. **A faixa da rodada (1..100) em 5 lugares e a capacidade máxima (200) em 2.** Onde aparecem:
   - `RoundNumber`
   - `Pairings`
   - `connections/api/RoundNumberParameter`
   - `events/api/EventResponse`
   - `matching/api/ApiSchemas.MAX_PEOPLE`

   O events não pode importar o matching, por causa do ciclo. A saída é um teste que compare os valores, ou
   publicar os limites no módulo dono.
8. **`Pairings` repete `RoundService.seatOf` e `latestStartedOf`, e lê a porta direto.** Remove Middle
   Man: `Pairings` passa a delegar ao serviço.
9. **Os blocos `@ApiResponse` 429/503 e os `@Parameter` de eventId/number copiados em 7 controllers.**
   Também há números de rate limit ("60 por hora") escritos à mão em 11 descrições, que viram mentira se
   `application.properties` mudar. As saídas possíveis são meta-annotations ou um `OpenApiCustomizer`. Só
   com o `OpenApiContractIT` vigiando o diff, num PR próprio.

   **Feito em parte** (branch `refactor/locks-and-spec-numbers`, `docs/openapi.json` intacto):
   - Os números das descrições leem constantes. Faixa da rodada: `Pairings.FIRST_ROUND`/`LAST_ROUND`, pelo
     `ApiSchemas` de cada módulo. Teto do `maxPageSize`: `MAX_PAGE_SIZE`, `PageSize.MAX` e
     `ChatParameters.MAX_PAGE_SIZE`. Chat: `Chat.MAX_MESSAGES` e `ChatMessageText.MAX_LENGTH`. Capacidade do
     evento: `Capacity.MIN_PLACES`/`MAX_PLACES`.
   - Os limites por conta ("60 por hora", "10 denúncias por dia") citam o padrão: `DEFAULT_CAPACITY` em cada
     `*RateLimitProperties` e `Reports.DEFAULT_DAILY_LIMIT`. O número ainda existe em dois lugares (a constante e
     o `application.properties`), porque a anotação só aceita constante. O `RateLimitDescriptionsTest` compara a
     spec com o `application.properties` e falha se divergirem. A unidade ("por hora") continua escrita à mão e
     também é conferida pelo teste.
   - Os `@Parameter` de `eventId` e `number` do chat viraram `@EventIdPathParameter` e
     `@RoundNumberPathParameter` (meta-annotations; o springdoc as resolve).
   - Ficam de fora: os blocos `@ApiResponse` 429/503 e os `@Parameter` dos outros 6 controllers, e os dois
     números de `Duration` ("12 horas", "365 dias" em `CreateEventRequest` e `AdminEventController`), que não são
     constantes de anotação.
10. **Texto livre Unicode.** `EventText` e `ProfileText` são idênticos, e o próprio comentário admite a
    cópia. O filtro de caracteres invisíveis é política técnica, não modelo, e caberia no shared kernel da
    raiz. Isso exige revisar a ADR 0007.
11. **`OffsetDateTime.ofInstant(x, UTC)` e a leitura de `timestamptz` repetidos uns 15 vezes nos adapters.**
    Um `JdbcInstants` resolveria, junto do item 5.

### Desenho e camadas

12. **`events/domain/RegistrationRepository` é JDBC puro dentro de `domain`.** A ADR 0007 fala em Spring
    Data no domain do supporting. Proposta: emendar a ADR (o menor custo) ou mover para `adapter`.
    **Decisão sua.**
13. **`WebLoginConfiguration` tem 6 parâmetros `@Value`, e `SecurityConfiguration` relê
    `duora.auth.audience` e duplica o `Assert.hasText`.** A regra Java 7 pede um record
    `@ConfigurationProperties("duora.auth")` com `@NotBlank`. O comentário atual justifica o `@Value`: o
    binder aceitaria um placeholder não resolvido como texto. Um `@Pattern` que recuse `${` cobre isso. PR
    próprio, por ser segurança.
14. **`Event` e `Profile` reidratam e revalidam Value Objects a cada acessor.** Numa lista de 50 eventos,
    título e descrição são normalizados 50 vezes. Há um risco maior: se uma regra endurecer, linhas antigas
    viram 400 no GET. As saídas são Hide Delegate (`startsAt()`, `endsAt()`) e reidratar sem revalidar.
15. **`ProfileService` e `ProfileRepository` usam `UUID` onde o resto usa `AccountId`** (Primitive
    Obsession). A saída é Change Signature.
16. **`RefusalReason.EVENT_NOT_PUBLISHED` é inalcançável** (Dead Code): `RegistrationService` filtra o
    rascunho antes. Removê-lo tira um valor de enum da spec, e o `oasdiff` acusa breaking change.
    **Decisão sua.**
17. **O `Location` é montado por concatenação em `AdminRoundController`, `RegistrationController` e
    `AdminEventController`.** O `DecisionController` já usa `UriComponentsBuilder`. Mudança pequena, que
    fica para a próxima passada.
18. **O relógio tem precisão de microssegundos em 3 serviços** (`truncatedTo(MICROS)` em `RoundService`,
    `DecisionService` e `RegistrationService`), e o `BlockService` não trunca. `Clock.tick` em
    `ClockConfiguration` resolveria, mas muda o instante gravado em bloqueios. Isso é mudança de
    comportamento, mesmo que inofensiva.
19. **`OpenApiConfiguration` tem 356 linhas** e mistura segurança, logout, schemas de ProblemDetail e
    respostas transversais (Divergent Change). As descrições dos enums `RefusalReason` e `FieldErrorCode`
    repetem o Javadoc deles. A saída é Extract Class `ProblemDetailSchemas`, com o texto vindo do próprio
    enum.

Itens revisados e deixados como estão:

- `MaximumMatching.findAugmentingPathEnd`: 29 linhas, aninhamento 4. É o algoritmo de Edmonds, coberto por
  1.200 casos aleatórios.
- Os pass-throughs de serviço (`BlockService.unblock`, `RoundService.find`): seguram a fronteira de
  transação.
- As portas com um implementador: exigidas pela ADR 0007 no core.

### Testes

Passada só de testes em `refactor/test-quality-pass` (2026-10-09), sem tocar em `src/main`. O número de testes
mudou como descrito no fim desta seção. Cada item abaixo diz o que foi feito e o que sobrou.

20. **Helpers repetidos em 3 ou mais classes (Rule of Three).** Feito, com cada grupo num commit mecânico:
    - **`AccountFixtures`** (`bipo.tech.duoraapi`): `firstAccess`, `openAccount`, `accountIdOf`, `accountOf`. Havia
      `accountIdOf` em 6 classes e o `select id from account where subject = ...` em 15 lugares. Os testes mantêm
      um embrulho de uma linha onde ele deixa a chamada curta (`accountOf("ana")`), sem repetir o SQL.
    - **`TestIdentities.bearer(objectId)`** troca as 8 cópias do `jwt().jwt(token -> token.issuer(ISSUER)...)`. O
      `ISSUER` solto em `ReportIT` e `ReportFieldErrorsIT` também saiu.
    - **`ConcurrentCalls`** (`together`, `sameCallTogether`, `inAnotherThread`, `statusCodeOf`) e **`HeldLock`**
      (um `AutoCloseable` que segura o lock numa transação e solta, esperando o dono, mesmo se o corpo do teste
      falhar). Saíram o executor, a trava de partida, o `get(30, SECONDS)` e o `awaitQuietly` de 10 classes. O
      `ChatIT.aReaderAfterEachCommitNeverSkipsAMessage` fica com a própria estrutura: é um leitor em laço contra
      escritores, e não uma corrida de chamadas.
    - **`RoundFixtures`** (`matching`): `underwayEventWith`, `pairedInRoundOne`, `startRound`. Eram 6 variações, só
      uma delas com a regra "o perfil completo se cria uma vez por pessoa". O `ChatFixtures` delega.
    - **`RateLimitTestSupport`**: `expectRejectedByTheLimit` (429), `expectUnavailableBecauseTheLimitCannotBeCounted`
      (503), `whileTheLimitCannotBeCounted` (o `rename` da tabela, com `finally`), `bucketKeysOf` e `clearBuckets`
      (18 lugares). O `ReportIT` também usa.
    - **Não feito:** os `webSession(...)`/`admin()` com claims diferentes (8 classes com `oidcLogin`, 5 com `ROLE_ADMIN`
      no `jwt`) ficam como estão, porque cada um carrega um claim que o teste mostra; e os `insertAccount`,
      `insertBlock` e `insertConnection` dos `*SchemaIT`, com o `assertViolates(constraint, ...)` das ~25 asserções.
      Os dois são o mesmo tipo de commit mecânico, e entram na próxima passada.
21. **Asserts frágeis.** Aplicado de forma **provisória**, na linha da recomendação da decisão 2 abaixo, até você
    decidir. `ProblemJson.strictIgnoringDetail()` compara o corpo inteiro em STRICT (título, status, `instance`,
    `reason`, `errors`), falha se aparecer um campo a mais e deixa o `detail` de fora. Passaram a usá-lo: os 6
    `*FieldErrorsIT` (o texto do Spring, `"Failed to read request"`, saiu dos argumentos), as recusas com `reason`
    do `RoundIT`, `RegistrationIT` e `AdminEventIT`, os 409/400 com `reason` ou `errors` do `ProfileIT` e
    `ReportIT`, e o 429 de todos os `*RateLimitIT` com corpo.
    O `detail` **continua** assertado onde o texto é o comportamento testado:
    - os testes que dizem no nome que o `detail` nomeia o teto da lista (`BlockIT` e `ConnectionIT`, `maxPageSize`) e os
      de `pageToken`/`status` inválidos das listas de eventos;
    - os "mesma resposta em todos os casos" (os 4 `ConnectionIT.*HaveNoPartner` e, no `RegistrationIT`, rascunho =
      evento inexistente), onde o texto é o que prova que as respostas são iguais;
    - os 400/404 sem `reason` nem `errors` cujo único testemunho da causa é o texto (`BlockIT`, `ReportIT`,
      `AdminEventIT`, `RegistrationIT`, `MalformedRequestInputIT`);
    - o 503 de "não consegui contar o limite", que não pode ter `detail`: o STRICT sem ele fica.
    Se a decisão 2 for "o `detail` é contrato", é só trocar `strictIgnoringDetail()` por `JsonCompareMode.STRICT` e
    voltar os textos, que estão no histórico do git.
22. **Relógio real.** Feito:
    - `ProfileIT` e `ProfileFieldErrorsIT` importam o `TestClockConfiguration`, e o "menor de idade" (2020-01-01)
      não vira maior em 2038.
    - `BlockIT` avança o `TestClock` entre os bloqueios. "Bloquear de novo mantém a data do primeiro" agora prova
      de verdade (o segundo bloqueio aconteceria um segundo depois), e a ordem "mais recente primeiro" não depende
      de três POSTs terem `created_at` diferentes.
    - `ExpiredRateLimitBucketCleanerIT` fica com o relógio do sistema, e a classe agora diz por quê (o Bucket4j
      grava `expires_at` com `System.currentTimeMillis()`).
    - `AccountRateLimitIT` fica com o `Thread.sleep`, já justificado no próprio código (o que se testa é um teto
      de tempo).
23. **Testes com vários comportamentos.** Feito:
    - `ConnectionIT.onlyWhoFormedThePairDecides` virou 4 testes (fora do sorteio, sem inscrição, rodada que não
      existe, evento que não existe), com um helper que afirma o mesmo 404, sem id de conta e nada gravado.
    - `RegistrationIT.invalidPageOfOwnRegistrationsIsABadRequest` virou `@ParameterizedTest` com 4 casos.
    - `ProfileIT.absentFieldsStayAndNullClearsTheBio` virou `fieldsAbsentFromThePatchStayAsTheyWere` e
      `nullInThePatchClearsTheBio`.
    - `PairingsIT.theRoundNumbersGoFromOneToOneHundred` foi removido: só comparava duas constantes.
    - Revisados e deixados como estão, porque o nome diz tudo e a asserção é uma só: `aDecisionCannotChange`,
      `theAnswerIsTheSameWhetherThePartnerSaidNoOrHasNotDecided`, `anotherUserNeitherSeesNorCancelsTheRegistration`.
24. **DDL em tabela compartilhada.** Feito: o `src/test/resources/junit-platform.properties` desliga o paralelismo
    de forma explícita e diz por que, e a Javadoc de `RateLimitTestSupport.whileTheLimitCannotBeCounted` e da
    constante do `UnexpectedErrorIT` apontam para ele.

**Contagem de testes** (`./mvnw clean verify`, 0 falhas): 2963 em `0163efc`, a base desta branch, e 2994 depois do
rebase em `main`. Dos 31 a mais, 25 vêm dos PRs #46 e #48 (`ChatKeyTest`, `MaximumMatchingTest`,
`PriorityMatchingTest`, `RoundSummaryTest`, +1 em `ChatTest` e +1 em `ChatIT`), e 6 são desta passada: +3 em
`ConnectionIT`, +3 em `RegistrationIT`, +1 em `ProfileIT` e -1 em `PairingsIT`.

## 4. Decisões que dependem de você

| # | Decisão | Recomendação |
|---|---|---|
| 1 | Migrar o `BlockPageToken` para o `KeysetPageToken`: muda o separador e o detail do 400 (item 3.1) | Sim, como `fix` com nota no changelog. O front não guarda tokens |
| 2 | O `detail` em inglês faz parte do contrato? (item 3.21) | Não. Os testes assertam `reason`/`errors[].code`, e o texto fica livre para melhorar. **Aplicado de forma provisória** na passada de testes, com as exceções do item 3.21 |
| 3 | `RegistrationRepository` com JDBC no `domain` do events (item 3.12) | Emendar a ADR 0007 para "Spring Data ou JDBC" no domain do supporting |
| 4 | Remover `EVENT_NOT_PUBLISHED` da spec (item 3.16) | Remover agora, na 0.1.0, ainda sem consumidor externo |

## 5. Chat

O módulo entrou nos PRs #34 e #40 e ficou fora da passada acima. A revisão seguiu os mesmos critérios e
reaproveitou as unificações de `config`. O contrato não mudou: `docs/openapi.json` intacto, `OpenApiContractIT`
verde.

### 5.1 Refatorado nesta branch

| Smell | Onde estava | Refactoring |
|---|---|---|
| Duplicate Code: as convenções da spec | `chat/api/ApiSchemas` repetia os cinco valores de `config.ApiSchemaConventions` | Apelidos curtos, como nos outros módulos; as anotações não mudaram |
| Duplicate Code: o `maxPageSize` em mais uma cópia | `ChatParameters.integerWithin`, a quarta cópia da leitura de `config.MaxPageSize` | `MaxPageSize.parse`, com o 400 vindo de `MalformedRequestInputHandler`. O `afterSeq` era o único outro usuário do helper genérico, que foi incorporado nele (Inline Function) |
| Duplicate Code: a ordem dos ids de conta | `ChatPair.comesBefore`, a cópia que a passada anterior contou como "4 com o chat" | `AccountId.compareTo`, como em `ConnectionPair` |
| Mysterious Name | `JdbcChatRepository.CHAT_COLUMNS` e `MESSAGE_COLUMNS` guardavam consultas inteiras, uma delas com o `where` | `SELECT_CHAT_BY_KEY` e `SELECT_MESSAGES` |
| Temporal Coupling e detalhe de infraestrutura na porta | `ChatRepository.limitLockWait`, `addIfAbsent`, `lock` e `find`: `ChatService.send` tinha de chamar os três primeiros nessa ordem, e `chatOf` os dois últimos | A porta oferece `findOrAdd` e `lockOrAdd`. O adapter define o `lock_timeout` dentro de `lockOrAdd`, como `JdbcRoundRepository` e `JdbcDecisionRepository` (pull complexity down). A leitura continua criando o chat sem teto de espera, como antes |
| Regra de negócio no serviço, com valor mágico | `ChatService.conditionsOf`: `latestRoundOf(...).orElse(0) == roundNumber` | `ChatKey.isLatestRound(OptionalInt)`, regra pura com teste unitário. O estado aberto ou fechado agora é calculado só no domínio (`OpeningConditions`, `ChatKey`, `Chat`), e o serviço só junta o que os outros módulos dizem |
| Duplicate Code e parâmetro que só atravessa | `partnerOf` + `new ChatKey(..., ChatPair.of(...))` nos cinco casos de uso, e o `partner` passado a `conditionsOf` | `ChatService.chatKeyOf`. O bloqueio lê o par da chave, e a denúncia nomeia o autor da mensagem como conta denunciada, que é o que `Reports` pede (mesmo valor de antes) |

Teste de caracterização novo: `ChatIT.pageSizeOutsideTheLimitsNamesTheLimitOfThisList` fixa o ProblemDetail
inteiro (STRICT) do 400 de `maxPageSize`. Rodou verde sobre o código antigo antes da troca. `ChatKeyTest` ganhou
os casos de `isLatestRound`.

### 5.2 Concorrência e transações (só leitura)

Nenhum bug que justifique `fix` naquela branch. O item 1 abaixo foi corrigido depois. O que foi conferido:

- **Envio:** `insert ... on conflict do nothing` seguido de `select ... for update` (hoje um comando só, ver o
  item 1). Em READ COMMITTED, o insert espera a transação que cria o mesmo chat, e o `select` seguinte, num
  snapshot novo, enxerga a linha confirmada. A Idempotency-Key é procurada já com o chat travado e tem `unique` no banco. O `update` de
  `last_seq` confere a sequência anterior. As leituras de rodada atual e de bloqueio acontecem depois do lock.
- **Teto de espera:** `set_config('lock_timeout', ..., true)` vale até o fim da transação. O 55P03 vira
  `CannotAcquireLockException` e 503 com `Retry-After: 1`, coberto por
  `ChatIT.aSendIsRefusedWhenAnotherHoldsTheChatTooLong`.
- **Expurgo:** um comando por lote, `for update skip locked` no subselect, índice em `purge_after` (V14). Duas
  réplicas não apagam o mesmo chat, coberto pelos testes de concorrência do `ChatPurgeIT`.
- **Denúncia sem transação:** a mensagem não muda depois de gravada, e a cota é contada em outra conexão.

Registrado, sem correção:

1. **Corrida rara entre uma requisição e o expurgo devolvia 500. Resolvido na branch `fix/chat-purge-race`.**
   O caso é um chat de evento acabado há mais de 24 h. O `insert ... on conflict do nothing` não travava a linha
   que já existia. Se o expurgo a apagasse entre o insert e a leitura, `findOrAdd` e `lockOrAdd` não achavam o
   chat e lançavam `IllegalStateException`, e a resposta era 500.
   - **Reprodução:** `ChatPurgeRaceIT` instala uma barreira, um trigger `after insert ... for each statement` na
     tabela chat que espera um advisory lock segurado pelo teste. Com a requisição parada depois do insert, o
     `ChatPurge.purgeExpired` de verdade roda em outra conexão, e só então a barreira abre. Antes da correção, o
     `GET .../chat` e o envio responderam 500.
   - **Correção:** `JdbcChatRepository.addOrLock` cria ou trava e devolve o chat num comando só:
     `insert ... on conflict (...) do update set last_seq = chat.last_seq returning id, last_seq`. Com a linha
     travada, o expurgo a pula (`skip locked`) e a apaga na execução seguinte. Se o expurgo a travou antes, o
     comando espera por ele e cria outra linha. `lockOrAdd` usa o comando sob o `lock_timeout` de 2 s. `findOrAdd`
     faz primeiro um `select` e só usa o comando quando o chat ainda não existe, para quem só lê não gravar uma
     versão nova da linha a cada leitura.
   - **Comportamento:** o mesmo de antes fora da corrida. A leitura responde 200 com o chat fechado e o envio
     responde 409 `CHAT_CLOSED`. O chat vazio que a leitura recria depois do expurgo continua como está, e a
     decisão 6 da ADR 0021 segue pendente.
   - **Testes:** três em `ChatPurgeRaceIT`. Os dois da barreira falhavam com 500 antes da correção. O terceiro
     cobre a ordem inversa, com o expurgo segurando o chat apagado e o envio esperando por ele, e também passava
     antes.
2. **O instante do envio é lido antes da espera pelo lock.** `now` e o horário do evento são lidos antes de
   `lockOrAdd`, que pode esperar até 2 s. Uma mensagem pode ser aceita até 2 s depois do fim do evento, e o
   `sentAt` de duas mensagens seguidas pode sair fora de ordem por até 2 s. A spec já diz que `sentAt` não
   define a ordem. Fica como está.

### 5.3 Registrado, não feito

3. **Mais cópias dos itens 3.3 a 3.6 e 3.10, agora contando o chat.**
   - Os handlers 503 de lock existem em 4 módulos (item 3.3).
   - Os handlers `exceção → ProblemDetail(404/400)` (item 3.4) ganham `ChatNotFoundException`,
     `MessageNotFoundException`, `OwnMessageNotReportableException` e o `InvalidRequestException` do chat, que
     tem o mesmo nome do de `connections`.
   - `lock_timeout` + 55P03 aparecem em 4 adapters, mais o `set_config` de `RegistrationRepository` (item 3.5).
     O `PostgresLocks` proposto passa a ter quatro usuários e vale um PR próprio, porque toca matching,
     connections e events. **Feito** (item 3.5).
   - A paginação "limit + 1" tem 3 cópias (`ConnectionService`, `BlockService`, `ChatService` com
     `MessagesPage`), mais o `ResultPage` do events (item 3.6). O cursor do chat é a posição, e não o keyset, então
     o que se repete é só o corte da sobra.
   - O texto livre Unicode tem 4 cópias com a mesma regex e a mesma normalização (item 3.10): `EventText`,
     `ProfileText`, `ReportDescription` e `ChatMessageText`. O comentário de `ChatMessageText` admite a cópia.
     Continua dependendo da revisão da ADR 0007.
4. **A faixa da rodada (1..100) validada 3 vezes com a mesma mensagem.** Os lugares são
   `ChatParameters.roundNumber`, `connections/api/RoundNumberParameter.validated` e a pré-condição de
   `Pairings.partnerOf`. Cada fronteira lança a própria `InvalidRequestException`. A saída é `Pairings` publicar
   um `isRoundNumber(int)` e cada módulo manter só a exceção. Junta-se ao item 3.7.
5. **Os textos da spec do chat repetem números à mão:**
   - "1 a 100", "0 a 300" e "500 caracteres" em `ChatController`;
   - "20 por minuto" e "10 denúncias por dia", que viram mentira se `application.properties` mudar;
   - os `@Parameter` de `eventId` e `number`, copiados nos 5 endpoints.

   É o item 3.9, e precisa do `OpenApiContractIT` vigiando o diff. **Feito** (item 3.9): os números leem
   constantes e os `@Parameter` viraram meta-annotations, sem mudar o `docs/openapi.json`.
6. **O `Location` da denúncia é montado por concatenação** (`REPORTS_PATH + report.id()` em
   `ChatController.report`), enquanto o envio usa `UriComponentsBuilder`. É o item 3.17.
7. **`Reports` é `@Component`, e `Pairings` e `EventCalendar` são `@Service`.** As três são APIs publicadas com o
   mesmo papel. A diferença é só de estereótipo e não muda nada em execução. Uniformizar junto com o item 3.8.

Itens revisados e deixados como estão:

- `ChatController` tem 269 linhas, quase todas de anotação OpenAPI. O corpo dos métodos tem de 1 a 10 linhas.
- `ChatService` ficou com métodos de no máximo 11 linhas, e a única lógica dele é juntar o que matching, events e
  trustsafety dizem.
- `ChatPurge.purgeExpired`: o laço `do/while` termina no primeiro lote incompleto ou no teto de lotes, e o
  instante de corte é lido uma vez.
- `JitteredDelayTrigger` e `ChatPurgeScheduling` estão cobertos por teste unitário e pelo `ChatPurgeSchedulingIT`.
- O `ChatExceptionHandler` local continua, como nos outros módulos (item 3.4).
