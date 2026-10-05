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

A API exige a configuração de autenticação (veja [Autenticação](#autenticação)). Com as
variáveis definidas:

```bash
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

## Testes

| Comando | O que roda | Precisa de Docker |
|---|---|---|
| `./mvnw test` | Testes rápidos (`*Test`): unitários, Mockito, `@WebMvcTest`, segurança, ArchUnit | Não |
| `./mvnw verify` | Os rápidos + integração (`*IT`): `@DataJpaTest`, `@SpringBootTest`, migrations Flyway | Sim |

Os testes de integração usam PostgreSQL real (Testcontainers com `@ServiceConnection`), nunca H2.

| Tipo | Exemplo |
|---|---|
| Unitário puro (JUnit + AssertJ) | `waitlist/domain/EmailAddressTest` |
| Mockito | `waitlist/application/WaitlistServiceTest` |
| `@WebMvcTest` | `waitlist/api/WaitlistControllerTest` |
| Spring Security (401/403) | `waitlist/api/WaitlistSecurityTest` |
| Validação de JWT (tokens reais, JWKS local) | `config/BearerTokenValidationTest` |
| Subida sem configuração obrigatória | `config/RequiredAuthenticationSettingsIT` |
| `@DataJpaTest` + PostgreSQL | `waitlist/domain/WaitlistEntryRepositoryIT` |
| `@SpringBootTest` completo | `waitlist/JoinWaitlistIT` |
| Migrations Flyway | `FlywayMigrationIT` |
| Regras de arquitetura (ArchUnit) | `ArchitectureTest` |

### Cobertura

O JaCoCo gera relatório sem meta mínima por enquanto:

- `target/site/jacoco/index.html`: só os testes rápidos (após `test`)
- `target/site/jacoco-all/index.html`: rápidos + integração (após `verify`)

No CI, o relatório completo fica como artefato `jacoco-report`.

## Estrutura

Pacotes por feature, cada uma dividida em camadas:

```
bipo.tech.duoraapi
├── config/              # segurança, relógio
└── waitlist/
    ├── api/             # controller, DTOs, rate limit
    ├── application/     # casos de uso (WaitlistService)
    └── domain/          # entidade, value objects, repositório
```

Os testes espelham a mesma estrutura. O `ArchitectureTest` garante que `domain` não dependa de `api`.

As migrations ficam em `src/main/resources/db/migration`. O Hibernate só valida o schema
(`ddl-auto=validate`); quem o cria e altera é o Flyway.

## API

| Método | Rota | Acesso | Resposta |
|---|---|---|---|
| `POST` | `/api/waitlist` | Público | `202`, para e-mail novo ou repetido |
| `GET` | `/api/admin/waitlist/stats` | `ADMIN` | `200` com `{"total": n}` |
| `GET` | `/actuator/health` | Público | Estado da aplicação |

## Autenticação

O login é feito pelo **Microsoft Entra External ID**. A API só valida o bearer token (JWT) de
cada requisição: assinatura `RS256`, issuer, audience e expiração. A decisão e as alternativas
estão em [ADR 0001](docs/adr/0001-autenticacao-entra-external-id.md).

Variáveis obrigatórias (sem elas a aplicação não sobe):

| Variável | Valor |
|---|---|
| `DUORA_AUTH_ISSUER_URI` | `https://{tenant-id}.ciamlogin.com/{tenant-id}/v2.0` |
| `DUORA_AUTH_JWK_SET_URI` | O `jwks_uri` do documento `https://{subdomínio}.ciamlogin.com/{tenant-id}/v2.0/.well-known/openid-configuration` |
| `DUORA_AUTH_AUDIENCE` | Client id do registro da API no tenant |

Configuração no tenant externo:

1. Registre a aplicação **duora-api**: em *Expose an API*, defina o Application ID URI e um scope.
2. No manifesto do registro, garanta tokens v2 (`"requestedAccessTokenVersion": 2`).
3. Em *App roles*, crie o papel com valor `ADMIN` para usuários e atribua-o a quem administra.
4. Registre o app cliente (mobile ou web) com permissão para o scope da API. O fluxo é
   authorization code com PKCE.

## Segurança

- **Negado por padrão:** toda rota exige token válido, exceto a allowlist em `SecurityConfiguration`.
  Sem token ou com token inválido, `401`; sem o papel necessário, `403`.
- **Waitlist sem vazamento:** o `POST` responde igual para e-mail novo ou já inscrito.
- **Rate limit:** 10 inscrições por IP por hora (`duora.waitlist.join-rate-limit.*`), com `429` e
  `Retry-After` acima disso. O limite vale por instância e usa o IP da conexão; atrás de proxy, é
  preciso configurar `server.forward-headers-strategy`.
- **Entrada estrita:** campos JSON desconhecidos são rejeitados com `400`.
- **CI:** o gitleaks varre o histórico em busca de segredos a cada push e pull request.
