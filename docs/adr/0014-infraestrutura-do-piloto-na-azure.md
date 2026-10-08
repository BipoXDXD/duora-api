# 0014. Infraestrutura do piloto na Azure como código

- **Status:** Proposta. Decidido na sessão autônoma de 2026-10-05; revisar com o usuário.
  **Pendente com o usuário: aprovar custo antes do primeiro apply.**
  Revista em 2026-10-06 (região, assinatura, só homologação): ver "Revisão de 2026-10-06" no fim.
- **Data:** 2026-10-05
- **Relacionadas:** [ADR 0001](0001-autenticacao-entra-external-id.md), [0002](0002-front-web-com-bff.md),
  [0006](0006-rate-limit-no-postgresql.md) (proxy confiável), [0007](0007-estilo-por-modulo.md),
  [0008](0008-imagem-e-let-it-crash.md) (imagem, probes e shutdown)

## Contexto

A etapa 1 do plano (§9, "Fundação") pede repositório, CI, ambiente Azure, login, perfil e
bloqueio/denúncia. A §6 descreve os recursos do piloto: Container Apps em VNet com HTTPS público,
PostgreSQL privado, Key Vault com managed identity, Log Analytics e Application Insights, Container
Registry, alertas de custo, migrações por Container Apps Job e deploy por GitHub Actions com OIDC.
Nada disso existia como código. O projeto tem uma pessoa só, o custo sai do bolso dela, e a Azure
ainda não tem nenhum recurso do Duora além do tenant do Entra.

Esta ADR também fecha duas pendências da ADR 0008: o que entra na readiness e o prazo do graceful
shutdown frente ao grace period da plataforma. E dá o valor de `DUORA_TRUSTED_PROXIES` que a ADR 0006
deixou para a infraestrutura.

### Ferramenta de infraestrutura

| Opção | Prós | Contras |
|---|---|---|
| **Bicep** | Nativo da Azure, sem arquivo de state para guardar e proteger; tipos e linter offline (`az bicep build/lint`); `what-if` antes do apply; é o que o plano (§9) pede | Só Azure; sem state, o drift só aparece no `what-if` |
| OpenTofu | Multinuvem; `plan` explícito com state | State remoto com lock exige um Storage Account, e o state guarda as senhas em texto; mais uma ferramenta para quem está começando |
| Scripts `az` ou portal | Rápido para a primeira vez | Imperativo, sem revisão em PR, não recria o ambiente igual |

### Plataforma da API

| Opção | Prós | Contras |
|---|---|---|
| **Azure Container Apps (perfil Consumption, VNet própria)** | PaaS de containers; probes, revisões com troca sem downtime, jobs, escala a zero e identidade gerenciada prontos; cobra por segundo de uso, com cota gratuita mensal | Sem `preStop`; limites de probe (`failureThreshold` até 10); custo de réplica sempre ligada sobe com vCPU ativa |
| App Service (Linux) | Simples, slots de deploy | Preço fixo por plano mesmo parado; VNet exige plano pago; sem um equivalente direto do job de migração |
| AKS | Controle total | Operar cluster; contraria o plano (§6) e a dose mínima de infraestrutura |

### Registry

| Opção | Prós | Contras |
|---|---|---|
| **Azure Container Registry Basic, compartilhado entre ambientes** | O Container Apps puxa com identidade gerenciada (`AcrPull`), sem senha; o GitHub publica por OIDC (`AcrPush`); a mesma imagem promovida de homologação a produção | Cerca de US$ 5 por mês; mais um resource group |
| GHCR | Gratuito para o volume do piloto | Pacote privado exige credencial do GitHub guardada como segredo no Container Apps: um token de longa duração |

### Migrações e credenciais do banco

| Opção | Prós | Contras |
|---|---|---|
| Flyway na subida da API | Nada a mais para rodar | Várias réplicas migrando juntas; a API precisa de permissão de DDL; uma migração lenta atrasa a partida |
| Job com a API inteira (`--spring.main.web-application-type=none`) | Mesmo processo de sempre | O agendamento do rate limit mantém a JVM viva, e o job exigiria todas as variáveis do Entra |
| Imagem oficial do Flyway | Ferramenta pronta | Outra imagem para versionar junto com as migrations, que hoje moram no jar |
| **Job com a mesma imagem e um ponto de entrada próprio (`DatabaseMigration`)** | Mesmo artefato do deploy; não sobe o Spring; termina sozinho com código de saída; cria o papel restrito da API | Código de infraestrutura no repositório, com teste próprio |

Para a autenticação no banco, a alternativa forte é o Microsoft Entra com identidade gerenciada (sem
senha nenhuma). Ela exige a biblioteca `azure-identity-extensions` na API e no job e a criação dos
papéis por `pgaadauth_create_principal`; fica como evolução (pendência abaixo).

## Decisão

**Bicep em `infra/azure/`**, com dois níveis:

- `shared.bicep` no resource group `duora-shared`: o ACR Basic (sem usuário admin, sem pull anônimo)
  e a identidade `id-duora-publisher`, com credencial federada só para a branch `main` do repositório
  e `AcrPush` no registry.
- `main.bicep` em um resource group por ambiente (`duora-hml`, `duora-prod`), com
  `hml.bicepparam` e `prod.bicepparam`:
  - VNet com duas subnets: `/24` delegada ao Container Apps (perfil de workload; o mínimo é `/27` e o
    tamanho não muda depois) e `/24` delegada ao PostgreSQL, com zona DNS privada.
  - **PostgreSQL Flexible Server 18**, Burstable `Standard_B1ms`, 32 GiB com crescimento automático,
    sem HA, sem backup geo-redundante, acesso só privado (`publicNetworkAccess: Disabled`), retenção de
    backup de 7 dias em homologação e 14 em produção (plano §6), e
    `idle_in_transaction_session_timeout` de 60 s.
  - **Key Vault** com RBAC e três segredos (senha de administração do banco, senha do papel da API e
    segredo do cliente web do Entra). Cada identidade recebe `Key Vault Secrets User` só nos segredos
    que usa. Proteção contra purga só em produção.
  - Log Analytics (retenção de 30 dias, teto diário de ingestão) e Application Insights apoiado nele,
    sem chave local.
  - Ambiente do Container Apps na VNet, ingress externo, perfil Consumption.
  - **API** (`ca-duora-<env>-api`): ingress HTTPS (HTTP redireciona), single revision mode, imagem
    puxada com identidade, segredos por referência ao Key Vault. Produção: 1 vCPU, 2 GiB, 1 a 3
    réplicas (plano §6). Homologação: 0,5 vCPU, 1 GiB, 0 a 1 réplica (escala a zero).
  - **Job de migração** (`caj-duora-<env>-migrate`), disparo manual, mesma imagem, comando
    `java -cp app.jar bipo.tech.duoraapi.migration.DatabaseMigration`, uma nova tentativa e 10 minutos
    de teto.
  - Identidade de deploy `id-duora-<env>-deploy` com credencial federada só para o environment do
    GitHub (`homologacao` ou `producao`) e um papel próprio no resource group (`Duora deployer`): ler
    e atualizar o app e o job, disparar o job e ler execuções e revisões, mais o `join` no ambiente e
    o `assign` nas identidades que o Azure exige ao reenviar app e job. Sem `delete`, sem Key Vault.
    `Contributor` foi descartado porque permite apagar o ambiente e mudar ingress e logs.
  - Alertas: orçamento do resource group com aviso em 70%, 90% e 100% (plano §6) e réplica da API com
    mais de 3 reinícios (pendência da ADR 0008). A métrica `RestartCount` é acumulada por réplica, então
    o alerta usa o máximo, não a soma da janela.
- **Credenciais do banco** (plano §7: "aplicação acessa banco sem privilégios de administrador;
  migrações têm credencial separada"): o job conecta com o login de administração, garante o papel
  `duora_app` com a senha atual (hash SCRAM calculado no cliente pelo driver), roda o Flyway e
  concede ao papel só `SELECT/INSERT/UPDATE/DELETE` em todas as tabelas e uso das sequências, sem
  acesso ao `flyway_schema_history`. Os privilégios valem para as tabelas que já existem (`GRANT ... ON
  ALL TABLES IN SCHEMA public` a cada execução) e para as que o dono das migrations criar depois
  (`ALTER DEFAULT PRIVILEGES`), então módulos novos (identity, profiles, trustsafety...) não pedem
  mudança no job. A API conecta como `duora_app` com `SPRING_FLYWAY_ENABLED=false`. Trocar a senha do
  papel é trocar o segredo e rodar o job.
- **Telemetria (opcional, desligada):** o parâmetro `enableOpenTelemetry` (variável
  `DUORA_ENABLE_OPENTELEMETRY`, padrão `false`) liga o agente OpenTelemetry gerenciado do ambiente do
  Container Apps, com traces e logs para o Application Insights. Ligado, o ambiente injeta
  `OTEL_EXPORTER_OTLP_ENDPOINT` nas réplicas; a API já exporta por OTLP quando essa variável existe
  (PR de logs estruturados, `spring-boot-starter-opentelemetry`), sem depender de nada deste Bicep.
  Desligado, nada é coletado nem cobrado e a connection string do Application Insights fica fechada
  (`DisableLocalAuth`), porque o agente gerenciado só envia por ela. As propriedades
  `appInsightsConfiguration` e `openTelemetryConfiguration` só existem em versão preview da API
  (`Microsoft.App/managedEnvironments@2025-10-02-preview`); a GA mais recente (2026-01-01) não as tem.
  Ligar custa a ingestão no Log Analytics (US$ 4,60/GB acima da cota gratuita) e passa pelo mesmo teto
  diário; fica para decidir com o custo aprovado e a API exportando.
- **TLS do banco:** `sslmode=verify-full` com `DefaultJavaSSLFactory`, que usa o `cacerts` do JRE; as
  raízes da Azure (DigiCert Global Root G2 e Microsoft RSA Root CA 2017) estão nele.
- **Probes** (fecha a Divergência 12 de DevOps e a pendência da ADR 0008): liveness em
  `/actuator/health/liveness`, só estado interno; readiness em `/actuator/health/readiness`, com
  `readinessState` e o banco (sem o banco a API não atende nada; terceiros como o Entra ficam fora);
  startup na liveness com até ~110 s para a JVM subir. As duas rotas são públicas e respondem só
  `{"status":"UP"}`. Readiness tolera 30 s de falha antes de tirar a réplica, para um soluço do banco
  não derrubar todas de uma vez.
- **Shutdown** (regra DevOps 12): `terminationGracePeriodSeconds: 30` no Container Apps e
  `spring.lifecycle.timeout-per-shutdown-phase=20s`, com `server.shutdown=graceful` explícito.
- **`DUORA_TRUSTED_PROXIES`** = a faixa da subnet do Container Apps (`10.10.0.0/24` em homologação,
  `10.20.0.0/24` em produção). A documentação da Microsoft não diz de que endereço o Envoy do ingress
  conecta à réplica; a verificação está no README (pendência abaixo). Se a faixa estiver errada, o
  erro é seguro: o Tomcat ignora o `X-Forwarded-For` e todos os clientes dividem um bucket, o que o
  teste do README mostra na hora.
- **Deploy** (`.github/workflows/deploy.yml`): só `workflow_dispatch`, só da `main`, só com o CI verde
  no commit. Publica a imagem com a tag do SHA (depois do smoke test), migra com o job, troca a imagem
  da API e espera a revisão nova ficar pronta e a readiness responder `UP` pelo ingress. Produção só
  depois de homologação, atrás da aprovação do environment `producao` no GitHub. As actions são
  fixadas por SHA. Falha no primeiro job se faltar variável.
- **O apply da infraestrutura é manual**, feito pelo usuário com `what-if` antes (README). O workflow
  não tem permissão para criar nem apagar recursos.
- **Validação offline no CI** (job `Bicep`): `az bicep build`, `build-params`, `lint` (erros do
  `bicepconfig.json` falham) e formatação, sem login na Azure.

**Fora da etapa 1:** Blob Storage (fotos e uploads), Web PubSub (tempo real) e Communication Services
(e-mail). A etapa 1 não tem foto, sala nem convite; cada um entra com a funcionalidade que o pede.
Static Web Apps fica no repositório `duora-web`.

**Atualização 2026-10-08 (ADR 0021):** o Web PubSub deixou de ser um recurso planejado do primeiro
deploy. O tempo real começa por polling curto e depois SSE no próprio Spring, sem recurso novo na Azure
([ADR 0021](0021-chat-temporario-e-reconexao.md)). Ele só entra, com Bicep e papel RBAC, se o k6 mostrar
necessidade. O custo a acompanhar passa a ser a réplica ativa enquanto houver aba aberta e as requisições
do polling na cota grátis do Container Apps.

O motivo técnico é ter os dois ambientes recriáveis a partir do Git, sem segredo de longa duração
fora do Key Vault e com a API sem privilégio de DDL. O motivo de negócio é o custo: o menor tier que
roda o fluxo inteiro, com orçamento e teto de logs, até o piloto medir carga.

## Consequências

### Custo mensal estimado

Preços de varejo em **US$** para **Brazil South** (região original, revista em 2026-10-06; a
estimativa vigente está na revisão no fim), consultados em **2026-10-05** na
[Azure Retail Prices API](https://prices.azure.com/api/retail/prices) (a mesma base da
[calculadora](https://azure.microsoft.com/pricing/calculator/)), com 730 horas no mês. Sem impostos,
sem descontos e sem o crédito gratuito de conta nova. **Pendente com o usuário: aprovar custo antes do
primeiro apply.**

| Recurso | Preço consultado | Homologação | Produção |
|---|---|---|---|
| PostgreSQL B1ms | US$ 0,035/h | 25,55 | 25,55 |
| Disco do PostgreSQL, 32 GB | US$ 0,2185/GB-mês | 6,99 | 6,99 |
| Backup do PostgreSQL | US$ 0,095/GB-mês acima do tamanho provisionado | 0 | 0 |
| Container Apps, API | vCPU ativa US$ 0,000024/s, ociosa US$ 0,000003/s; memória US$ 0,000003/GiB-s; cota grátis de 180 mil vCPU-s e 360 mil GiB-s por assinatura | ~0 (escala a zero) | 22 (ociosa o mês todo) a 73 (ativa o mês todo) |
| Job de migração | mesmo preço, segundos por deploy | ~0 | ~0 |
| Log Analytics | 5 GB/mês grátis por conta de cobrança; US$ 4,60/GB acima | 0 (teto 0,2 GB/dia) | 0 a 46 (teto 0,5 GB/dia) |
| Zona DNS privada | US$ 0,50/zona | 0,50 | 0,50 |
| Key Vault, alertas, VNet, Application Insights sem ingestão | centavos | < 1 | < 1 |
| **Subtotal** | | **~34** | **~56 a 153** |
| ACR Basic (compartilhado) | US$ 0,1666/dia | | **~5** |

**Total estimado: cerca de US$ 95 a 145 por mês** no uso esperado (API de produção ociosa a maior
parte do tempo e logs dentro da cota grátis). O teto teórico, com a API de produção sempre ativa e os
tetos de log dos dois ambientes estourados todo dia (21 GB, 16 acima da cota), fica perto de
US$ 220. O que mais pesa é o PostgreSQL e a réplica sempre ligada de produção.

- O backup do PostgreSQL não é cobrado até o tamanho do disco provisionado
  ([documentação](https://learn.microsoft.com/azure/postgresql/backup-restore/concepts-backup-restore#backup-storage-cost)).
  O crescimento automático do disco dobra tamanho e preço a cada passo (32 → 64 GB).
- Uma conta gratuita da Azure inclui, por 12 meses, 750 horas de B1ms e 32 GB de disco por mês; se a
  assinatura tiver esse benefício, um dos dois bancos sai de graça no período.

- O plano (§6) cita B2s como hipótese; B2s custa US$ 0,14/h, ou **US$ 76,65 a mais por mês por
  ambiente**. Fica B1ms (1 vCore com crédito de burst, 2 GiB, até ~50 conexões) até a medição pedir
  mais; a troca é um parâmetro e um reinício de minutos.
- Homologação pode parar o banco quando não estiver em uso
  (`az postgres flexible-server stop`; volta sozinho depois de 7 dias), economizando os US$ 25,55 do
  compute nesse período.
- O orçamento do Bicep (US$ 50 em homologação e US$ 150 em produção) está em unidades da moeda de
  cobrança da assinatura; se ela for real, o valor precisa mudar. (Revisto em 2026-10-06: R$ 55 em
  homologação e R$ 800 em produção; ver a revisão no fim.)
- Itens cobrados só quando ligados, e deixados de fora: private endpoint do Key Vault e do ambiente
  (o "Environment Private Endpoint" e o planned maintenance do Container Apps custam US$ 0,20/h cada),
  HA do banco, perfil Dedicated.

### Outras consequências

- A API ganha duas rotas públicas (`/actuator/health/liveness` e `/readiness`), que só dizem
  `UP`/`DOWN`. `/actuator/health/**` fora delas continua exigindo credencial (`DenyByDefaultIT`).
- O driver do PostgreSQL passa de `runtime` a `compile` no `pom.xml`, para o job usar
  `PGConnection.alterUserPassword`. O pacote `migration` entra como infraestrutura na classificação da
  [ADR 0007](0007-estilo-por-modulo.md) (`ArchitectureTest`).
- O primeiro apply de cada ambiente é em duas etapas (`DUORA_DEPLOY_API=false`, job, apply completo):
  a API com Flyway desligado não sobe num banco vazio.
- Reaplicar o Bicep depois do primeiro deploy pede a tag da imagem em uso
  (`DUORA_API_IMAGE_TAG`), senão o apply volta a API para a tag informada. O README mostra como ler a
  atual.
- Os segredos passam pelo apply como parâmetros seguros (lidos de variáveis de ambiente no
  `.bicepparam`) e ficam no Key Vault; o Container Apps relê a versão nova em até 30 minutos. O
  segredo do Entra (180 dias) precisa de renovação com data marcada.
- O ingress usa o domínio padrão `*.azurecontainerapps.io` até existir `api.<domínio>` (plano §6).
- Os logs HTTP do Container Apps guardam o `X-Forwarded-For` (IP é dado pessoal): retenção de 30
  dias no Log Analytics, registrada no inventário de tratamento da LGPD.
- O rollback da API é reativar a revisão anterior ou implantar a imagem anterior; o banco não volta
  sozinho, então cada migration precisa ser compatível com a revisão anterior (plano §9).

### Pendências com o usuário

1. **Aprovar o custo** acima antes do primeiro apply (decisão crítica).
2. ~~Confirmar **Brazil South** e a residência dos dados: banco, logs e backups ficam no Brasil; o
   tenant do Entra está nos EUA (ADR 0001).~~ **Revista em 2026-10-06:** região North Central US e
   dados nos EUA (ver a revisão no fim). Continua valendo confirmar no primeiro `what-if` que o
   PostgreSQL 18 está disponível na região.
3. Criar o segredo do cliente web por ambiente (ou registros separados para homologação, como pede o
   plano §6) e registrar os redirect URIs do FQDN de cada ambiente no tenant.
4. Domínio próprio para API e front no mesmo site, antes do login web valer fora de homologação (o
   cookie `SameSite=Lax` da ADR 0002 depende disso).
5. Verificar no primeiro deploy a faixa de `DUORA_TRUSTED_PROXIES`, o TLS `verify-full` e se o papel
   `Duora deployer` cobre todos os comandos do `deploy.sh` (a lista de actions foi montada pela
   documentação, sem teste contra a Azure; um `AuthorizationFailed` diz qual action falta).
6. Avaliar a autenticação do banco pelo Entra com identidade gerenciada, que elimina as duas senhas.
7. Ligar a telemetria (`DUORA_ENABLE_OPENTELEMETRY=true`) depois que a API exportar OTLP; até lá o
   Application Insights existe, mas não recebe nada.

## Compliance

- Job `Bicep` do CI (`infra/azure/validate.sh`): compila os templates e os três `.bicepparam`, roda
  o linter com as regras de segredo como erro (`secure-secrets-in-params`,
  `outputs-should-not-contain-secrets`, `use-secure-value-for-secure-inputs`) e confere a formatação.
- `HealthProbesIT`: liveness e readiness respondem `200` sem credencial e só com o estado; a
  readiness inclui o banco e a liveness não.
- `DenyByDefaultIT`: o resto do `/actuator/health/**` continua fechado.
- `DatabaseMigrationIT`, contra PostgreSQL 18 real com um migrador sem superusuário (como na Azure):
  aplica todas as migrations; o papel da API lê e escreve todas as tabelas da aplicação, não cria nem
  apaga tabela e não lê o histórico do Flyway; rodar de novo com outra senha a troca.
- `DatabaseMigrationSettingsTest`: o job não começa sem variável ou com ela em branco, só aceita nome
  de papel simples (vai em DDL) e não imprime senha.
- `infra/docker/smoke-test.sh` (CI e workflow de deploy): roda o job com a credencial de
  administração, sobe a API com o papel restrito e Flyway desligado, confere health, as duas probes,
  usuário sem root e graceful shutdown.
- `infra/azure/deploy.sh`: o deploy falha se a migração não terminar em `Succeeded`, se a revisão nova
  não ficar pronta ou se a readiness pelo ingress não responder `UP`.

## Revisão de 2026-10-06

Decisões do usuário, que revisam a região e o escopo desta ADR. O histórico acima fica como estava;
onde conflita com esta seção, vale esta.

- **Assinatura:** passa a ser **Azure for Students**, com US$ 100 de crédito válidos até 2027-09-02.
  Quando o crédito ou o prazo acabam, os recursos param e não há cobrança. Moeda de cobrança: real.
- **Região: North Central US (`northcentralus`)**, substituindo Brazil South. A política da assinatura
  só permite `southafricanorth`, `northcentralus`, `mexicocentral`, `canadacentral` e `italynorth`
  (não há região no Brasil). Motivo da escolha: latência a partir do Brasil e maturidade dos serviços
  usados (Container Apps, PostgreSQL Flexible Server, Key Vault). O Bicep não fixa região: tudo
  herda a do resource group (`az group create --location northcentralus`).
- **LGPD:** banco, logs e backups passam a ficar nos EUA, uma transferência internacional de dados
  pessoais (LGPD, arts. 33 a 36), como já ocorre com o tenant do Entra External ID (ADR 0001).
  Entra no inventário de tratamento e na política de privacidade do piloto, com a base legal da
  transferência a definir antes de haver usuários reais.
- **Só homologação por enquanto.** O primeiro apply cria `duora-shared` e `duora-hml`. Produção
  (`prod.bicepparam`, `duora-prod`, o job `deploy-producao` do `deploy.yml`) continua no repositório,
  sem apply, até haver usuários reais. Nada de produção foi apagado; a região de produção, quando
  for aplicada, também é a do resource group. O environment `producao` do GitHub não precisa existir
  até lá.
- **Orçamento de homologação: US$ 10 por mês.** O recurso `Microsoft.Consumption/budgets` usa a
  moeda de cobrança da assinatura (real), então o Bicep registra **R$ 55** (cerca de US$ 10).
  `prod.bicepparam` passa de 150 para **R$ 800** (cerca de US$ 150) pelo mesmo motivo: 150 em reais
  seria só cerca de US$ 27. O câmbio é aproximado; reveja os valores se ele mudar muito. Não
  verifiquei se a oferta Azure for Students aceita orçamentos no Cost Management; se não aceitar, o
  apply desse recurso falha e o controle passa a ser o saldo do crédito.
- **Nova estimativa de custo (homologação, US$/mês, sem produção):** cerca de **US$ 5 a 15**.
  - PostgreSQL B1ms: com Azure for Students, 750 h/mês de B1ms, 32 GB de disco e 32 GB de backup
    ficam gratuitos por 12 meses
    ([fonte](https://learn.microsoft.com/azure/postgresql/configure-maintain/how-to-deploy-on-azure-free-account)),
    então os US$ 25,55 + US$ 6,99 da tabela acima saem do cálculo no período. Depois dos 12 meses,
    voltam (cerca de US$ 32).
  - Container Apps: a cota mensal gratuita (180 mil vCPU-s e 360 mil GiB-s) cobre a API que escala a
    zero.
  - Log Analytics: 5 GB/mês gratuitos, com teto diário de 0,2 GB (6 GB/mês no máximo).
  - ACR Basic: cerca de **US$ 5**, o item que mais pesa. Zona DNS privada e o resto somam centavos.
  - Os preços da tabela de custo acima eram de Brazil South; a região nova pode diferir, e a
    estimativa não foi reconsultada na Retail Prices API.
- **PostgreSQL em homologação dentro do free tier:** `postgresStorageSizeGB = 32` (limite gratuito de
  32 GB) e retenção de backup de 7 dias. O backup não é cobrado até o tamanho provisionado, então os
  7 dias cabem nos 32 GB enquanto o banco for pequeno. O `autoGrow` continua ligado, porque disco
  cheio deixa o banco só leitura; se o banco passar de 32 GB, ele sobe para 64 GB e sai do free tier.
  Só um servidor B1ms cabe nas 750 h gratuitas: produção, quando aplicada, paga o dela.
- **Risco: a assinatura pode morar no tenant da universidade.** Azure for Students costuma ser
  vinculada ao diretório da instituição. Se o vínculo acabar (formatura, fim do e-mail acadêmico), a
  assinatura e os recursos podem ser perdidos sem aviso, e o administrador do tenant da universidade
  pode ter políticas e visibilidade sobre eles. Mitigação: tudo é recriável a partir do repositório
  (Bicep), os segredos têm cópia local, e o piloto é só homologação. Antes de produção, migrar para
  uma assinatura própria (pay-as-you-go) fora do tenant da universidade.
