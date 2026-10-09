# 0024. Primeiro jogo e o módulo `experiences`

- **Status:** Proposta — aguarda decisão do usuário. Nada foi implementado. As decisões que dependem dele
  estão em "Pendente com o usuário"; o resto é recomendação.
- **Data:** 2026-10-08
- **Relacionadas:** [ADR 0003](0003-estilo-de-testes.md), [ADR 0004](0004-identificadores-e-unicidade.md),
  [ADR 0005](0005-contrato-da-api.md), [ADR 0006](0006-rate-limit-no-postgresql.md),
  [ADR 0007](0007-estilo-por-modulo.md), [ADR 0009](0009-outbox-e-eventos.md),
  [ADR 0013](0013-logs-estruturados-e-correlation-id.md), [ADR 0015](0015-bloqueio-e-denuncia.md),
  [ADR 0016](0016-eventos-e-inscricoes.md), [ADR 0017](0017-pareamento.md),
  [ADR 0019](0019-decisao-privada-e-conexoes.md), [ADR 0021](0021-chat-temporario-e-reconexao.md)
- **Pode alterar:** a linha "Ação de jogo" da [ADR 0005](0005-contrato-da-api.md) e a linha "Concorrência"
  do plano (§4, "versão otimista para ações do jogo"), conforme as decisões pendentes 9 e 10 abaixo.

## Contexto

A etapa 2 do plano (§9) é "evento, pareamento, **um jogo**, chat temporário, reconexão e decisão privada".
Já existem eventos e inscrições ([ADR 0016](0016-eventos-e-inscricoes.md)), rodadas e pares
([ADR 0017](0017-pareamento.md), API publicada `matching.Pairings.partnerOf`), a decisão privada com
conexões ([ADR 0019](0019-decisao-privada-e-conexoes.md)) e o chat temporário em implementação
([ADR 0021](0021-chat-temporario-e-reconexao.md): polling e depois SSE, cursor por sequência do banco,
`Idempotency-Key`). Falta o jogo.

O que o plano já fixa para o módulo `experiences`:

- §3: "catálogo versionado, regras, ações e estado das partidas". É **core** ([ADR 0007](0007-estilo-por-modulo.md)):
  domínio em Java puro, aplicação falando com banco por portas, adapters por fora.
- §4: tabelas `experience_versions`, `game_sessions`, `participants`, `actions`; "JSONB com propósito:
  conteúdo narrativo e estado variável dos jogos"; "versão otimista para ações do jogo".
- §5: Strategy para as regras de cada minijogo "quando houver variação real"; máquina de estados "aguardando,
  ativa, encerrada ou cancelada", com estados e transições explícitos.
- §8, casos prioritários: "**ação repetida não avança duas etapas**", "**usuário não lê pistas ou decisões
  alheias**", "**reconexão preserva o progresso**". Carga: 50 salas e 100 participantes, medindo latência,
  erros, conexões e entrega de eventos.
- [ADR 0005](0005-contrato-da-api.md): "ação de jogo é Create num sub-recurso (`POST /game-sessions/{id}/actions`)
  com `Idempotency-Key`". Escrita antes de existirem rodadas; esta ADR reabre a forma (decisão 4).
- [ADR 0009](0009-outbox-e-eventos.md) e [0021](0021-chat-temporario-e-reconexao.md): o canal de tempo real
  leva só avisos por chave (`type`, `sessionId`, `version`); o estado vem de um `GET` que reautoriza.

Forças do contexto: dev solo, piloto fechado, US$ 100 de crédito ([ADR 0014](0014-infraestrutura-do-piloto-na-azure.md)),
produto 18+ com dado pessoal sensível. As três características que mais pesam neste módulo são
**privacidade** (nada da outra pessoa vaza antes da hora), **integridade** (repetição e concorrência não
corrompem a partida) e **recuperabilidade** (fechar a aba ou trocar de rede não perde nada).

## 1. Perguntas de produto: qual é o primeiro jogo

A dupla acabou de ser sorteada numa rodada, se conhece há minutos e pode estar no mesmo lugar (evento
presencial) ou não (evento online). O jogo serve para quebrar o gelo e dar assunto, não para competir.

### Candidato A: "Sintonia" (perguntas com revelação simultânea)

- **Regras:** um baralho fixo de 5 perguntas de múltipla escolha, com 2 a 4 opções curadas cada ("Programa
  ideal de domingo: praia, trilha, sofá, museu"). Os dois respondem a mesma pergunta em privado; quando os
  dois responderam, as duas respostas aparecem juntas e a próxima pergunta abre. No fim, "vocês combinaram
  em 3 de 5" e a lista das respostas lado a lado, que vira assunto.
- **Duração:** 2 a 4 minutos (sem cronômetro por pergunta na primeira versão; ver P5).
- **Estados:** `Em andamento(etapa k)` → … → `Concluída`; `Encerrada` se a rodada passar, o evento acabar ou
  houver bloqueio antes do fim.
- **Informação privada:** a própria resposta da etapa aberta. A da outra pessoa só aparece quando as duas
  existem. O que cada um vê da outra na etapa aberta: no máximo "já respondeu" (P4).
- **Ações:** uma jogada por pessoa e etapa: escolher uma opção. Final, sem troca.
- **Fim:** as 5 etapas reveladas.
- **Risco de abuso:** baixo. Não há texto livre, então não há assédio, contato ("meu insta é…") nem conteúdo
  a moderar dentro do jogo. O risco fica no **baralho**: perguntas curadas não podem tocar categorias
  sensíveis da LGPD (art. 11: religião, política, saúde, vida sexual), nem ser constrangedoras para quem se
  conheceu há minutos. As respostas ainda são dado pessoal (preferências) e seguem a retenção do chat (P7).

### Candidato B: "Duas verdades e uma mentira"

- **Regras:** cada um escreve três frases curtas sobre si e marca qual é a mentira. Depois, cada um tenta
  adivinhar a mentira do outro. Revelação simultânea dos palpites e das mentiras.
- **Duração:** 4 a 8 minutos (escrever é o gargalo, sobretudo no celular).
- **Estados:** `Escrevendo` (os dois) → `Adivinhando` (os dois) → `Concluída`; `Encerrada` como em A.
- **Informação privada:** qual frase é a mentira, até a revelação; as frases do outro, até os dois terminarem
  de escrever (senão quem escreve depois se inspira nas do primeiro).
- **Ações:** enviar as três frases com a mentira marcada (uma vez); enviar o palpite (uma vez).
- **Fim:** os dois palpites revelados.
- **Risco de abuso:** **alto**. É texto livre: assédio, conteúdo sexual explícito, contato fora do app, dado
  sensível ("sou soropositivo"). Precisa de tudo o que o chat precisa (validação de texto, denúncia com
  evidência, expurgo, `toString` redigido, canário no log) **e** de mais um ponto de denúncia dentro do jogo.

### Candidato C: "Pistas trocadas" (cooperativo com informação assimétrica)

- **Regras:** uma grade de 9 cartas ilustradas; uma delas é o alvo. Cada pessoa vê 2 pistas que a outra não vê
  ("não é um animal", "tem algo azul"). Conversando (no chat ou ao vivo), os dois precisam escolher a mesma
  carta. Até 3 tentativas; cada tentativa errada revela mais uma pista para cada um.
- **Duração:** 3 a 6 minutos.
- **Estados:** `Em andamento(tentativa k)` → `Acertaram` ou `Esgotaram as tentativas`; `Encerrada` como em A.
- **Informação privada:** as próprias pistas (nunca as do outro, nem depois do fim, salvo decisão de produto);
  o alvo, para os dois, até o fim; a escolha de cada um na tentativa aberta.
- **Ações:** escolher uma carta por tentativa (revelação simultânea das escolhas, como em A).
- **Fim:** escolhas iguais e certas, ou 3 tentativas.
- **Risco de abuso:** baixo dentro do jogo (sem texto livre; a conversa vai pelo chat, que já tem as
  proteções da [ADR 0021](0021-chat-temporario-e-reconexao.md)). O risco é de **vazamento**: é o caso
  prioritário "usuário não lê pistas alheias" na forma mais literal, e a autoria do conteúdo é cara (cada
  puzzle precisa de alvo, distratores e pistas que, juntas, determinam uma carta só).

### Comparação

| Critério | A. Sintonia | B. Duas verdades | C. Pistas trocadas |
|---|---|---|---|
| Exercita "ação repetida não avança duas etapas" | Sim (5 etapas em sequência) | Sim (2 etapas) | Sim (até 3 tentativas) |
| Exercita "não lê pistas ou decisões alheias" | Sim: resposta escondida até a revelação | Sim: mentira e frases escondidas | Sim, o mais forte: pistas nunca reveladas ao outro |
| Exercita "reconexão preserva o progresso" | Sim | Sim (rascunho de texto é do front) | Sim |
| Moderação de conteúdo do usuário | Nenhuma | Igual ao chat, mais denúncia no jogo | Nenhuma no jogo |
| Custo de conteúdo | Baixo: algumas dezenas de perguntas | Zero (o usuário escreve) | Alto: puzzles com pistas consistentes e ilustrações |
| Custo de implementação | O menor | Médio (texto, denúncia, expurgo) | Maior (visão por jogador com pistas, tentativas, imagens) |
| Valor de quebra-gelo | Bom: as respostas viram assunto | O melhor: fala de si | Bom: cooperação e conversa |
| Funciona sem conversar | Sim | Sim | Não: depende do chat ou de estar junto |

## 2. Modelo de domínio do `experiences`

### 2.1 Catálogo versionado: definição como dado ou como código

| Opção | Prós | Contras |
|---|---|---|
| Tudo em código (uma classe por jogo, perguntas em constantes) | Tipado, testado, sem parser | Trocar uma pergunta exige deploy; "versão" vira o commit, e uma partida em andamento muda de conteúdo no meio de um deploy |
| Tudo como dado (regras numa DSL ou JSON interpretado) | Jogo novo sem deploy | Um interpretador de regras é um produto à parte (Interpreter sem necessidade medida); regras fora do compilador e dos testes de domínio |
| **Regras em código, conteúdo como dado versionado e imutável** | Regras testadas sem Spring ([ADR 0003](0003-estilo-de-testes.md)); conteúdo (perguntas, opções, puzzles) muda sem tocar as regras; a partida fixa a versão e nunca muda de conteúdo no meio | Dois artefatos para manter alinhados (o tipo de regra que a versão declara precisa existir no código); o conteúdo precisa ser validado ao ler do banco |

**Recomendação:** regras em código, conteúdo como dado.

- `experience_version` guarda `(experience_key, version)` único, o tipo de regra (`rules`, por exemplo
  `reveal-v1`) e o conteúdo em JSONB. **Imutável depois de publicada**: versão nova é linha nova; um trigger
  recusa `update` e `delete`. A partida aponta para a versão exata.
- Na primeira versão o conteúdo entra por migration Flyway (sem CRUD de ADMIN). Um CRUD de catálogo só
  quando houver curadoria frequente.
- O conteúdo é validado na subida: o adapter lê todas as versões e as converte em tipos de domínio
  (`Deck`, `Question`, `ChoiceId`); conteúdo inválido (pergunta sem opções, id de opção repetido, texto
  acima do limite, `rules` desconhecido) **derruba a subida**. É dado vindo do banco tratado como entrada
  externa (regra de segurança "valide o que vem do banco").
- **Strategy só com o segundo jogo** (plano §5: "introduzir quando houver variação real"): até lá, as regras da Sintonia são o domínio. Quando o segundo jogo entrar, a interface
  `GameRules` nasce do que os dois têm em comum, escolhida pelo `rules` da versão, nunca pelo cliente, num
  registro que falha na subida se houver `rules` sem implementação.

### 2.2 A partida ligada ao par da rodada

- Uma partida por **(evento, rodada, par normalizado)**, como o chat: o par vem de
  `matching.Pairings.partnerOf`, e o `experiences` não lê `round_seat`. Sem FK para tabelas do `matching`
  (motivo da [ADR 0019](0019-decisao-privada-e-conexoes.md)).
- Criada **no primeiro acesso** de qualquer um dos dois (`insert ... on conflict do nothing` na chave
  natural, depois `select`), como o chat. Nenhum acoplamento com o sorteio da rodada.
- A versão da experiência é fixada na criação. Qual versão: a de maior número da experiência configurada
  (`duora.experiences.default=sintonia`), até o produto decidir quem escolhe o jogo (P2).
- **Aberta** enquanto: o evento está em andamento (`events.EventRoster`), a rodada é a última iniciada
  (`events.RoundProgress`, implementada pelo `matching`) e não há bloqueio entre os dois
  (`trustsafety.Blocking.existsBetween`). Estado **calculado**, nunca guardado, como o chat: nenhum job
  encerra partidas. A resposta é a mesma para os três motivos, para não revelar o bloqueio
  ([ADR 0015](0015-bloqueio-e-denuncia.md)).
- O cálculo "a dupla da rodada ainda está ativa" passa a existir no chat e no jogo. É o **mesmo
  conhecimento** (DRY de regra, não coincidência de código): proposto que o `matching` publique essa
  consulta (por exemplo `Pairings.activePartnerOf(eventId, round, account, now)`) ao entrar o jogo, e que o
  chat passe a usá-la. Se o chat já tiver extraído algo equivalente na branch dele, o jogo reusa.

### 2.3 Máquina de estados como tipos

Os estados têm dados diferentes, então `sealed interface` com `record`s e `switch` exaustivo, sem
`default` (regra DDD 17 e Java 3). Para a Sintonia:

```java
sealed interface GameState {
    record InProgress(StepNumber step, Optional<ChoiceId> myChoice, boolean partnerHasMoved,
                      List<RevealedStep> revealed) implements GameState {}
    record Completed(List<RevealedStep> revealed) implements GameState {}
    record Closed() implements GameState {}   // rodada passou, evento acabou ou bloqueio: um só motivo visível
}
```

- O agregado `GameSession` guarda os fatos (as jogadas) e devolve o estado **para um observador**:
  `session.stateFor(viewer, openness)`. Não existe "estado global" que depois se filtra: a projeção por
  observador é a única forma de ler, e é ela que garante a privacidade (2.5).
- A jogada é um comando da raiz: `session.move(player, step, choice, openness)` devolve um resultado selado
  `Moved(seq)`, `AlreadyMoved` (mesma escolha: idempotente) ou `Refused(reason)` com
  `STEP_NOT_OPEN`, `MOVE_IS_FINAL`, `GAME_CLOSED`. Recusa é valor, não exceção para fluxo normal.
- "Aguardando" do plano (§5) não existe na Sintonia: a partida começa no primeiro acesso. Entra se o produto
  pedir que os dois confirmem presença antes da primeira pergunta (P3). "Cancelada" é o `Closed`.
- O domínio recebe `openness` (aberta ou encerrada) já calculado pela aplicação a partir das APIs
  publicadas, e `now` como valor: nada de relógio nem de outro módulo dentro do domínio.

### 2.4 Jogadas, sequência e idempotência: "ação repetida não avança duas etapas"

O risco concreto: o clique duplo, o reenvio após timeout e a aba recarregada mandam a mesma jogada duas
vezes; se a jogada só diz "minha resposta é B", a segunda cai na etapa seguinte e responde a pergunta que a
pessoa nem leu. A defesa é a jogada **dizer a qual etapa responde**, e o banco aceitar uma jogada por pessoa e
etapa.

| Opção | Prós | Contras |
|---|---|---|
| `POST .../actions` com `Idempotency-Key` (a [ADR 0005](0005-contrato-da-api.md)) | Genérico para qualquer jogo; padrão já usado no chat | A chave só resolve o **mesmo** pedido reenviado: dois cliques que geram duas chaves ainda avançam duas etapas, a menos que a jogada também traga a etapa. Exige tabela de chaves, fingerprint e limpeza |
| Versão otimista da partida (`If-Match` com a versão lida, `412` se mudou) | Padrão de concorrência do plano (§4) | A versão muda quando **a outra pessoa** joga. Na revelação simultânea os dois jogam quase juntos, então o segundo leva `412` no caso normal e precisa reler e reenviar: conflito por desenho, não por exceção |
| **Jogada endereçada pela chave de negócio: `PUT .../game/steps/{step}/move`** | A etapa está na URL: a jogada da etapa 3 nunca vale para a 4. A PK `(partida, etapa, pessoa)` é a invariante no banco. Repetir a mesma escolha → `200` com a mesma jogada; outra escolha → `409`. Mesmo padrão de inscrição, rodada e decisão (ADRs [0016](0016-eventos-e-inscricoes.md), [0017](0017-pareamento.md), [0019](0019-decisao-privada-e-conexoes.md)), sem tabela de chaves | Diverge da linha da ADR 0005. Cada jogo precisa de uma chave natural para a jogada (etapa, ou etapa e tentativa); um jogo futuro com jogada sem chave natural volta a precisar de `Idempotency-Key` |

**Recomendação:** `PUT` pela chave de negócio. As três candidatas têm chave natural (A: etapa; B: "frases"
e "palpite"; C: tentativa). A `Idempotency-Key` fica para quando surgir jogada sem chave natural.

**Número de sequência:** toda jogada aceita recebe `seq = last_seq + 1` da partida, sob o lock da linha
(2.6), como o `last_seq` do chat. O `seq` não autoriza nada: é a **versão** da partida, usada pelo aviso de
tempo real e pela reconexão (2.7). Ordem pelo banco, nunca por timestamp.

### 2.5 Revelação simultânea sem vazar a resposta de quem respondeu primeiro

Mesmo cuidado da [ADR 0019](0019-decisao-privada-e-conexoes.md): nenhuma resposta da API, nenhum aviso e
nenhum log mostra a escolha da outra pessoa numa etapa aberta.

- **Uma só projeção:** `stateFor(viewer)` monta a visão a partir das jogadas; a escolha da outra pessoa
  só entra em `revealed` quando as duas jogadas da etapa existem. O `GET` e a resposta do `PUT` usam a mesma
  projeção, então o `PUT` de quem responde primeiro devolve só a própria escolha, e o de quem responde por
  último já traz a revelação (sem outra ida ao servidor).
- **Sem horário da outra pessoa:** a visão não traz `movedAt` de ninguém além de quem chama, para não dizer
  quem foi rápido ou quem hesitou. A ordem de quem respondeu primeiro não aparece.
- **`partnerHasMoved`:** diz que a outra pessoa já respondeu a etapa aberta, nunca o quê. Ajuda o "sua vez" e,
  ao vivo, a pessoa veria isso de qualquer jeito. É decisão de produto (P4); a alternativa é omitir o campo.
- **Aviso por chave:** `{id, type: "game.session.changed", sessionId, version}`. Sem etapa, escolha nem
  autor ([ADR 0009](0009-outbox-e-eventos.md)).
- **Logs:** a escolha e o texto das perguntas não vão para log; `toString` redigido nos VOs de jogada e
  canário no `SensitiveDataLoggingIT` ([ADR 0013](0013-logs-estruturados-e-correlation-id.md)).
- **Resíduo aceito:** se as duas pessoas jogam no mesmo instante, a segunda espera o lock por milissegundos,
  o que diz no máximo que a outra estava jogando (como na 0019).

### 2.6 Concorrência: os dois jogando ao mesmo tempo

A transação da jogada, curta e sem chamada externa:

1. Rate limit fora da transação (bucket `game:<conta>`, [ADR 0006](0006-rate-limit-no-postgresql.md)).
2. `Pairings.partnerOf` → sem par, `404`.
3. Cria a partida se não existe (`on conflict do nothing`) e trava a linha: `select ... for update`, com
   `lock_timeout` de 2 s → `503` com `Retry-After: 1` (o `PUT` é idempotente, repetir é seguro).
4. Calcula `openness` (evento, rodada, bloqueio) **sob o lock**, lê as jogadas e chama `session.move`.
5. Grava a jogada com o `seq` novo e atualiza `last_seq`. Na fatia do SSE, `NOTIFY` na mesma transação.

Por que lock pessimista e não versão otimista (diverge do plano, §4): o conflito entre as duas pessoas é o
caso normal da revelação simultânea; com o lock, os dois `PUT` simultâneos **passam** em série, e o segundo
vê a jogada do primeiro e já devolve a revelação. A PK `(partida, etapa, pessoa)` segura a invariante mesmo
se o lock for esquecido. É a mesma escolha do `last_seq` do chat.

Resíduo: um bloqueio confirmado durante a transação da jogada (READ COMMITTED) não é visto, como nas ADRs
0017, 0019 e 0021. Janela de milissegundos; proposto aceitar, com a mesma decisão pendente da 0021.

### 2.7 Reconexão: estado autorizado por `GET` e cursor

O estado de uma partida é pequeno (no máximo 2 × 20 jogadas), então a reconexão relê a **visão inteira**, em
vez de paginar jogadas por cursor como o chat:

- `GET /api/events/{eventId}/rounds/{number}/game` devolve a visão de quem chama, com `version` (o
  `last_seq`) e `ETag`. Com `If-None-Match` igual → `304` sem corpo: o polling a cada 2 s fica barato em
  rede. O `ETag` inclui o estado calculado (aberta ou encerrada), porque a partida pode encerrar sem jogada
  nova.
- Protocolo do cliente, o mesmo da [ADR 0021](0021-chat-temporario-e-reconexao.md): ao abrir a tela, ao
  reconectar o transporte, ao voltar a aba para frente e ao receber `409`, faz o `GET`. Aviso com
  `version <=` a maior vista é ignorado; maior, faz o `GET`. Aviso perdido ou repetido cai no mesmo `GET`.
- Jogada pendente: o front guarda a etapa e a escolha até receber `201`/`200`, e reenvia o mesmo `PUT` na
  reconexão. Se a resposta for `409 MOVE_IS_FINAL`, o `GET` mostra a escolha que valeu.
- O progresso está no banco: reinício da réplica, troca de rede ou de aparelho (mesma conta) não perdem nada.

## 3. Persistência, tempo e transporte

### 3.1 Tabelas (proposta)

| Opção para guardar o estado | Prós | Contras |
|---|---|---|
| Snapshot em JSONB + `status` + `version` na partida (o caminho mais comum para máquina de estados persistida) | Uma linha por partida; genérico para qualquer jogo | As invariantes ("uma jogada por pessoa e etapa") ficam dentro do JSON, longe das constraints; cada jogada reescreve o documento inteiro; ler a escolha da outra pessoa exige o documento todo |
| **Jogadas como linhas, estado derivado delas** | A invariante vira PK; nada de estado guardado que possa divergir dos fatos; o estado se recalcula de no máximo 40 linhas | Uma consulta a mais por leitura; o "estado" não está numa coluna para relatório |
| Event sourcing | Histórico completo | Sem requisito de auditoria que o justifique (regra DDD 16) |

**Recomendação:** jogadas como linhas, com o conteúdo da jogada em JSONB validado pelas regras (o plano,
§4, prevê JSONB para "estado variável dos jogos"). Não é event sourcing: não há eventos nem replay, só os
fatos da partida e uma função pura que os lê.

- `experience_version (id uuid default uuidv7(), experience_key text, version int, rules text, content jsonb,
  published_at timestamptz)`; `UNIQUE (experience_key, version)`; `CHECK` de formato da chave
  (`^[a-z][a-z0-9-]{1,40}$`), `version > 0` e `rules` numa lista fixa; trigger que recusa `update` e `delete`.
- `game_session (id uuid default uuidv7(), event_id, round_number, first_account_id, second_account_id,
  experience_version_id, last_seq int not null default 0, created_at, purge_after timestamptz)`.
  `UNIQUE (event_id, round_number, first_account_id, second_account_id)`; `CHECK (first_account_id <
  second_account_id)`; `CHECK` de 1 a 100 no número da rodada; FKs para `account` (`on delete restrict`,
  com índice) e para `experience_version` (`restrict`).
- `game_move (session_id, step smallint, account_id, seq int, payload jsonb, moved_at timestamptz)`.
  PK `(session_id, step, account_id)`: **uma jogada por pessoa e etapa**, a invariante do caso prioritário.
  `UNIQUE (session_id, seq)`; `CHECK (step between 1 and 20)` e `seq > 0`; FK para `game_session`
  `on delete cascade` (mesmo agregado); FK de `account_id` para `account` com índice. Que a pessoa é um dos
  dois do par é garantido pela aplicação (sob o lock); uma FK composta para `(session_id, first|second)` não
  cabe numa coluna só.
- Volume: 50 partidas × 10 jogadas = 500 linhas por evento; nada a particionar.
- Adapter com `JdbcClient` e SQL direto, como `connections` e `trustsafety`.
- Expurgo junto com o chat (proposto: 24 h depois do fim agendado do evento, P7), com o mesmo job em lotes
  com `FOR UPDATE SKIP LOCKED`, jitter e métrica de partidas vencidas.

### 3.2 Tempo

- `java.time.Clock` injetado (o projeto já tem `ClockConfiguration`), `Clock` fixo nos testes. O domínio
  recebe `Instant now` como parâmetro.
- **Prazo da partida:** sem cronômetro próprio na primeira versão. A partida encerra quando a rodada deixa de
  ser a última ou o evento acaba, calculado na leitura (2.2). Sem job, sem lock entre réplicas.
- **Cronômetro por etapa, se o produto pedir (P5):** a etapa k abre no `moved_at` da segunda jogada da etapa
  k−1 e expira `N` segundos depois. A expiração entra na função pura `stateFor(viewer, now)` e na validação
  da jogada (`409 STEP_EXPIRED`), sem nada gravado e sem job. Os instantes vêm de réplicas diferentes: uma
  tolerância de 2 s absorve a diferença entre relógios. Etapa expirada sem jogada revela "sem resposta" e
  abre a próxima.

### 3.3 Transporte: como chega o "sua vez"

Na Sintonia não há turno: "sua vez" é "a etapa aberta ainda não tem a sua jogada", e "aguardando" é "você
jogou e a outra pessoa não". A visão traz isso pronto (`myChoice` vazio ou preenchido, `partnerHasMoved`).

- **Agora (polling, [ADR 0021](0021-chat-temporario-e-reconexao.md)):** o front faz `GET .../game` a cada 2 s
  com a aba visível e na tela do jogo, com `If-None-Match`. Latência até 2 s para ver a revelação.
- **Depois (SSE):** a transação da jogada faz `NOTIFY` com o aviso por chave; o stream `GET /api/me/stream`
  (o mesmo do chat) entrega aos dois da partida, e o cliente faz o `GET`. O polling vira fallback de
  intervalo longo. O aviso sem outbox segue a pendência 1 da ADR 0021.
- **Carga a medir:** 100 participantes fazendo polling do jogo = 50 `GET`/s, cada um com ~5 consultas
  (par, partida, jogadas, rodada atual, evento, bloqueio) ≈ 250 consultas/s no B1ms, somadas às do chat se
  as duas telas fizerem polling ao mesmo tempo. Mitigação: só a tela visível faz polling. O k6 decide.

## 4. Contrato proposto

- `GET /api/events/{eventId}/rounds/{number}/game` → `{sessionId, experience: {key, version}, status,
  step, question: {text, choices: [{id, text}]}, myChoice, partnerHasMoved, revealed: [{step, myChoice,
  partnerChoice}], version}`. `404` igual para quem não está no par, rodada inexistente e evento inexistente.
  `sessionId` é opaco e só casa avisos com a tela.
- `PUT .../game/steps/{step}/move` com `{choice}` → `201` + `Location` na primeira vez; `200` com a mesma
  escolha repetida; `409` com `reason` ([ADR 0020](0020-motivo-das-recusas-no-problem-detail.md)):
  `MOVE_IS_FINAL`, `STEP_NOT_OPEN`, `GAME_CLOSED`. Corpo: a visão de quem chama (a mesma do `GET`).
  Corpo estrito: chave desconhecida, `seq`, conta, etapa no corpo → `400`. Etapa fora de 1 a 20 ou escolha
  que não existe na pergunta → `400` com `errors` ([ADR 0018](0018-erros-de-campo-no-problem-detail.md)).
- `GET .../game/steps/{step}/move` → a própria jogada (alvo do `Location`); `404` sem ela. Nunca a da outra
  pessoa.
- Sem id de pessoa nas rotas, como o par, a decisão e o chat.

## 5. STRIDE

Dado sensível: preferências pessoais respondidas a um desconhecido e quem jogou com quem. Testes a escrever
(nenhum existe); cada um precisa ser visto falhando com a mitigação desligada.

| Ameaça | Mitigação | Teste a escrever |
|---|---|---|
| Spoofing: CSRF faz uma jogada pela sessão web | Token CSRF em todo `PUT` | `GameIT.aWebSessionWithoutCsrfTokenCannotMove`, `aWebSessionWithCsrfTokenMoves` |
| Spoofing/Elevation: quem não é do par lê ou joga (BOLA) | Rota sem id de pessoa; par por `Pairings.partnerOf`; `404` igual | `GameIT.someoneOutsideThePairCannotReadNorMove` (corpo sem pergunta nem escolha) |
| Information disclosure: a escolha da outra pessoa aparece antes da revelação | Projeção única por observador | `GameViewTest.aViewerNeverSeesThePartnersChoiceOfAnOpenStep`; `GamePrivacyIT.thePartnersChoiceStaysHiddenUntilBothMove`, `theFirstMoverResponseNeverCarriesThePartnersChoice` |
| Information disclosure: quem respondeu primeiro ou demorou | Visão sem `movedAt` alheio; conjunto de chaves exato | `GamePrivacyIT.theViewHasExactlyTheExpectedKeys`, `theOtherPlayersMoveCannotBeReadByItsUrl` |
| Information disclosure: aviso de tempo real com conteúdo | Aviso só por chave | `GameHintTest.aHintCarriesOnlyIdTypeSessionIdAndVersion` |
| Information disclosure: aviso entregue a quem não é da partida (SSE) | Stream filtrado pelas partidas de quem chama | `GameStreamIT.aStreamReceivesOnlyHintsOfTheCallersGames` |
| Information disclosure: escolha no log | `toString` redigido; canário | `SensitiveDataLoggingIT.gameMovesNeverReachTheLog`, `ChoiceTest.doesNotExposeTheChoiceInToString` |
| Information disclosure: o bloqueado descobre o bloqueio | Partida encerrada responde igual pelos três motivos | `GameIT.aBlockedGameLooksLikeARoundThatEnded` |
| Information disclosure: respostas guardadas além do prazo | Expurgo com `delete` real | `GamePurgeIT.movesArePurgedAfterTheRetention`, `twoReplicasPurgeWithoutConflict` |
| Tampering: jogada repetida avança duas etapas | Etapa na URL; PK `(partida, etapa, pessoa)`; etapa não aberta → `409` | `GameIT.aRepeatedMoveDoesNotAdvanceTwoSteps`, `aStepThatIsNotOpenCannotBeAnswered`; `GameSessionTest.aMoveForAPastStepNeverOpensTheNextOne`; `ExperiencesSchemaIT.aPlayerMovesOncePerStep` |
| Tampering: trocar a escolha já feita | Jogada final no domínio e PK no banco | `GameIT.aMoveIsFinal`; `GameSessionTest.aDifferentChoiceForTheSameStepIsRefused` |
| Tampering: jogadas simultâneas perdem a revelação ou gravam duas vezes | Lock da linha da partida; `seq` sob o lock; PK | `GameIT.simultaneousMovesOfBothPlayersAreBothRecordedAndRevealed`, `simultaneousRepeatsOfTheSameMoveRecordOne`, `concurrentMovesGetConsecutiveVersions` |
| Tampering: corpo com conta, `seq`, etapa ou estado | DTO estrito | `GameFieldErrorsIT.unknownFieldsAreRejectedWithoutWriting` |
| Tampering: conteúdo do catálogo muda com partidas em andamento | Versão imutável (trigger); partida fixa a versão | `ExperiencesSchemaIT.aPublishedVersionCannotChangeNorBeDeleted`; `GameIT.aSessionKeepsItsExperienceVersion` |
| Tampering: conteúdo inválido no banco quebra a partida | Validação do catálogo na subida (falha fechada) | `DeckTest` (partições: sem perguntas, opção repetida, texto longo, `rules` desconhecido); `ExperienceCatalogIT.theApplicationRefusesToStartWithAnInvalidDeck` |
| Repudiation: quem escolheu o quê | Autor, `seq` e hora gravados pelo servidor | Coberto pelos testes de gravação; sem trilha extra, como nas ADRs 0017 e 0019 |
| Denial of service: etapa, escolha ou id inválidos viram `500` | Validação na fronteira → `400` | `GameIT.anInvalidStepOrChoiceIsABadRequestAndWritesNothing`, `anEventIdThatIsNotAUuidIsABadRequest` |
| Denial of service: rajada de jogadas | Bucket `game:<conta>` entre réplicas; `429` + `Retry-After`; repetição idempotente gasta; falha fechada | `GameRateLimitIT.movesAboveTheLimitAreRejectedWithRetryAfterAndWithoutWriting`, `idempotentRepeatsSpendTheLimit`, `moveIsRefusedWhenTheLimitCannotBeCounted` |
| Denial of service: jogada presa segura conexões | `lock_timeout` 2 s → `503` + `Retry-After` | `GameIT.aMoveIsRefusedWhenTheLockIsHeldTooLong` |
| Denial of service: polling pesa no banco | `304` com `ETag`; só a tela visível; medir no k6 | `GameIT.anUnchangedGameAnswersNotModified`; fatia de carga |
| Elevation of privilege: jogar depois da rodada ou do evento | Estado aberto calculado sob o lock | `GameIT.aGameClosesWhenTheNextRoundStarts`, `aGameClosesWhenTheEventEnds` |
| Elevation of privilege: jogar depois do bloqueio | `Blocking.existsBetween` sob o lock | `GameIT.aBlockEitherWayClosesTheGame` |
| Elevation of privilege: rota nova pública por engano | Negar por padrão | `DenyByDefaultIT` (rotas novas na enumeração) |

Ameaças a mais se o produto escolher B ou C:

- **B:** as do chat para texto (assédio, contato fora do app, dado sensível, texto no log): validação de
  texto, denúncia de frase com evidência (`trustsafety.Reports.fileWithEvidence`), expurgo e canário. E
  "as frases do outro antes de eu terminar" (`GamePrivacyIT.statementsStayHiddenUntilBothHaveWritten`).
- **C:** pista alheia em qualquer resposta, aviso ou URL de imagem (`GamePrivacyIT.aPlayerNeverReceivesThePartnersClues`),
  alvo antes do fim, nome de arquivo da imagem que entrega o alvo (ids opacos nas imagens).

## 6. Recomendação

**Bottom line.** Recomendo **A, "Sintonia"**, como o primeiro jogo, com o modelo da seção 2: regras em
código, conteúdo como dado imutável, jogadas como linhas com PK `(partida, etapa, pessoa)`, `PUT` pela
chave de negócio e lock da linha da partida.

- **Motivo:** exercita os três casos prioritários do plano (a etapa na URL contra a jogada repetida, a
  projeção por observador contra o vazamento, a releitura completa na reconexão) **sem texto livre**, então
  sem moderação, denúncia nem conteúdo sensível escrito pelo usuário, e com o menor custo de conteúdo. O
  esqueleto (catálogo, partida, jogadas, projeção, aviso) é o mesmo que B e C vão usar; o segundo jogo da
  etapa 3 entra como Strategy sobre ele.
- **Custo principal:** é o jogo menos "jogo" dos três: perguntas e respostas podem parecer um questionário
  se o baralho for fraco, e a curadoria do baralho é trabalho de produto, não de código. C seria o segundo
  jogo natural (o mais divertido e o teste mais forte de "pistas alheias"), ao custo de autoria dos puzzles.

### Plano em fatias finas

Cada fatia é um PR, com testes primeiro. A ordem pedida é tracer bullet, privacidade, idempotência,
reconexão e web.

| # | Fatia | Pronto quando |
|---|---|---|
| 1 | **Tracer bullet (API):** módulo `experiences` classificado como core no `ArchitectureTest`; migration com `experience_version` (baralho `sintonia` v1 com 5 perguntas), `game_session` e `game_move`; catálogo validado na subida; `GET .../game` e `PUT .../steps/{step}/move` com a regra lockstep; aberta = par existe, evento em andamento, rodada é a última | `GameIT.twoPlayersCompleteAGame` (os dois chegam a `Completed` com as 5 revelações); `someoneOutsideThePairCannotReadNorMove`; `DeckTest`, `GameSessionTest` sem Spring; `DenyByDefaultIT`; spec OpenAPI, Spectral, `oasdiff` e Schemathesis verdes |
| 2 | **Privacidade:** projeção por observador com conjunto de chaves exato, sem horários alheios, jogada alheia inalcançável pela URL, bloqueio com a mesma resposta de encerrada, `toString` redigido e canário no log | Todos os testes de Information disclosure da seção 5 (menos o de SSE e o de expurgo) passam e falham com a mitigação desligada |
| 3 | **Idempotência e concorrência:** repetição `200`, troca `409`, etapa não aberta `409`, lock da partida com `lock_timeout`, jogadas simultâneas, constraints, rate limit | Todos os testes de Tampering e Denial of service da seção 5; o IT de corrida (5 repetições) falha com o lock desligado e passa com ele |
| 4 | **Reconexão e encerramento:** `version` e `ETag`/`304`; encerramento calculado por rodada seguinte, fim do evento e bloqueio; expurgo junto com o chat; consulta "dupla ativa" publicada pelo `matching` (2.2) | `GameIT.anUnchangedGameAnswersNotModified`, `aGameClosesWhenTheNextRoundStarts`, `aGameClosesWhenTheEventEnds`, `aBlockEitherWayClosesTheGame`; `GamePurgeIT`; a partida sobrevive a reinício da aplicação no IT |
| 5 | **Web (`duora-web`):** tela do jogo com polling de 2 s (aba visível), estados carregando, sua vez, aguardando, revelação, concluída, encerrada e erro; jogada pendente reenviada na reconexão; acessível por teclado e leitor de tela | Dois contextos do Playwright completam a Sintonia em homologação; terceiro usuário recebe `404`; recarregar a aba no meio preserva a etapa; Vitest/MSW cobrem o `409` e a reconexão |
| 6 | **Aviso por SSE** (quando a fatia 5 da [ADR 0021](0021-chat-temporario-e-reconexao.md) existir): `NOTIFY` na transação da jogada, aviso no mesmo stream | `GameStreamIT` (aviso só no commit, só para os dois da partida); `GameHintTest` |
| 7 | **Carga:** k6 com 50 partidas e 100 participantes junto com o chat, em homologação | p95 do `PUT`, p95 do tempo até a outra pessoa ver a revelação, consultas/s e CPU do B1ms, taxa de `503` por `lock_timeout` e de erros registrados aqui; decisão sobre o intervalo do polling |

## Pendente com o usuário (decisões críticas)

**Produto:**

1. **Qual é o primeiro jogo:** A (recomendado), B ou C.
2. **Quem escolhe o jogo de cada rodada:** um jogo fixo para o piloto (proposto), o ADMIN por evento, ou por
   rodada?
3. **Presença:** a partida começa no primeiro acesso (proposto) ou só quando os dois confirmam "estou pronto"?
4. **"A outra pessoa já respondeu":** mostrar (proposto, sem o valor) ou esconder até a revelação?
5. **Cronômetro por etapa:** sem cronômetro (proposto na primeira versão) ou N segundos por pergunta, com
   "sem resposta" ao expirar?
6. **Baralho:** quantas perguntas por partida (proposto: 5), quem escreve e revisa, quais temas ficam
   proibidos (proposto: nenhuma categoria do art. 11 da LGPD, nada sexual), e se a mesma dupla pode ver a
   mesma pergunta em outro evento.
7. **Retenção das respostas:** junto com o chat, 24 h depois do fim do evento (proposto), ou guardar o
   resultado ("combinaram em 3 de 5") por mais tempo para a decisão privada ou para métricas do produto?
8. **Fim do jogo e decisão privada:** a tela da decisão ([ADR 0019](0019-decisao-privada-e-conexoes.md))
   aparece depois do jogo? O resultado do jogo aparece junto?

**Técnicas:**

9. **Forma da jogada:** `PUT .../steps/{step}/move` pela chave de negócio (recomendado) ou o
   `POST .../actions` com `Idempotency-Key` da [ADR 0005](0005-contrato-da-api.md). Escolhido o `PUT`, a ADR
   0005 ganha uma nota apontando para esta.
10. **Concorrência:** lock pessimista da linha da partida (recomendado) no lugar da "versão otimista para
    ações do jogo" do plano (§4). Escolhido o lock, o plano ganha a correção riscada, como nas atualizações
    da ADR 0021.
11. **Consulta "dupla ativa" publicada pelo `matching`** e usada pelo chat e pelo jogo (2.2), em vez de cada
    módulo repetir a regra.

## Consequências

- Módulo novo `experiences` (core), dependente de `matching.Pairings`, `events.EventRoster`,
  `events.RoundProgress` e `trustsafety.Blocking` pelas APIs publicadas. Três tabelas novas, com FKs
  `restrict` para `account`: a limpeza dos testes (`AccountTables`) e a futura exclusão de conta apagam as
  partidas antes.
- O conteúdo dos jogos passa a ser dado versionado no banco, entregue por migration; mudar uma pergunta é
  publicar uma versão nova, nunca editar a antiga.
- Mais uma rota em polling: a cota grátis de requisições do Container Apps é dividida entre chat e jogo
  (estimativa: 50 `GET`/s a mais durante a rodada, a medir).
- Se a decisão 9 for o `PUT`, o contrato passa a ter dois jeitos de idempotência: chave de negócio (jogo,
  decisão, inscrição, rodada) e `Idempotency-Key` (chat), cada um onde se aplica.

## Compliance

Esta ADR é proposta: os testes da seção 5 são o critério de aceite das fatias. Fronteira entre módulos no
`ArchitectureTest` (`everyClassBelongsToAClassifiedModule` com `experiences` na lista do core,
`coreDomainIsFrameworkFree`, `coreApplicationTalksToInfrastructureThroughPorts`,
`modulesUseOnlyPublishedApisOfOtherModules`, `modulesAreFreeOfCycles`). Contrato pela
[ADR 0012](0012-contrato-openapi.md).
