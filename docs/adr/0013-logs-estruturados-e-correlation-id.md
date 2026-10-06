# 0013. Logs em JSON (ECS) e o trace id W3C como correlation ID

- **Status:** Aceita. Decidido na sessão autônoma de 2026-10-05; revisar com o usuário.
- **Data:** 2026-10-05

## Contexto

O plano (§2, "Operação") pede logs estruturados e OpenTelemetry/Application Insights; o §7 proíbe
conversas, preferências e tokens em log. Até aqui, os logs saíam em texto, sem identificador de
requisição, e o stack trace de uma exceção não tratada era registrado pelo Tomcat sem nenhum id que
o ligasse à resposta 500. Em DEBUG, o Spring MVC registrava o corpo lido, e o `toString` do record
da waitlist imprimia o e-mail.

Alternativas para o formato do log (suporte nativo do Boot, `logging.structured.format.console`):

| Opção | Prós | Contras |
|---|---|---|
| **ECS (Elastic Common Schema)** | Schema público e versionado (`ecs.version`), que o OpenTelemetry adotou nas convenções semânticas; campos `service.*` e `error.type/message/stack_trace` separados | Objetos aninhados (`log.level` vira `{"log":{"level":...}}`), um pouco mais verboso |
| Logstash | Plano e compacto | Formato de uma ferramenta que não usamos (ELK); erro só como `stack_trace` |
| GELF | Bom para Graylog | Também atrelado a uma ferramenta; nomes com `_` |
| Texto (padrão) | Legível no terminal | O Log Analytics não separa campos; stack trace quebra em várias linhas |

Alternativas para o correlation ID:

| Opção | Prós | Contras |
|---|---|---|
| Filtro próprio: UUID ou `X-Request-Id` validado, no MDC | Pouco código, sem dependência nova | Um segundo id ao lado do trace quando o tracing chegar; não conversa com o Application Insights |
| **Micrometer Tracing + OpenTelemetry (`spring-boot-starter-opentelemetry`)** | O trace id W3C já entra no MDC e no JSON (`traceId`, `spanId`); o `traceparent` é validado pelo propagador W3C; o mesmo id vira o `operation_Id` no Application Insights quando o envio por OTLP ligar | Mais dependências (SDK do OTel); exige desligar o que manda dados por padrão |
| Agente Java do Application Insights | Instrumentação automática completa | Agente na imagem, configuração fora do Spring, preso ao fornecedor |

## Decisão

- **Logs em ECS no stdout**, uma linha JSON por evento, em todos os ambientes; o perfil
  `plain-logs` volta ao texto para desenvolvimento. A plataforma (Container Apps → Log Analytics)
  agrega; a aplicação não escreve arquivo.
- **Correlation ID = trace id W3C**, com Micrometer Tracing e a ponte do OpenTelemetry. Entra só
  pelo `traceparent` válido (versão, 32 hex minúsculos não zerados, span não zerado, tamanho
  exato); qualquer outro valor é ignorado e o servidor gera um id novo. Só W3C é consumido (B3 não),
  o baggage está desligado e o sampler é `trace-id-ratio`, que ignora a flag "sampled" do cliente:
  com o padrão (`parent-based`), qualquer cliente forçaria a exportação de todos os seus traces.
- **`X-Request-Id` na resposta**, sempre, inclusive nas recusas da segurança (filtro logo depois da
  observação HTTP e antes do Spring Security). O `X-Request-Id` enviado pelo cliente **não** é
  adotado: texto livre não serve de trace id, e dois ids por requisição dobrariam a busca. Quem
  quiser correlacionar do lado do cliente manda `traceparent`.
- **Exceção não tratada** é registrada por um filtro dentro do trace (ERROR com stack trace e
  `traceId`) e vira 500 pelo `/error`, cujo `ProblemDetail` leva `requestId` com o mesmo valor do
  header. O Tomcat deixa de registrá-la (antes, sem id). Nada de causa, classe ou SQL no corpo.
- **Envio por OTLP desligado** até haver endpoint: sem `OTEL_EXPORTER_OTLP_ENDPOINT` (o Boot 4.1
  mapeia as variáveis `OTEL_*`) ou `management.opentelemetry.tracing.export.otlp.endpoint`, nenhum
  span sai do processo; o registry de métricas OTLP fica desligado
  (`management.otlp.metrics.export.enabled=false`). Nenhum segredo no repositório.
- **Dados pessoais fora do `toString`:** `JoinWaitlistRequest` e `EmailAddress` imprimem
  `<redacted>` no lugar do e-mail.

O motivo técnico é ter um único id por requisição, do header ao log e ao trace, e um formato de log
consultável por campo. O motivo de negócio é atender quem reporta um erro (o id está na resposta)
sem expor dado pessoal de quem entrou na waitlist, e chegar ao Application Insights sem refazer a
instrumentação.

## Consequências

- A saída dos testes também é JSON (é o formato de produção que os testes verificam).
- Uma exceção não tratada não escapa mais do MockMvc: o teste vê 500 e confere a causa no log
  (`WebLoginIT`).
- O `ProblemDetail` do `/error` ganhou a propriedade `requestId` (mudança aditiva); o schema da
  OpenAPI precisa refleti-la.
- O cliente pode reutilizar um trace id de propósito; o id só serve para correlação, nunca para
  autorização ou deduplicação.
- Stack traces do Spring passam de 30 KB numa linha só. Falta conferir no Container Apps se linhas
  acima de 16 KB são quebradas; se forem, usar `logging.structured.json.stacktrace.root=first` e
  `max-length`.
- No deploy: ligar o agente OpenTelemetry gerenciado do Container Apps (ou o endpoint OTLP) com
  destino Application Insights; decidir se as métricas também vão por OTLP; conferir se o agente
  injeta `OTEL_TRACES_SAMPLER` e se ele sobrepõe o `trace-id-ratio`.
- Probabilidade de amostragem no padrão do Boot (10%); ajustar quando houver custo medido.

## Compliance

- `RequestCorrelationIT`: id gerado (32 hex, não zerado, diferente a cada requisição); `traceparent`
  válido vira o id; sete variações inválidas são trocadas por id novo; `X-Request-Id` do cliente não
  é ecoado; o 401 da segurança também leva o header.
- `UnexpectedErrorIT`: o 500 responde sem `Exception`, `SQL`, `at `, nome de pacote nem `trace`, com
  `requestId` igual ao header; o log tem a linha ERROR com esse id e o stack trace; o e-mail
  (canário) não aparece no log nem no corpo.
- `SensitiveDataLoggingIT`: com o Spring MVC e a API em DEBUG, um canário em e-mail aceito e
  recusado, `Authorization` (Bearer e Basic), cookies de sessão e CSRF, query string, `traceparent`,
  `X-Request-Id`, `baggage` e `code` do callback OAuth2 nunca aparece no log, no corpo nem nos
  headers da resposta.
- `EmailAddressTest`: o `toString` não traz o endereço.
- `infra/docker/smoke-test.sh` continua achando "Graceful shutdown complete" na mensagem JSON.
