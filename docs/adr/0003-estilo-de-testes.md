# 0003. Estilo de testes: domínio sem mocks e banco sempre real

- **Status:** Aceita
- **Data:** 2026-10-05

## Contexto

Os primeiros testes misturavam dois estilos. `WaitlistServiceTest` mockava o repositório e
verificava a chamada a `insertIfAbsent`. `WaitlistControllerTest` e `WaitlistSecurityTest` usavam
`@WebMvcTest` com o `WaitlistService` trocado por `@MockitoBean`. Esses testes verificam como as
classes do próprio sistema conversam entre si, não o que a API faz. Quebram em refactorings que
preservam o comportamento e passam com o banco errado.

Alternativas consideradas:

| Opção | Prós | Contras |
|---|---|---|
| **Khorikov (Unit Testing: Principles, Practices, and Patterns)** | Testa só comportamento observável. O banco próprio é real, então constraints, `on conflict` e transações entram no teste. Refactoring interno não quebra teste | Mais testes de integração, que dependem de Docker e são mais lentos que `@WebMvcTest` |
| Escola de Londres: mock de todo colaborador, slices `@WebMvcTest`/`@DataJpaTest` | Testes rápidos e isolados; localiza a falha | Teste acoplado à implementação. Mock de repositório não pega erro de SQL, de constraint nem de transação |
| Mistura sem regra | Nenhum custo de decisão | Cada teste novo reabre a discussão; o plano (§8) e o código já divergiam |

## Decisão

Seguimos o estilo de Khorikov:

- **Domínio** (entidades, value objects, políticas) com teste unitário puro, sem Spring e sem
  Mockito, verificando saída ou estado.
- **Banco próprio sempre real:** PostgreSQL 18 via Testcontainers com `@ServiceConnection`, nunca
  H2 nem mock de repositório. O teste confere o estado relendo o banco.
- **Controllers, serviços de aplicação e segurança** com `@SpringBootTest` + `@AutoConfigureMockMvc`
  e os serviços de verdade, cobrindo o caminho feliz mais longo e as bordas que o unitário não
  alcança (autorização, constraint, concorrência, idempotência).
- **Mockito só para dependências externas que a API não controla** (unmanaged): Web PubSub,
  e-mail, Blob Storage quando entrega URL ao navegador. O mock fica no último tipo nosso antes
  do SDK. O Entra é simulado por um servidor HTTP local que entrega JWKS e tokens, como em
  `WebLoginIT`.
- Serviço de aplicação sem regra (só orquestra) não ganha teste unitário: o teste de integração
  já o cobre.

O motivo técnico é ter testes que só quebram quando o comportamento muda. O motivo de negócio é
a confiança para refatorar sozinho, sem revisor, um sistema que vai ter pagamentos e dados
pessoais.

## Consequências

- `WaitlistServiceTest` e `WaitlistControllerTest` saíram. Os casos de entrada inválida do
  controller foram para `JoinWaitlistIT`, que confere que nada foi gravado.
  `WaitlistSecurityTest` e `BearerTokenValidationTest` viraram `WaitlistSecurityIT` e
  `BearerTokenValidationIT`, com contexto completo e banco real.
- `./mvnw test` fica só com o domínio e o ArchUnit; quase tudo roda em `./mvnw verify`, que exige
  Docker. O CI já roda `verify`.
- Cada combinação diferente de configuração (`@DynamicPropertySource`, `@MockitoBean`) cria outro
  contexto Spring e outro container. Para manter o cache, os mocks de dependências externas devem
  morar numa `@TestConfiguration` compartilhada, e não espalhados por `@MockitoBean`.
- O `jwt()` do Spring Security Test pula a validação do token. Ele serve para testar autorização
  por papel; a validação de tokens usa o `JwtDecoder` real em `BearerTokenValidationIT`.
- O plano técnico (§8) foi atualizado: Mockito deixa de ser ferramenta de unidade.
- jqwik e PIT ainda não têm suporte confirmado ao JUnit 6 do Boot 4; property-based e mutation
  testing esperam um spike.

## Compliance

- `TestStyleTest` (ArchUnit sobre o código de teste) falha com `@WebMvcTest` ou com campo
  `@MockitoBean`, `@MockitoSpyBean`, `@Mock` ou `@Spy` de tipo do próprio sistema.
- Revisão de PR cobre o que o ArchUnit não vê: `mock(...)` chamado direto no corpo do teste.
- `ArchitectureTest` confere que o domínio não depende das camadas de cima, o que mantém o
  domínio testável sem Spring ([ADR 0007](0007-estilo-por-modulo.md)).
