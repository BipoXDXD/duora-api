# Teste de carga (k6)

Cenários que medem as disputas que as ADRs [0016](../../docs/adr/0016-eventos-e-inscricoes.md),
[0017](../../docs/adr/0017-pareamento.md) e [0019](../../docs/adr/0019-decisao-privada-e-conexoes.md) deixaram
para o k6, e conferem as invariantes depois. Decisões e números em [ADR 0022](../../docs/adr/0022-teste-de-carga-com-k6.md)
e [RESULTS.md](RESULTS.md). **Manual**: não há workflow de CI, para não gastar minutos.

## Como rodar

Precisa de Docker, `curl`, `openssl`, `xxd` e `jq`.

```bash
docker build -t duora-api:load-local .
tools/load/run.sh duora-api:load-local            # os três cenários
tools/load/run.sh duora-api:load-local rounds     # ou um só: registration | rounds | decisions
REPEAT=2 tools/load/run.sh duora-api:load-local   # cada cenário duas vezes na mesma subida (a 2ª com a JVM aquecida)
```

O script sobe um PostgreSQL 18 e a imagem da API numa rede própria, com os limites de produção
(API: 1 vCPU e 2 GiB, [ADR 0014](../../docs/adr/0014-infraestrutura-do-piloto-na-azure.md); banco: 2 vCPU e 4 GiB,
o B2s hipotético do plano), e roda o k6 pela imagem `grafana/k6` fixada por digest. No fim imprime a tabela de
latências (`summarize.sh`) e deixa tudo em `tools/load/results/<data>/` (ignorado pelo git): o log de cada
cenário, o resumo em JSON do k6, as amostras do banco e da API e o log da API. Sai com código diferente de 0 se
um threshold ou uma invariante falhar.

Variáveis: `ACCOUNTS` (200 contas sintéticas), `REGISTRATION_ACCOUNTS` (100) e `REGISTRATION_CAPACITY` (50) para o
cenário da inscrição, `REPEAT` (1), `API_CPUS`/`API_MEMORY`, `DB_CPUS`/`DB_MEMORY` e `LOAD_RESULTS_DIR`.

## O que cada cenário faz

| Cenário | Carga | Esperado |
|---|---|---|
| `registration` | 100 contas com perfil completo se inscrevem **ao mesmo tempo** num evento de capacidade 50; depois cada uma repete o `PUT` (clique duplo) | Exatamente 50 `201` e 50 `409`; a repetição devolve a mesma inscrição (`200`); nenhuma resposta fora do contrato além do `503` com `Retry-After: 1`, que o cliente repete; o ADMIN conta 50 e o banco tem 50 linhas |
| `rounds` | O ADMIN inicia a rodada 1 em 50 eventos em andamento (4 inscritos cada) em paralelo, **cada pedido em dobro** (duas abas); 20 s depois, a rodada 2 do mesmo jeito | Por (evento, número): um `201` e um `200`, nunca dois sorteios; 2 pares por rodada; pares recíprocos e sem repetir o da rodada 1; no banco, 100 rodadas e 400 assentos |
| `decisions` | 200 contas (100 pares de 50 eventos) decidem **ao mesmo tempo**; os pares se dividem em quatro grupos (sim/sim, sim/não, não/sim, não/não); cada conta ainda repete a decisão e tenta a outra | Cada resposta só conta a própria decisão; conexões = pares com dois sim (25), cada uma vista pelas duas contas e por mais ninguém; no banco, o mesmo número recalculado das decisões |

Os `503` com `Retry-After: 1` são o contrato quando a API recusa por falta de espera (lock acima do teto ou
limite que não pôde ser contado, ADRs 0006, 0016, 0017 e 0019); o cliente de carga repete até 5 vezes, como o
front deve fazer. Eles contam, mas só até 10% dos pedidos: mais que isso é regressão.

## Thresholds

| Cenário | Threshold | Por quê |
|---|---|---|
| `registration` | p95 do `PUT` < 2,5 s | Pior p95 frio medido: 1,7 s (RESULTS.md). O teto do lock é 2 s; acima de 2,5 s o `503` deixou de ser exceção |
| `rounds` | p95 do `PUT` < 1,5 s | Pior p95 frio medido: 0,59 s nas execuções oficiais e 0,90 s numa extra. O sorteio de 4 pessoas é barato; 1,5 s já indicaria fila no pool, e não o sorteio |
| `decisions` | p95 do `PUT` < 2,5 s | Pior p95 frio medido: 1,75 s. Teto do advisory lock: 2 s |
| todos | `unexpected_responses == 0`, `invariants_ok == 100%`, contagens exatas (50 inscritas, 50 rodadas criadas e 50 repetidas por onda, 200 decisões) | São o contrato: qualquer desvio é defeito, não lentidão |
| todos | `503` documentados <= 10% | Aceitos, mas só como exceção |

São valores **iniciais**: 30 a 40% acima do pior p95 medido com a JVM fria, numa máquina só. Ajuste depois de
medir no ambiente de homologação, e registre a mudança no RESULTS.md.

## Autenticação e dados

- **Bearer, como o `contract-test.sh`:** um par de chaves RSA por execução, o JWKS num container `busybox` e tokens
  assinados com `openssl` (`oid` = `load-user-NNN`, e `load-admin` com o papel `ADMIN`). A porta de sessão (BFF)
  fica de fora, porque exige o login interativo no Entra.
- **Contas e perfis pela API** (`seed.js`): o primeiro acesso abre a conta (ADR 0011) e o `PATCH` completa o perfil.
- **Evento de inscrição pela API:** o ADMIN cria e publica (`registration.js`, `setup`).
- **Eventos em andamento por SQL** (`sql/in-progress-events.sql`): a API só cria eventos no futuro e o sorteio exige
  evento em andamento; o banco descartável recebe 50 eventos que começaram há 30 minutos, com 4 inscritos cada
  (as linhas que a API gravaria). É a única escrita direta, e só neste banco.
- **Invariantes no banco** (`sql/invariants-*.sql`): conferidas depois do k6, de forma independente do que a API
  responde (por exemplo, as conexões esperadas saem das decisões gravadas).

## Limites de rate limit elevados (e por quê)

- `DUORA_MATCHING_ROUNDRATELIMIT_CAPACITY`: o limite da rodada é por conta ADMIN (30 por hora), e o cenário usa uma
  conta só para 200 pedidos. Um `429` mediria o limite, que já tem teste próprio (`RoundRateLimitIT`).
- `DUORA_CONNECTIONS_DECISIONRATELIMIT_CAPACITY`: o limite por conta da decisão ainda não está em `main`; a variável é
  inofensiva enquanto não existir e evita o `429` quando existir.
- A inscrição **mantém** o limite de produção (60 por hora por conta): cada conta faz uns 10 pedidos no máximo.

## Arquivos

| Arquivo | Para quê |
|---|---|
| `run.sh` | Sobe o ambiente, prepara as contas, roda os cenários e confere as invariantes |
| `summarize.sh` | Tabela de latências e de uso do banco e da API a partir de um diretório de resultados |
| `lib.js` | Chamada HTTP com tag por operação, leitura de `tokens.json` e `data.json` (gerados por execução), helpers |
| `seed.js` | Abre as 200 contas e completa os perfis |
| `registration.js`, `rounds.js`, `decisions.js` | Os cenários |
| `sql/` | Limpeza entre cenários, eventos em andamento, exportação de ids, invariantes e amostra de atividade |
