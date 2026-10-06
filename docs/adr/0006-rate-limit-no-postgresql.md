# 0006. Rate limit com estado no PostgreSQL

- **Status:** Aceita
- **Data:** 2026-10-05

## Contexto

O `POST /api/waitlist` é público e limitado a 10 inscrições por IP por hora. Os buckets do
Bucket4j ficavam num cache Caffeine em memória, então o limite valia por réplica: com três
réplicas no Azure Container Apps, um cliente teria três vezes o limite, e cada reinício zerava a
contagem. Os próximos limites (login, envio de código, denúncia) precisam valer de verdade.

Alternativas consideradas:

| Opção | Prós | Contras |
|---|---|---|
| Bucket4j em memória (Caffeine) | Sem I/O por requisição; nada para operar | Limite multiplicado pelo número de réplicas e perdido a cada reinício |
| **Bucket4j com backend PostgreSQL** | Limite único entre réplicas, usando o banco que já existe; sem serviço novo | Uma transação curta com `SELECT ... FOR UPDATE` por requisição limitada; a tabela cresce e precisa de limpeza |
| Bucket4j com Redis (Azure Cache for Redis) | Mais rápido que o banco; feito para isso | Serviço novo para operar e pagar, sem problema medido que o justifique |
| Rate limit no gateway (Front Door, APIM) | Barra o tráfego antes da aplicação | Custo alto para o piloto; regras por conta (não por IP) continuam na aplicação |

## Decisão

Os buckets ficam na tabela `rate_limit_bucket`, gerenciada pelo `PostgreSQLSelectForUpdateBasedProxyManager`
do Bucket4j (`config/RateLimitConfiguration`). Cada limite usa um prefixo na chave
(`join-waitlist:<cliente>`), para a mesma tabela servir aos próximos. O cliente é o IPv4 ou, em
IPv6, a rede /64 (`2001:db8:1:2::/64`): quem tem um IPv6 controla a rede inteira e, limitado por
endereço, poderia trocar de endereço a cada requisição, escapar do limite e encher a tabela.

O Bucket4j grava em `expires_at` o instante em que o bucket se repõe por inteiro, mais um minuto
de folga. `ExpiredRateLimitBucketCleaner` apaga esses buckets a cada 10 minutos
(`duora.rate-limit.cleanup-interval`), em lotes de 1.000; várias réplicas podem limpar ao mesmo
tempo porque o Bucket4j apaga com `FOR UPDATE SKIP LOCKED`. A métrica
`duora.rate-limit.buckets` mostra o tamanho da tabela.

O motivo técnico é um limite que vale para o sistema, e não para cada processo, sem infraestrutura
nova. O motivo de negócio é que limite de login e de envio de código só protege conta e custo se
não puder ser multiplicado.

## Consequências

- Toda requisição limitada abre uma transação curta no banco. Se o banco cair, a rota responde
  erro em vez de passar sem limite (falha fechada); a inscrição dependeria do banco de qualquer
  forma.
- A tabela não tem teto de linhas, ao contrário do cache em memória. O crescimento fica limitado
  pela quantidade de IPv4 e de redes /64 de quem ataca, e cada linha some em até uma hora mais o
  intervalo da limpeza; a métrica de tamanho mostra se isso mudar.
- O IP do cliente fica gravado na chave até a reposição completa mais um minuto (no máximo cerca de
  uma hora na waitlist). É dado pessoal pela LGPD: entra no registro de operações de tratamento,
  com finalidade de segurança e retenção curta.
- O cliente ainda é identificado pelo IP da conexão. Atrás do proxy do Container Apps é preciso
  configurar `server.forward-headers-strategy`, senão todos os clientes dividem o IP do proxy.
- O Caffeine saiu do `pom.xml`.
- Se a latência medida no k6 pesar, o caminho é Redis com o mesmo Bucket4j, trocando só o
  `ProxyManager`.

## Compliance

- `JoinWaitlistRateLimitFilterIT.sharesLimitAcrossReplicas`: dois filtros com gerenciadores de
  bucket diferentes, que só compartilham o banco, somam o mesmo limite.
- `JoinWaitlistRateLimitFilterIT.sharesLimitWithinIpv6Slash64` e `countsEachIpv6Slash64Separately`:
  endereços da mesma rede /64 somam o mesmo limite; redes diferentes, não.
- `JoinWaitlistIT.joiningAboveRateLimitIsRejectedWithoutWriting`: acima do limite, `429` com
  `Retry-After` e nada gravado.
- `ExpiredRateLimitBucketCleanerIT`: o `expires_at` gravado é a reposição completa mais a folga, a
  limpeza apaga só o que venceu e continua enquanto houver lote cheio, e a métrica conta os buckets.
