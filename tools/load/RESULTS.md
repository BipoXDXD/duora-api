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
| Rate limit | produção, exceto o da rodada e o da decisão elevados (ver `README.md`) |

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
   `io.github.bucket4j.TimeoutException`), e nenhum `503` veio do `lock_timeout`.

## Variante de estresse (fora dos thresholds): 200 contas, capacidade 100

`REGISTRATION_ACCOUNTS=200 REGISTRATION_CAPACITY=100 REPEAT=2 tools/load/run.sh <imagem> registration`, uma subida:

| Passada | p50 | p95 | p99 | máx | 503 | Invariantes |
|---|---|---|---|---|---|---|
| 1 (fria) | 1680 | 2870 | 3070 | 3160 | 18 | 100 inscritas, 100 `409` |
| 2 (aquecida) | 795 | 1380 | 1480 | 1560 | 3 | 100 inscritas, 100 `409` |

Dobrando a rajada, a passada fria passou do threshold de p95 (2,5 s) e produziu 18 `503` (9% dos pedidos), todos
recuperados na repetição de 1 s: a capacidade nunca foi ultrapassada. Os 21 `503` das duas passadas têm a mesma
causa do item 3 (21 erros do limitador no log, nenhum `lock_timeout`).

## Como reproduzir

```bash
docker build -t duora-api:load-local .
REPEAT=2 tools/load/run.sh duora-api:load-local
```

Resumos em JSON, logs, amostras e o log da API de cada execução ficam em `tools/load/results/<data>/`.
