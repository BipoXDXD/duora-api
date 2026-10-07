# 0018. Erros de campo no ProblemDetail dos 400 de validação

- **Status:** Proposta. Decidido na sessão autônoma de 2026-10-07; revisar com o usuário
- **Data:** 2026-10-07
- **Complementa:** [ADR 0005](0005-contrato-da-api.md), [ADR 0012](0012-contrato-openapi.md)

## Contexto

Os 400 de validação do corpo traziam só o `detail`, um texto em inglês para pessoas ("bio must have at
most 300 characters"). O `duora-web` descobria o campo que falhou lendo esse texto, o que não é contrato:
reescrever uma mensagem quebrava o formulário sem quebrar a spec. A RFC 9457 permite membros de extensão
no Problem Details, e o front gera os tipos da spec ([ADR 0012](0012-contrato-openapi.md)).

| Opção | Prós | Contras |
|---|---|---|
| **Lista `errors: [{field, code}]` no 400** | Aditiva e compatível; cabe vários campos (o `@Valid` acusa todos de uma vez); é a forma do exemplo da RFC 9457 | O cliente percorre uma lista mesmo quando o domínio acusa um campo por vez |
| `field` e `code` únicos no topo do ProblemDetail | Mais simples de ler | Não cabe o `@Valid` com dois campos ausentes; virar lista depois é breaking change |
| 422 para valor bem formado mas inválido, 400 só para JSON ilegível | Separa sintaxe de semântica, como a RFC 9110 sugere | Muda o status de respostas que o front já trata: breaking change, e o 400 continuaria sem o campo |

Para nomear o campo:

| Opção | Prós | Contras |
|---|---|---|
| **Nome da propriedade JSON (`bio`)** | É o nome que o front já usa no formulário; os corpos da API são planos | Num corpo aninhado, futuro, viraria caminho com ponto (`address.city`), uma convenção nossa |
| JSON Pointer (`/bio`), como o `pointer` do exemplo da RFC 9457 | Padrão (RFC 6901), cobre aninhamento e listas, `""` aponta o corpo inteiro | O front tira a barra em todo uso, sem ganho enquanto os corpos forem planos |

## Decisão

- Os 400 de validação do corpo levam, além do `detail` (que continua igual), o membro `errors`: uma
  lista de `{field, code}` ordenada por `field`, com no máximo 20 itens. Na spec, o 400 de toda operação
  com corpo usa o schema `ValidationProblemDetail` (o `ProblemDetail` com `errors` opcional, porque um 400
  de fora do controller, como a recusa do firewall do Spring Security, chega sem ele) e `FieldError`.
- `field` é o nome da propriedade JSON. Fica ausente quando o erro é do corpo inteiro (`MALFORMED_BODY`)
  e quando a chave desconhecida não tem formato de nome (`^[A-Za-z][A-Za-z0-9]*$`, até 64 caracteres):
  a chave vem do cliente e não volta como texto livre.
- `code` vem de uma lista fechada, o enum `FieldErrorCode`, que a spec repete. Os nomes seguem as
  palavras-chave do JSON Schema quando há uma: `REQUIRED`, `TOO_SHORT`, `TOO_LONG`, `BELOW_MINIMUM`,
  `ABOVE_MAXIMUM`, `INVALID_FORMAT`, `UNSUPPORTED_VALUE`, `FORBIDDEN_CHARACTER`, `SELF_REFERENCE`,
  `UNKNOWN_FIELD`, `MALFORMED_BODY`. Em datas, mínimo e máximo valem na ordem do tempo: data de nascimento
  de menor de idade é `ABOVE_MAXIMUM`.
- Nenhum item repete o valor recebido.
- Um ponto só traduz as três origens, `RequestBodyProblemHandler` (em `config`): o corpo que o Jackson
  não lê (JSON malformado, tipo errado, enum fora da lista, chave desconhecida), o bean validation do
  `@Valid` e as exceções de valor inválido dos módulos. Estas estendem `InvalidFieldException`, na raiz
  do pacote, com o campo e o code; o domínio não importa nada da web. O handler substitui o de
  ProblemDetail do Boot, que só existe sem outro `ResponseEntityExceptionHandler`, e herda o resto do
  tratamento dele.
- Fora do escopo: path e query (o `MalformedRequestInputHandler` já responde 400 sem o valor) e as
  recusas que não são de campo (bloquear a si mesmo, que é path).

O motivo técnico: o cliente decide pelo `code`, que tem tipo na spec, e não por texto; a lista aceita o
caso de vários campos sem mudar de forma depois. O motivo de negócio: o formulário marca o campo certo,
em português, e a mensagem em inglês pode mudar sem quebrar o front.

## Consequências

- `FieldErrorCode` é contrato: renomear ou remover um code é breaking change, e acrescentar um muda o enum
  da resposta. O schema pede ao cliente que trate code desconhecido como erro genérico do campo.
- Uma constraint nova do bean validation sem code (hoje só `@NotNull` tem) faz o handler lançar
  `IllegalStateException` em vez de inventar um code fora da lista, e a resposta sai sem `errors`: o IT da
  constraint, que compara `errors` exato, falha. Ao usar `@Size` ou outra, acrescente a tradução.
- As exceções de valor inválido de módulo novo estendem `InvalidFieldException`; sem isso, o 400 sai do
  handler do módulo, sem `errors`.
- O nome do campo é o do JSON. Renomear uma propriedade já era breaking change; agora também muda o
  `field`.

## Compliance

- `WaitlistFieldErrorsIT`, `ProfileFieldErrorsIT`, `ReportFieldErrorsIT` e `EventFieldErrorsIT`: cada
  regra de cada corpo responde o ProblemDetail inteiro, com `errors` exato (comparação estrita); JSON
  malformado responde `MALFORMED_BODY` sem `field`; um canário `CANARY-<uuid>` no valor recusado, ou como
  chave desconhecida, nunca aparece no corpo.
- `OpenApiContractIT.bodyValidationProblemsDocumentTheFieldErrors`: o 400 de toda operação com corpo
  referencia `ValidationProblemDetail`, e o enum de `code` na spec é exatamente o `FieldErrorCode`.
- CI: Spectral sem erro, `oasdiff breaking` sem quebra e Schemathesis conferindo cada 400 contra o schema.
