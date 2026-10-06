# 0005. Convenções do contrato da API

- **Status:** Aceita
- **Data:** 2026-10-05

## Contexto

Os próximos endpoints trazem ações de estado (bloquear, denunciar, aceitar, cancelar), listas
paginadas e a primeira operação que precisa ser idempotente (ação de jogo). Os livros de
referência divergem: o *API Design Patterns* (padrão AIP do Google) e o REST comum de *The Design of
Web APIs* respondem de forma diferente a ações, pré-condições, criação, remoção e erros. Trocar
depois é breaking change para o `duora-web` e para o futuro app Android.

| Tema | AIP (API Design Patterns) | REST comum (Design of Web APIs) |
|---|---|---|
| Ação de estado | `POST /x/{id}:verbo` | `POST /x/{id}/verbo` |
| Estado inválido para a ação | 412 | 409 |
| Idempotência | `requestId` no corpo | header `Idempotency-Key` |
| Create | 200 com o recurso | 201 + `Location` + recurso |
| Delete repetido | 404 | 204 (idempotente) |
| Nomes na URL | camelCase | kebab-case |
| Erros | `Operation.result` | Problem Details (RFC 9457) |

## Decisão

Mistura deliberada, escolhida tema a tema:

- **Ações de estado no estilo AIP:** `POST /recurso/{id}:verbo` (`:cancel`, `:accept`, `:block`). O id é
  tipado (`UUID`) no controller, porque o padrão `{id}:verbo` aceita id vazio.
- **409** quando o estado atual não permite a ação; **412** só para `If-Match` com ETag desatualizado.
- **Header `Idempotency-Key`** nas operações repetíveis com efeito, inclusive ações sem corpo.
- **Create:** 201 + `Location` + o recurso criado. **Delete:** idempotente, 204 também na repetição;
  recurso de outro usuário continua 404.
- **URLs em kebab-case** (`/game-sessions`).
- **Erros em `ProblemDetail`** (`application/problem+json`), sem stack trace, SQL nem nome de
  classe. Vale também para as recusas da segurança (401, 403) e para o que o servidor encaminha a
  `/error`; nas recusas, sem `detail`, para não dizer ao cliente por que o token falhou.
- **Listas:** envelope `{items, nextPageToken}` com cursor opaco e sem total. Cada item traz a
  referência (id) e um resumo mínimo do relacionado (nome de exibição, foto aprovada), montado na
  própria query e respeitando bloqueio e moderação na hora da leitura.
- **Ação de jogo** é Create num sub-recurso (`POST /game-sessions/{id}/actions`) com
  `Idempotency-Key`, porque cada ação é um registro persistido.

Exceção já existente: `POST /api/waitlist` responde 202 sem corpo nem `Location` para e-mail novo
ou repetido, para não revelar quem está na lista.

O motivo técnico: cada código HTTP com um sentido só (412 é de cabeçalho condicional, RFC 9110) e
o caminho padrão do Spring; do AIP fica a forma da ação, que separa bem verbo de sub-recurso. O
motivo de negócio: resumos nas listas evitam uma requisição por item no celular, e montá-los na
query evita vazar perfil de quem bloqueou.

## Consequências

- O contrato mistura AIP (ação, paginação) e REST (o resto); a mistura fica documentada aqui.
- Antes do primeiro endpoint de ação em produção, conferir que o ingress do Container Apps e o
  springdoc preservam o `:` no path.
- Cada resumo embutido numa lista é mais um contrato: o teste confere o conjunto exato de chaves.
- A idempotência segue a regra de segurança: chave escopada por usuário, fingerprint do corpo e
  reserva atômica.

## Compliance

- `CustomActionRoutingTest`: o Spring MVC roteia cada `:verbo` para o seu handler, recusa variações
  (sem verbo, verbo desconhecido, segmento extra), responde 400 para id vazio ou inválido, e o
  matcher do Spring Security trata o `:` e o `%3A` como o controller.
- `JoinWaitlistIT`: erros de entrada respondem `application/problem+json`.
- `WaitlistSecurityIT`, `BearerTokenValidationIT` e `WebLoginIT`: 401 e 403 nas duas portas
  respondem `application/problem+json` com o conjunto exato de chaves.
- `UnexpectedErrorIT`: com HTTP real, uma exceção não tratada numa rota pública chega a `/error`
  e responde `500` em `application/problem+json`, sem nome de classe, SQL nem stack trace.
- Revisão de PR: status, header e envelope de cada endpoint novo seguem esta ADR.
