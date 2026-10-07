# 0016. Eventos e inscrições

- **Status:** Aceita, provisória. Decidido na sessão autônoma de 2026-10-05; revisar com o usuário.
- **Data:** 2026-10-06
- **Relacionadas:** [ADR 0004](0004-identificadores-e-unicidade.md), [ADR 0005](0005-contrato-da-api.md),
  [ADR 0007](0007-estilo-por-modulo.md), [ADR 0011](0011-conta-e-perfil.md)

## Contexto

A etapa 2 do plano (§9) é "uma experiência completa": evento, pareamento, um jogo, chat temporário,
reconexão e decisão privada. O primeiro passo é o módulo `events`, só com eventos e inscrições; pareamento,
jogo e chat ficam para os próximos passos.

Requisitos do passo:

- o ADMIN cria um evento (título, descrição curta, início e fim em `timestamptz`, capacidade) e o
  publica ou cancela, com o mínimo de edição;
- quem está logado lista os eventos publicados que ainda vão começar, por keyset, e lê um evento;
- a pessoa se inscreve e cancela a própria inscrição; só com perfil completo e 18 anos; uma inscrição por
  pessoa e evento (plano §4); capacidade respeitada sob concorrência, sem read-modify-write; inscrever-se
  de novo devolve a mesma inscrição; evento cancelado, encerrado ou já começado recusa;
- só a própria pessoa vê as suas inscrições; o ADMIN vê a contagem; nenhum usuário vê a lista de
  participantes;
- relógio injetado, testes com relógio fixo.

## Alternativas e decisões

### Classificação do módulo

| Opção | Prós | Contras |
|---|---|---|
| **Supporting, camadas simples (`api` → `application` → `domain`)** | Igual a `profiles`; Spring Data e JDBC no `domain` sem portas; as regras de estado continuam em Java puro e testadas sem Spring | Se o pareamento morar aqui, o módulo cresce e precisa ser reclassificado |
| Core, ports & adapters | Domínio sem framework, como `matching` e `experiences` | Interface e adapter para um CRUD com uma máquina de três estados; o diferencial do produto está no pareamento e nos jogos, não na agenda |

**Decisão:** `events` é **supporting** ([ADR 0007](0007-estilo-por-modulo.md) atualizada). O pareamento
vai para `matching` (core), que lê as inscrições pela API publicada deste módulo quando existir.

### Ciclo de vida do evento

| Opção | Prós | Contras |
|---|---|---|
| **Guardar só `DRAFT`, `PUBLISHED` e `CANCELLED`; "em andamento" e "encerrado" vêm do horário** | Nada fica desatualizado: um evento acaba sozinho quando `ends_at` passa; nenhum job | Quem lê o estado precisa olhar também os horários |
| Guardar `ENDED` (ação `:end` ou job agendado) | Estado explícito numa coluna | Job com lock entre réplicas, ou ADMIN lembrando de encerrar; enquanto não roda, o estado mente |

**Decisão:** três estados guardados, transições só por ação ([ADR 0005](0005-contrato-da-api.md)):
`POST /api/admin/events/{id}:publish` (só rascunho que ainda não começou) e `:cancel` (rascunho ou
publicado que ainda não acabou, inclusive em andamento). Estado inválido para a ação → `409`. O horário é
**intervalo semiaberto `[startsAt, endsAt)`**: começou no instante de início, acabou no instante de fim.
Publicar e cancelar ao mesmo tempo é resolvido pela versão otimista (`@Version`), também com `409`. Não há
edição: um evento errado é cancelado e criado de novo (ver pendências).

### Contrato da inscrição

| Opção | Prós | Contras |
|---|---|---|
| **Sub-recurso singular da pessoa: `PUT`/`GET`/`DELETE /api/events/{id}/registration`** | Sem id de inscrição na URL: não há como apontar para a de outra pessoa (sem BOLA por id). `PUT` e `DELETE` são idempotentes pela semântica do HTTP, e clientes e proxies podem repeti-los | `PUT` sem corpo é menos comum; o recurso "existe" só para quem chama |
| Ações `POST /api/events/{id}:register` e `:unregister` | Segue a forma `:verbo` da ADR 0005 | A inscrição é um registro persistido, não uma transição de estado do evento; `POST` não é idempotente para cliente e proxy |
| `POST /api/registrations` + `DELETE /api/registrations/{id}` | REST comum | Id da inscrição na URL (BOLA a testar por id); a segunda inscrição precisaria de `409` ou de chave de idempotência |

**Decisão:** sub-recurso singular. `PUT` → `201` com `Location` e `{eventId, registeredAt}` na primeira
vez, `200` com a **mesma** inscrição (mesma data) nas repetições; `GET` → a própria inscrição ou `404`;
`DELETE` → `204`, também quando não havia inscrição. `GET /api/me/registrations` lista as próprias.

**Idempotência sem `Idempotency-Key`**, uma exceção consciente à ADR 0005: a chave de negócio (evento,
conta) já identifica a intenção, é única no banco e é gravada na mesma transação do efeito. O header só
acrescentaria uma segunda chave para a mesma coisa. Se a inscrição passar a ter corpo ou cobrança, a
ADR 0005 volta a valer (ver pendências).

**Sem `If-Match` no `PUT`**, apesar da regra geral do projeto para `PUT`/`PATCH`: o `If-Match` protege um
corpo contra a edição de outra aba (lost update), e esta inscrição não tem corpo nem versão a perder. Os dois
`PUT` simultâneos chegam ao mesmo estado.

**Sem `Idempotency-Key` nas rotas do ADMIN** (`POST /api/admin/events`, `:publish`, `:cancel`), outra exceção
à ADR 0005: repetir a criação depois de perder a resposta gera um segundo rascunho, que só o ADMIN vê e que
ele cancela; repetir `:publish` ou `:cancel` responde `409` sem mudar nada, e o estado se confere com um
`GET`. Implementar a chave (tabela, fingerprint do corpo, resposta guardada, limpeza) não se paga para um
ADMIN só no piloto. Entra quando a área administrativa tiver mais de uma pessoa ou automação.

### Capacidade sob concorrência

"Contar e depois inserir" em READ COMMITTED deixa duas pessoas ocuparem a mesma última vaga (write
skew: cada transação conta sem ver a inserção da outra).

| Opção | Prós | Contras |
|---|---|---|
| **Travar a linha do evento (`select ... for update`), contar e inserir** | É o "bloqueio transacional do registro coordenador" do plano (§4); contagem exata, sem dado derivado guardado; o mesmo lock serializa a inscrição com o cancelamento do evento | As inscrições de um mesmo evento entram uma por vez (fila na linha); a invariante vive no código, sob o lock, e não numa constraint |
| Contador `registered_count` com `CHECK (registered_count <= capacity)` e `UPDATE ... where registered_count < capacity` | Invariante no banco, um comando atômico | Dado derivado que pode divergir das inscrições; o `UPDATE` do JPA no cancelamento sobrescreveria o contador se a coluna estivesse mapeada (lost update), o que exige mapeamento só leitura e cuidado em todo caminho futuro |
| `SERIALIZABLE` com retry de `40001` | Sem lock explícito | Retry da transação inteira por fora do `@Transactional`; com muita gente no mesmo evento, os abortos viram tempestade de retries |

**Decisão:** lock do evento. A ordem em `RegistrationService.register` é: teto de espera do lock →
`findByIdForUpdate` → evento visível (senão `404`) → inscrição já existe? devolve a mesma → perfil completo
(senão `403`) → `Event.ensureAcceptsRegistration(contagem, agora)` (senão `409`) → `insert`. A
`PRIMARY KEY (event_id, account_id)` continua como última defesa da unicidade. Como as filas por evento
são pequenas no piloto (capacidade até 200), o custo é aceito; se o k6 mostrar disputa, o contador com
`CHECK` é o próximo passo.

**Espera pelo lock com teto:** `set_config('lock_timeout', '2s', true)` antes do lock
(`RegistrationRepository.LOCK_TIMEOUT`). Passou disso, `503` com `Retry-After: 1`, sem gravar; repetir é
seguro porque o `PUT` é idempotente. Sem o teto, uma transação presa seguraria conexões até o timeout do
pool (30 s).

Cancelar a inscrição não trava o evento: só diminui a contagem, e uma inscrição concorrente que contou
antes só fica mais conservadora.

### Elegibilidade

A inscrição exige o perfil completo **na hora**: nome, região e 18 anos completos no instante do relógio,
a mesma regra do `complete` do perfil ([ADR 0011](0011-conta-e-perfil.md)). O módulo `events` pergunta ao
`profiles` por uma API publicada nova, `profiles.ProfileCompleteness.isComplete(AccountId, Instant)`, na
raiz do pacote, como manda o `ArchitectureTest`; não lê a tabela `profile`. O critério fica num lugar só.
Perfil incompleto → **`403`** com o que falta: a pessoa está autenticada, mas ainda não pode participar,
e o front precisa distinguir isso do `409` de evento lotado.

### Privacidade

Saber quem vai a um encontro é dado pessoal sensível num app de encontros. Nenhuma rota lista
participantes: o usuário vê só as próprias inscrições, e o ADMIN só a contagem
(`registrationCount`). A resposta de evento para usuários não traz capacidade nem contagem (o conjunto de
chaves é conferido nos testes). Rascunho responde exatamente como evento inexistente (`404`, mesmo corpo).

### Listas e paginação

- Keyset por `(startsAt, id)`, sem `OFFSET`; envelope `{items, nextPageToken}` da ADR 0005, sem total;
  `nextPageToken` é `null` quando não há mais itens (a consulta pede um item a mais para saber).
- `pageSize` de 1 a 50, padrão 10; fora disso, `400`.
- O token é Base64 URL-safe de "início id", **não cifrado**: só carrega o que o cliente já viu na página, e
  toda consulta continua filtrando por estado e por dono. Adulterá-lo só muda onde a lista recomeça. Token
  malformado, ou com instante fora de 1970 a 9999 (o `Instant` aceita anos que o `timestamptz` recusaria
  com erro do banco), → `400`.
- `GET /api/events`: publicados que ainda não começaram. `GET /api/me/registrations`: as próprias
  inscrições em eventos que ainda não acabaram, inclusive cancelados (para a pessoa saber) e em andamento.

### Modelo de dados e limites

`V9__create_event_and_registration.sql`:

- `event (id uuid default uuidv7(), title, description, starts_at, ends_at, capacity, status, created_at,
  version)`, com `CHECK` repetindo os limites; `status` em `CHECK`, porque o conjunto é fixo no código.
- `registration (event_id, account_id, registered_at)`, **PK composta e sem UUID próprio**, como o perfil:
  a inscrição nunca aparece por id numa URL. FKs `on delete restrict` para `event` e `account`
  (como na ADR 0011, apagar exige decidir o destino das inscrições). Índice em `account_id` (FK e "as
  minhas inscrições"); `event_id` já é o início da PK.
- Sem índice parcial para a lista de próximos eventos: a tabela é pequena no piloto; entra com medição.
- Limites: título 1–80 caracteres em uma linha; descrição 1–500, em parágrafos; os dois sem controle,
  NUL nem invisíveis (mesmas regras do perfil); capacidade 2–200 (2 porque o encontro é em pares; 200 é o
  dobro da carga simulada no plano §8); duração até 12 horas; início no futuro e até 365 dias à frente.
- Horários só em ISO 8601 **com fuso** (`Z` ou `-03:00`) e guardados em microssegundos, a precisão do
  `timestamptz`. `spring.jackson.deserialization.accept-float-as-int=false` passa a valer para a API toda:
  `10.5` num inteiro é `400`, e não `10`.

### Relógio

Todos os serviços recebem o `Clock` injetado. Os testes de integração de eventos usam
`TestClockConfiguration` (relógio parado em 2026-10-06T12:00Z, que o teste avança), num contexto Spring
próprio com o próprio PostgreSQL.

## Pendente com o usuário (decisões críticas, só o mínimo implementado)

1. **Cobrança.** As inscrições são gratuitas. Evento pago muda a inscrição (reserva com prazo enquanto o
   checkout não termina, reembolso no cancelamento, `Idempotency-Key` da ADR 0005) e fica para a etapa 4.
2. **Critérios de elegibilidade além de perfil completo e 18+.** Ainda não há: verificação real de idade
   (pendência 1 da ADR 0011), região da pessoa × local do evento, bloqueios do `trustsafety` (duas pessoas
   que se bloquearam no mesmo evento? A pergunta já existe: `Blocking.existsBetween`, da [ADR 0015](0015-bloqueio-e-denuncia.md)), faixa etária, preferências de pareamento, histórico de faltas.
3. **Local e formato do evento** (presencial, online, endereço): não modelados. Endereço preciso é dado
   sensível e mudaria a regra de visibilidade.
4. **Lista de espera** quando o evento lota, e **prazo para cancelar a inscrição** (hoje, até o início)
   com política de falta (no-show).
5. **Aviso aos inscritos** quando o evento é cancelado: depende da outbox ([ADR 0009](0009-outbox-e-eventos.md))
   e do módulo `notifications`.
6. **Edição de evento publicado** (horário, capacidade): não existe. Diminuir a capacidade abaixo dos
   inscritos ou mudar o horário depois das inscrições pede regra de produto.
7. **Mostrar vagas restantes ou "lotado" ao usuário.** Hoje a pessoa só descobre ao tentar (`409`).
8. **Retenção (LGPD)** das inscrições de eventos passados e exclusão de conta: a FK `restrict` obriga o
   fluxo de exclusão a passar por aqui.

## Consequências

- Inscrições de um mesmo evento são serializadas pelo lock; cada inscrição faz uma contagem
  (`count(*)` pela PK). Medir no k6 com o B2s antes de otimizar.
- Não há lista de eventos para o ADMIN (nem de rascunhos): ele guarda o id da criação. Entra quando houver
  a área administrativa do front.
- Não há rate limit na inscrição: a operação é idempotente, autenticada e barata. Reavaliar se surgir abuso.
- Não há trilha de auditoria de quem criou, publicou ou cancelou um evento (Repudiation, abaixo).
- O plano (§4) chama a tabela de `registrations`; aqui as tabelas são no singular, como `account` e `profile`.
- A migration é a `V7`. Se outro ramo em paralelo também criar uma `V7`, o Flyway falha na subida com
  versão duplicada, e uma das duas é renumerada antes do merge.

## Compliance

STRIDE do fluxo (permissão de ADMIN e dado pessoal: quem vai a qual encontro):

| Ameaça | Mitigação | Teste |
|---|---|---|
| Elevation of privilege: usuário comum cria, publica, cancela ou lê evento de admin | Rota `/api/admin/**` exige `ADMIN` | `AdminEventIT.userWithoutTheAdminRoleCannotCreate`, `userWithoutTheAdminRoleIsForbiddenAndChangesNothing`, `anotherRoleIsForbidden` |
| Elevation of privilege: rota nova pública por engano | Negar por padrão | `DenyByDefaultIT` (enumera as rotas novas), `AdminEventIT.anonymousCannotCreate`, `EventCatalogIT.anonymousCannotListOrRead`, `RegistrationIT.anonymousCannotRegisterNorList` |
| Elevation of privilege: menor ou perfil incompleto se inscreve | Perfil completo e 18+ conferidos na hora, pela API do `profiles` | `RegistrationIT.emptyProfileCannotRegister`, `profileWithoutRegionCannotRegister`, `minorCannotRegisterEvenWithAFilledProfile`, `personTurningEighteenOnTheDayCanRegister` |
| Tampering: mass assignment (`status`, `id`, `registrationCount`, `createdAt`, `version`) | DTO só com os campos do ADMIN; chave desconhecida → `400` | `AdminEventIT.serverOwnedFieldIsRejectedWithoutWriting` |
| Tampering: CSRF cria evento, inscreve ou cancela pela sessão web | Token CSRF obrigatório | `AdminEventIT.adminWebSessionWithoutCsrfTokenCannotCreate`, `RegistrationIT.webSessionWithoutCsrfTokenCannotRegisterNorCancel` |
| Tampering: usuário B cancela a inscrição de A (BOLA) | Sem id de inscrição na rota; o serviço só recebe a conta autenticada | `RegistrationIT.anotherUserNeitherSeesNorCancelsTheRegistration` |
| Tampering: corrida ultrapassa a capacidade | Lock do evento + contagem na mesma transação | `RegistrationIT.concurrentRegistrationsNeverExceedTheCapacity`, `lastPlaceIsTakenAndThenTheEventIsFull` |
| Tampering: clique duplo ou retry duplica a inscrição | PK (evento, conta) + consulta sob o lock; repetição devolve a mesma | `RegistrationIT.concurrentRepeatedRegistrationsCreateOnlyOne`, `registeringAgainAnswersTheSameRegistration` |
| Tampering: publicar e cancelar ao mesmo tempo grava os dois | `@Version` no evento | `AdminEventIT.concurrentPublishesPublishOnce`, `concurrentPublishAndCancelLeaveTheStateOfTheActionsThatSucceeded` |
| Tampering: inscrição entra num evento que acabou de ser cancelado | O cancelamento espera o lock da inscrição; a inscrição que espera vê o evento cancelado | `RegistrationIT.registrationRacingTheEventCancellationEndsConsistent` |
| Tampering: inscrição em evento cancelado, começado ou rascunho | Regras no domínio, conferidas com o evento travado | `RegistrationIT.cancelledEventRefusesRegistration`, `startedEventRefusesRegistration`, `draftLooksLikeAnEventThatDoesNotExist`; `EventTest` |
| Information disclosure: lista de participantes ou contagem para usuários | Nenhuma rota de participantes; respostas com allowlist de chaves | `EventCatalogIT.readsAPublishedEvent`, `listsOnlyPublishedEventsThatHaveNotStartedInStartOrder` (conjunto exato), `RegistrationIT.adminSeesHowManyPeopleRegisteredButNotWho` |
| Information disclosure: usuário B vê as inscrições de A | Consultas filtram pela conta autenticada | `RegistrationIT.anotherUserNeitherSeesNorCancelsTheRegistration`, `listsOwnRegistrationsOfEventsThatHaveNotEndedInStartOrder` |
| Information disclosure: rascunho descoberto por id | Rascunho responde igual a inexistente | `EventCatalogIT.draftLooksExactlyLikeAnEventThatDoesNotExist` |
| Denial of service: entrada inválida ou enorme vira `500` | Limites em todo campo, horário com fuso, inteiro estrito, `pageSize` e token limitados | `AdminEventIT.invalidInputIsRejectedWithoutWriting`, `acceptsValuesOnTheBorder`, `sqlInTheTitleIsStoredAsPlainText`, `EventCatalogIT.invalidPageSizeIsABadRequest`, `invalidPageTokenIsABadRequest` (inclusive ano fora do `timestamptz`), `RegistrationIT.invalidPageOfOwnRegistrationsIsABadRequest`; `PageTokenTest`, `PageSizeTest` |
| Denial of service: inscrição presa no lock segura conexões | `lock_timeout` de 2 s → `503` com `Retry-After` | `RegistrationIT.registrationThatWaitsTooLongForTheEventLockIsRefused` |

Repudiation (quem criou, publicou ou cancelou) não é tratada: não há trilha de auditoria. Entra junto com a
administração, se ela precisar do histórico.

Regras de domínio sem Spring: `EventTest` (transições, bordas do intervalo semiaberto, capacidade),
`EventScheduleTest`, `EventTitleTest`, `EventDescriptionTest`, `CapacityTest`. Fronteira entre módulos:
`ArchitectureTest.modulesUseOnlyPublishedApisOfOtherModules` e a classificação de `events` em
`ArchitectureTest.SUPPORTING_MODULES`.
