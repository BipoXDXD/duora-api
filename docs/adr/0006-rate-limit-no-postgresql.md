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
  `503` em `ProblemDetail` em vez de passar sem limite (falha fechada); a inscrição dependeria do
  banco de qualquer forma. Cada comando do Bucket4j tem teto de 3 s
  (`RateLimitConfiguration.REQUEST_TIMEOUT`), para requisições do mesmo cliente não esperarem o
  lock da linha sem limite. O Bucket4j conta nesse teto a espera por conexão do pool, mas não a corta:
  quem a corta é o timeout do pool (Hikari, 30 s). O teto era de 1 s e passou a 3 s em 2026-10-08,
  quando o k6 mostrou `503` do limitador numa rajada de inscrições; o pool dedicado ao rate limit
  (bulkhead) foi medido e piorou ([ADR 0016](0016-eventos-e-inscricoes.md), seção "O limite por conta
  numa rajada de inscrições").
- A tabela não tem teto de linhas, ao contrário do cache em memória. O crescimento fica limitado
  pela quantidade de IPv4 e de redes /64 de quem ataca, e cada linha some em até uma hora mais o
  intervalo da limpeza; a métrica de tamanho mostra se isso mudar.
- O IP do cliente fica gravado na chave até a reposição completa mais um minuto (no máximo cerca de
  uma hora na waitlist). É dado pessoal pela LGPD: entra no registro de operações de tratamento,
  com finalidade de segurança e retenção curta.
- **Cliente atrás do proxy (decidido em 2026-10-05):** sem tratar o proxy, todos os visitantes
  dividiriam o IP do ingress do Container Apps e um único bucket: 10 inscrições por hora para o
  sistema inteiro, já que o estado é compartilhado entre réplicas. A trava escolhida é uma
  propriedade obrigatória no modo de deploy, e não um passo no smoke test:
  - O perfil `behind-proxy` (nome da funcionalidade, não do ambiente) liga
    `server.forward-headers-strategy=native` e lê `server.tomcat.remoteip.internal-proxies` de
    `DUORA_TRUSTED_PROXIES`, a faixa de onde o ingress conecta (CIDR separados por vírgula). O
    Tomcat só troca o IP da conexão pelo do `X-Forwarded-For` quando ela vem dessa faixa, e pega o
    endereço mais à direita que não é proxy: o que o cliente escreve antes no header não escolhe o
    bucket. O `X-Forwarded-Proto` passa a valer junto, o que o login web precisa para montar o
    `redirect_uri` em `https` atrás do TLS do ingress.
  - Com o perfil, a aplicação não sobe sem a variável (placeholder sem valor) nem com ela em branco
    (`TrustedProxyProperties`, `@NotBlank` sobre a mesma chave do Boot). Em branco é o caso
    perigoso: o Tomcat confiaria em qualquer conexão e o cliente escolheria o próprio IP.
  - O `Dockerfile` define `SPRING_PROFILES_ACTIVE=behind-proxy`: a imagem é o artefato de deploy e
    só roda atrás do ingress, então a trava não depende de lembrar o perfil na configuração do
    Container Apps. Quem definir `SPRING_PROFILES_ACTIVE` no deploy precisa manter `behind-proxy`
    na lista. Desenvolvimento local e testes não ligam o perfil e sobem sem configurar nada.
  - Alternativas: ativar o perfil só na configuração do Container Apps (a imagem ficaria neutra,
    mas esquecer o perfil voltaria ao bucket único sem erro nenhum); detectar o Container Apps pela
    variável `CONTAINER_APP_NAME` (automático, mas acopla o código à plataforma e não tem teste
    fora dela); passo no smoke test conferindo chaves distintas por `X-Forwarded-For` (pega a
    imagem, não a configuração do deploy real).
  - O valor de `DUORA_TRUSTED_PROXIES` depende da rede do ambiente do Container Apps (subnet de
    infraestrutura) e entra junto com a infraestrutura do deploy.
- O Caffeine saiu do `pom.xml`.
- Se a latência medida no k6 pesar, o caminho é Redis com o mesmo Bucket4j, trocando só o
  `ProxyManager`.

## Compliance

- `JoinWaitlistRateLimitFilterIT.sharesLimitAcrossReplicas`: dois filtros com gerenciadores de
  bucket diferentes, que só compartilham o banco, somam o mesmo limite.
- `JoinWaitlistRateLimitFilterIT.sharesLimitWithinIpv6Slash64` e `countsEachIpv6Slash64Separately`:
  endereços da mesma rede /64 somam o mesmo limite; redes diferentes, não.
- `JoinWaitlistRateLimitFilterIT.rejectsRequestWithServiceUnavailableWhenBucketStoreIsDown` e
  `rejectsRequestWithServiceUnavailableWhenBucketStaysLocked`: com o banco recusando conexão ou com
  a linha do bucket presa por outra transação, a requisição não passa e recebe `503` (falha fechada
  e timeout).
- `JoinWaitlistIT.joiningAboveRateLimitIsRejectedWithoutWriting`: acima do limite, `429` com
  `Retry-After` e nada gravado.
- `RequiredTrustedProxySettingsIT`: com o perfil `behind-proxy`, a aplicação não sobe sem
  `DUORA_TRUSTED_PROXIES` nem com ela em branco.
- `ForwardedClientAddressIT`, com HTTP real pelo Tomcat: vindo de proxy confiável, cada
  `X-Forwarded-For` tem o próprio limite (`forwardedClientsGetSeparateLimits`), que continua valendo
  (`forwardedClientIsStillLimited`), e o que o cliente põe antes no header não troca o bucket
  (`clientCannotChooseItsAddressThroughForwardedChain`); vindo de fora da faixa, o header é ignorado
  (`forwardedHeaderDoesNotChangeTheClient`).
- `infra/docker/smoke-test.sh` sobe a imagem, que liga o perfil, com `DUORA_TRUSTED_PROXIES`.
- `ExpiredRateLimitBucketCleanerIT`: o `expires_at` gravado é a reposição completa mais a folga, a
  limpeza apaga só o que venceu e continua enquanto houver lote cheio, e a métrica conta os buckets.
