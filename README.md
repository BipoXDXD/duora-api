# Duora API

[![CI](https://github.com/BipoXDXD/duora-api/actions/workflows/ci.yml/badge.svg)](https://github.com/BipoXDXD/duora-api/actions/workflows/ci.yml)

Backend do Duora. Java 25, Spring Boot 4, PostgreSQL e Flyway.

O projeto está no início: há uma **waitlist** de pré-lançamento e a **conta e o perfil** do
usuário autenticado (etapa 1 do plano). Matching, chat, eventos e pagamentos vêm depois.

## Pré-requisitos

- JDK 25
- Docker em execução, para os testes de integração e para rodar a aplicação localmente

O Maven vem pelo wrapper (`./mvnw`); não é preciso instalá-lo.

O compilador roda com `-Xlint:all` e `-Werror`: qualquer aviso do `javac`, em `main` ou `test`, quebra o build.

## Rodando localmente

A API exige a configuração de autenticação (veja [Autenticação](#autenticação)). O script do
tenant grava as variáveis em `~/.config/duora/dev.env`:

```bash
set -a; source ~/.config/duora/dev.env; set +a
./mvnw spring-boot:run
```

Os logs saem em JSON (veja [Logs e correlation ID](#logs-e-correlation-id)); para lê-los em texto
no terminal, acrescente `-Dspring-boot.run.profiles=plain-logs` (vale também para
`spring-boot:test-run`).

Sobe a aplicação em `http://localhost:8080` e, junto, o PostgreSQL do `compose.yaml` (suporte a
Docker Compose do Spring Boot). Os dados ficam entre execuções: ao parar a aplicação o container
é só parado. O Flyway aplica as migrations na subida.

Sem tenant configurado, use:

```bash
./mvnw spring-boot:test-run
```

Sobe com um PostgreSQL descartável via Testcontainers e valores fictícios de autenticação: as
rotas públicas funcionam, as protegidas respondem `401` a requisições sem token.

Para apagar o banco do compose: `docker compose down -v`.

```bash
curl -i -X POST localhost:8080/api/waitlist \
  -H 'Content-Type: application/json' \
  -d '{"email": "ana@example.com"}'
```

## Imagem Docker

```bash
docker build -t duora-api:local .
infra/docker/smoke-test.sh duora-api:local
```

O `Dockerfile` compila com o JDK e roda só com o JRE, em camadas, como usuário sem privilégios; as
imagens base são fixadas por digest. O smoke test sobe um PostgreSQL descartável, roda o job de
migração da imagem, sobe a API com o papel restrito do banco e confere health, probes, usuário e
encerramento por SIGTERM. O CI faz os dois a cada push
([ADR 0008](docs/adr/0008-imagem-e-let-it-crash.md) e [0014](docs/adr/0014-infraestrutura-do-piloto-na-azure.md)).

A imagem liga o perfil `behind-proxy`, porque só roda atrás do ingress do Container Apps. Além das
variáveis do Entra (abaixo), ela exige esta, e não sobe sem ela ou com ela em branco:

| Variável | Valor |
|---|---|
| `DUORA_TRUSTED_PROXIES` | Faixa de onde o ingress conecta à aplicação, em CIDR separados por vírgula (ex.: a subnet de infraestrutura do ambiente) |

Quem definir `SPRING_PROFILES_ACTIVE` no deploy mantém `behind-proxy` na lista
([ADR 0006](docs/adr/0006-rate-limit-no-postgresql.md)).

## Deploy na Azure

A infraestrutura do piloto (Container Apps, PostgreSQL 18 privado, Key Vault, logs e alertas) está em
Bicep em [`infra/azure/`](infra/azure/README.md), com o passo a passo do primeiro apply, que é manual.
Cada commit da `main` vai para homologação e produção pelo workflow **Deploy**, disparado à mão: ele
publica a imagem, migra o banco com um Container Apps Job e troca a revisão da API. Decisões e custo
estimado: [ADR 0014](docs/adr/0014-infraestrutura-do-piloto-na-azure.md).

No deploy, quem migra é o job, com a credencial de administração; a API conecta com um papel que só
lê e escreve dados e roda com `SPRING_FLYWAY_ENABLED=false`. O job é a própria imagem com outro ponto
de entrada:

```bash
java -cp app.jar bipo.tech.duoraapi.migration.DatabaseMigration
# DUORA_MIGRATION_JDBC_URL, DUORA_MIGRATION_USERNAME, DUORA_MIGRATION_PASSWORD,
# DUORA_APP_DB_USERNAME e DUORA_APP_DB_PASSWORD
```

## Testes

| Comando | O que roda | Precisa de Docker |
|---|---|---|
| `./mvnw test` | Testes rápidos (`*Test`): domínio puro e ArchUnit | Não |
| `./mvnw verify` | Os rápidos + integração (`*IT`): `@SpringBootTest`, `@DataJpaTest`, migrations Flyway | Sim |

Os testes seguem o estilo de Khorikov ([ADR 0003](docs/adr/0003-estilo-de-testes.md)): o domínio é
testado sem mocks; o banco próprio é sempre real (Testcontainers com `@ServiceConnection`, nunca
H2); controllers e segurança passam por `@SpringBootTest` + MockMvc com os serviços de verdade.
Mockito (`@MockitoBean`) só entra para dependências externas que a API não controla, como o Entra,
o Web PubSub ou um serviço de e-mail.

| Tipo | Exemplo |
|---|---|
| Unitário puro (JUnit + AssertJ) | `waitlist/domain/EmailAddressTest`, `profiles/domain/ProfileTest` |
| `@SpringBootTest` + MockMvc, ponta a ponta | `waitlist/JoinWaitlistIT`, `profiles/ProfileIT` |
| Concorrência (primeiro acesso, edições simultâneas) | `identity/AccountProvisioningIT`, `profiles/ProfileIT` |
| Spring Security (401/403) | `waitlist/WaitlistSecurityIT` |
| Validação de JWT (tokens reais, JWKS local) | `config/BearerTokenValidationIT` |
| Login web (BFF): sessão, cookie, CSRF, logout | `config/WebLoginIT` |
| Subida sem configuração obrigatória | `config/RequiredAuthenticationSettingsIT`, `config/RequiredTrustedProxySettingsIT` |
| HTTP real pelo Tomcat (`X-Forwarded-For`, `/error`) | `config/ForwardedClientAddressIT`, `config/UnexpectedErrorIT` |
| Correlation ID (`X-Request-Id`, `traceparent`) | `config/RequestCorrelationIT` |
| Canário de dados sensíveis no log | `config/SensitiveDataLoggingIT` |
| Contrato OpenAPI: sem drift, acesso à documentação | `config/OpenApiContractIT`, `config/ApiDocsAccessIT` |
| `@DataJpaTest` + PostgreSQL | `waitlist/domain/WaitlistEntryRepositoryIT` |
| Migrations Flyway | `FlywayMigrationIT` |
| Regras de arquitetura (ArchUnit) | `ArchitectureTest`, `ArchitectureRulesTest` |

### Cobertura

O JaCoCo gera relatório sem meta mínima por enquanto:

- `target/site/jacoco/index.html`: só os testes rápidos (após `test`)
- `target/site/jacoco-all/index.html`: rápidos + integração (após `verify`)

No CI, o relatório completo fica como artefato `jacoco-report`.

## Estrutura

Pacotes por módulo, cada um dividido em camadas:

```
bipo.tech.duoraapi
├── config/              # segurança, sessão, relógio, rate limit compartilhado
├── connections/         # decisão privada depois da rodada e conexões por interesse mútuo
│   ├── adapter/         # repositórios JDBC; advisory lock por par e rodada
│   ├── api/             # decisão como sub-recurso singular da rodada, lista das próprias conexões
│   ├── application/     # DecisionService (par e bloqueio pelas APIs publicadas), ConnectionService
│   └── domain/          # decisão final, regra do interesse mútuo, par normalizado, ports
├── events/              # eventos e inscrições; EventRoster é a API publicada
│   ├── api/             # rotas do ADMIN, lista e inscrição; paginação por keyset
│   ├── application/     # casos de uso (administração, catálogo, inscrição)
│   └── domain/          # evento e suas regras de estado, repositórios
├── identity/            # conta interna; AccountId é a API publicada
│   ├── api/             # abre a conta e a entrega ao parâmetro AccountId
│   ├── application/     # AccountService
│   └── domain/          # conta, identidade externa, repositório
├── matching/            # rodadas de pareamento de um evento; Pairings é a API publicada
│   ├── adapter/         # repositório JDBC das rodadas e assentos
│   ├── api/             # rota do ADMIN e o próprio par
│   ├── application/     # RoundService (inscritos e bloqueios pelas APIs publicadas)
│   └── domain/          # sorteio puro (emparelhamento máximo), rodada, port do repositório
├── migration/           # job de migração do deploy (Flyway + papel restrito da API)
├── profiles/            # perfil do próprio usuário e GET /api/me; ProfileCompleteness é a API publicada
│   ├── api/
│   ├── application/
│   └── domain/          # regras 18+, value objects, repositório
├── trustsafety/         # bloqueio e denúncia; Blocking é a API publicada
│   ├── adapter/         # repositórios JDBC, cota de denúncias
│   ├── api/
│   ├── application/     # BlockService, ReportService, port da cota
│   └── domain/          # bloqueio, denúncia, motivos, ports dos repositórios
└── waitlist/
    ├── api/             # controller, DTOs, rate limit
    ├── application/     # casos de uso (WaitlistService)
    └── domain/          # entidade, value objects, repositório
```

Os testes espelham a mesma estrutura. Um módulo só usa de outro a API publicada, que são as
classes na raiz do pacote dele (como `identity.AccountId`); as camadas são internas
([ADR 0011](docs/adr/0011-conta-e-perfil.md)). Módulos de apoio (waitlist, identity, profiles, events) usam
essas camadas simples; os do core (pareamento, minijogos, conexões, trustsafety) usam ports & adapters, com
domínio sem framework. O `ArchitectureTest` cobra a classificação e a direção das dependências
([ADR 0007](docs/adr/0007-estilo-por-modulo.md)).

As migrations ficam em `src/main/resources/db/migration`. O Hibernate só valida o schema
(`ddl-auto=validate`); quem o cria e altera é o Flyway.

## API

| Método | Rota | Acesso | Resposta |
|---|---|---|---|
| `POST` | `/api/waitlist` | Público | `202`, para e-mail novo ou repetido |
| `GET` | `/api/admin/waitlist/stats` | `ADMIN` | `200` com `{"total": n}` |
| `GET` | `/api/me` | Autenticado | `200` com `{"displayName": "...", "profileComplete": false}` (`displayName` é o nome do Entra, `null` se não houver); `401` sem sessão. Abre a conta interna no primeiro acesso |
| `GET` | `/api/me/profile` | Autenticado | `200` com `{displayName, birthDate, bio, region, complete}` e `ETag` com a versão (`"0"` antes da primeira edição) |
| `PATCH` | `/api/me/profile` | Autenticado | Edição parcial: campo ausente não muda, `null` apaga. Exige `If-Match` com o `ETag` lido: sem ele `428`, desatualizado `412`. `200` com o perfil e o `ETag` novo; `400` para valor inválido ou campo desconhecido; `409` ao trocar a data de nascimento (`reason` `BIRTH_DATE_ALREADY_SET`) |
| `POST` | `/api/accounts/{accountId}:block` | Autenticado | Bloqueia outra conta: `204`, também se já bloqueada (mantém a data do primeiro bloqueio); `400` para si mesmo ou id que não é UUID; `404` sem conta com esse id |
| `POST` | `/api/accounts/{accountId}:unblock` | Autenticado | Desfaz o próprio bloqueio: `204`, também sem bloqueio; o bloqueio feito pela outra pessoa continua valendo |
| `GET` | `/api/me/blocked-accounts` | Autenticado | Quem o usuário bloqueou, do mais recente ao mais antigo: `{items: [{accountId, blockedAt}], nextPageToken}`, `maxPageSize` de 1 a 100 (padrão 20), `pageToken` da página anterior; `400` fora disso |
| `POST` | `/api/reports` | Autenticado | Denuncia outra conta com `{reportedAccountId, reason, description}`; `reason` de lista fechada, `description` até 1000 caracteres e obrigatória com `OTHER`. `201` com `Location` e a denúncia (`status` `OPEN`); `400` para si mesmo ou valor inválido; `404` sem conta; `429` com `Retry-After` acima da cota; `503` com a cota indisponível. Denunciar não bloqueia |
| `GET` | `/api/reports/{id}` | Autenticado | A própria denúncia; de outra pessoa ou inexistente, `404` |
| `POST` | `/api/admin/events` | `ADMIN` | Cria um rascunho com `{title, description, startsAt, endsAt, capacity}` (horários ISO 8601 com fuso). `201` com `Location` e o evento; `400` para valor inválido ou campo desconhecido |
| `GET` | `/api/admin/events/{id}` | `ADMIN` | `200` com o evento, o `status` (`DRAFT`, `PUBLISHED`, `CANCELLED`) e `registrationCount`; nunca a lista de inscritos |
| `POST` | `/api/admin/events/{id}:publish` | `ADMIN` | `200` com o evento publicado; `409` se não for rascunho ou já tiver começado (`reason` `EVENT_ALREADY_PUBLISHED`, `EVENT_CANCELLED`, `EVENT_STARTED` ou `EVENT_ENDED`) |
| `POST` | `/api/admin/events/{id}:cancel` | `ADMIN` | `200` com o evento cancelado; `409` se já cancelado ou encerrado (`reason` `EVENT_CANCELLED` ou `EVENT_ENDED`) |
| `GET` | `/api/events` | Autenticado | Publicados que ainda não começaram, por início: `{items, nextPageToken}`, `maxPageSize` de 1 a 50 (padrão 10; vazio ou não inteiro `400`), `pageToken` da página anterior |
| `GET` | `/api/events/{id}` | Autenticado | `200` com `{id, title, description, startsAt, endsAt, status, currentRound}` (`currentRound`: a última rodada iniciada, ou `null`); rascunho ou inexistente `404` |
| `PUT` | `/api/events/{id}/registration` | Autenticado | Inscreve quem chama: `201` com `Location` na primeira vez, `200` com a mesma inscrição nas repetições; `403` com perfil incompleto ou menor de idade (`reason` `PROFILE_INCOMPLETE` ou `UNDERAGE`); `409` com evento cancelado, começado, encerrado ou lotado (`EVENT_CANCELLED`, `EVENT_STARTED`, `EVENT_ENDED`, `EVENT_FULL`) |
| `GET` | `/api/events/{id}/registration` | Autenticado | A própria inscrição, ou `404` |
| `DELETE` | `/api/events/{id}/registration` | Autenticado | Cancela a própria inscrição: `204`, também sem inscrição; `409` depois do início (`EVENT_STARTED` ou `EVENT_ENDED`) |
| `GET` | `/api/me/registrations` | Autenticado | As próprias inscrições em eventos que ainda não acabaram, com o resumo do evento, no mesmo envelope paginado |
| `PUT` | `/api/admin/events/{eventId}/rounds/{number}` | `ADMIN` | Inicia a rodada com o evento em andamento e sorteia os pares entre os inscritos. `201` com `Location` na primeira vez, `200` com a mesma rodada nas repetições, inclusive simultâneas; `409` fora do horário, com evento cancelado ou rascunho (`EVENT_NOT_UNDERWAY`), ou sem a rodada anterior (`ROUND_OUT_OF_SEQUENCE`); só contagens (`pairCount`, `sittingOutCount`), nunca quem |
| `GET` | `/api/admin/events/{eventId}/rounds/{number}` | `ADMIN` | A mesma resposta da rodada, ou `404` |
| `GET` | `/api/events/{eventId}/rounds/{number}/pairing` | Autenticado | O próprio par: `{eventId, roundNumber, partnerAccountId}`, com `null` para quem ficou de fora; `404` para quem não estava no sorteio, igual a rodada inexistente |
| `PUT` | `/api/events/{eventId}/rounds/{number}/decision` | Autenticado | Decide em privado se continua em contato com o par da rodada: `{"interested": true\|false}`. `201` com `Location` na primeira vez, `200` repetindo a mesma escolha, `409` com a outra (decisão final, `reason` `DECISION_ALREADY_MADE`); `404` para quem não formou par. A resposta nunca diz nada da decisão do par; com dois "sim" e sem bloqueio, a conexão aparece em `/api/me/connections` |
| `GET` | `/api/events/{eventId}/rounds/{number}/decision` | Autenticado | A própria decisão, ou `404` |
| `GET` | `/api/me/connections` | Autenticado | As próprias conexões, da mais recente à mais antiga: `{items: [{accountId, connectedAt}], nextPageToken}`, `maxPageSize` de 1 a 100 (padrão 20) |
| `GET` | `/actuator/health` | Público | Estado da aplicação |

### Contrato (OpenAPI)

A spec OpenAPI 3.1 versionada fica em [`docs/openapi.json`](docs/openapi.json); o `duora-web` gera
os tipos dela com `openapi-typescript`. Ela é gerada pela aplicação (springdoc), e o
`OpenApiContractIT` falha se a versionada divergir da gerada. Depois de mudar um endpoint:

```bash
./mvnw verify                          # falha apontando a divergência e grava target/openapi.json
cp target/openapi.json docs/openapi.json
```

Em produção a documentação não existe. Com o perfil `api-docs`
(`SPRING_PROFILES_ACTIVE=api-docs`), a spec fica em `/api/admin/openapi` (`.yaml` também) e o
Swagger UI em `/api/admin/swagger-ui.html`, só para `ADMIN`.

O CI confere o contrato de três jeitos ([ADR 0012](docs/adr/0012-contrato-openapi.md)):

| Verificação | Rodar localmente |
|---|---|
| Lint com Spectral e o ruleset OWASP | `npm ci --ignore-scripts --prefix tools/contract && npm run --prefix tools/contract lint` |
| Breaking change contra a `main` (oasdiff) | `tools/contract/check-breaking.sh origin/main` |
| Fuzzing com Schemathesis contra a imagem | `docker build -t duora-api:local . && infra/docker/contract-test.sh duora-api:local` |

O fuzzing parte de uma seed: o CI de PR usa uma fixa, e o workflow semanal `contract-fuzz.yml`
(também manual) usa uma aleatória e a imprime. Para repetir uma execução, rode o script com
`SCHEMATHESIS_SEED=<seed>`. Toda resposta documenta o header `X-Request-Id`, e o `ProblemDetail` do
`500` documenta `requestId`.

Breaking change intencional: registre-a em [`docs/api-changelog.md`](docs/api-changelog.md) no
mesmo PR, com o que o front precisa mudar; sem isso o CI recusa.

## Logs e correlation ID

Os logs vão para o stdout em JSON no formato ECS (Elastic Common Schema), uma linha por evento; o
Container Apps os leva ao Log Analytics. Cada linha de uma requisição traz `traceId` e `spanId`
([ADR 0013](docs/adr/0013-logs-estruturados-e-correlation-id.md)).

- O correlation ID é o trace id W3C. Toda resposta o devolve no header `X-Request-Id`, e o
  `ProblemDetail` de um erro inesperado (`500`) o repete em `requestId`. Quem reportar um erro informa esse
  valor; no Log Analytics, basta filtrar pelo `traceId`.
- Um `traceparent` válido enviado pelo cliente é aproveitado; qualquer outro valor é ignorado e o
  servidor gera um id. O `X-Request-Id` enviado pelo cliente não é usado.
- Exceção não tratada: uma linha `ERROR` com o stack trace e o `traceId`; o cliente recebe só
  status, título e `requestId`.
- Nunca vão para o log: e-mail, `Authorization`, cookies, tokens, `code` do login, query string
  (testado por `SensitiveDataLoggingIT`).

O envio de traces por OTLP (para o Application Insights) fica desligado até existir endpoint. Para
ligar, defina `OTEL_EXPORTER_OTLP_ENDPOINT` (ou `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`)
no deploy.

## Autenticação

O login é feito pelo **Microsoft Entra External ID**, e a API tem duas portas de entrada com as
mesmas regras de rota:

- **Front web (BFF):** o próprio Spring faz o login no Entra e o navegador só recebe o cookie de
  sessão `__Host-DUORA_SESSION` (`HttpOnly`, `Secure`, `SameSite=Lax`). Mutações exigem o token
  CSRF do cookie `XSRF-TOKEN`, devolvido no header `X-XSRF-TOKEN`. A sessão fica no PostgreSQL.
- **Bearer:** requisições com `Authorization: Bearer <JWT>`, para o futuro app Android e chamadas
  máquina a máquina. A API valida assinatura `RS256`, issuer, audience, expiração e `oid`.

A identidade do usuário é a claim `oid`; os papéis vêm da claim `roles` do access token da API.
Decisões e alternativas: [ADR 0001](docs/adr/0001-autenticacao-entra-external-id.md) e
[ADR 0002](docs/adr/0002-front-web-com-bff.md).

Para o front (repositório `duora-web`):

| Ação | Como |
|---|---|
| Entrar | Navegar para `/oauth2/authorization/entra`; após o login, volta para `/` |
| Sair | `POST /logout` com o header `X-XSRF-TOKEN`; a resposta é `200` com `{"logoutUrl": "..."}`, e o front navega até essa URL para sair também do Entra |
| Saber se está logado | `GET /api/me`: `200` com o nome de exibição e `profileComplete`, ou `401` sem sessão |
| Completar ou editar o perfil | `GET /api/me/profile` e `PATCH /api/me/profile` com `If-Match` (o `ETag` do `GET`) e `X-XSRF-TOKEN`; em `412`, ler de novo e reaplicar |
| Ver e se inscrever em eventos | `GET /api/events`; `PUT /api/events/{id}/registration` com `X-XSRF-TOKEN` (repetir é seguro); em `403` com `PROFILE_INCOMPLETE`, levar ao cadastro do perfil |
| Explicar uma recusa | Ler `reason` no `ProblemDetail` do `409` e do `403` de regra; motivo desconhecido ou ausente é recusa genérica do status ([ADR 0020](docs/adr/0020-motivo-das-recusas-no-problem-detail.md)) |
| Saber o próprio par na rodada | `GET /api/events/{id}` traz `currentRound`; com ele, `GET /api/events/{eventId}/rounds/{currentRound}/pairing`; `partnerAccountId` `null` é "de fora nesta rodada", e `404` é "você não estava nela" |

Em desenvolvimento, o Vite faz proxy da API, para front e API ficarem na mesma origem
(`http://localhost:5173`).

Variáveis obrigatórias (sem elas, ou com elas em branco, a aplicação não sobe):

| Variável | Valor |
|---|---|
| `DUORA_AUTH_ISSUER_URI` | `https://{tenant-id}.ciamlogin.com/{tenant-id}/v2.0` |
| `DUORA_AUTH_AUTHORITY` | `https://{subdomínio}.ciamlogin.com/{tenant-id}` |
| `DUORA_AUTH_JWK_SET_URI` | `{authority}/discovery/v2.0/keys` |
| `DUORA_AUTH_AUDIENCE` | Client id do registro `duora-api` |
| `DUORA_AUTH_WEB_CLIENT_ID` | Client id do registro `duora-web` |
| `DUORA_AUTH_WEB_CLIENT_SECRET` | Segredo do registro `duora-web` (nunca no repositório) |

O tenant externo `duoraapp` (dados nos Estados Unidos) é configurado por script, que pode ser
executado de novo sem duplicar nada:

```bash
az login --tenant d3e03557-0f3b-4474-9586-7ad43938423d --allow-no-subscriptions
infra/entra/configure-tenant.sh
```

O script cria ou atualiza os registros `duora-api` (scope `access_as_user`, app role `ADMIN`,
tokens v2), `duora-web` (cliente confidencial do BFF) e `duora-android`, dá o consentimento de
administrador, liga os clientes ao fluxo de cadastro e login com e-mail e senha e remove registros
obsoletos. As variáveis vão para `~/.config/duora/dev.env` (permissão 600); o segredo do
`duora-web` é criado só se ainda não estiver lá, vale 180 dias e nunca é exibido. Para dar acesso
de admin a alguém, atribua o app role `ADMIN` da `duora-api` ao usuário em *Enterprise applications*.

## Decisões de arquitetura

| ADR | Decisão |
|---|---|
| [0001](docs/adr/0001-autenticacao-entra-external-id.md) | Entra External ID; API como resource server |
| [0002](docs/adr/0002-front-web-com-bff.md) | Front web com BFF no Spring |
| [0003](docs/adr/0003-estilo-de-testes.md) | Testes no estilo Khorikov: domínio sem mocks, banco real |
| [0004](docs/adr/0004-identificadores-e-unicidade.md) | UUIDv7 como PK e id público; `NULLS NOT DISTINCT` |
| [0005](docs/adr/0005-contrato-da-api.md) | Contrato: `POST /x/{id}:verbo`, 409/412, `Idempotency-Key`, `ProblemDetail` |
| [0006](docs/adr/0006-rate-limit-no-postgresql.md) | Rate limit com estado no PostgreSQL |
| [0007](docs/adr/0007-estilo-por-modulo.md) | Ports & adapters no core, camadas simples no supporting |
| [0008](docs/adr/0008-imagem-e-let-it-crash.md) | Dockerfile multi-stage; queda só em estado irrecuperável |
| [0009](docs/adr/0009-outbox-e-eventos.md) | Outbox próprio, eventos por chave, partição por limiar |
| [0010](docs/adr/0010-sem-refresh-token.md) | Login web sem refresh token |
| [0011](docs/adr/0011-conta-e-perfil.md) | Conta por emissor + `oid`; perfil singular com `If-Match`; regras 18+ |
| [0012](docs/adr/0012-contrato-openapi.md) | Spec OpenAPI gerada e versionada; Spectral, oasdiff e Schemathesis no CI |
| [0013](docs/adr/0013-logs-estruturados-e-correlation-id.md) | Logs em JSON (ECS); trace id W3C como correlation ID |
| [0014](docs/adr/0014-infraestrutura-do-piloto-na-azure.md) | Infraestrutura do piloto em Bicep, deploy por OIDC (proposta, custo pendente) |
| [0015](docs/adr/0015-bloqueio-e-denuncia.md) | Bloqueio e denúncia entre contas |
| [0016](docs/adr/0016-eventos-e-inscricoes.md) | Eventos e inscrições: lock do evento para a capacidade, inscrição como sub-recurso idempotente |
| [0017](docs/adr/0017-pareamento.md) | Pareamento: rodada numerada por `PUT` idempotente, emparelhamento máximo com prioridade para quem ficou de fora |
| [0018](docs/adr/0018-erros-de-campo-no-problem-detail.md) | Erros de campo (`errors: [{field, code}]`) no ProblemDetail dos 400 de validação |
| [0019](docs/adr/0019-decisao-privada-e-conexoes.md) | Decisão privada e final por rodada; conexão por interesse mútuo, serializada por advisory lock e única pelo par normalizado |
| [0020](docs/adr/0020-motivo-das-recusas-no-problem-detail.md) | Motivo (`reason`) no ProblemDetail dos 409 e 403 de regra de negócio |
| [0021](docs/adr/0021-chat-temporario-e-reconexao.md) | Chat temporário da rodada, transporte de tempo real e reconexão por cursor de sequência (proposta, aguarda decisão) |

## Segurança

- **Negado por padrão:** toda rota exige sessão ou token válido, exceto a allowlist em
  `SecurityConfiguration`. Sem credencial válida, `401`; sem o papel necessário, `403`.
- **Waitlist sem vazamento:** o `POST` responde igual para e-mail novo ou já inscrito.
- **Rate limit:** 10 inscrições por hora por IPv4 ou rede IPv6 /64 (`duora.waitlist.join-rate-limit.*`), com `429` e
  `Retry-After` acima disso. Os buckets ficam no PostgreSQL, então o limite vale para todas as
  réplicas juntas ([ADR 0006](docs/adr/0006-rate-limit-no-postgresql.md)). Localmente, o limite
  usa o IP da conexão. Atrás do ingress (perfil `behind-proxy`, ligado na imagem), usa o
  `X-Forwarded-For` só quando a conexão vem da faixa `DUORA_TRUSTED_PROXIES`.
- **Entrada estrita:** campos JSON desconhecidos e números fracionários em campos inteiros são
  rejeitados com `400`.
- **Perfil:** cada usuário só alcança o próprio (`/api/me/profile`, sem id na rota). Região só como
  UF (`BR-SP`), nunca localização precisa; data de nascimento só de maior de idade, informada uma vez,
  e a elegibilidade é calculada na hora, nunca guardada ([ADR 0011](docs/adr/0011-conta-e-perfil.md)).
- **Bloqueio e denúncia:** a lista de bloqueios e as denúncias só aparecem para quem as fez; a
  denúncia de outra pessoa responde `404`, igual a um id inexistente. A resposta de `:block` não
  revela se a outra pessoa bloqueou você. Cada conta faz até 10 denúncias por dia
  (`duora.trustsafety.report-rate-limit.*`), contadas no PostgreSQL, e o relato nunca vai para o log
  ([ADR 0015](docs/adr/0015-bloqueio-e-denuncia.md)).
- **Eventos:** ninguém vê quem se inscreveu: cada pessoa só alcança a própria inscrição (sem id na rota), e
  o ADMIN só vê a contagem. Rascunho responde como evento inexistente. A capacidade vale sob concorrência.
  Inscrever e cancelar dividem um limite de 60 chamadas por hora por conta
  (`duora.events.registration-rate-limit.*`), repetições idempotentes incluídas, com `429` e `Retry-After`
  ([ADR 0016](docs/adr/0016-eventos-e-inscricoes.md)).
- **Pareamento:** pares bloqueados nunca se formam, nem o mesmo par duas vezes no evento (constraint no
  banco); cada pessoa só lê o próprio par, e o ADMIN só vê contagens. Duas chamadas simultâneas criam uma
  rodada só, e cada conta ADMIN pode pedir 30 sorteios por hora (`duora.matching.round-rate-limit.*`), com
  `429` e `Retry-After` ([ADR 0017](docs/adr/0017-pareamento.md)).
- **Decisão privada e conexões:** só decide quem formou o par na rodada; cada pessoa lê só a própria decisão,
  e nenhuma resposta muda conforme a decisão do par (disse não ou ainda não decidiu). Dois "sim" simultâneos
  formam exatamente uma conexão, e um bloqueio em qualquer direção impede que ela se forme. Cada conta
  pode enviar 120 decisões por hora (`duora.connections.decision-rate-limit.*`), repetições idempotentes
  incluídas, com `429` e `Retry-After` ([ADR 0019](docs/adr/0019-decisao-privada-e-conexoes.md)).
- **CI:** o gitleaks varre o histórico em busca de segredos a cada push e pull request.
