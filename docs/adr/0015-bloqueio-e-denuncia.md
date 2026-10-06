# 0015. Bloqueio e denúncia entre contas

- **Status:** Aceita, provisória. Decidido na sessão autônoma de 2026-10-05; revisar com o usuário.
- **Data:** 2026-10-05
- **Relacionadas:** [ADR 0004](0004-identificadores-e-unicidade.md), [ADR 0005](0005-contrato-da-api.md),
  [ADR 0006](0006-rate-limit-no-postgresql.md), [ADR 0007](0007-estilo-por-modulo.md),
  [ADR 0011](0011-conta-e-perfil.md)

## Contexto

O plano (§7, "Produto 18+") pede denúncia, bloqueio e moderação desde o piloto, e a etapa 1 (§9) inclui
o "fluxo de bloqueio/denúncia". O módulo `trustsafety` é **core** (ADR 0007): ports & adapters, domínio
sem framework. Os próximos módulos (chat, pareamento) precisam saber se duas pessoas estão separadas por
um bloqueio, sem conhecer as tabelas do `trustsafety`. Bloqueio e denúncia lidam com dado sensível: quem
bloqueou quem, e um relato livre que pode citar terceiros.

## Alternativas e decisões

### Como o cliente se refere à outra pessoa

| Opção | Prós | Contras |
|---|---|---|
| **Id da conta (UUIDv7) como id público opaco** | Já existe; a ADR 0011 previu que "a referência pública será o id da conta"; 74 bits aleatórios, não dá para enumerar | O prefixo revela o instante de criação da conta (ADR 0004); quem recebe o id pode guardá-lo |
| Handle (`@ana`) | Legível; dá para digitar | Coluna nova com unicidade, regras de troca e reserva; enumerável por dicionário; nada no produto pede busca por handle |
| Id opaco por relação (o chat expõe um id da conversa, e o bloqueio parte dele) | Nunca expõe o id da conta | Depende de módulos que ainda não existem; a lista "quem eu bloqueei" precisaria de outro id |

**Decisão:** o id da conta é o id público da pessoa, sempre como UUID tipado no path. Conta inexistente
→ `404` (`account not found`), sem eco do id no `detail`. O 404 só confirma o que o próprio cliente já
tinha: ninguém adivinha um UUIDv7, e o id só chega ao cliente quando o app mostra aquela pessoa. A
resposta não revela bloqueio nem denúncia: bloquear quem bloqueou você, ou denunciá-lo, responde igual a
qualquer outro caso.

### Contrato

| Operação | Rota | Resposta |
|---|---|---|
| Bloquear | `POST /api/accounts/{accountId}:block` | `204`; repetido também `204` (idempotente) |
| Desbloquear | `POST /api/accounts/{accountId}:unblock` | `204`, mesmo sem bloqueio ou com conta inexistente (o estado pedido já vale) |
| Quem eu bloqueei | `GET /api/me/blocked-accounts?maxPageSize=&pageToken=` | `{items: [{accountId, blockedAt}], nextPageToken}` |
| Denunciar | `POST /api/reports` | `201` + `Location` + a denúncia |
| Ler a própria denúncia | `GET /api/reports/{id}` | `200`; de outra pessoa ou inexistente, o mesmo `404` |

- Ações no estilo `:verbo` da ADR 0005. Bloquear a si mesmo → `400`; é entrada inválida, e não estado
  (409).
- **Lista:** keyset por `(created_at, blocked_account_id)`, mais recentes primeiro; `maxPageSize` de 1 a
  100 (padrão 20), fora disso `400`; fim da lista = `nextPageToken: null`. O token é Base64 URL da
  posição, opaco mas **não cifrado**: não autoriza nada, porque a consulta sempre filtra por quem pede.
  Token inválido → `400`.
- **Item sem resumo da pessoa**, contrariando a ADR 0005 (nome de exibição no item): o nome mora no
  `profiles`, que não publica API, e o `trustsafety` não lê a tabela de outro módulo. Entra quando o
  `profiles` publicar uma consulta de resumo; até lá o item é só `accountId` e `blockedAt`.
- **Denúncia:** `{reportedAccountId, reason, description}`; chave desconhecida → `400` (estado, autoria,
  id e data são do servidor). Resposta `{id, reportedAccountId, reason, description, status, createdAt}`.

### Modelo de dados

- `account_block (blocker_account_id, blocked_account_id, created_at)`: **PK no par** (a `UNIQUE` do par
  e o índice da FK de quem bloqueia), `CHECK` contra auto-bloqueio, índice próprio na FK de quem foi
  bloqueado. O bloqueio tem direção: A→B e B→A são linhas diferentes, e cada lado desfaz só o seu.
  Bloquear é `insert ... on conflict do nothing`: pedidos simultâneos deixam uma linha só, com a data do
  primeiro.
- `report (id uuid default uuidv7(), reporter_account_id, reported_account_id, reason, description,
  status, created_at)`: `CHECK` da lista de motivos, da descrição (1 a 1000), do estado (`OPEN`), contra
  auto-denúncia e "`OTHER` exige descrição"; índices nas duas FKs.
- **`on delete restrict`** em todas as FKs para `account`, como em `profile` (ADR 0011): a exclusão de
  conta vai ter de decidir o que fazer com bloqueios e denúncias (que podem ser evidência).
- Conta alvo inexistente é detectada pela própria FK (SQLSTATE `23503`) no insert, e não por uma consulta
  à tabela `account`, que é do `identity`.
- **SQL direto (`JdbcClient`) nos adapters**, sem JPA: bloqueio é um par com data, e a denúncia não muda
  depois de criada; não há estado que pague o mapeamento.
- Motivos e estado em `CHECK`, e não em lookup table: a lista muda junto com o código (`OTHER` tem
  regra), então cada mudança já exige deploy e migration.

### Motivos

`HARASSMENT`, `HATE_SPEECH`, `SEXUAL_CONTENT`, `VIOLENCE_OR_THREAT`, `SCAM_OR_SPAM`, `FAKE_PROFILE`,
`SUSPECTED_MINOR`, `OTHER`. Descrição opcional, salvo em `OTHER`; até 1000 caracteres (code points, após
NFC e `strip`), com as mesmas proibições da bio (controle, NUL, invisíveis, controles de direção).

### Denunciar também bloqueia?

| Opção | Prós | Contras |
|---|---|---|
| **Operações separadas; o front oferece "também bloquear" e chama `:block` depois** | Cada operação faz uma coisa; as duas são idempotentes, então repetir após falha é seguro; denunciar sem bloquear continua possível (ex.: perfil falso que não incomodou) | Duas requisições; entre elas, a denúncia existe sem o bloqueio por instantes |
| Denúncia sempre bloqueia | Protege por padrão | Tira a escolha da pessoa; mistura dois agregados numa operação |
| Campo `block: true` na denúncia | Uma requisição, atômica | Parâmetro booleano que escolhe a lógica; dois agregados na mesma transação |

**Decisão:** separadas. O campo `block` no corpo é recusado como qualquer chave desconhecida.

### Cota de denúncias

**10 denúncias por conta por dia**, repostas aos poucos (uma a cada 2,4 h), num bucket do Bucket4j na
tabela `rate_limit_bucket` (ADR 0006), chave `report:<conta>`. Por conta, e não por IP: a denúncia exige
login, e trocar de rede não renova a cota. Acima dela, `429` com `Retry-After`; com o banco fora ou o
lock preso, `503` (falha fechada). Só a denúncia válida gasta a cota; a que aponta para conta inexistente
também gasta, o que limita quem tenta adivinhar ids. O bloqueio **não** tem cota: protege quem bloqueia e
não cria trabalho para ninguém. Valores em `duora.trustsafety.report-rate-limit`.

### Sem `Idempotency-Key` na denúncia

A ADR 0005 pede `Idempotency-Key` nas operações repetíveis com efeito. A denúncia ainda não tem: um
reenvio cria uma segunda denúncia igual, que a moderação reconhece pelo par e pelo texto, e a cota
limita o volume. A infraestrutura de idempotência (chave por usuário, fingerprint, reserva atômica) entra
com a primeira operação em que a repetição custa caro (ação de jogo, pagamento), e a denúncia passa a
usá-la. Bloquear e desbloquear já são idempotentes pelo estado.

### Fila de moderação

**Fica para depois.** O plano exige acesso a evidências "restrito e auditado", e a trilha de auditoria
de leitura não existe; um `GET /api/admin/reports` sem ela violaria o plano. Até a moderação ser
decidida, as denúncias ficam no banco, sem leitura administrativa pela API.

### API para os outros módulos

`trustsafety.Blocking.existsBetween(AccountId, AccountId)`, na raiz do pacote (API publicada, ADR 0011):
se uma conta bloqueou a outra, em qualquer direção. É uma consulta só; quando chamada dentro da transação
de outro módulo, participa dela. Nada mais foi construído para chat e pareamento.

## Pendente com o usuário (decisões críticas, só o mínimo implementado)

1. **Quem modera e como.** Moderação humana própria, terceirizada ou ferramenta; papéis, MFA e os
   estados seguintes da denúncia (em análise, procedente, improcedente) e as ações (aviso, suspensão,
   banimento). Hoje só existe `OPEN`, e nada lê a fila.
2. **Retenção de denúncias e evidências (LGPD).** Por quanto tempo guardar a denúncia e o relato, o que
   fazer quando denunciante ou denunciado excluir a conta (as FKs `restrict` obrigam essa decisão), e se
   a denúncia guarda evidência além do texto (captura de mensagens, fotos). Hoje nada é apagado.
3. **Suspeita de menor.** O motivo `SUSPECTED_MINOR` existe e é gravado como qualquer outro. Falta o
   fluxo de proteção do plano §7: prioridade na fila, suspensão preventiva, a quem comunicar e com que
   base legal. Ligado à pendência 3 da ADR 0011.
4. **Histórico de bloqueios.** Desbloquear apaga a linha: não fica registro de que houve bloqueio. Se a
   moderação precisar do histórico (padrão de assédio), o desbloqueio vira mudança de estado.
5. **Efeito do bloqueio nos outros módulos.** O que o bloqueio esconde (perfil, presença no mesmo evento,
   conversas existentes) será decidido com chat e pareamento; hoje só a consulta existe.

## Consequências

- Os módulos que juntam pessoas chamam `Blocking.existsBetween` antes de juntar, e a ADR 0009 já prevê
  revalidar destinatários na outbox contra um bloqueio posterior.
- O front recebe o id da conta das pessoas que vê e o usa nas ações; a lista de bloqueados mostra só ids
  até o `profiles` publicar o resumo.
- A tabela `rate_limit_bucket` ganha uma linha por conta que denuncia, apagada pela limpeza da ADR 0006
  depois da reposição completa (no máximo um dia) mais um minuto.
- Os testes de integração limpam as contas por `AccountTables`, que apaga antes os dados dos módulos.

## Compliance

STRIDE do fluxo (dado sensível: quem bloqueou quem e o relato da denúncia):

| Ameaça | Mitigação | Teste |
|---|---|---|
| Spoofing: CSRF bloqueia ou denuncia em nome de quem está logado na web | Token CSRF em toda mutação da sessão web | `BlockIT.webSessionBlockWithoutCsrfTokenIsRejectedWithoutWriting`, `ReportIT.webSessionReportWithoutCsrfTokenIsRejectedWithoutWriting` |
| Spoofing/Tampering: denúncia em nome de outra conta, ou com estado já resolvido (mass assignment) | Autor = conta autenticada; DTO só com alvo, motivo e descrição; chave desconhecida → 400 | `ReportIT.unknownFieldIsRejectedWithoutWriting` |
| Tampering: auto-bloqueio ou auto-denúncia (dados que poluem a moderação) | Regra no domínio + `CHECK` no banco | `BlockTest.anAccountCannotBlockItself`, `NewReportTest.anAccountCannotReportItself`, `BlockIT.blockingYourselfIsRejectedWithoutWriting`, `ReportIT.reportingYourselfIsRejectedWithoutWriting`, `TrustSafetySchemaIT.anAccountCannotBlockItself`, `anAccountCannotReportItself` |
| Tampering: bloqueios duplicados sob concorrência | PK do par + `insert ... on conflict do nothing` | `BlockIT.concurrentBlocksOfTheSamePairKeepASingleBlock`, `blockingTwiceKeepsASingleBlockFromTheFirstTime`, `TrustSafetySchemaIT.aPairHasASingleBlockInEachDirection` |
| Tampering: dado de outro módulo apagado por baixo (cascata) | FKs `on delete restrict` | `TrustSafetySchemaIT.accountWithBlocksCannotBeDeletedBehindTheModulesBack`, `accountWithReportsCannotBeDeletedBehindTheModulesBack` |
| Repudiation: quem denunciou e quando | `reporter_account_id` e `created_at` gravados pelo servidor | `ReportIT.filingAReportRecordsItOpenForModeration` |
| Information disclosure: B lê a denúncia de A (BOLA) | Consulta por id **e** autor; alheia = mesmo 404 do inexistente, sem dado no corpo | `ReportIT.nobodyReadsSomeoneElsesReport` |
| Information disclosure: B vê quem A bloqueou | Lista filtrada por quem pede; token só marca posição | `BlockIT.listShowsOnlyTheCallersOwnBlocks`, `pageTokenFromAnotherAccountOnlyPagesTheCallersOwnList` |
| Information disclosure: quem foi bloqueado descobre o bloqueio | Bloquear e denunciar respondem igual com ou sem bloqueio do outro lado; nenhuma rota diz quem bloqueou você | `BlockIT.blockingSomeoneWhoBlockedYouLooksLikeAnyOtherBlock`, `ReportIT.reportingSomeoneWhoBlockedYouLooksLikeAnyOtherReport` |
| Information disclosure: enumeração de contas pelo 404 | Ids UUIDv7 não adivinháveis; 404 sem eco; tentativas de denúncia gastam a cota | `BlockIT.blockingAnUnknownAccountIsNotFoundWithoutWriting`, `ReportIT.reportingAnUnknownAccountIsNotFoundWithoutWriting` |
| Information disclosure: relato da denúncia no log (inclusive no DEBUG do Spring MVC, que imprime o corpo lido e escrito) ou ecoado no erro | `toString` redigido no VO e nos DTOs de entrada e saída; mensagens de erro sem o valor | `ReportIT.reportTextNeverReachesTheLog`, `rejectedDescriptionIsNotEchoedInTheResponse`, `SensitiveDataLoggingIT.acceptedReportDescriptionNeverReachesTheLog`, `rejectedReportDescriptionNeverReachesTheLog`, `ReportDescriptionTest`, `FileReportRequestTest`, `ReportResponseTest` (`doesNotExpose...InToString`) |
| Denial of service: denúncias em massa contra uma pessoa ou para afogar a moderação | Cota de 10/dia por conta, entre réplicas; 429 + `Retry-After`; falha fechada | `ReportIT.reportingAboveTheDailyQuotaIsRejectedWithRetryAfterAndWithoutWriting`, `quotaIsCountedForEachAccountSeparately`, `BucketReportQuotaIT.rejectsWhenTheStoreIsDown` |
| Denial of service: entrada grande ou inválida vira 500 | Limites na descrição e no `maxPageSize`; 400 sem gravar | `ReportIT.invalidInputIsRejectedWithoutWriting`, `descriptionOfAThousandCharactersIsAccepted`, `BlockIT.pageSizeOutsideTheLimitsIsRejected`, `malformedPageTokenIsRejected`, `ReportDescriptionTest` |
| Elevation of privilege: rota nova pública por engano | Negar por padrão | `DenyByDefaultIT` (inclui as cinco rotas), `BlockIT.anonymousCannotBlock`, `ReportIT.anonymousCannotReportNorRead` |

Contrato ([ADR 0012](0012-contrato-openapi.md)): `OpenApiContractIT.blockAndReportDocumentTheirContract`
confere na spec os erros, a cota (429 + `Retry-After`), o `Location`, a paginação e a lista de motivos.
No Spectral, a regra `owasp:api2:2023-no-credentials-in-url` fica desligada só para `pageToken`, que é
cursor e não credencial. No Schemathesis, a cota de denúncias sobe em `infra/docker/contract-test.sh`
(senão o fuzzing pararia no 429), e o 400 entra entre as respostas esperadas para corpo válido em
`POST /api/reports` (motivo `OTHER` sem descrição, caracteres invisíveis) e em
`GET /api/me/blocked-accounts` (pageToken que a API não gerou). O fuzzing achou dois defeitos, corrigidos
com teste: `?=null` virava 500 e um id inválido voltava inteiro no `detail` e no `instance`
(`MalformedRequestInputIT`), e `maxPageSize=` vazio valia o padrão (`BlockIT.pageSizeOutsideTheLimitsIsRejected`).

Fronteira entre módulos: `ArchitectureTest` (core: domínio sem Spring, aplicação por portas; outros
módulos só usam `trustsafety.Blocking`). A consulta publicada: `BlockIT.blockingIsSeenFromBothSidesUntilUnblocked`.
