# 0020. Motivo das recusas de regra de negócio no ProblemDetail

- **Status:** Proposta. Decidido em sessão autônoma em 2026-10-08; revisar com o usuário
- **Data:** 2026-10-08
- **Complementa:** [ADR 0005](0005-contrato-da-api.md), [ADR 0012](0012-contrato-openapi.md),
  [ADR 0018](0018-erros-de-campo-no-problem-detail.md)

## Contexto

O `duora-web` não sabia por que uma ação foi recusada. O `409` da inscrição vinha igual para evento lotado,
cancelado, começado ou encerrado, e o `403` não separava perfil incompleto de menor de idade. O front
mostrava mensagens genéricas ou teria de ler o `detail`, texto em inglês que não é contrato. A
[ADR 0018](0018-erros-de-campo-no-problem-detail.md) resolveu o mesmo problema nos `400` com `errors`.

| Opção | Prós | Contras |
|---|---|---|
| **Membro de extensão `reason` (string de lista fechada, enum na spec)** | Aditivo e compatível; o front gera o tipo da spec; um campo só, igual em todo `409` e `403` de regra; segue o espírito do `code` da ADR 0018 | Convenção nossa: um cliente genérico de RFC 9457 não o conhece (trata como membro desconhecido, o que a RFC permite) |
| `type` com URI por motivo (RFC 9457, seção 3.1.1), como `/problems/event-full` | É o mecanismo padrão da RFC; cada tipo poderia ter página própria | O `type` hoje é omitido (`about:blank`) em toda a API; URI é texto livre na spec, sem enum para gerar o tipo; mudar o `type` de um problema já publicado é breaking para quem o compara |
| Status diferentes por motivo (`410` encerrado, `423`, `422`...) | Nenhum campo novo | Muda o status de respostas que o front já trata: breaking; não há status para "lotado" ou "menor de idade"; a [ADR 0005](0005-contrato-da-api.md) fixa o `409` para estado inválido |

## Decisão

- Os `409` e os `403` de regra de negócio levam, além do `detail` (que continua igual), o membro `reason`,
  de uma lista fechada: o enum `RefusalReason`, na raiz do pacote, que a spec repete no schema
  `RefusalProblemDetail`. `reason` é opcional no schema porque o mesmo status também sai sem ele: o `403`
  do CSRF e o `409` de duas ações simultâneas no mesmo evento (versão otimista), que não é regra de negócio.
- Um ponto só traduz: `RefusalProblemHandler` (em `config`). As exceções de recusa estendem
  `ActionRefusedException`, também na raiz, com o motivo; o domínio não importa nada da web. O motivo
  decide o status num `switch` exaustivo sem `default`: `PROFILE_INCOMPLETE` e `UNDERAGE` são `403`
  (falta algo a quem chama); o resto é `409` (o estado do recurso não permite).
- Na spec, `OpenApiConfiguration` troca o schema de todo `409` e do `403` declarado pelo controller em
  rota autenticada (o de regra; os da segurança entram depois) por `RefusalProblemDetail`. Os controllers
  não mudam.
- Nomes no vocabulário do domínio, com o objeto antes do estado:

| Rota | Status | `reason` |
|---|---|---|
| `PUT /api/events/{eventId}/registration` | 403 | `PROFILE_INCOMPLETE`, `UNDERAGE` |
| `PUT /api/events/{eventId}/registration` | 409 | `EVENT_CANCELLED`, `EVENT_STARTED`, `EVENT_ENDED`, `EVENT_FULL` |
| `DELETE /api/events/{eventId}/registration` | 409 | `EVENT_STARTED`, `EVENT_ENDED` |
| `POST /api/admin/events/{id}:publish` | 409 | `EVENT_ALREADY_PUBLISHED`, `EVENT_CANCELLED`, `EVENT_STARTED`, `EVENT_ENDED` (e sem `reason` na corrida com outra ação) |
| `POST /api/admin/events/{id}:cancel` | 409 | `EVENT_CANCELLED`, `EVENT_ENDED` (e sem `reason` na corrida) |
| `PUT /api/admin/events/{eventId}/rounds/{number}` | 409 | `EVENT_NOT_UNDERWAY`, `ROUND_OUT_OF_SEQUENCE` |
| `PATCH /api/me/profile` | 409 | `BIRTH_DATE_ALREADY_SET` |

- `EVENT_STARTED` e `EVENT_ENDED` passam a ser distintos: antes, inscrever-se, sair e publicar depois do
  fim respondiam "already started". O `detail` do evento encerrado é "the event has already ended".
- `EVENT_NOT_PUBLISHED` existe no domínio (inscrição em rascunho), mas hoje não chega ao cliente: para quem
  não é ADMIN, rascunho responde `404` antes. Fica no enum porque a regra do domínio o usa.
- `trustsafety` não tem `409` nem `403` de regra hoje (bloquear a si mesmo é `400` de campo).

**Menor de idade (`UNDERAGE`):** `ProfileCompleteness`, a API publicada do `profiles`, devolvia só um
booleano. Agora devolve `Eligibility` (`ELIGIBLE`, `PROFILE_INCOMPLETE`, `UNDERAGE`): incompleto quando
falta nome, data de nascimento ou região; menor quando tudo está preenchido mas a pessoa não tem 18 anos no
instante da inscrição. Pela API, o perfil não aceita data de menor, então `UNDERAGE` só acontece com dado
que chegou ao banco por outro caminho (importação, suporte); a regra é conferida de novo na inscrição do
mesmo jeito. **Não vaza dado:** quem recebe o motivo é a própria pessoa, sobre o próprio perfil, cuja data
de nascimento ela já lê em `GET /api/me/profile`. Nenhuma rota diz isso sobre outra conta, e o
`ProfileCompleteness` não expõe a data nem a idade a outros módulos, só a elegibilidade.

O motivo técnico: o cliente decide pelo `reason`, que tem tipo na spec, e não por texto; o `detail` pode
mudar sem quebrar o front. O motivo de negócio: a pessoa lê "evento lotado" ou "complete seu perfil" em
português, com a ação certa (ir ao perfil, procurar outro evento), em vez de "não foi possível".

## Consequências

- `RefusalReason` é contrato: renomear ou remover um motivo é breaking change, e acrescentar um muda o enum
  da resposta (o schema pede ao cliente que trate motivo desconhecido como recusa genérica). Motivo novo
  não compila sem status no `RefusalProblemHandler`.
- Recusa nova de módulo novo estende `ActionRefusedException`; sem isso, o `409` sai do handler do módulo,
  sem `reason`, e só o IT da recusa, que compara o corpo inteiro, percebe.
- Os handlers de `EventStateConflictException`, `IncompleteProfileException` (agora
  `IneligibleToRegisterException`), `EventNotUnderwayException`, `RoundOutOfSequenceException` e
  `BirthDateAlreadySetException` saíram dos módulos.
- O `409` de concorrência otimista continua sem `reason`. Se o front precisar distinguir, um motivo
  `CONCURRENT_CHANGE` é aditivo.

## Compliance

- `RegistrationIT`, `AdminEventIT`, `RoundIT` e `ProfileIT`: cada recusa compara o ProblemDetail inteiro
  (comparação estrita), com `reason` exato: `emptyProfileCannotRegister` (`PROFILE_INCOMPLETE`),
  `minorCannotRegisterEvenWithAFilledProfile` (`UNDERAGE`), `cancelledEventRefusesRegistration`,
  `startedEventRefusesRegistration`, `endedEventRefusesRegistration`, `lastPlaceIsTakenAndThenTheEventIsFull`,
  `registrationCannotBeCancelledOnceTheEventStarted`, `registrationCannotBeCancelledOnceTheEventEnded`,
  `publishingTwiceIsAConflict`, `draftWhoseStartHasPassedCannotBePublished`,
  `draftWhoseEndHasPassedCannotBePublished`, `cancelledEventCannotBePublished`, `cancellingTwiceIsAConflict`,
  `endedEventCannotBeCancelled`, `roundsDoNotStart*`, `aRoundNeedsThePreviousOneAndWritesNothingWithoutIt`,
  `changingTheBirthDateIsAConflict`.
- `EventTest` e `ProfileTest`: o motivo de cada recusa do domínio e a diferença entre preenchido e completo.
- `OpenApiContractIT.refusalsDocumentTheirReason`: todo `409` e o `403` da inscrição referenciam
  `RefusalProblemDetail`, e o enum de `reason` na spec é exatamente o `RefusalReason`.
- CI: Spectral sem erro, `oasdiff breaking` sem quebra e Schemathesis conferindo cada `409` e `403` contra o
  schema.
