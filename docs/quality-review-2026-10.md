# Revisão de qualidade, outubro de 2026

- **Data:** 2026-10-08
- **Branch:** `refactor/quality-pass`
- **Escopo:** `matching`, `connections`, `events`, `profiles`, `trustsafety` (sem denúncia), `identity` e
  `config`, código e testes.
- **Fora do escopo:** o módulo `chat`, os arquivos de denúncia do `trustsafety` (`Report*`, `NewReport`,
  `report_message_evidence`) e os testes que o PR #40 altera (`OpenApiContractIT`, `DenyByDefaultIT`).

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

20. **Helpers repetidos em 3 ou mais classes (Rule of Three).** É o maior custo de manutenção da suíte.
    Ordem sugerida de extração:
    - `accountIdOf`/`accountOf` em 8 cópias e "primeiro acesso cria a conta" em 7: um `AccountFixtures`.
    - `user()`, `admin()` e `webSession()` com claims ligeiramente diferentes, em uns 15 lugares: ampliar
      `TestIdentities`.
    - O harness de concorrência (latch + executor + `get(30, SECONDS)`) em 7 classes e o `awaitQuietly`
      com "segurar o lock" em 3: `ConcurrentCalls` e `HeldLock`.
    - `pairedInRoundOne` e `underwayEventWith` em 6 variações: um `RoundFixtures`.
    - Nos `*RateLimitIT`: `keysOfTheLimit` em 5 cópias, o `rename rate_limit_bucket` em 6 e o JSON do 429 em
      5. Um `RateLimitTestSupport` resolve.
    - `insertAccount`, `insertBlock` e `insertConnection` nos `*SchemaIT`, e o
      `isInstanceOf(DataIntegrityViolationException).hasMessageContaining(...)` em umas 25 asserções: um
      `assertViolates(constraint, ...)`.

    Cada um é um commit mecânico, que toca de 5 a 20 arquivos. Ficou para uma passada só de testes, depois
    do merge do PR #40, que mexe nas mesmas classes.
21. **Asserts frágeis.** O `detail` em inglês está fixado em JSON STRICT nos `*FieldErrorsIT`, inclusive o
    texto do Spring (`"Failed to read request"`, `"Invalid request content."`), e nas recusas de negócio.
    Isso quebra num upgrade do Spring sem que o contrato mude. **Decisão sua:** o `detail` faz parte do
    contrato?
    - Se não faz, os testes assertam `status`, `reason`/`errors[].code` e `instance`.
    - Se faz, os textos ficam numa classe só, com um teste por texto.
22. **Relógio real.**
    - `ProfileIT` e `ProfileFieldErrorsIT` usam datas absolutas com o relógio do sistema: o caso "menor de
      idade" (2020-01-01) vira maior de idade em 2038. A saída é importar `TestClockConfiguration`.
    - `BlockIT` ordena por `created_at` de três POSTs seguidos, sem relógio injetado.
    - `AccountRateLimitIT` tem `Thread.sleep` justificado (o que se testa é um teto de tempo).
    - `ExpiredRateLimitBucketCleanerIT` usa `System.currentTimeMillis()`, porque o Bucket4j usa o relógio do
      sistema. Deixar, mas escrever o porquê.
23. **Testes com vários comportamentos.** Viram `@ParameterizedTest` ou se dividem:
    - `ConnectionIT.onlyWhoFormedThePairDecides` (4 cenários)
    - `RegistrationIT.invalidPageOfOwnRegistrationsIsABadRequest` (4 chamadas)
    - `ProfileIT.absentFieldsStayAndNullClearsTheBio`

    `PairingsIT.theRoundNumbersGoFromOneToOneHundred` só testa duas constantes: remover.
24. **DDL em tabela compartilhada.** `rename rate_limit_bucket` e `rename waitlist_entry` estão seguros só
    porque a suíte é sequencial. Registrar isso em `TestStyleTest` ou em `junit-platform.properties`.

## 4. Decisões que dependem de você

| # | Decisão | Recomendação |
|---|---|---|
| 1 | Migrar o `BlockPageToken` para o `KeysetPageToken`: muda o separador e o detail do 400 (item 3.1) | Sim, como `fix` com nota no changelog. O front não guarda tokens |
| 2 | O `detail` em inglês faz parte do contrato? (item 3.21) | Não. Os testes assertam `reason`/`errors[].code`, e o texto fica livre para melhorar |
| 3 | `RegistrationRepository` com JDBC no `domain` do events (item 3.12) | Emendar a ADR 0007 para "Spring Data ou JDBC" no domain do supporting |
| 4 | Remover `EVENT_NOT_PUBLISHED` da spec (item 3.16) | Remover agora, na 0.1.0, ainda sem consumidor externo |
