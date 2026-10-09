# 0022. Teste de carga manual com k6

- **Status:** Aceita, provisória. Implementada sem o usuário; revisar com ele. As decisões tomadas sem o
  usuário estão marcadas como **(autônoma)**.
- **Data:** 2026-10-08
- **Relacionadas:** [ADR 0006](0006-rate-limit-no-postgresql.md), [ADR 0012](0012-contrato-openapi.md),
  [ADR 0014](0014-infraestrutura-do-piloto-na-azure.md), [ADR 0016](0016-eventos-e-inscricoes.md),
  [ADR 0017](0017-pareamento.md), [ADR 0019](0019-decisao-privada-e-conexoes.md)

## Contexto

O plano (§8) pede carga com k6 ("entrada simultânea, chat e ações") e uma meta experimental: simular 50
salas e 100 participantes, medir latência, erros e conexões, sem prometer capacidade. As ADRs 0016, 0017 e
0019 deixaram três medições para o k6: a disputa do lock da inscrição (e o teto de 2 s), o sorteio da
rodada (chave da rodada e teto de 5 s) e o advisory lock da decisão (teto de 2 s). Os testes de integração
já provam as invariantes com poucas threads; faltava ver o comportamento com o pool, a CPU e o limite de
produção e com 100 a 200 pedidos de uma vez.

## Alternativas e decisões

### Onde e quando roda **(autônoma)**

| Opção | Prós | Contras |
|---|---|---|
| **Manual, script em `tools/load/`, documentado** | Zero minutos de CI; roda antes de mexer em lock, pool ou limite; os números são de uma máquina conhecida | Esquecível; sem histórico automático |
| Workflow de CI em PR | Pega regressão cedo | Runner compartilhado dá latência instável (threshold flaky); gasta minutos que o piloto não tem orçamento para gastar |
| Workflow semanal, como o fuzzing | Histórico | Mesmo ruído do runner; os thresholds teriam de ser frouxos a ponto de não pegar nada |

**Decisão:** manual. Se um dia houver ambiente de homologação com recursos fixos, o mesmo `run.sh` pode virar
um workflow semanal contra ele.

### Ferramenta e ambiente **(autônoma)**

k6 (o plano já o escolheu), pela imagem `grafana/k6` fixada por digest, como o Schemathesis e o busybox dos outros
scripts. O alvo é a **imagem de produção** num PostgreSQL 18 descartável, com 1 vCPU e 2 GiB para a API (ADR
0014) e 2 vCPU e 4 GiB para o banco (o B2s do plano), pool e Tomcat no padrão: o que se mede é o
comportamento que o deploy teria, e não a aplicação solta na IDE.

### Autenticação **(autônoma)**

A técnica do `contract-test.sh`: par de chaves RSA por execução, JWKS num `busybox` e tokens Bearer assinados
com `openssl`, um por conta sintética (`oid` = `load-user-NNN`) e um com o papel `ADMIN`. A porta de sessão
(BFF) fica de fora, porque o login interativo no Entra não é automatizável; a carga passa pelos mesmos
controllers e serviços, só sem o filtro de sessão e o CSRF.

### Preparo dos dados **(autônoma)**

| Opção | Prós | Contras |
|---|---|---|
| **API onde existe rota, SQL só onde não existe** | Contas, perfis e o evento da inscrição exercitam o caminho real; o SQL é uma exceção pequena e documentada | Dois mecanismos |
| Tudo por SQL | Rápido e simples | Pularia a criação de conta e as validações do perfil |
| Relógio injetável na imagem | Sem SQL | Muda o artefato que se mede e exigiria código de produção |

**Decisão:** contas e perfis (`seed.js`) e o evento da inscrição pela API. Os 50 eventos **em andamento** vêm
de `sql/in-progress-events.sql`, porque a API só cria evento no futuro e o sorteio só aceita evento em
andamento. O banco é descartável e as linhas são as que a API gravaria.

### Limites de rate limit **(autônoma)**

Elevados só onde o cenário gastaria o saldo real: o da rodada (por conta ADMIN, 30 por hora; o cenário usa uma
conta para 200 pedidos) e o da decisão (por conta, ainda fora de `main`). O da inscrição fica no valor de
produção. Um `429` mediria o limite, que já tem teste próprio.

### O que cada cenário afirma **(autônoma)**

- **Invariantes são exatas, latência é aproximada.** Contagens exatas (50 inscritas, uma rodada criada por
  (evento, número), 25 conexões) e `unexpected_responses == 0` são thresholds duros, e o banco é consultado
  depois do k6 de forma independente do que a API responde (por exemplo, as conexões esperadas saem das
  decisões gravadas). O `503` com `Retry-After: 1` é contrato: o cliente repete até 5 vezes e ele é contado,
  aceito só até 10% dos pedidos.
- **Disputa de verdade:** cada pedido de rodada vai em dobro (duas abas), e os pares da decisão se dividem em
  quatro grupos (sim/sim, sim/não, não/sim, não/não) com os dois lados decidindo no mesmo instante.
- **Thresholds de latência são iniciais:** 30 a 40% acima do pior p95 medido com a JVM fria (2,5 s, 1,5 s e
  2,5 s; detalhes no `tools/load/README.md`). Servem para pegar regressão grosseira, e não como SLO.

## Consequências

- **Os números informam as ADRs** (`tools/load/RESULTS.md`):
  - Inscrição: o lock do evento aguenta 100 contas num evento de 50 vagas com p95 de 1,5 a 1,7 s frio e 0,5 s
    aquecido, e a capacidade nunca foi excedida. Não há razão medida para trocar pelo contador com `CHECK`
    ([ADR 0016](0016-eventos-e-inscricoes.md)).
  - O teto de 2 s do lock nunca disparou. Os `503` observados (0 a 1 por execução, 18 numa rajada de 200 contas)
    vieram do limite por conta: o comando do Bucket4j tem teto de 1 s e disputa o pool de 10 conexões com as
    transações que esperam o lock do evento. O comportamento é correto (falha fechada, `Retry-After: 1`,
    nada gravado), mas a causa não é a descrita na ADR 0016.
  - **Atualização de 2026-10-08:** as cinco saídas (pool maior, pool próprio do limitador, teto maior no
    limitador, `lock_timeout` menor e semáforo antes da conexão) foram medidas na variante de estresse, com o
    `API_ENV_FILE` do `run.sh` para as de configuração e imagens de protótipo para as de código. O teto do
    limitador passou de 1 s a 3 s: os `503` da passada fria caíram de 11 a 22 para 0 em seis subidas. Pool maior
    e `lock_timeout` menor não mudaram nada, e o pool próprio piorou. Números e decisão na [ADR
    0016](0016-eventos-e-inscricoes.md) (seção "O limite por conta numa rajada de inscrições") e em
    `tools/load/RESULTS.md`.
  - Sorteio: 50 eventos em paralelo, cada pedido em dobro, deram sempre um sorteio por (evento, número) e p95 de
    0,46 a 0,59 s frio. O teto de 5 s não foi nem tocado. O pior caso de 200 candidatos num evento só (1,2 s na
    ADR 0017) **não** foi medido aqui.
  - Decisão: 200 decisões simultâneas deram sempre 25 conexões (os pares com dois sim), sem `503`, com p95 de 1,4 a
    1,7 s frio e 0,75 a 0,9 s aquecido. O advisory lock quase não aparece (no máximo 1 conexão esperando lock);
    a latência é fila de CPU da API.
- **A JVM fria pesa 2 a 3 vezes** na latência da primeira rajada depois de um deploy, e a probe de readiness não
  aquece nada. Se isso importar para o piloto, a pendência é um aquecimento antes de a réplica entrar no tráfego
  (decisão de produto e de infraestrutura, fora deste passo).
- Os resultados são de um notebook, com gerador e API na mesma máquina: **não são capacidade garantida**.
- O `run.sh` precisa de Docker, `curl`, `openssl`, `xxd` e `jq`; o digest do k6 é atualizado à mão (o
  Dependabot não enxerga a variável do script).
- O cenário de decisão assume que a rodada 1 de eventos de 4 pessoas forma sempre 2 pares (sem bloqueios); se o
  sorteio mudar de critério, o `setup` do `decisions.js` lê os pares reais e o número esperado de conexões se
  ajusta sozinho (a invariante do banco recalcula a partir das decisões).

## Pendente com o usuário

1. **Rodar contra a homologação** com recursos fixos e, então, decidir se um workflow semanal compensa.
2. **Pool:** o teto do limitador foi resolvido (acima); ficam o `connectionTimeout` de 30 s do Hikari e o tamanho
   do pool diante do B1ms (pendência 9 da [ADR 0016](0016-eventos-e-inscricoes.md)). Se a abertura real ainda
   der `503`, o semáforo por réplica já tem números.
3. **Aquecimento da JVM** antes da readiness.
4. **Cenários que faltam do plano:** chat, reconexão, queda do PubSub, reenvio da outbox e restauração do banco
   (dependem de módulos que ainda não existem) e o sorteio de 200 candidatos num evento só.
