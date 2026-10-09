# 0016. Eventos e inscrições

- **Status:** Aceita, provisória. Decidido na sessão autônoma de 2026-10-05; revisar com o usuário.
- **Data:** 2026-10-06
- **Relacionadas:** [ADR 0004](0004-identificadores-e-unicidade.md), [ADR 0005](0005-contrato-da-api.md),
  [ADR 0006](0006-rate-limit-no-postgresql.md), [ADR 0007](0007-estilo-por-modulo.md),
  [ADR 0011](0011-conta-e-perfil.md)

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
- `maxPageSize` de 1 a 50, padrão 10; fora disso, vazio ou não inteiro, `400`. O nome e o tratamento são os
  de `GET /api/me/blocked-accounts` ([ADR 0015](0015-bloqueio-e-denuncia.md)), para o contrato ter uma
  paginação só.
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

### Rate limit

A versão inicial não limitava a inscrição, sob o argumento de que a operação é idempotente, autenticada e
barata. O argumento falha no custo: cada `PUT` abre uma transação, trava a linha do evento até o commit e
faz uma contagem, mesmo quando só devolve a inscrição que já existe. Uma conta que repete a chamada sem
parar disputa o lock com todas as outras inscrições do evento (o teto de 2 s só transforma a espera em
`503`), e a idempotência faz cada repetição responder `200`, então nada sinaliza o abuso. A [ADR
0006](0006-rate-limit-no-postgresql.md), que já limita denúncias, resolve isso com o mesmo mecanismo.

| Opção | Prós | Contras |
|---|---|---|
| Sem limite, só o `lock_timeout` | Nada a mudar | Uma conta aumenta a latência de todo o evento; o `503` cai em quem não abusou |
| Limite por IP, como a fila de espera | Barra antes de autenticar | A inscrição exige login e o IP é compartilhado (rede de casa, operadora): pune quem está na mesma rede; trocar de rede renova o limite |
| **Limite por conta, um bucket para `PUT` e `DELETE`** | A conta é o que o abuso repete; trocar de rede não renova; vale entre réplicas | Uma ida ao banco por chamada (transação curta, em outra conexão e antes da transação da inscrição) |
| Dois buckets, um por operação | Valores independentes | Inscrever e cancelar em alternância gastaria um saldo cada, dobrando o abuso possível sem motivo |

**Decisão:** um bucket por conta, chave `registration:<conta>` na tabela `rate_limit_bucket`, compartilhado
por `PUT` e `DELETE /api/events/{eventId}/registration`. O `DELETE` não trava o evento, mas apaga uma linha e
é a outra metade do ciclo inscrever e cancelar: limitar só o `PUT` deixaria o laço alternado correr livre
pelo `DELETE`. Acima do limite, `429` com `Retry-After` em segundos (teto documentado de 86400, o de um
dia, garantido porque o período configurado é validado no boot); com o bucket impossível de contar, `503`
com `Retry-After: 1` e nada é feito (falha fechada, [ADR 0006](0006-rate-limit-no-postgresql.md)).

**Valores:** 60 chamadas por hora, repostas aos poucos (uma por minuto), em
`duora.events.registration-rate-limit.capacity` e `.period`. Uso humano normal é de um punhado de chamadas
por sessão: ver a lista, inscrever-se em uns poucos eventos, desistir de um e voltar atrás. Mesmo uma pessoa
indecisa, que alterne dez vezes entre três eventos, fica em 30 chamadas. O teto de 60 deixa folga para isso e
para retentativas do front; e uma conta que o esgote espera, no pior caso, um minuto por chamada. A
capacidade é positiva e o período vai até um dia: qualquer outro valor derruba a subida
(`RequiredRateLimitSettingsIT`).

**A repetição idempotente também gasta.** O `200` da mesma inscrição, o `204` de cancelar o que não existe e
o `404` de evento inexistente consomem uma chamada. O custo que o limite protege é o lock e a transação, e a
repetição os paga por inteiro; isentá-la deixaria justamente o abuso mais barato (repetir a mesma chamada)
sem freio. O `404` gasta também, o que limita quem tenta adivinhar ids de evento. Em compensação, o front
nunca precisa repetir a chamada que já deu certo: um clique duplo gasta duas, de 60. Não gastam: id que não
é UUID (`400` antes do controller), falta de credencial (`401`) e sessão sem CSRF (`403`), que são
recusados antes de tocar o banco.

O limite é consumido no controller, antes de chamar o serviço: ele protege a borda HTTP, e a ida ao bucket
acontece fora da transação da inscrição, para a espera pelo bucket não segurar o lock do evento.

### Relógio

Todos os serviços recebem o `Clock` injetado. Os testes de integração de eventos usam
`TestClockConfiguration` (relógio parado em 2026-10-06T12:00Z, que o teste avança), num contexto Spring
próprio com o próprio PostgreSQL.

### O limite por conta numa rajada de inscrições (2026-10-08) **(autônoma)**

O k6 ([ADR 0022](0022-teste-de-carga-com-k6.md)) mostrou que os `503` de uma abertura de inscrições não vêm
do `lock_timeout` de 2 s, como esta ADR previa, e sim do limite por conta. A causa está no Bucket4j: o teto de
cada comando (`RateLimitConfiguration.REQUEST_TIMEOUT`, então 1 s) começa a contar **antes** de pedir a conexão
ao pool e só é conferido entre os passos. Numa rajada, o comando espera na fila do pool de 10 conexões atrás
das transações de inscrição, que seguram a conexão enquanto esperam o lock do evento; recebe a conexão depois
de 1 s, descobre que o prazo passou e responde `503` (falha fechada, `Retry-After: 1`, nada gravado). O teto
não corta a espera pelo pool; só a transforma em erro depois que ela já aconteceu. Quem corta a espera é o
`connectionTimeout` do Hikari (30 s).

Medição: 200 contas ao mesmo tempo num evento de 100 vagas (2x o plano), 1 vCPU e 2 GiB na API, três subidas
por opção, primeira passada com a JVM fria; detalhes e a tabela completa em `tools/load/RESULTS.md`.

| Opção | 503 na passada fria (3 subidas) | p95 frio (ms) | Prós | Contras |
|---|---|---|---|---|
| Antes: teto de 1 s, pool de 10 | 20, 22, 11 | 2585 a 2788 | — | 1 conta em 10 recebe `503` e repete |
| (a) Pool de 20 | 22, 22, 22 | 2336 a 2754 | Só configuração | Não resolve: as 10 conexões a mais ficam esperando o lock (19 de 20 esperando); passa da regra de ~4 conexões por núcleo do banco: o B1ms tem 1 vCore e até ~50 conexões, e 3 réplicas com 20 já seriam 60 |
| (b) Pool próprio de 2 conexões para o limitador | 35, 6, 31 | 2198 a 2371 | Isola o limitador do lock | Pior: as 200 chamadas fazem fila nas 2 conexões, e a primeira chamada de cada conta precisa de duas transações (cria o bucket e consome); um `DataSource` a mais para configurar e fechar |
| (c) **Teto de 3 s no comando do limitador** | **0, 0, 0** (e 0 em mais 3 subidas) | 2651 a 3487 | Uma constante; o limitador continua falhando fechado com pool ocupado além de 3 s | O pedido que antes voltava `503` em ~1 s agora espera e termina; o p95 por pedido sobe um pouco, mas o tempo da pessoa (pedido e repetição) não piora, e o máximo cai |
| (c) Teto de 2 s | 0, 0, 0, 1, 0, 0 (6 subidas) | 2530 a 2998 | Igual ao teto do lock | Ainda deixou um `503` escapar |
| (d) `lock_timeout` de 1 s em vez de 2 s | 24, 16, 28 | 2576 a 2812 | Uma constante | Não resolve: nenhuma espera individual pelo lock passou de 1 s; o que demora é a fila do pool, e não o lock |
| (e) Semáforo de 4 inscrições por réplica antes de pegar conexão (bulkhead) | 0, 0, 0 | 2191 a 2649 | Resolve e baixa a latência; só 3 conexões esperando lock em vez de 9 | Código novo: semáforo em memória por réplica, teto de espera e `503` próprios, testes; global (um evento quente segura os outros) ou por evento (mapa com limpeza); acopla o número de permissões ao tamanho do pool |

**Decisão:** (c), teto de 3 s. É a mudança mais simples que zerou os `503` nas seis subidas de estresse, sem
conexão a mais no banco e sem mecanismo novo. A falha fechada continua: com o pool ocupado além de 3 s, ou o
banco fora, o limitador responde `503`. O valor vale para todos os limites por conta (denúncia, rodada,
decisão, inscrição), que dividem a mesma tabela e o mesmo pool. Com a máquina mais carregada (load average
de 6 a 7 por outros processos), as passadas intercaladas antes e depois deram 16, 49 e 51 `503` antes e 0, 2
e 0 depois: o teto maior absorve a rajada medida, mas não promete zero quando a CPU passa do que foi medido.
Se a abertura de inscrições real mostrar `503` de novo, o próximo passo é o semáforo (e), que já tem números.

Testes: `AccountRateLimitIT.waitsForAPoolThatIsBusyForLessThanTheTimeout` (o pool ocupado por 1,5 s, que
com o teto de 1 s virava `503`) e `rejectsWhenThePoolStaysBusyBeyondTheTimeout` (ocupado além do teto, falha
fechada). A spec não muda: o `503` com `Retry-After: 1` já estava documentado.

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
9. **Espera pelo pool e tamanho do pool** (seção "O limite por conta numa rajada"). A espera por conexão só é
   cortada pelo `connectionTimeout` do Hikari, de 30 s: encurtá-lo (Fail Fast) pede mapear a falta de conexão
   para `503` com `Retry-After` na API toda, e não só aqui. E o pool padrão de 10 por réplica, com até 3
   réplicas, já passa das ~4 conexões ativas por núcleo do B1ms; a medição mostrou que conexão a mais só
   aumenta a fila no lock. Diminuir o pool ou trocar o banco é decisão de infraestrutura (ADR 0014).

## Consequências

- Inscrições de um mesmo evento são serializadas pelo lock; cada inscrição faz uma contagem
  (`count(*)` pela PK). Medir no k6 com o B2s antes de otimizar.
- Não há lista de eventos para o ADMIN (nem de rascunhos): ele guarda o id da criação. Entra quando houver
  a área administrativa do front.
- A inscrição e o cancelamento têm limite por conta (seção "Rate limit"): 60 por hora, somados. A tabela
  `rate_limit_bucket` ganha uma linha por conta que se inscreve, apagada pela limpeza da [ADR
  0006](0006-rate-limit-no-postgresql.md) depois da reposição. O limite adiciona uma ida ao banco por
  chamada; medir no k6 com o B2s junto com o lock.
- Não há trilha de auditoria de quem criou, publicou ou cancelou um evento (Repudiation, abaixo).
- O plano (§4) chama a tabela de `registrations`; aqui as tabelas são no singular, como `account` e `profile`.
- A migration é a `V7`. Se outro ramo em paralelo também criar uma `V7`, o Flyway falha na subida com
  versão duplicada, e uma das duas é renumerada antes do merge.
- **Medido no k6** ([ADR 0022](0022-teste-de-carga-com-k6.md), `tools/load/RESULTS.md`): 100 contas ao mesmo
  tempo num evento de 50 vagas dão sempre 50 inscritas, p95 de 1,5 a 1,7 s com a JVM fria e 0,5 s aquecida
  (1 vCPU); o contador com `CHECK` não se justifica por esses números. O teto de 2 s do lock nunca disparou:
  os raros `503` (0 a 1 por execução; 18 com 200 contas) vieram do teto de 1 s do limite por conta esperando
  conexão do pool de 10. Com o teto em 3 s (seção "O limite por conta numa rajada"), as rajadas de 200
  contas deixaram de dar `503` nas subidas medidas.

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
| Denial of service: entrada inválida ou enorme vira `500` | Limites em todo campo, horário com fuso, inteiro estrito, `maxPageSize` e token limitados | `AdminEventIT.invalidInputIsRejectedWithoutWriting`, `acceptsValuesOnTheBorder`, `sqlInTheTitleIsStoredAsPlainText`, `EventCatalogIT.invalidPageSizeIsABadRequest`, `invalidPageTokenIsABadRequest` (inclusive ano fora do `timestamptz`), `RegistrationIT.invalidPageOfOwnRegistrationsIsABadRequest`; `PageTokenTest`, `PageSizeTest` |
| Denial of service: inscrição presa no lock segura conexões | `lock_timeout` de 2 s → `503` com `Retry-After` | `RegistrationIT.registrationThatWaitsTooLongForTheEventLockIsRefused` |
| Denial of service: uma conta repete `PUT`/`DELETE` e disputa o lock do evento | 60 chamadas por hora por conta, entre réplicas; `429` + `Retry-After`; repetição idempotente também gasta; um bucket para as duas operações | `RegistrationRateLimitIT.callsAboveTheLimitAreRejectedWithRetryAfterAndChangeNothing`, `idempotentRepeatsSpendTheLimit`, `registeringAndCancellingShareOneLimit`, `callsForUnknownEventsSpendTheLimit`, `theLimitIsCountedForEachAccountSeparately`; `AccountRateLimitIT` |
| Denial of service: rajada de inscrições recebe `503` do limitador antes de chegar ao lock | Teto de 3 s no comando do limitador, que conta também a fila do pool | `AccountRateLimitIT.waitsForAPoolThatIsBusyForLessThanTheTimeout`; k6 de estresse em `tools/load/RESULTS.md` |
| Denial of service: limite que cai com o banco deixa passar | Falha fechada: `503` com `Retry-After: 1`, sem gravar nem cancelar | `RegistrationRateLimitIT.registrationIsRefusedWithoutWritingWhenTheLimitCannotBeCounted`, `cancellationIsRefusedWithoutChangingAnythingWhenTheLimitCannotBeCounted`; `AccountRateLimitIT.rejectsWhenTheStoreIsDown`, `rejectsWhenThePoolStaysBusyBeyondTheTimeout` |
| Denial of service: configuração com valor que desliga ou quebra o limite | Capacidade positiva e período de até um dia validados no boot | `RequiredRateLimitSettingsIT.applicationRefusesToStartWithAnInvalidLimit`; `AccountRateLimitIT.refusesACapacityThatIsNotPositive`, `refusesAPeriodOutsideZeroToOneDay` |

Repudiation (quem criou, publicou ou cancelou) não é tratada: não há trilha de auditoria. Entra junto com a
administração, se ela precisar do histórico.

Contrato ([ADR 0012](0012-contrato-openapi.md)): `OpenApiContractIT.eventsAndRegistrationsDocumentTheirContract`
confere na spec os erros, o `Location` dos `201`, o `503` com `Retry-After`, a paginação e os estados. No
Spectral, a regra `owasp:api2:2023-no-credentials-in-url` fica desligada para o `pageToken` de
`GET /api/events` e `GET /api/me/registrations`, como no `trustsafety`. No Schemathesis, o `400` entra entre
as respostas esperadas para corpo válido em `POST /api/admin/events` (início no passado ou além de um ano,
duração acima de 12 horas, caracteres invisíveis) e nas duas listas (pageToken que a API não gerou). O
fuzzing achou um defeito, corrigido com teste: `GET` e `OPTIONS` em `/api/admin/events/{id}:publish` e
`:cancel` caíam na rota de leitura com o id `"uuid:publish"`, e o `Allow` anunciava um `GET` que a ação
não tem (`AdminEventIT.actionRouteAnswersOnlyToPost`).

Regras de domínio sem Spring: `EventTest` (transições, bordas do intervalo semiaberto, capacidade),
`EventScheduleTest`, `EventTitleTest`, `EventDescriptionTest`, `CapacityTest`. Fronteira entre módulos:
`ArchitectureTest.modulesUseOnlyPublishedApisOfOtherModules` e a classificação de `events` em
`ArchitectureTest.SUPPORTING_MODULES`.
