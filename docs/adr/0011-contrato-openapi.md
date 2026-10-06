# 0011. Contrato OpenAPI gerado, versionado e verificado no CI

- **Status:** Proposta. Decidido na sessão autônoma de 2026-10-05; revisar com o usuário
- **Data:** 2026-10-05
- **Complementa:** [ADR 0005](0005-contrato-da-api.md)

## Contexto

O `duora-web` gera os tipos da API com `openapi-typescript` (plano §1), e o plano (§2) pede
springdoc-openapi com "documentação administrativa com acesso restrito". A API ainda não publicava
spec nenhuma: o front não tinha de onde gerar tipos, e uma quebra de contrato só apareceria no
navegador. As regras de segurança do projeto pedem, além disso, lint OWASP da spec, fuzzing
(Schemathesis) sem 500 e diff de breaking change no CI, com documentação fechada em produção.

Quatro decisões, cada uma com alternativas:

**1. De onde vem a spec**

| Opção | Prós | Contras |
|---|---|---|
| **Code-first (springdoc) com a spec gerada versionada** | Spec e código não divergem: um teste falha se divergirem. Nenhum arquivo a manter à mão | O design não vem antes do código; anotações do springdoc nos controllers |
| Spec-first (YAML escrito à mão, código validado contra ela) | Design antes do código (DWA cap. 6) | Duas fontes da verdade para um dev só; precisa de validação extra para não mentir |

**2. Onde e para quem a documentação é servida**

| Opção | Prós | Contras |
|---|---|---|
| Sempre ligada, só autenticação | Nada a configurar | Superfície a mais em produção, para qualquer usuário |
| Sempre ligada, só ADMIN | Admin consulta em produção | Ainda é superfície em produção; a spec já está no repositório |
| **Desligada por padrão; perfil `api-docs` a liga, e mesmo assim só ADMIN** | Em produção as rotas nem existem; um perfil ligado por engano continua restrito | Em desenvolvimento, ver o Swagger UI exige login como ADMIN |

**3. Como aprovar uma breaking change**

| Opção | Prós | Contras |
|---|---|---|
| Label no PR | Um clique | O workflow precisa reagir a `labeled`; a decisão não fica no repositório |
| **Entrada em `docs/api-changelog.md` no mesmo PR** | A decisão fica registrada junto do código, com o que o front precisa mudar | Um arquivo a mais para editar |

**4. Até onde o Schemathesis vai**

| Opção | Prós | Contras |
|---|---|---|
| Só rotas públicas | Simples | Rotas autenticadas só veriam 401 |
| **Token bearer de verdade, assinado por uma chave descartável servida num JWKS local** | Fuzzing também das rotas autenticadas e de ADMIN, pelo `JwtDecoder` real | Um container a mais no teste; a porta de sessão (BFF) continua de fora |

## Decisão

- **springdoc-openapi 3.1.1** (linha do Spring Boot 4), spec **OpenAPI 3.1**, gerada pela
  aplicação. `OpenApiConfiguration` declara as duas portas de entrada como esquemas de segurança
  (`bearer` e `session`, cookie `__Host-DUORA_SESSION`), exigidas por padrão; operação pública
  declara `security: []` no controller (`@SecurityRequirements`). Respostas que não saem do
  controller entram para toda operação: 401 (protegidas), 403 (ADMIN e mutações com sessão, pelo
  CSRF), 415 (com corpo) e 500, todas em `ProblemDetail`.
- **Acesso:** `springdoc.api-docs.enabled` e `springdoc.swagger-ui.enabled` são `false` por padrão.
  O perfil `api-docs` liga os dois em `/api/admin/openapi` e `/api/admin/swagger-ui.html` (arquivos
  em `/api/admin/swagger-ui/`), sob a regra `/api/admin/**` que já exige ADMIN.
- **Spec versionada:** `docs/openapi.json`. `OpenApiContractIT` gera a spec, grava em
  `target/openapi.json` e falha se ela divergir da versionada. Para atualizar:
  `./mvnw verify` e `cp target/openapi.json docs/openapi.json`.
- **CI** (`.github/workflows/ci.yml`):
  - job *Contrato da API*: Spectral com `spectral:oas` e o ruleset OWASP
    (`tools/contract/.spectral.yaml`, erro quebra o build); em PR, `oasdiff breaking` contra a spec
    da branch base (`tools/contract/check-breaking.sh`), que só aceita a quebra registrada em
    `docs/api-changelog.md` no mesmo PR;
  - job *Imagem Docker*: depois do smoke test, `infra/docker/contract-test.sh` sobe a imagem e roda
    Schemathesis `--checks all` com um token ADMIN.
- **Regras OWASP desligadas**, com o motivo no ruleset: `rate-limit` (headers `RateLimit-*` que a API
  não manda; o limite existe onde há abuso, e o 429 com `Retry-After` está documentado),
  `define-cors-origin` (sem CORS, mesmo site) e `write-restricted` só no `POST /api/waitlist`,
  anônimo de propósito (ADR 0002).

O motivo técnico: uma fonte da verdade (o código), com um teste que impede a spec versionada de
mentir e três verificações independentes no CI. O motivo de negócio: o front quebra no build, e
não no navegador do usuário, quando o contrato muda, e uma quebra só entra de propósito.

## Consequências

- Os controllers ganham anotações do springdoc (`@Operation`, `@ApiResponse`, `@Schema`): o preço
  do code-first. Os limites declarados (como `maxLength` do e-mail) referenciam a constante do
  domínio, para não divergirem.
- O diff de breaking change compara com a branch base, não com a versão em produção, que ainda não
  existe. Quando houver deploy, comparar com a spec da versão implantada.
- O Schemathesis cobre só a porta bearer; a de sessão depende do login interativo no Entra e segue
  coberta pelos testes de integração (`WebLoginIT`). `QUERY` e `TRACE` ficam fora da sondagem de
  método não suportado (`tools/contract/schemathesis.toml`): o `StrictHttpFirewall` do Spring
  Security os recusa com 400 antes do roteamento, por design.
- O fuzzing já achou um defeito: `Content-Type: multipart/form-data` sem boundary respondia 500 em
  qualquer rota. Como a API não recebe upload, o multipart foi desligado
  (`spring.servlet.multipart.enabled=false`); volta com a primeira rota de upload.
- Imagens do oasdiff, do Schemathesis e do busybox (JWKS) ficam fixadas por digest em scripts, fora
  do alcance do Dependabot; atualizar à mão. Spectral e o ruleset seguem o `package-lock.json`, que
  o Dependabot atualiza.
- Rotas fora do MVC (`/oauth2/authorization/entra`, `/logout`) não aparecem na spec; o README
  continua documentando-as para o front.

## Compliance

- `ApiDocsAccessIT`: com o perfil, spec (JSON e YAML) e Swagger UI respondem 401 sem credencial e
  403 sem ADMIN, em `ProblemDetail`; o ADMIN lê a spec, e os arquivos do Swagger UI ficam sob
  `/api/admin`.
- `ApiDocsDisabledByDefaultIT`: sem o perfil, nem o ADMIN acha a documentação (404), inclusive nos
  caminhos padrão do springdoc.
- `OpenApiContractIT`: sem drift entre `docs/openapi.json` e a spec gerada; cada operação declara a
  autenticação que a segurança aplica (pública não pede credencial; protegida responde 401 e o
  documenta) e documenta 403, 415 e 500 quando valem.
- `OpenApiCustomActionIT`: `POST /x/{id}:verbo` (ADR 0005) aparece com o `:` no path e o id como
  parâmetro de path `uuid`.
- `MultipartContentTypeIT`: multipart malformado responde 415 no `POST` e é ignorado no `GET`.
- CI: Spectral sem erro, `oasdiff breaking` sem quebra não registrada, Schemathesis sem violação.
