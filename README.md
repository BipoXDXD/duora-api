# Duora API

[![CI](https://github.com/BipoXDXD/duora-api/actions/workflows/ci.yml/badge.svg)](https://github.com/BipoXDXD/duora-api/actions/workflows/ci.yml)

Backend do Duora. Java 25, Spring Boot 4, PostgreSQL e Flyway.

O projeto está no início: a primeira feature é uma **waitlist** de pré-lançamento, que serve
também para exercitar toda a infraestrutura de testes. Matching, chat, eventos e pagamentos
vêm depois.

## Pré-requisitos

- JDK 25
- Docker em execução, para os testes de integração e para rodar a aplicação localmente

O Maven vem pelo wrapper (`./mvnw`); não é preciso instalá-lo.

## Rodando localmente

A API exige a configuração de autenticação (veja [Autenticação](#autenticação)). O script do
tenant grava as variáveis em `~/.config/duora/dev.env`:

```bash
set -a; source ~/.config/duora/dev.env; set +a
./mvnw spring-boot:run
```

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
imagens base são fixadas por digest. O smoke test sobe a imagem com um PostgreSQL descartável e
confere health, usuário e encerramento por SIGTERM. O CI faz os dois a cada push
([ADR 0008](docs/adr/0008-imagem-e-let-it-crash.md)).

A imagem liga o perfil `behind-proxy`, porque só roda atrás do ingress do Container Apps. Além das
variáveis do Entra (abaixo), ela exige esta, e não sobe sem ela ou com ela em branco:

| Variável | Valor |
|---|---|
| `DUORA_TRUSTED_PROXIES` | Faixa de onde o ingress conecta à aplicação, em CIDR separados por vírgula (ex.: a subnet de infraestrutura do ambiente) |

Quem definir `SPRING_PROFILES_ACTIVE` no deploy mantém `behind-proxy` na lista
([ADR 0006](docs/adr/0006-rate-limit-no-postgresql.md)).

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
| Unitário puro (JUnit + AssertJ) | `waitlist/domain/EmailAddressTest` |
| `@SpringBootTest` + MockMvc, ponta a ponta | `waitlist/JoinWaitlistIT` |
| Spring Security (401/403) | `waitlist/WaitlistSecurityIT` |
| Validação de JWT (tokens reais, JWKS local) | `config/BearerTokenValidationIT` |
| Login web (BFF): sessão, cookie, CSRF, logout | `config/WebLoginIT` |
| Subida sem configuração obrigatória | `config/RequiredAuthenticationSettingsIT`, `config/RequiredTrustedProxySettingsIT` |
| HTTP real pelo Tomcat (`X-Forwarded-For`, `/error`) | `config/ForwardedClientAddressIT`, `config/UnexpectedErrorIT` |
| `@DataJpaTest` + PostgreSQL | `waitlist/domain/WaitlistEntryRepositoryIT` |
| Migrations Flyway | `FlywayMigrationIT` |
| Regras de arquitetura (ArchUnit) | `ArchitectureTest` |

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
└── waitlist/
    ├── api/             # controller, DTOs, rate limit
    ├── application/     # casos de uso (WaitlistService)
    └── domain/          # entidade, value objects, repositório
```

Os testes espelham a mesma estrutura. Módulos de apoio (como a waitlist) usam essas camadas
simples; os do core (pareamento, minijogos, conexões, moderação) vão usar ports & adapters, com
domínio sem framework. O `ArchitectureTest` cobra a classificação e a direção das dependências
([ADR 0007](docs/adr/0007-estilo-por-modulo.md)).

As migrations ficam em `src/main/resources/db/migration`. O Hibernate só valida o schema
(`ddl-auto=validate`); quem o cria e altera é o Flyway.

## API

| Método | Rota | Acesso | Resposta |
|---|---|---|---|
| `POST` | `/api/waitlist` | Público | `202`, para e-mail novo ou repetido |
| `GET` | `/api/admin/waitlist/stats` | `ADMIN` | `200` com `{"total": n}` |
| `GET` | `/api/me` | Autenticado | `200` com `{"displayName": "..."}` (`null` se o Entra não tiver nome); `401` sem sessão |
| `GET` | `/actuator/health` | Público | Estado da aplicação |

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
| Saber se está logado | `GET /api/me`: `200` com o nome de exibição, ou `401` sem sessão |

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

## Segurança

- **Negado por padrão:** toda rota exige sessão ou token válido, exceto a allowlist em
  `SecurityConfiguration`. Sem credencial válida, `401`; sem o papel necessário, `403`.
- **Waitlist sem vazamento:** o `POST` responde igual para e-mail novo ou já inscrito.
- **Rate limit:** 10 inscrições por hora por IPv4 ou rede IPv6 /64 (`duora.waitlist.join-rate-limit.*`), com `429` e
  `Retry-After` acima disso. Os buckets ficam no PostgreSQL, então o limite vale para todas as
  réplicas juntas ([ADR 0006](docs/adr/0006-rate-limit-no-postgresql.md)). Localmente, o limite
  usa o IP da conexão. Atrás do ingress (perfil `behind-proxy`, ligado na imagem), usa o
  `X-Forwarded-For` só quando a conexão vem da faixa `DUORA_TRUSTED_PROXIES`.
- **Entrada estrita:** campos JSON desconhecidos são rejeitados com `400`.
- **CI:** o gitleaks varre o histórico em busca de segredos a cada push e pull request.
