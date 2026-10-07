# 0017. Pareamento: rodadas, sorteio dos pares e quem fica de fora

- **Status:** Proposta. O núcleo do sorteio está implementado; o resto é desenho para revisão com o usuário.
  As decisões tomadas sem o usuário estão marcadas como **(autônoma)**.
- **Data:** 2026-10-07
- **Relacionadas:** [ADR 0003](0003-estilo-de-testes.md), [ADR 0004](0004-identificadores-e-unicidade.md),
  [ADR 0005](0005-contrato-da-api.md), [ADR 0007](0007-estilo-por-modulo.md),
  [ADR 0009](0009-outbox-e-eventos.md), [ADR 0011](0011-conta-e-perfil.md),
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

  **Decisão (autônoma):** a nova API publicada, a ser criada no `trustsafety` junto com a integração (ainda
  não existe; ver "Falta implementar").
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
mesma rodada na repetição, `409` se a rodada anterior não existe ou o evento não está em andamento, `404`
para evento inexistente ou rascunho. Sem `Idempotency-Key` nem `If-Match`, pelos mesmos motivos da
[ADR 0016](0016-eventos-e-inscricoes.md) (a chave de negócio identifica a intenção; não há corpo a perder).

**Concorrência, sem checar e depois inserir:**

1. `insert into round (event_id, number, ...) ... on conflict do nothing returning ...`. A PK
   `(event_id, number)` faz a segunda transação concorrente **esperar** a primeira; quando ela commita, o
   insert não faz nada e a segunda relê e devolve a rodada vencedora (`200`). Só quem inseriu sorteia.
2. A sequência é do banco: `round.previous_number` com FK para `(event_id, number)` da própria tabela e
   `CHECK (number = 1 and previous_number is null or previous_number = number - 1)`. A rodada N+1 nunca
   existe sem a N commitada: se a N ainda não commitou, a FK recusa (`409`), sem consulta prévia. O
   comportamento exato da FK diante de uma N em voo (recusar ou esperar) será conferido no teste de
   integração; os dois resultados preservam a sequência.
3. Na mesma transação, o vencedor lê inscritos, bloqueios e rodadas anteriores, sorteia e grava os assentos.
   Como a rodada N+1 só existe depois de a N commitar, os pares anteriores lidos estão completos.
4. Teto de espera: `lock_timeout` curto, como na [ADR 0016](0016-eventos-e-inscricoes.md), com `503` e
   `Retry-After` (repetir é seguro porque o `PUT` é idempotente).

Não há lock na linha do evento: ela é do `events`, e o `matching` não trava tabela alheia. A PK da rodada é
o "registro coordenador" do plano (§4).

### Como o `matching` obtém os inscritos

O `events` não publica nada sobre inscrições hoje. Ler a tabela `registration` quebraria a regra de dono.
**Decisão (autônoma):** criar a API publicada mínima no `events`, na raiz do pacote (como
`profiles.ProfileCompleteness`): os inscritos de um evento e se ele está em andamento num instante. É
indispensável, mas **não foi criada neste ramo**: o `events` está em revisão em paralelo, e o pedido foi não
mexer nos arquivos dele. Entra no passo de integração.

### Modelo de dados (planejado, migration ainda não escrita)

- `round (event_id, number, previous_number, seed, started_at)`, PK `(event_id, number)`, FK para `event`
  (`on delete restrict`, como a inscrição) e a auto-FK da sequência acima. A semente fica guardada para o
  sorteio poder ser reproduzido numa investigação.
- `round_seat (event_id, round_number, account_id, partner_account_id)`, um assento por pessoa e rodada,
  **simétrico** (A→B e B→A); `partner_account_id` nulo é quem ficou de fora.
  - PK `(event_id, round_number, account_id)`: ninguém em dois pares na mesma rodada.
  - `UNIQUE (event_id, account_id, partner_account_id)`: o par não se repete no evento. Os nulos de quem
    ficou de fora não colidem entre si (`NULLS DISTINCT`, o padrão), que é o que se quer aqui: ficar de fora
    em várias rodadas é permitido.
  - `CHECK (account_id <> partner_account_id)`; FK do parceiro para o assento dele na mesma rodada
    (`deferrable initially deferred`, porque os dois lados entram na mesma transação).
  - "Minha dupla na rodada" é `where account_id = :eu`: a consulta só alcança o próprio assento.
- Número da migration: `main` já tem `V7` e `V8` (`trustsafety`) e o `events` também criou uma `V7`, que vai
  virar `V9` no merge. A do `matching` será a **`V10`**.

### Contrato planejado

- `PUT /api/admin/events/{eventId}/rounds/{number}` (ADMIN): resposta com número, `startedAt`, quantos pares
  e quantas pessoas de fora; **sem** a lista de quem formou par com quem (o ADMIN não precisa dela para
  conduzir o evento; ver pendências).
- `GET /api/events/{eventId}/rounds/{number}/pairing` (a própria pessoa): o próprio parceiro ou "de fora";
  `404` igual para rodada inexistente, evento alheio ou pessoa que não estava no sorteio. O que mostrar do
  parceiro é pendência do usuário.

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
8. **Aviso de "sua dupla saiu":** depende da outbox ([ADR 0009](0009-outbox-e-eventos.md)) e do Web PubSub.
9. **jqwik:** o spike de 2026-10-07 com o jqwik 1.10.1 rodou no JUnit 6 do Boot 4 (propriedade falhando com
   shrink e 232 testes Jupiter e jqwik juntos no `./mvnw test`), o que contradiz a anotação de que ele era
   incompatível. Adotar é decisão de biblioteca: até lá, as propriedades rodam como casos aleatórios com
   semente fixa num teste parametrizado (`RoundPairingRandomCasesTest`).

## Falta implementar (depois da revisão)

Na ordem: (1) rebase sobre a `main` com o `events` integrado (`V7` do `events` renumerada para `V9`);
(2) API publicada de inscritos no `events` e de pares bloqueados no `trustsafety`; (3) `V10` com as tabelas
acima e teste de schema; (4) portas e adapters JDBC; (5) caso de uso de iniciar rodada e teste de
integração com corrida de dois `PUT` iguais e de `N` contra `N+1`; (6) as duas rotas com springdoc,
`DenyByDefaultIT`, STRIDE e regeneração do `docs/openapi.json`.

## Consequências

- O sorteio é testado sem Spring nem banco ([ADR 0003](0003-estilo-de-testes.md)), com exemplos por
  partição e 300 grupos aleatórios conferidos contra busca exaustiva (máximo de pares, nenhum par proibido,
  cada pessoa em exatamente um lugar e nenhuma troca que deixaria o sorteio mais justo).
- O algoritmo é menos óbvio que um embaralhamento; a explicação mora no Javadoc de `MaximumMatching` e
  `PriorityMatching`, e um teste quebra se o blossom for desligado.
- A justiça vale dentro de um evento; entre eventos, ninguém carrega "rodadas sem par".

## Compliance

- `RoundPairingTest`: partições (0, 1, 2 pessoas, ímpar, todos os pares proibidos, grafo em que o guloso
  falha, grafo que exige contrair um ciclo ímpar, estrela sem emparelhamento perfeito), determinismo e
  variação pela semente.
- `RoundPairingRandomCasesTest`: as quatro propriedades acima em 300 grupos de 0 a 9 pessoas.
- `PairTest`, `CandidateTest`: invariantes dos valores.
- `ArchitectureTest.coreDomainIsFrameworkFree`: o domínio do `matching` é Java puro.
- Ao implementar o resto: teste de schema para cada constraint, IT de corrida para a rodada única e STRIDE
  das rotas (dono só vê o próprio par; alheio → `404`).
