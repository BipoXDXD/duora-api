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

```bash
./mvnw spring-boot:test-run
```

Sobe a aplicação em `http://localhost:8080` com um PostgreSQL descartável via Testcontainers
(`TestDuoraApiApplication`). O Flyway aplica as migrations na subida.

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

## Segurança

- **Negado por padrão:** toda rota exige autenticação, exceto a allowlist em `SecurityConfiguration`.
  Sem credencial, `401`; sem o papel necessário, `403`. O mecanismo de login ainda não foi escolhido.
- **Waitlist sem vazamento:** o `POST` responde igual para e-mail novo ou já inscrito.
- **Rate limit:** 10 inscrições por IP por hora (`duora.waitlist.join-rate-limit.*`), com `429` e
  `Retry-After` acima disso. O limite vale por instância e usa o IP da conexão; atrás de proxy, é
  preciso configurar `server.forward-headers-strategy`.
- **Entrada estrita:** campos JSON desconhecidos são rejeitados com `400`.
- **CI:** o gitleaks varre o histórico em busca de segredos a cada push e pull request.
