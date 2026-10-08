# 0019. Decisão privada depois da rodada e conexões por interesse mútuo

- **Status:** Aceita, provisória. Implementada sem o usuário; revisar com ele. As decisões tomadas sem o
  usuário estão marcadas como **(autônoma)**, e as que dependem dele estão em "Pendente com o usuário".
- **Data:** 2026-10-08
- **Relacionadas:** [ADR 0003](0003-estilo-de-testes.md), [ADR 0005](0005-contrato-da-api.md),
  [ADR 0006](0006-rate-limit-no-postgresql.md), [ADR 0007](0007-estilo-por-modulo.md), [ADR 0009](0009-outbox-e-eventos.md),
  [ADR 0011](0011-conta-e-perfil.md), [ADR 0015](0015-bloqueio-e-denuncia.md),
  [ADR 0017](0017-pareamento.md), [ADR 0018](0018-erros-de-campo-no-problem-detail.md)

## Contexto

A etapa 2 do plano (§9) termina com a "decisão privada": depois de uma rodada de pareamento
([ADR 0017](0017-pareamento.md)), cada pessoa diz, em privado, se quer continuar em contato com o par
daquela rodada. Interesse mútuo vira uma **conexão**. O plano (§3) põe isso no módulo `connections`, que é
**core** ([ADR 0007](0007-estilo-por-modulo.md)); pede "uma decisão por sessão/pessoa; uma conexão por par
normalizado" (§4) e "serializar a avaliação de interesse mútuo e criar a conexão na mesma transação,
evitando ausência ou duplicação de conexão com aceites simultâneos". Os casos prioritários (§8) incluem
"dois aceites simultâneos criam uma única conexão" e "usuário não lê pistas ou decisões alheias".

Requisitos deste passo:

- só decide quem formou o par na rodada; `connections` não lê tabelas do `matching`;
- uma decisão por (rodada, pessoa); repetir a mesma é idempotente, a outra é recusada;
- ninguém descobre a decisão do outro, exceto pelo surgimento da conexão quando os dois dizem sim: nenhuma
  resposta (status, corpo, tempo perceptível) distingue "o outro disse não" de "o outro ainda não decidiu";
- dois "sim" simultâneos formam exatamente uma conexão, sem "checar e depois inserir";
- bloqueio ([ADR 0015](0015-bloqueio-e-denuncia.md)) entre as duas pessoas impede a conexão;
- cada pessoa lista as próprias conexões, com o id da outra conta e a data.

Notificações, chat e outbox ficam fora deste passo.

## Alternativas e decisões

### Quem pode decidir: API publicada do `matching` **(autônoma)**

| Opção | Prós | Contras |
|---|---|---|
| **API publicada `matching.Pairings.partnerOf(eventId, roundNumber, account)`** | `matching` continua dono de `round_seat`; uma consulta pela PK; o mesmo padrão de `EventRoster` e `Blocking` | Mais uma operação publicada para manter |
| `connections` lê `round_seat` direto | Uma consulta, nada muda no `matching` | Quebra a regra de dono da tabela ([ADR 0011](0011-conta-e-perfil.md)); o `ArchitectureTest` não pega SQL |
| FK de `round_decision` para `round_seat` | O banco garante que só o par decide | Acopla o esquema de `connections` ao de `matching`; uma mudança na tabela de assentos quebra a outra |

**Decisão:** `Pairings.partnerOf` devolve o par, ou vazio para quem ficou de fora, não estava no sorteio ou
pediu uma rodada que não existe. Também publica os limites do número da rodada (`FIRST_ROUND`, `LAST_ROUND`),
para a fronteira de `connections` responder `400` sem repetir o teto de 100. Os assentos não mudam depois
do sorteio, então ler o par e depois gravar a decisão não é uma corrida. Sem porta própria em
`connections` para `Pairings` e `Blocking`, pelo mesmo motivo da [ADR 0017](0017-pareamento.md): são a
fronteira dos donos, e um adapter que só repassa seria raso.

### Forma da decisão **(autônoma)**

| Opção | Prós | Contras |
|---|---|---|
| **`{"interested": true\|false}`** | O mínimo que o produto pede (sim/não); o tipo diz tudo | Um terceiro valor (talvez) exigiria mudar o tipo do campo |
| `{"decision": "YES"\|"NO"}` (enum) | Aceita um terceiro valor sem quebrar a forma | Generalidade especulativa; o enum do Jackson aceita número por padrão, como o booleano |

**Decisão:** booleano estrito. Por padrão o Jackson aceita `1` e `"true"` como `true`; o
`StrictBooleanDeserializer` recusa os dois com `400` `INVALID_FORMAT` ([ADR 0018](0018-erros-de-campo-no-problem-detail.md)).

### Decisão final, repetição e rota **(autônoma)**

`PUT /api/events/{eventId}/rounds/{number}/decision`, sub-recurso singular como o par
(`.../pairing`) e a inscrição ([ADR 0016](0016-eventos-e-inscricoes.md)): não há id de pessoa na rota,
então não há como ler ou gravar a decisão de outra pessoa.

- `201` com `Location` na primeira vez; `200` com a mesma decisão (mesma data) ao repetir a mesma escolha,
  inclusive em abas simultâneas; `409` com a outra escolha. Sem `Idempotency-Key` nem `If-Match`: a chave de
  negócio (evento, rodada, pessoa) identifica a intenção, como nas rotas `PUT` das ADRs 0016 e 0017.
- `GET` na mesma rota lê a própria decisão (`404` sem ela), para o `Location` apontar para algo legível e o
  front recuperar o estado depois de uma reconexão.
- A resposta tem só `{eventId, roundNumber, interested, decidedAt}`: nem o id do par (que a pessoa já lê
  no pareamento) nem se a conexão se formou.
- `404` igual para quem ficou de fora, quem não estava no sorteio, rodada inexistente e evento inexistente.

**Decisão final (provisória):** o usuário pediu decisão única; a alternativa "mudar até um prazo" fica
pendente (abaixo).

### Privacidade da decisão do par **(autônoma)**

Depois de gravar a decisão de quem chama, o serviço faz sempre o mesmo trabalho, qualquer que seja a
situação do par: lê a decisão dele, consulta o bloqueio e aplica a regra pura
`Connection.fromDecisions(minha, dele, bloqueio)`. A resposta é montada só a partir da decisão de quem
chama. A única diferença de trabalho é o `insert` da conexão quando os dois disseram sim, que é exatamente
o caso em que a pessoa pode saber (a conexão aparece na lista).

Resíduo aceito: se o par estiver decidindo **no mesmo instante**, a segunda decisão espera o lock (abaixo)
por alguns milissegundos. Isso diz no máximo que o par estava decidindo, nunca o valor.

### Dois "sim" simultâneos: serialização e unicidade **(autônoma)**

Duas coisas podem dar errado com aceites simultâneos em READ COMMITTED: **duas** conexões (duplicação) e
**nenhuma** (cada transação não vê o "sim" ainda não confirmado da outra, um write skew). A unicidade
resolve a primeira; a segunda precisa serializar as duas decisões do mesmo par.

| Opção | Prós | Contras |
|---|---|---|
| **Advisory lock de transação por (evento, rodada, par normalizado), `pg_advisory_xact_lock(hashtextextended(chave, 0))`** | Uma linha de SQL; trava antes de existir qualquer linha; some sozinho no commit ou rollback; respeita `lock_timeout` | A chave é um hash de 64 bits: dois pares diferentes podem colidir e só esperar um pelo outro; o lock não aparece no esquema |
| Linha coordenadora por par e rodada (`insert ... on conflict do nothing` + `select ... for update`) | O "registro coordenador" do plano, visível no esquema | Mais uma tabela e duas idas ao banco só para ter o que travar |
| `SERIALIZABLE` com retry em `40001` | O banco detecta o write skew sozinho | Retry da transação inteira por fora do `@Transactional`; uma decisão pode falhar por conflito com outra que não tem nada a ver (predicate lock em página) |
| Uma linha por par com as duas decisões em colunas (`on conflict do update`) | Um só statement atômico | As duas decisões na mesma linha; a regra "uma por pessoa" e o `409` viram SQL condicional por lado |

**Decisão:** advisory lock. Com ele, a segunda decisão espera a primeira confirmar e, como cada statement
em READ COMMITTED tira uma foto nova, lê o "sim" dela. A conexão entra com
`insert ... on conflict do nothing` na chave primária, o par normalizado: mesmo sem o lock, nunca há duas.

- Teto de espera: `lock_timeout` de 2 s (`JdbcDecisionRepository.LOCK_TIMEOUT`), com `503` e
  `Retry-After: 1`. Repetir é seguro: a mesma escolha é idempotente. É o mesmo teto da inscrição
  ([ADR 0016](0016-eventos-e-inscricoes.md)), porque a outra transação só grava duas ou três linhas.
- O `IT` de corrida (`ConnectionIT.simultaneousYesesCreateExactlyOneConnection`, 5 repetições) falhou nas 5
  com o lock desligado de propósito, e passa com ele.

### Bloqueio **(autônoma, provisória)**

`Blocking.existsBetween` (qualquer direção) é consultado na mesma transação, sempre. Com bloqueio, a
conexão não se forma, e a resposta da decisão é a mesma: não revela o bloqueio a quem foi bloqueado
([ADR 0015](0015-bloqueio-e-denuncia.md)). A decisão em si é gravada mesmo com bloqueio.

O que fazer com uma conexão que **já existe** quando um bloqueio é criado fica pendente; o mínimo
implementado é não criar. A lista de conexões não filtra bloqueios.

### Modelo de dados (`V11__create_round_decision_and_connection.sql`)

- `round_decision (event_id, round_number, account_id, partner_account_id, interested, decided_at)`, PK
  `(event_id, round_number, account_id)`: uma decisão por pessoa e rodada, também com pedidos simultâneos
  (`insert ... on conflict do nothing`). `CHECK` de 1 a 100 no número e `account_id <> partner_account_id`.
  Sem `update`: a decisão é final. FKs para `account` (`on delete restrict`, como as demais), cada uma com
  índice. Sem FK para `event`, `round` ou `round_seat` (acima).
- `connection (first_account_id, second_account_id, connected_at)`, PK do par e
  `CHECK (first_account_id < second_account_id)`: o par normalizado é único, e a linha invertida seria
  recusada. A ordem no Java (`ConnectionPair.of`) é a do texto do UUID, a mesma do tipo `uuid` no
  PostgreSQL. Índice em `second_account_id` para a FK e para a lista.
- **Uma conexão por par, não por evento (autônoma):** o plano pede "uma conexão por par normalizado". Se
  as mesmas pessoas se encontram de novo em outro evento e dizem sim de novo, a conexão continua a
  primeira, com a data dela.
- **Sem id próprio na conexão (autônoma):** como `account_block`, a chave é o par. Um id surrogate entra
  quando o chat ou a remoção precisarem apontar para a conexão (migração aditiva).
- `connected_at` é a data da decisão que completou o interesse mútuo.

### Lista das próprias conexões

`GET /api/me/connections`: `{items: [{accountId, connectedAt}], nextPageToken}`, da mais recente para a
mais antiga, com a outra conta desempatando. Keyset, sem `OFFSET`; `maxPageSize` de 1 a 100 (padrão 20),
como `/api/me/blocked-accounts`, cujo item tem a mesma forma (conta e data). O `pageToken` é Base64 URL da
última conexão vista, opaco e não cifrado: a consulta filtra sempre por quem chama, então um token
adulterado só muda onde a própria lista recomeça; instantes fora de 1970 a 9999 são `400`, para não virar
erro do banco. A consulta junta as duas metades (`first = eu` e `second = eu`) com `union all`, cada uma
pelo próprio índice.

### Rate limit **(autônoma)**

A versão inicial não limitava o `PUT` da decisão, sob o argumento de que ele é idempotente, autenticado e
uma decisão por rodada. O argumento falha no custo, como na inscrição ([ADR 0016](0016-eventos-e-inscricoes.md)):
cada chamada abre uma transação, disputa o advisory lock do par e lê a decisão do par e o bloqueio, mesmo
quando só devolve a decisão que já existe. Uma conta que repete a chamada em laço (um script do front, um
token vazado, um clique repetido) mantém conexões do pool presas, e as chamadas concorrentes sobre o mesmo
par esperam até o teto de 2 s, que só transforma a espera em `503` para quem não abusou. A idempotência faz
cada repetição responder `200`, então nada sinaliza o abuso. A [ADR 0006](0006-rate-limit-no-postgresql.md)
resolve isso com o mesmo mecanismo das denúncias, das inscrições e das rodadas.

| Opção | Prós | Contras |
|---|---|---|
| Sem limite, só o `lock_timeout` | Nada a mudar | Uma conta aumenta a latência das decisões do par e ocupa o pool; o `503` cai em quem não abusou |
| Limite por IP, como a fila de espera | Barra antes de autenticar | A decisão exige login e o IP é compartilhado (rede do local do evento, operadora): pune quem está na mesma rede; trocar de rede renova o limite |
| **Limite por conta, um bucket para o `PUT`** | A conta é o que o abuso repete; trocar de rede não renova; vale entre réplicas | Uma ida ao banco por chamada (transação curta, em outra conexão e antes da transação da decisão) |
| Limite por conta e rodada | Um laço numa rodada não esgota as outras | Abrir um bucket por (conta, rodada) multiplica as linhas da tabela por até 100 e não limita o que importa, que é a capacidade do servidor |

**Decisão:** um bucket por conta, chave `decision:<conta>` na tabela `rate_limit_bucket` ([ADR
0006](0006-rate-limit-no-postgresql.md)), consumido no controller depois de validar o número da rodada e
antes de abrir a transação. Por conta, e não por rodada: o recurso caro é a conexão e o lock, e quem decide
em vários eventos divide o mesmo saldo. A leitura (`GET`) é uma consulta pela chave primária de quem chama e
não gasta. Acima do limite, `429` com `Retry-After` em segundos (teto documentado de 86400, garantido porque
o período configurado é validado no boot); com o bucket impossível de contar, `503` com `Retry-After: 1` e
nada é gravado (falha fechada).

**Valores:** 120 chamadas por hora, repostas aos poucos (uma a cada 30 segundos), em
`duora.connections.decision-rate-limit.capacity` e `.period`. Uso humano normal é uma decisão por rodada, e
uma rodada dura minutos: algumas por hora durante o evento. O caso mais pesado é decidir tudo de uma vez
depois do encontro (estimativa: um evento tem até umas 20 rodadas; o máximo de 100 é folga): 20 decisões, ou
40 com uma repetição do front em cada, cabem no saldo com folga, e o clique duplo gasta duas. O teto de 120
é o dobro do das inscrições porque aqui o ciclo é por rodada, e não por evento; ainda assim, o abuso fica
em cerca de 2880 chamadas por dia por conta, cada uma curta (lock de até 2 s e duas ou três linhas). Quem
esgota espera 30 segundos por chamada. A capacidade é positiva e o período vai até um dia: qualquer outro
valor derruba a subida (`RequiredRateLimitSettingsIT`).

**A repetição idempotente também gasta.** O `200` da mesma escolha paga a transação e o lock por inteiro;
isentá-la deixaria justamente o abuso mais barato (repetir a mesma chamada) sem freio. O `404` de quem não
formou par e o `409` da outra escolha gastam também, o que limita quem tenta adivinhar ids de evento e
números de rodada. Não gastam: id que não é UUID, número fora de 1 a 100 e corpo inválido (`400`, recusados
antes de tocar o banco), falta de credencial (`401`) e sessão sem CSRF (`403`). A resposta do `429` não traz
nada da decisão nem do par, e o `429` não revela nada que a pessoa não saiba: depende só de quantas chamadas
ela mesma fez.

O limite é consumido no controller, antes de chamar o serviço: ele protege a borda HTTP, e a ida ao bucket
acontece fora da transação da decisão, para a espera pelo bucket não segurar o advisory lock do par.

## Pendente com o usuário (decisões críticas)

1. **Mudar a decisão até um prazo** em vez de decisão final (implementado: final, `409`). Com prazo, um
   "não" depois de um "sim" teria de desfazer uma conexão que talvez já tenha aparecido para o outro.
2. **Prazo para decidir:** até o fim do evento? N horas depois da rodada? Hoje não há prazo: a decisão vale
   enquanto a rodada existir. Com prazo, entra a regra no domínio e um `409` depois dele.
3. **Bloqueio de quem já está conectado:** apagar a conexão, escondê-la da lista ou manter (implementado:
   manter, a lista não filtra). Também: bloqueio criado no mesmo instante do segundo "sim" (READ COMMITTED)
   pode não ser visto, como na [ADR 0017](0017-pareamento.md).
4. **O que a conexão mostra** além do id da outra conta e da data (nome de exibição, foto), o que depende
   de uma API publicada do `profiles`.
5. **Aviso de "vocês se conectaram"** para quem decidiu primeiro: depende da outbox
   ([ADR 0009](0009-outbox-e-eventos.md)) e do módulo de notificações.
6. **Remover uma conexão** ("desconectar") e o que isso faz com o chat.
7. **Retenção das decisões:** guardar para sempre, apagar depois do evento ou depois do prazo. São dado
   pessoal sensível (interesse romântico) sem uso depois da conexão formada.
8. **Exclusão de conta:** as FKs são `restrict`; a futura exclusão precisa apagar decisões e conexões antes.

## Consequências

- `matching` ganha mais uma API publicada (`Pairings`), e `connections` passa a depender dela e de
  `trustsafety.Blocking`.
- O advisory lock usa o espaço global de chaves do PostgreSQL; a chave começa por `connections.decision`
  para não colidir com outro uso futuro.
- A regra do interesse mútuo é uma função pura testada sem banco; atomicidade e unicidade ficam no banco e
  são testadas com PostgreSQL real.
- A decisão tem limite por conta (seção "Rate limit"): 120 por hora. A leitura da decisão e a lista de
  conexões não têm, por serem consultas pela chave de quem chama. A tabela `rate_limit_bucket` ganha uma
  linha por conta que decide, apagada pela limpeza da [ADR 0006](0006-rate-limit-no-postgresql.md) depois
  da reposição. O limite adiciona uma ida ao banco por chamada; medir no k6 com o B2s junto com o lock.
- `StrictBooleanDeserializer` é local ao módulo; se outro corpo ganhar booleano, a mesma questão de coerção
  volta (candidato a configuração global, que mudaria outros corpos).

## Compliance

STRIDE do fluxo (dado pessoal sensível: interesse de uma pessoa por outra num app de encontros):

| Ameaça | Mitigação | Teste |
|---|---|---|
| Information disclosure: a resposta da decisão revela se o par disse não ou ainda não decidiu | Mesmo trabalho em todos os casos; resposta montada só com a decisão de quem chama | `ConnectionIT.theAnswerIsTheSameWhetherThePartnerSaidNoOrHasNotDecided`, `aNoOnEitherSideFormsNothing`, `aDecisionIsRecordedAndOnlyTellsAboutTheCaller` (JSON estrito); `ConnectionTest` |
| Information disclosure: alguém lê a decisão do par | Rota sem id de pessoa; consulta pela conta de quem chama | `ConnectionIT.thePartnerCannotReadTheDecision` |
| Information disclosure: alguém vê conexões alheias | Lista filtrada por quem chama na própria query; token não autoriza nada | `ConnectionIT.nobodySeesTheConnectionsOfOthers`, `twoYesesFormOneConnectionThatBothSee` (JSON estrito) |
| Spoofing/Elevation of privilege: quem não formou o par decide sobre alguém | Par vem de `Pairings.partnerOf`; `404` igual para os quatro casos | `ConnectionIT.onlyWhoFormedThePairDecides`; `PairingsIT` |
| Elevation of privilege: rota nova pública por engano | Negar por padrão | `DenyByDefaultIT` (as rotas novas estão na enumeração) |
| Tampering: CSRF grava uma decisão pela sessão web | Token CSRF obrigatório | `ConnectionIT.aWebSessionWithoutCsrfTokenCannotDecide`, `aWebSessionWithCsrfTokenDecides` |
| Tampering: corpo com par, dono ou valor coagido (`1`, `"true"`) | DTO estrito: chave desconhecida e tipo errado são `400` com `errors` | `ConnectionFieldErrorsIT` |
| Tampering: mudar uma decisão já feita | Decisão final no domínio (`409`); PK e nenhum `update` no banco | `ConnectionIT.aDecisionCannotChange`; `DecisionTest.aDecisionIsFinal`; `ConnectionsSchemaIT.aPersonDecidesOncePerRound` |
| Tampering: dois "sim" simultâneos criam zero ou duas conexões | Advisory lock por par e rodada + PK do par normalizado com `on conflict do nothing` | `ConnectionIT.simultaneousYesesCreateExactlyOneConnection`, `simultaneousRepeatsOfTheSameDecisionRecordItOnce`, `anAlreadyConnectedPairKeepsTheFirstConnection`; `ConnectionsSchemaIT.aPairHasASingleConnection`, `theConnectionPairIsNormalized` |
| Tampering: conexão entre pessoas bloqueadas | `Blocking.existsBetween` na mesma transação, qualquer direção | `ConnectionIT.aBlockEitherWayPreventsTheConnection`; `ConnectionTest.aBlockBetweenThemFormsNothingEvenWithTwoYeses` |
| Tampering: "sim" de outra rodada, evento ou pessoa completa o interesse mútuo | `Decision.answers` exige mesmo evento, rodada e par cruzado | `ConnectionTest.aYesFromAnotherRoundFormsNothing`, `aYesFromAnotherEventFormsNothing`, `aYesAboutSomeoneElseFormsNothing` |
| Denial of service: número, id, `maxPageSize` ou `pageToken` inválidos viram `500` | Validação na fronteira → `400`; instante do token limitado | `ConnectionIT.anInvalidRoundNumberIsABadRequestAndWritesNothing`, `anEventIdThatIsNotAUuidIsABadRequest`, `anInvalidPageSizeIsABadRequest`, `aPageTokenTheApiDidNotIssueIsABadRequest` |
| Denial of service: uma decisão presa segura conexões | `lock_timeout` de 2 s → `503` com `Retry-After` | `ConnectionIT.aDecisionIsRefusedWhenThePartnersTakesTooLong` |
| Denial of service: uma conta (ou token vazado) repete o `PUT` e prende conexões e o lock do par | 120 chamadas por hora por conta, entre réplicas; `429` + `Retry-After`; repetição idempotente também gasta | `DecisionRateLimitIT.callsAboveTheLimitAreRejectedWithRetryAfterAndRecordNothing`, `idempotentRepeatsSpendTheLimit`, `callsFromWhoFormedNoPairSpendTheLimit`, `theLimitIsCountedForEachAccountSeparately`, `callsRefusedBeforeTheDatabaseDoNotSpendTheLimit`, `readingTheDecisionDoesNotSpendTheLimit`, `theBucketLivesUnderTheDecisionKeyOfTheAccount`; `RequiredRateLimitSettingsIT` |
| Denial of service: limite que cai com o banco deixa passar | Falha fechada: `503` com `Retry-After: 1`, nada gravado | `DecisionRateLimitIT.decisionIsRefusedWithoutWritingWhenTheLimitCannotBeCounted`; `AccountRateLimitIT.rejectsWhenTheStoreIsDown` |

Repudiation: a decisão guarda quem e quando (`decided_at`); não há outra trilha de auditoria.

Regras sem Spring: `ConnectionTest`, `DecisionTest`, `ConnectionPairTest`. Fronteira entre módulos:
`ArchitectureTest.coreDomainIsFrameworkFree`, `coreApplicationTalksToInfrastructureThroughPorts` e
`modulesUseOnlyPublishedApisOfOtherModules`. Contrato: `OpenApiContractIT`, Spectral, `oasdiff` e
Schemathesis.
