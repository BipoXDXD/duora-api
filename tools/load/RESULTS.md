# Resultados do teste de carga

> **Não é capacidade garantida.** São medições de um notebook, com o gerador de carga e a API dividindo a mesma
> máquina, para achar disputas e regressões (plano §8). Servem para comparar execuções entre si, não para
> prometer números em produção.

## Ambiente

| Item | Valor |
|---|---|
| Data | 2026-10-08 |
| Máquina | Apple M5, 10 núcleos, 16 GB; Docker Desktop 29.7.2 (VM com 10 CPUs e 7,7 GiB), `linux/arm64` |
| API | imagem construída de `main` (3a4be1f), **1 vCPU e 2 GiB** (como a produção, ADR 0014), pool Hikari e Tomcat no padrão (10 conexões) |
| Banco | `postgres:18-alpine`, 2 vCPU e 4 GiB (um B2s hipotético), sem ajuste |
| Gerador | k6 2.3.0 em container na mesma rede, sem limite de CPU (compete com a API pelo mesmo hardware) |
| Rate limit | produção, exceto o da rodada, o da decisão e o do envio do chat elevados (ver `README.md`) |

Três subidas completas e independentes, cada uma com cada cenário duas vezes: a **primeira passada é com a JVM
fria** (o que um deploy novo enfrenta) e a **segunda com a JVM aquecida**. Todas passaram em todos os thresholds
e invariantes. Latência em ms, medida pelo k6 na operação indicada; p50/p95/p99/máx por execução.

## Com a JVM fria (primeira passada)

| Cenário | Carga | Execução | p50 | p95 | p99 | máx | 503 | Inesperadas |
|---|---|---|---|---|---|---|---|---|
| `registration` | 100 `PUT` simultâneos, 1 evento, capacidade 50 | 1 | 993 | 1491 | 1504 | 1590 | 0 | 0 |
| | | 2 | 999 | 1513 | 1603 | 1605 | 0 | 0 |
| | | 3 | 1021 | 1515 | 1528 | 1625 | 1 | 0 |
| `rounds` | 2 ondas de 100 `PUT` (50 eventos x 2 pedidos) | 1 | 280 | 456 | 459 | 461 | 0 | 0 |
| | | 2 | 370 | 480 | 562 | 570 | 0 | 0 |
| | | 3 | 408 | 590 | 595 | 600 | 0 | 0 |
| `decisions` | 200 `PUT` simultâneos (100 pares) | 1 | 585 | 1389 | 1796 | 2090 | 0 | 0 |
| | | 2 | 566 | 1557 | 1856 | 1978 | 0 | 0 |
| | | 3 | 602 | 1705 | 2082 | 2284 | 0 | 0 |

## Com a JVM aquecida (segunda passada)

| Cenário | Execução | p50 | p95 | p99 | máx | 503 |
|---|---|---|---|---|---|---|
| `registration` | 1 / 2 / 3 | 307 / 312 / 297 | 517 / 503 / 519 | 601 / 598 / 598 | 614 / 608 / 603 | 0 / 0 / 0 |
| `rounds` | 1 / 2 / 3 | 170 / 200 / 123 | 270 / 290 / 219 | 276 / 293 / 222 | 277 / 296 / 223 | 0 / 0 / 0 |
| `decisions` | 1 / 2 / 3 | 254 / 234 / 273 | 753 / 784 / 864 | 865 / 985 / 1056 | 1349 / 1185 / 1254 | 0 / 0 / 0 |

No `rounds` a métrica junta as duas ondas (rodadas 1 e 2, 20 s uma da outra) de cada passada; por isso o número "frio" mistura a onda fria com uma já um pouco mais aquecida.

## Invariantes (todas as 18 passadas: 3 subidas x 3 cenários x 2)

- **Inscrição:** sempre exatamente 50 inscritas e 50 `409`; nenhum evento acima da capacidade; a repetição do `PUT`
  devolveu a mesma inscrição; o ADMIN contou 50 e o banco tem 50 linhas.
- **Sorteio:** sempre 1 `201` e 1 `200` por (evento, número), 100 rodadas e 400 assentos, nenhum par recíproco
  quebrado, nenhum par repetido no evento, ninguém em dois lugares.
- **Decisão:** sempre 200 decisões e 25 conexões, que são os pares com dois sim recalculados das próprias decisões;
  nenhuma conexão sem os dois sim, nenhum par com dois sim sem conexão; cada conexão visível para as duas contas e
  para mais ninguém.
- Nenhuma resposta fora do contrato (`unexpected_responses` = 0 em todas); nenhum 5xx além do `503` com
  `Retry-After: 1`.

Uma execução extra e isolada de `rounds` (depois de editar comentários, fora da tabela) deu p95 de 899 ms, p50 de
703 ms e CPU da API a 100%: a variância entre execuções na mesma máquina chega a quase o dobro, e o threshold de
1,5 s ficou apenas 1,7 vez acima dela. Trate o threshold como alarme grosso.

## O que os números mostram

1. **O gargalo é a CPU da API, não o lock.** O container da API passou de 90% de uma vCPU em todas as passadas
   frias, enquanto o banco mostrou no máximo 9 conexões esperando lock (na inscrição, que serializa por desenho)
   e quase nenhuma na decisão. Com a JVM aquecida a mesma carga cai para um terço a metade da latência.
2. **O pool de 10 conexões está sempre cheio** nos picos (o máximo amostrado foi 10 em todas as passadas), e boa
   parte dele fica esperando lock (inscrição) ou "ocioso em transação" (a thread aguardando CPU com a transação
   aberta). Em rajadas maiores a fila do pool é a primeira coisa a ceder (abaixo).
3. **Os `503` raros não vêm do teto de 2 s do lock da inscrição.** O que os produz é o limite por conta
   (`AccountRateLimit`, ADR 0006), cujo comando no Bucket4j tem teto de 1 s (`RateLimitConfiguration.REQUEST_TIMEOUT`)
   e disputa a mesma conexão do pool com as transações que esperam o lock do evento. Nos testes padrão foram 0 a 1
   por execução. Os logs da API confirmam a causa (`Rate limit store failed for registration:` com
   `io.github.bucket4j.TimeoutException`), e nenhum `503` veio do `lock_timeout`. Corrigido com o teto de 3 s
   (seção "Teto do limitador", abaixo).

## Variante de estresse (fora dos thresholds): 200 contas, capacidade 100

`REGISTRATION_ACCOUNTS=200 REGISTRATION_CAPACITY=100 REPEAT=2 tools/load/run.sh <imagem> registration`, uma subida:

| Passada | p50 | p95 | p99 | máx | 503 | Invariantes |
|---|---|---|---|---|---|---|
| 1 (fria) | 1680 | 2870 | 3070 | 3160 | 18 | 100 inscritas, 100 `409` |
| 2 (aquecida) | 795 | 1380 | 1480 | 1560 | 3 | 100 inscritas, 100 `409` |

Dobrando a rajada, a passada fria passou do threshold de p95 (2,5 s) e produziu 18 `503` (9% dos pedidos), todos
recuperados na repetição de 1 s: a capacidade nunca foi ultrapassada. Os 21 `503` das duas passadas têm a mesma
causa do item 3 (21 erros do limitador no log, nenhum `lock_timeout`).

## Teto do limitador: opções e antes/depois (2026-10-08)

O item 3 tem uma causa precisa: o Bucket4j começa a contar o teto do comando **antes** de pedir a conexão ao pool
e o confere entre os passos. Na rajada, o comando espera a conexão atrás das transações que esperam o lock do
evento, recebe-a depois de 1 s e falha. Decisão e prós e contras na ADR 0016 (seção "O limite por conta numa
rajada de inscrições").

Mesma máquina e mesmo ambiente da tabela de cima; imagens construídas do ramo `fix/rate-limit-pool-contention`.
As opções de configuração rodaram com `API_ENV_FILE`; as de código, com imagens de protótipo descartadas depois.
Variante de estresse (`REGISTRATION_ACCOUNTS=200 REGISTRATION_CAPACITY=100 REPEAT=2`), uma subida por linha de
execução. **Tempo da conta** é a `iteration_duration` do k6: o primeiro `PUT` com as repetições do `503` (1 s de
espera cada) mais o `PUT` repetido, isto é, o que a pessoa espera do clique à resposta final.

| Opção | Subidas | 503 na passada fria | p95 frio por pedido (ms) | Tempo da conta, máx (ms) | 503 na passada aquecida | Conexões esperando lock (máx) |
|---|---|---|---|---|---|---|
| Antes: teto de 1 s, pool de 10 | 3 | 20, 22, 11 | 2585 a 2788 | 3725 a 3929 | 5, 2, 4 | 8 a 9 |
| (a) Pool de 20 | 3 | 22, 22, 22 | 2336 a 2754 | 3326 a 3758 | 1, 1, 0 | 18 a 19 |
| (b) Pool próprio de 2 para o limitador | 3 | 35, 6, 31 | 2198 a 2371 | 2907 a 3194 | 0, 0, 1 | 9 |
| (c) Teto de 2 s | 6 | 0, 0, 0, 1, 0, 0 | 2530 a 2998 | 2946 a 3712 | 0 em todas | 8 a 9 |
| **(c) Teto de 3 s (escolhida)** | 6 | **0 em todas** | 2651 a 3487 | 2942 a 3907 | 0 em todas | 8 a 9 |
| (d) `lock_timeout` de 1 s | 3 | 24, 16, 28 | 2576 a 2812 | 3892 a 4161 | 0, 1, 0 | 7 a 9 |
| (e) Semáforo de 4 inscrições por réplica | 3 | 0, 0, 0 | 2191 a 2649 | 2992 a 3165 | 0 em todas | 2 a 3 |

Todos os `503` vieram do limitador (as contagens batem com `Rate limit store failed` no log da API); nenhum veio
do `lock_timeout`, nem com ele em 1 s: cada espera pelo lock é curta, o que demora é a fila do pool. Pool maior só
põe mais conexões na fila do lock. O pool próprio do limitador piora, porque as 200 chamadas fazem fila nas 2
conexões dele. O p95 por pedido sobe um pouco com o teto maior porque o pedido que antes voltava `503` em ~1 s
agora espera e termina; o tempo da conta não piora. O semáforo (e) tem a melhor latência, mas é código novo
(ADR 0016).

### Antes e depois, intercalados

Para o ruído da máquina cair nos dois lados, as execuções finais alternaram a imagem de antes (teto de 1 s) e a
de depois (teto de 3 s, o commit do ramo), três rodadas de quatro. A máquina estava mais carregada que nas linhas
acima (load average de 6 a 7, de outros processos), por isso as latências são maiores que as de cima.

| Cenário | Passada | Antes: 503 por subida | Depois: 503 por subida | Antes: p95 (ms) | Depois: p95 (ms) | Antes: tempo da conta, máx (ms) | Depois: tempo da conta, máx (ms) |
|---|---|---|---|---|---|---|---|
| `registration` padrão (100 contas, 50 vagas) | fria | 18, 0, 6 | 0, 0, 0 | 1875 / 1677 / 2089 | 1437 / 1719 / 2489 | 2855 / 1985 / 2899 | 1734 / 2019 / 2716 |
| | aquecida | 0, 0, 0 | 0, 0, 0 | 1023 / 823 / 1108 | 1661 / 1839 / 861 | 1211 / 1023 / 1396 | 1861 / 2147 / 965 |
| Estresse (200 contas, 100 vagas) | fria | 16, 49, 51 | 0, 2, 0 | 2400 / 3465 / 4076 | 2965 / 4466 / 3304 | 3662 / 5177 / 5288 | 3273 / 5073 / 3696 |
| | aquecida | 4, 1, 10 | 0, 0, 0 | 1506 / 1838 / 2871 | 1517 / 1756 / 2098 | 2616 / 2663 / 3956 | 1712 / 1882 / 2318 |

- O cenário padrão passou em todos os thresholds nas três subidas de depois; antes, uma das três falhou por 18
  `503` (o teto aceito é 10).
- O estresse continua fora do threshold de p95 (2,5 s) na passada fria, antes e depois: é a fila de CPU de uma
  vCPU com a JVM fria (item 1), e não o limitador.
- Com a máquina sobrecarregada, o teto de 3 s ainda deixou passar 2 `503` numa subida de estresse (p50 de 3,2 s):
  ele absorve a rajada medida, mas não promete zero quando a CPU passa disso. A falha continua fechada e
  recuperada na repetição.
- As invariantes valeram em todas as execuções: exatamente a capacidade em inscritas, o resto `409`.

## Chat da rodada: o polling aguenta a meta? (ADR 0021, fatia 7)

Cenário `chat.js`: 50 eventos em andamento com a rodada 1 sorteada = **50 pares e 100 participantes**. Cada um
abre o chat, faz polling de `GET .../chat/messages?afterSeq=<maior seq visto>` **a cada 2 s** (como o front) e envia
uma mensagem a cada 10 a 20 s, por **5 minutos**; em ~5% dos envios repete o `POST` com a mesma `Idempotency-Key`.
Cada passada faz ~15.100 polls (~50 por segundo) e ~1.730 envios. Em 2026-10-08, na máquina da tabela "Ambiente"
(API com 1 vCPU e 2 GiB, banco com 2 vCPU e 4 GiB; o limite de envio do chat foi elevado, ver `README.md`).

Quatro subidas completas, cada uma com **duas passadas de 5 minutos** (a 1ª com a JVM fria, a 2ª aquecida).
As três primeiras usaram a imagem de `main` em `1b75852`, a quarta a de `ce25393` (depois do PR #39), que repetiu
os números. Todas passaram nos thresholds e nas invariantes. Latência em ms, medida pelo k6.

| Subida | Passada | GET (polling) p50 / p95 / p99 / máx | POST (envio) p50 / p95 / p99 / máx | 503 | CPU da API (máx / média) |
|---|---|---|---|---|---|
| 1 | fria | 2 / 7 / 21 / 256 | 4 / 14 / 52 / 177 | 0 | 98% / 15% |
| 1 | aquecida | 2 / 14 / 33 / 262 | 5 / 29 / 56 / 124 | 0 | 76% / 12% |
| 2 | fria | 3 / 41 / 610 / 2197 | 6 / 77 / 478 / 2593 | 0 | 139% / 28% |
| 2 | aquecida | 3 / 18 / 36 / 209 | 8 / 42 / 77 / 168 | 0 | 46% / 15% |
| 3 | fria | 4 / 105 / 1222 / 2899 | 9 / 209 / 2104 / 3596 | 3 | 211% / 27% |
| 3 | aquecida | 4 / 16 / 33 / 164 | 13 / 41 / 65 / 171 | 0 | 46% / 15% |
| 4 (`ce25393`) | fria | 2 / 6 / 12 / 94 | 4 / 15 / 35 / 91 | 0 | 93% / 15% |
| 4 (`ce25393`) | aquecida | 2 / 4 / 9 / 45 | 4 / 7 / 19 / 60 | 0 | 35% / 8% |

(Mais uma subida, só para medir o banco, deu 25 / 93 ms de p95 frio e 9 / 26 ms aquecido, no mesmo padrão.)
Picos de CPU acima de 100% são artefato da amostragem do `docker stats` com o limite de 1 vCPU; leia a média e o
fato de que o container ficou perto de 100% nos primeiros ~20 s da passada fria.

- **A passada fria é o único momento ruim.** Nos primeiros ~20 s a API (JVM sem JIT) satura a vCPU; daí a cauda de
  1 a 3,6 s e os 3 `503` documentados (`Rate limit store failed for chat:`, o mesmo teto de 1 s do limitador do item 3
  acima, sem nenhum `lock_timeout`), todos recuperados na repetição. Depois disso o p95 é de 4 a 18 ms.
  Esse é o cenário real de uma réplica que acorda (escala a zero) com 100 pessoas já com a aba aberta.
- **Banco: 4 comandos por poll.** O `pg_stat_statements` mostra, por `GET` de polling: a conta pelo `(issuer, subject)`,
  o par em `round_seat` pela PK, o chat pela chave natural e a página de `chat_message` pela PK, mais `BEGIN READ ONLY`
  e `COMMIT`; todos por índice, 0,006 a 0,05 ms de média. Cada envio custa uns 13 comandos (conta, par, evento, limitador,
  `insert ... on conflict` do chat, lock, busca da chave, bloqueio, última rodada, `insert` e `update`).
- **Carga sobre o banco com 100 participantes:** 49,6 a 49,9 requisições/s no k6 = **~283 comandos SQL/s** (mais ~120/s de
  `BEGIN`/`COMMIT`) e ~128 transações/s (inclui ~4/s do amostrador). O container do banco ficou com **10 a 15% de CPU em
  média** (até 165% num pico frio) e **no máximo 10 conexões** (o pool da API; nenhuma esperando lock).
- **A estimativa da ADR 0021 ("~50 consultas/s pela PK") confere em requisições (49,8/s), mas em comandos SQL são ~5,7 vezes
  mais** (283/s): o front não faz uma consulta por poll, e sim quatro. Continua baixo para o banco.

### Folga (fora dos thresholds): polling mais frequente

`CHAT_POLL_MS` menor, 120 s por passada, os mesmos 100 participantes (equivale a mais gente conversando ao mesmo tempo):

| Polling | Polls/s medidos | Passada | GET p50 / p95 / p99 / máx | POST p50 / p95 | 503 | CPU da API (média) | CPU do banco (média) |
|---|---|---|---|---|---|---|---|
| 500 ms (4x) | 187 | fria | 5 / 390 / 1037 / 2877 | 9 / 395 | 0 | 59% | n/d |
| 500 ms (4x) | 188 | aquecida | 7 / 200 / 924 / 2882 | 26 / 788 | 3 | 41% | n/d |
| 200 ms (10x) | 466 | fria | 6 / 200 / 493 / 2301 | 7 / 600 | 0 | 62% | n/d |
| 200 ms (10x) | 494 | aquecida | 3 / 10 / 17 / 213 | 5 / 12 | 0 | 41% | n/d |
| 200 ms (10x) | 370 | fria | 100 / 514 / 997 / 2913 | 310 / 1094 | 6 | 99% | 27% |
| 200 ms (10x) | 492 | aquecida | 5 / 32 / 88 / 607 | 17 / 86 | 0 | 51% | 28% |

A API chega a ~490 polls/s (10x) com 41 a 51% de CPU e p95 de 10 a 32 ms quando aquecida. A passada fria a 10x satura
a vCPU (99% de média, só 370 polls/s entregues) e o p95 vai a 0,5 s. Uma passada aquecida a 4x teve p95 de 200 ms e 3 `503`
nos primeiros segundos (três estouros de 1 s do limitador, esperas de até 7,5 s), sem causa identificada além da
variância do notebook; as outras três aquecidas ficaram em 10 a 32 ms. As invariantes (sem lacuna, sem duplicata,
todos leram tudo) valeram nas 16 passadas, ~21.100 mensagens no total.

### Conclusão

**Sim: o polling de 2 s aguenta a meta experimental (50 salas, 100 participantes) com 1 vCPU**, com folga grande depois
do aquecimento: ~50 requisições/s custam 8 a 28% de uma vCPU e 10 a 15% de CPU do banco, p95 de 4 a 18 ms. Com a JVM
fria a API já sente 4 vezes essa carga (p95 de 0,4 s) e satura em 7 vezes; aquecida, passa de 10 vezes sem sofrer. O que dói
é o **primeiro minuto de uma JVM fria** (em 2 das 5 passadas frias de 2 s o p99 passou de 0,6 s, e uma teve 3 `503`
documentados), não o ritmo do polling. Para a decisão
"polling para SSE" isto quer dizer: **a carga não é motivo para trocar**; o que justificaria o SSE é a latência de até 2 s e o
custo de acordar uma réplica fria com muitas abas abertas (o aquecimento da JVM da ADR 0022 resolve isso nos dois transportes).

Limites desta medição: gerador e API na mesma máquina; **Bearer**, sem a sessão do BFF nem o ingress do Container Apps
nem TLS; banco com 2 vCPU e sem os créditos de CPU de um B1ms (que só uma rodada em homologação mede); cada poll lê a conta
por `(issuer, subject)` sem cache. Por isso a conclusão vale como "não é o gargalo", e não como capacidade garantida.

## Como reproduzir

```bash
docker build -t duora-api:load-local .
REPEAT=2 tools/load/run.sh duora-api:load-local
REPEAT=2 tools/load/run.sh duora-api:load-local chat      # o chat, 5 minutos por passada
```

Para comparar configurações sem reconstruir a imagem, passe um arquivo de variáveis da API (o formato do
`docker run --env-file`), que fica copiado junto dos resultados:

```bash
echo SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=20 > /tmp/pool20.env
API_ENV_FILE=/tmp/pool20.env REGISTRATION_ACCOUNTS=200 REGISTRATION_CAPACITY=100 REPEAT=2 \
  tools/load/run.sh duora-api:load-local registration
```

Resumos em JSON, logs, amostras e o log da API de cada execução ficam em `tools/load/results/<data>/`.
