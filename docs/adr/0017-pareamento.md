# 0017. Pareamento: rodadas, sorteio dos pares e quem fica de fora

- **Status:** Aceita, provisória. Implementada sem o usuário; revisar com ele. As decisões tomadas sem o
  usuário estão marcadas como **(autônoma)**, e as que dependem dele estão em "Pendente com o usuário".
- **Data:** 2026-10-07
- **Relacionadas:** [ADR 0003](0003-estilo-de-testes.md), [ADR 0004](0004-identificadores-e-unicidade.md),
  [ADR 0005](0005-contrato-da-api.md), [ADR 0006](0006-rate-limit-no-postgresql.md),
  [ADR 0007](0007-estilo-por-modulo.md), [ADR 0009](0009-outbox-e-eventos.md), [ADR 0011](0011-conta-e-perfil.md),
  [ADR 0015](0015-bloqueio-e-denuncia.md), [ADR 0016](0016-eventos-e-inscricoes.md)

## Contexto

A etapa 2 do plano (§9) é "evento, pareamento, um jogo, chat temporário, reconexão e decisão privada". O
módulo `events` já tem eventos e inscrições ([ADR 0016](0016-eventos-e-inscricoes.md)); o próximo passo é
juntar os inscritos em pares. O plano (§3) põe em `matching` "rodadas, disponibilidade e pares mutuamente
elegíveis" e pede "bloqueio transacional do registro coordenador ao formar pares" (§4).

`matching` é **core** ([ADR 0007](0007-estilo-por-modulo.md)): domínio em Java puro, aplicação falando com
banco e outros módulos por portas, adapters por fora.

Requisitos deste passo:

- pares bloqueados ([ADR 0015](0015-bloqueio-e-denuncia.md), `trustsafety.Blocking`) nunca se formam, em
  nenhuma direção;
- o mesmo par não se repete dentro do mesmo evento;
- com número ímpar, ou quando os bloqueios impedem, alguém fica de fora, com rotação justa;
- duas chamadas simultâneas para iniciar a rodada geram **uma** rodada, garantido por constraint ou lock,
  nunca por "checar e depois inserir";
- `matching` não lê tabelas de outro módulo: inscritos e bloqueios chegam por API publicada;
- critérios de preferência e compatibilidade (gênero, orientação, idade, interesses) são decisão do usuário.
  Até lá, vale só a elegibilidade mínima: estar inscrito, não ter bloqueio com a outra pessoa e não ter
  formado par com ela no evento.

## Alternativas e decisões

### O que é uma rodada **(autônoma)**

| Opção | Prós | Contras |
|---|---|---|
| **Rodada numerada dentro do evento (1, 2, 3...), cada uma com um sorteio** | Simples de endereçar (`/rounds/{number}`); o número é a chave de idempotência natural; a ordem fica no banco | Não diz quanto tempo a rodada dura nem quando acaba: isso fica com o jogo e o chat |
| Rodada com janela de tempo (`startsAt`, `endsAt`) agendada na criação do evento | O evento vira uma agenda previsível para o participante | Exige decidir a duração de cada rodada e um job com lock entre réplicas antes de existir o jogo |
| Um pareamento só por evento, sem rodadas | O mais simples | O plano fala em rodadas; quem ficar de fora fica de fora o evento inteiro |

**Decisão:** rodada numerada, com `startedAt` e a semente do sorteio guardados. A rodada **N** só existe se
a **N−1** existe (garantido no banco, abaixo). Duração e encerramento da rodada ficam pendentes (dependem do
jogo e do chat).

### Como formar os pares **(autônoma)**

Os candidatos são um grafo: cada pessoa é um vértice e há aresta entre duas quando o par é permitido (sem
bloqueio e sem par anterior no evento).

| Opção | Prós | Contras |
|---|---|---|
| Embaralhar e juntar de dois em dois, pulando pares proibidos (guloso) | Poucas linhas | Deixa gente de fora sem necessidade: com A-B, B-C e C-D permitidos, juntar B-C abandona A e D, quando A-B e C-D cobririam todos |
| **Emparelhamento máximo (Edmonds, "blossom") com prioridade para quem ficou de fora** | Forma o maior número possível de pares em qualquer grafo; a prioridade é exata (ver abaixo); determinístico com semente | Algoritmo menos conhecido (~200 linhas) e mais caro; precisa de testes contra busca exaustiva |
| Emparelhamento de peso máximo (preferências como pesos) | Já acomodaria compatibilidade graduada | Algoritmo bem maior (Galil); sem critério de preferência decidido, é generalidade especulativa |

**Decisão:** emparelhamento máximo. O sorteio (`RoundPairing.draw`) é uma **função pura**: recebe os
candidatos (conta e quantas rodadas do evento ela já passou sem par), os pares proibidos e uma semente, e
devolve os pares e quem ficou de fora. Mesma entrada e mesma semente, mesma rodada, em qualquer ordem de
chegada dos candidatos.

- **Ordem de prioridade:** quem ficou de fora mais vezes vem primeiro; empates são embaralhados pela semente
  (sobre a ordem dos ids, para não depender da ordem de chegada).
- **Prioridade exata:** os conjuntos de pessoas que algum emparelhamento cobre formam um matroide, então o
  guloso pela ordem de prioridade acha, entre os emparelhamentos máximos, o que cobre primeiro quem tem mais
  prioridade (`PriorityMatching`). O teste "este conjunto pode ser coberto junto?" é um emparelhamento
  perfeito num grafo auxiliar. O Edmonds sozinho, com as raízes em ordem de prioridade, **não** basta: o fim
  de cada caminho de aumento é arbitrário, e um caso aleatório mostrou alguém com menos rodadas de fora
  tomando o lugar de quem tinha mais.
- **Custo medido** (teste descartável, JVM fria, uma execução): 200 candidatos com até 30% dos pares
  proibidos, 6 a 37 ms; 199 candidatos com todos os pares proibidos (o pior caso, todo mundo de fora),
  1,2 s. O sorteio roda uma vez por rodada, numa ação do ADMIN; a capacidade máxima do evento é 200.
- **Strategy** de pareamento (plano §5) só entra quando houver um segundo critério de verdade.

### Número ímpar e quem fica de fora **(autônoma, provisória)**

| Opção | Prós | Contras |
|---|---|---|
| **Uma pessoa fica de fora na rodada, com rotação por "rodadas sem par"** | Pares sempre de dois, como o jogo e o chat esperam; justiça medível | Alguém espera uma rodada inteira |
| Trio para quem sobrar | Ninguém espera | Jogo, chat e decisão privada passam a lidar com três pessoas; muda o produto |
| Alguém da equipe forma par com quem sobrar | Ninguém espera e o par é de dois | Precisa de equipe presente em todo evento; a "decisão privada" com alguém da equipe não faz sentido |

**Decisão:** fica de fora quem o sorteio não consegue cobrir, com prioridade para quem já ficou de fora mais
vezes **no mesmo evento**. Os bloqueios podem deixar mais de uma pessoa de fora; o sorteio sempre forma o
máximo possível de pares. A contagem de "rodadas sem par" sai das próprias rodadas gravadas, não de um
contador.

### Pares proibidos

- **Bloqueio:** a API publicada hoje é `Blocking.existsBetween(a, b)`, uma consulta por par. Com 200
  inscritos são 19.900 consultas por sorteio (N+1).

  | Opção | Prós | Contras |
  |---|---|---|
  | Chamar `existsBetween` para cada par | Nada muda no `trustsafety` | 19.900 consultas numa transação |
  | **Nova API publicada no `trustsafety`: os pares bloqueados entre um conjunto de contas, numa consulta** | Uma consulta; o `trustsafety` continua dono da tabela e da regra "qualquer direção" | Mais um método publicado para manter |
  | `matching` lê `account_block` direto | Uma consulta, sem mudar o `trustsafety` | Quebra a regra de dono da tabela ([ADR 0007](0007-estilo-por-modulo.md), [ADR 0011](0011-conta-e-perfil.md)) |

  **Decisão (autônoma):** a nova API publicada `Blocking.blockedPairsAmong(Collection<AccountId>)`, que
  devolve `BlockedPair` sem direção: quem bloqueou quem continua escondido também do `matching`. A consulta
  passa os ids num array (`= any(:ids)`), um parâmetro só para qualquer tamanho de grupo.
- **Par repetido:** sai das rodadas anteriores do mesmo evento, que são tabelas do próprio `matching`, e
  também é garantido por constraint (abaixo).
- **Bloqueio criado depois do sorteio** (ou commitado durante ele, em READ COMMITTED): o par já formado não é
  desfeito por este módulo. Quem junta as duas pessoas depois (jogo, chat) consulta o bloqueio de novo antes
  de cada interação, como a [ADR 0015](0015-bloqueio-e-denuncia.md) já prevê. O que acontece com a rodada
  em andamento é pendência do usuário.

### Quem dispara a rodada e a garantia de concorrência **(autônoma, provisória)**

| Opção | Prós | Contras |
|---|---|---|
| **`PUT /api/admin/events/{eventId}/rounds/{number}` pelo ADMIN** | Idempotente pela chave de negócio (evento, número), como a inscrição da [ADR 0016](0016-eventos-e-inscricoes.md): repetir devolve a mesma rodada; duas abas pedindo a mesma rodada não criam duas | `PUT` sem corpo é menos comum; o ADMIN precisa saber o próximo número (o front mostra) |
| `POST /api/admin/events/{eventId}:startRound` com `Idempotency-Key` | Segue a forma `:verbo` da [ADR 0005](0005-contrato-da-api.md) | Duas chamadas com chaves diferentes (duas abas) criam as rodadas N e N+1; a chave exige tabela, fingerprint e limpeza; a rodada é um recurso criado, não uma transição de estado do evento |
| Agendamento (job) a partir de uma duração de rodada | Sem ação manual durante o evento | Depende da duração da rodada (pendente) e de lock entre réplicas; um sorteio errado não tem quem pare |

**Decisão:** `PUT .../rounds/{number}` pelo ADMIN. Respostas: `201` + `Location` na criação, `200` com a
mesma rodada na repetição, `409` se a rodada anterior não existe ou o evento não está em andamento (inclusive
rascunho: o ADMIN vê rascunhos, então para ele o evento existe), `404` para evento inexistente. Sem `Idempotency-Key` nem `If-Match`, pelos mesmos motivos da
[ADR 0016](0016-eventos-e-inscricoes.md) (a chave de negócio identifica a intenção; não há corpo a perder).

**Concorrência, sem checar e depois inserir:**

1. `insert into round (event_id, number, ...) ... on conflict (event_id, number) do nothing`. A PK
   `(event_id, number)` faz a segunda transação concorrente **esperar** a primeira; quando ela commita, o
   insert não faz nada e a segunda relê e devolve a rodada vencedora (`200`). Só quem inseriu sorteia.
2. A sequência é do banco: `round.previous_number` com FK para `(event_id, number)` da própria tabela e
   `CHECK (previous_number is not distinct from nullif(number - 1, 0))` (um `CHECK` comum com `or` passaria
   com `previous_number` nulo, porque `CHECK` nulo não recusa). A rodada N+1 nunca existe sem a N commitada:
   sem a N, a FK recusa (`409`), sem consulta prévia. Com a N em voo, o PostgreSQL pode recusar ou esperar
   por ela; o teste de corrida aceita os dois resultados e confere que nunca há N+1 sem N.
3. Na mesma transação, o vencedor lê inscritos, bloqueios e rodadas anteriores, sorteia e grava os assentos.
   Como a rodada N+1 só existe depois de a N commitar, os pares anteriores lidos estão completos.
4. Teto de espera: `lock_timeout` de 5 s (`JdbcRoundRepository.LOCK_TIMEOUT`), com `503` e `Retry-After: 1`
   (repetir é seguro porque o `PUT` é idempotente). É maior que os 2 s da inscrição porque quem espera aguarda
   um sorteio inteiro, não uma contagem. O `JdbcClient` não traduz o `55P03` do PostgreSQL; o adapter o
   converte em `CannotAcquireLockException`.
5. Os assentos entram num insert só (`unnest` de dois arrays), sem uma ida ao banco por pessoa.

Não há lock na linha do evento: ela é do `events`, e o `matching` não trava tabela alheia. A PK da rodada é
o "registro coordenador" do plano (§4). Com o evento em andamento a lista de inscritos não muda (inscrever e
sair só valem até o início, [ADR 0016](0016-eventos-e-inscricoes.md)), então lê-la sem lock é seguro. Um
cancelamento do evento concorrente com o sorteio pode deixar uma rodada num evento recém-cancelado; a rodada
fica gravada e não faz mal (ver pendências).

O evento que já acabou continua respondendo `200` a uma rodada que já existe: a repetição do `PUT` é
idempotente mesmo depois do horário. Só criar rodada exige o evento em andamento.

**Número máximo de rodadas (autônoma):** 100 por evento (`RoundNumber.MAX`, repetido no `CHECK`), folga para
um evento de até 12 horas. Número fora de 1 a 100 → `400`.

### Rate limit **(autônoma)**

A versão inicial não limitava o `PUT` da rodada: a rota é de ADMIN e idempotente. O custo, porém, não depende
do efeito. Cada chamada abre uma transação, lê os inscritos pela API do `events` e disputa a chave da rodada
(`insert ... on conflict`, que faz a chamada esperar a concorrente até o teto de 5 s); a que cria a rodada
ainda roda o emparelhamento O(n³), medido em 1,2 s no pior caso (n = 200). Um ADMIN com o token vazado, um
script do front em laço ou um clique repetido mantêm conexões do pool presas por segundos. Uma rota de
ADMIN reduz quem pode abusar, mas não o estrago de um só.

**Decisão:** limite por conta ADMIN, num bucket `round:<conta>` da tabela `rate_limit_bucket` ([ADR
0006](0006-rate-limit-no-postgresql.md)), consumido no controller antes de ler os inscritos. Por conta, e não
por evento: o recurso caro é a capacidade do servidor, e o ADMIN com vários eventos em andamento divide o
mesmo saldo. A leitura (`GET`) é uma consulta pela chave primária e não gasta.

**Valores:** 30 chamadas por hora, repostas aos poucos (uma a cada 2 minutos), em
`duora.matching.round-rate-limit.capacity` e `.period`. Uso normal é uma rodada a cada poucos minutos por
evento (o máximo é 100 rodadas, folga para 12 horas), com alguns eventos simultâneos e o clique duplo
ocasional: bem abaixo de 30 por hora. O saldo cheio de 30 ainda deixa o ADMIN corrigir uma sequência de
`409`/`404`, e quem esgota espera 2 minutos. Valor inválido derruba a subida (`RequiredRateLimitSettingsIT`).

**A repetição idempotente também gasta**, pelo mesmo motivo da [ADR 0016](0016-eventos-e-inscricoes.md): o
`200` da rodada que já existe paga a leitura dos inscritos e a espera pela chave. O `404` de evento
inexistente e o `409` de rodada fora de ordem gastam também. O `403` de quem não é ADMIN e o `400` de número
inválido são barrados antes do controller e não gastam. `429` com `Retry-After`; bucket impossível de
contar → `503` com `Retry-After: 1`, sem sorteio (falha fechada). Como o `AccountId` do ADMIN passa a ser
resolvido, a primeira chamada abre a conta dele, se ainda não existir.

### Como o `matching` obtém os inscritos

Ler a tabela `registration` quebraria a regra de dono. **Decisão (autônoma):** a API publicada mínima
`events.EventRoster.rosterOf(eventId, now)`, na raiz do pacote (como `profiles.ProfileCompleteness`), que
devolve um tipo selado `events.Roster`: `UnknownEvent`, `NotUnderway` (rascunho, cancelado, antes do início
ou depois do fim) ou `Underway(registrants)`, com os inscritos na ordem dos ids. "Em andamento" é regra do
evento (`Event.isUnderway`, intervalo semiaberto `[startsAt, endsAt)`), e não do `matching`.

**Sem porta própria para as APIs publicadas (autônoma):** o `RoundService` chama `EventRoster` e `Blocking`
direto. As duas já são a fronteira dos módulos donos, devolvem só `AccountId` e tipos simples, e rodam na
mesma transação; uma interface no `matching` com um adapter que só repassa a chamada seria um módulo raso. A
[ADR 0007](0007-estilo-por-modulo.md) pede portas para banco e serviços externos, o que o repositório das
rodadas cumpre.

### Modelo de dados (`V10__create_round_and_round_seat.sql`)

- `round (event_id, number, previous_number, seed, started_at)`, PK `(event_id, number)`, FK para `event`
  (`on delete restrict`, como a inscrição) e a auto-FK da sequência acima. A semente fica guardada para o
  sorteio poder ser reproduzido numa investigação.
- `round_seat (event_id, round_number, account_id, partner_account_id)`, um assento por pessoa e rodada,
  **simétrico** (A→B e B→A); `partner_account_id` nulo é quem ficou de fora.
  - PK `(event_id, round_number, account_id)`: ninguém em dois pares na mesma rodada.
  - `UNIQUE (event_id, account_id, partner_account_id)`: o par não se repete no evento. Os nulos de quem
    ficou de fora não colidem entre si (`NULLS DISTINCT`, o padrão), que é o que se quer aqui: ficar de fora
    em várias rodadas é permitido.
  - `CHECK (account_id <> partner_account_id)`.
  - **Par recíproco garantido no banco:** FK `(event_id, round_number, partner_account_id, account_id)` para
    `(event_id, round_number, account_id, partner_account_id)` da própria tabela, isto é, o assento do
    parceiro precisa apontar de volta. É `deferrable initially deferred`, porque os dois lados entram na
    mesma transação; o `unique` de quatro colunas existe só para servir de alvo à FK.
  - FK de `account_id` para `account` e da rodada para `round`, `on delete restrict`. Não há FK para
    `registration`: seria acoplar o `matching` a mais uma tabela do `events`, e quem entra no sorteio já vem
    da API publicada dele.
  - "Minha dupla na rodada" é `where account_id = :eu`: a consulta só alcança o próprio assento.
- A semente vem de `ThreadLocalRandom` no serviço; o domínio só a recebe como valor.

### Contrato

- `PUT /api/admin/events/{eventId}/rounds/{number}` (ADMIN): `{eventId, number, startedAt, pairCount,
  sittingOutCount}`; **sem** a lista de quem formou par com quem (o ADMIN não precisa dela para conduzir o
  evento; ver pendências).
- `GET /api/admin/events/{eventId}/rounds/{number}` (ADMIN): a mesma resposta, para o `Location` do `201`
  apontar para algo legível; `404` sem a rodada.
- `GET /api/events/{eventId}/rounds/{number}/pairing` (a própria pessoa): `{eventId, roundNumber,
  partnerAccountId}`, com `null` para quem ficou de fora; `404` igual para rodada inexistente, evento
  inexistente ou pessoa que não estava no sorteio. **Só o id da conta do par (autônoma, provisória):** é o
  mínimo para o front e para um bloqueio (`POST /api/accounts/{accountId}:block`, que já recebe esse id);
  nome, foto ou nada até o jogo começar continua pendência do usuário.

### Rodada atual no evento (`currentRound`) **(autônoma, 2026-10-08)**

O front não sabia qual era a rodada atual de um evento e pedia à pessoa para digitar o número antes de ler
o próprio par. O número é do `matching`, e o `matching` já depende do `events` (`EventRoster`).

| Opção | Prós | Contras |
|---|---|---|
| Rota nova no `matching`, `GET /api/events/{eventId}/rounds/current` | Sem ciclo; a rodada continua só no `matching` | Mais uma ida do front a cada tela de evento; a rota precisa repetir a regra de visibilidade do evento (rascunho = `404`) pela API do `events` |
| `events` chama uma API publicada do `matching` | O mais direto | Ciclo `events` ↔ `matching`: os dois módulos deixam de poder ser separados ou testados um sem o outro |
| **`events` declara a interface `events.RoundProgress`, e o `matching` a implementa** | `currentRound` sai no próprio `GET /api/events/{id}`, uma ida só; o código continua dependendo só de `matching` para `events` (inversão de dependência) | O `events` passa a conhecer a palavra "rodada" e precisa de um bean do `matching` para subir |

**Decisão:** `events.RoundProgress.latestStartedRoundOf(eventId)`, implementada por
`matching.adapter.EventRoundProgress`, que chama `RoundService.latestStartedOf`. A consulta é
`order by number desc limit 1` pela chave primária `(event_id, number)`; como a sequência das rodadas não tem
buracos (FK da rodada anterior), o maior número é a última iniciada.

- `EventResponse.currentRound`: inteiro de 1 a 100 ou `null`, sempre presente. Só o número: sem pares, sem
  inscritos, sem contagens. Vale para qualquer pessoa logada que lê o evento, inscrita ou não, como o resto do
  evento; revela só que o evento já teve rodadas.
- Na lista `GET /api/events` o campo é sempre `null` sem consultar o `matching`: a lista só traz eventos que
  ainda não começaram, e rodada só começa com o evento em andamento.
- Evento acabado ou cancelado continua mostrando a última rodada iniciada.
- `ArchitectureTest.modulesAreFreeOfCycles` passa a recusar ciclo entre módulos no build.

## Pendente com o usuário (decisões críticas)

1. **Critérios de compatibilidade:** gênero e orientação (quem pode formar par com quem), faixa de idade,
   interesses, região. Mudam o grafo de pares permitidos e podem pedir pesos (emparelhamento de peso máximo).
2. **Presença ("disponibilidade" no plano):** hoje entram no sorteio todos os inscritos. Sem check-in, quem
   não apareceu ganha par e deixa alguém sozinho. Check-in no início do evento? Confirmação a cada rodada?
3. **Quem sobra:** fica de fora (implementado), trio, ou par com alguém da equipe.
4. **Duração e encerramento da rodada**, e se o disparo é manual (decidido aqui, provisório) ou agendado.
5. **Bloqueio ou denúncia durante a rodada:** encerrar o par na hora? Avisar a outra pessoa? Repor o par?
6. **O que a pessoa vê do parceiro** (nome de exibição, foto, nada até o jogo começar) e se o ADMIN vê quem
   formou par com quem (útil para moderação, sensível para privacidade).
7. **Elegibilidade no momento do sorteio:** conta suspensa pela moderação, inscrição cancelada durante o
   sorteio, perfil que deixou de estar completo.
8. **Evento cancelado durante a rodada:** a rodada continua gravada e o par continua visível. Encerrar os
   pares? Avisar?
9. **Aviso de "sua dupla saiu":** depende da outbox ([ADR 0009](0009-outbox-e-eventos.md)) e do Web PubSub.
10. **jqwik:** o spike de 2026-10-07 com o jqwik 1.10.1 rodou no JUnit 6 do Boot 4 (propriedade falhando com
    shrink e 232 testes Jupiter e jqwik juntos no `./mvnw test`), o que contradiz a anotação de que ele era
    incompatível. Adotar é decisão de biblioteca: até lá, as propriedades rodam como casos aleatórios com
    semente fixa num teste parametrizado (`RoundPairingRandomCasesTest`).

## Consequências

- O sorteio é testado sem Spring nem banco ([ADR 0003](0003-estilo-de-testes.md)), com exemplos por
  partição e 300 grupos aleatórios conferidos contra busca exaustiva (máximo de pares, nenhum par proibido,
  cada pessoa em exatamente um lugar e nenhuma troca que deixaria o sorteio mais justo).
- O algoritmo é menos óbvio que um embaralhamento; a explicação mora no Javadoc de `MaximumMatching` e
  `PriorityMatching`, e um teste quebra se o blossom for desligado.
- A justiça vale dentro de um evento; entre eventos, ninguém carrega "rodadas sem par".
- Duas APIs publicadas novas: `events.EventRoster` (com `events.Roster`) e
  `trustsafety.Blocking.blockedPairsAmong` (com `trustsafety.BlockedPair`). As tabelas `round` e
  `round_seat` têm FK `restrict` para `event` e `account`: a limpeza de dados de teste e a futura exclusão de
  conta passam a apagar rodadas antes (`AccountTables`).
- O `PUT` da rodada tem limite por conta ADMIN (seção "Rate limit"): 30 por hora. A leitura não tem, por ser
  uma consulta pela PK. A tabela `rate_limit_bucket` ganha uma linha por ADMIN que sorteia.
- Não há trilha de auditoria de quem iniciou cada rodada (Repudiation, abaixo); a semente guardada permite
  reproduzir o sorteio.

## Compliance

STRIDE do fluxo (permissão de ADMIN e dado pessoal: quem encontra quem num app de encontros):

| Ameaça | Mitigação | Teste |
|---|---|---|
| Elevation of privilege: usuário comum inicia ou lê uma rodada | Rota `/api/admin/**` exige `ADMIN` | `RoundIT.aUserWithoutTheAdminRoleCannotStartNorReadARound` |
| Elevation of privilege: rota nova pública por engano | Negar por padrão | `DenyByDefaultIT` (as rotas novas estão na enumeração) |
| Tampering: CSRF inicia uma rodada pela sessão web do ADMIN | Token CSRF obrigatório | `RoundIT.anAdminWebSessionWithoutCsrfTokenCannotStartARound`, `anAdminWebSessionWithCsrfTokenStartsARound` |
| Tampering: duas chamadas simultâneas criam duas rodadas ou dois sorteios | PK `(event_id, number)` + `insert ... on conflict do nothing`; só quem gravou sorteia | `RoundIT.concurrentStartsOfTheSameRoundCreateASingleRound`, `startingTheSameRoundAgainAnswersTheSameRound`, `MatchingSchemaIT.anEventHasOneRoundPerNumber` |
| Tampering: rodada fora de ordem (N sem N-1) | Auto-FK `round_previous_fk` + `round_sequence_check` | `RoundIT.aRoundNeedsThePreviousOneAndWritesNothingWithoutIt`, `aRoundStartedTogetherWithThePreviousOneNeverExistsWithoutIt`; `MatchingSchemaIT.aRoundNeedsThePreviousOne`, `aRoundAfterTheFirstMustPointToThePreviousOne`, `aRoundCannotSkipNumbers` |
| Tampering: pessoas bloqueadas formam par | Pares bloqueados entram como proibidos no sorteio | `RoundIT.peopleSeparatedByABlockAreNeverPaired`; `PairingHistoryTest.blockedPeopleAreNotPaired`; `BlockIT.blockedPairsAmongAGroupComeInEitherDirectionAndOnlyInsideTheGroup` |
| Tampering: o mesmo par se repete no evento | Pares anteriores proibidos no sorteio + `round_seat_pair_once_per_event` | `RoundIT.aPairIsNeverFormedTwiceInTheSameEvent`; `PairingHistoryTest.aPairAlreadyFormedInTheEventIsNotFormedAgain`; `MatchingSchemaIT.thePairDoesNotRepeatInTheEvent` |
| Tampering: par de um lado só ou pessoa em dois pares | FK recíproca deferrable + PK `(event_id, round_number, account_id)` | `MatchingSchemaIT.aOneSidedPairIsRejected`, `aPairMustBeReciprocalAndNotJustPointAtSomeonesSeat`, `aPersonHasOneSeatPerRound` |
| Tampering: rodada em evento rascunho, cancelado, antes do início ou depois do fim | `Event.isUnderway` pela API publicada do `events` | `RoundIT.roundsDoNotStartBeforeTheEvent`, `roundsDoNotStartAfterTheEvent`, `roundsDoNotStartInACancelledEvent`, `roundsDoNotStartInADraft`; `EventTest` (bordas de `isUnderway`); `EventRosterIT` |
| Information disclosure: alguém vê o par de outra pessoa | A consulta só alcança o assento de quem chama (sem id de pessoa na rota); `404` igual para rodada inexistente e para quem não estava nela | `RoundIT.someoneOutsideTheRoundCannotSeeAnyPairOfIt`, `aPairedPersonSeesOnlyTheirPartner` |
| Information disclosure: o ADMIN ou a resposta expõe quem formou par com quem | Resposta do ADMIN só com contagens; conjuntos de chaves exatos | `RoundIT.theAdminReadsTheCountsOfARoundButNotWhoIsInIt`, `startingTheFirstRoundPairsEveryRegistrant` (JSON estrito), `whoSatOutSeesNoPartner` |
| Information disclosure: o `matching` descobre quem bloqueou quem | `BlockedPair` sem direção | `BlockedPairTest.doesNotTellWhoBlockedWhom` |
| Denial of service: número ou id inválido vira `500` | `RoundNumber` de 1 a 100 e conversão de tipo na fronteira → `400` | `RoundIT.anInvalidRoundNumberIsABadRequestAndWritesNothing`, `anEventIdThatIsNotAUuidIsABadRequest`; `RoundNumberTest` |
| Denial of service: um pedido preso segura conexões | `lock_timeout` de 5 s → `503` com `Retry-After` | `RoundIT.aRoundStartIsRefusedWhenAnotherRequestHoldsItTooLong` |
| Denial of service: um ADMIN (ou token vazado) repete o `PUT` e prende conexões com leituras e sorteios | 30 chamadas por hora por conta ADMIN, entre réplicas; `429` + `Retry-After`; repetição idempotente também gasta | `RoundRateLimitIT.callsAboveTheLimitAreRejectedWithRetryAfterAndStartNoRound`, `idempotentRepeatsSpendTheLimit`, `callsForUnknownEventsSpendTheLimit`, `theLimitIsCountedForEachAdminAccountSeparately`, `readingARoundDoesNotSpendTheLimit`, `callsRefusedByTheRoleDoNotSpendTheLimit` |
| Denial of service: limite que cai com o banco deixa passar | Falha fechada: `503` com `Retry-After: 1`, sem sorteio | `RoundRateLimitIT.roundIsRefusedWithoutWritingWhenTheLimitCannotBeCounted`; `AccountRateLimitIT.rejectsWhenTheStoreIsDown` |
| Denial of service: sorteio caro demais | Emparelhamento O(n³) com n ≤ 200; pior caso medido em 1,2 s | Medição registrada acima (sem teste automático de tempo, que seria instável) |

Repudiation (quem iniciou a rodada) não é tratada: não há trilha de auditoria, como nos eventos.

Regras sem Spring: `RoundPairingTest`, `RoundPairingRandomCasesTest`, `PairingHistoryTest`, `RoundNumberTest`,
`PairTest`, `CandidateTest`. Fronteira entre módulos: `ArchitectureTest.coreDomainIsFrameworkFree`,
`coreApplicationTalksToInfrastructureThroughPorts`, `modulesUseOnlyPublishedApisOfOtherModules` e
`modulesAreFreeOfCycles`.

Rodada atual: `RoundIT.theEventTellsItsLatestStartedRound` (corpo inteiro, estrito, para inscrita e não
inscrita: só o número a mais), `anUnderwayEventWithoutRoundsHasNoCurrentRound`,
`theCurrentRoundBelongsToItsOwnEvent`; `EventCatalogIT` (o evento e a lista com `currentRound` null);
`OpenApiContractIT.theEventDocumentsItsCurrentRound`.
