// Homologação: o mínimo que roda o fluxo inteiro. A API escala a zero sem tráfego (a primeira
// requisição espera a JVM subir) e o banco é o menor Burstable. Valores secretos e os que só existem
// depois do primeiro apply vêm de variáveis de ambiente (infra/azure/README.md).
using 'main.bicep'

param environmentName = 'hml'
param deployApi = bool(readEnvironmentVariable('DUORA_DEPLOY_API', 'true'))

param registryName = readEnvironmentVariable('DUORA_REGISTRY_NAME')
param registryResourceGroup = 'duora-shared'
param apiImageTag = readEnvironmentVariable('DUORA_API_IMAGE_TAG')

param vnetAddressPrefix = '10.10.0.0/16'
param containerAppsSubnetPrefix = '10.10.0.0/24'
param postgresSubnetPrefix = '10.10.1.0/24'

param postgresSkuName = 'Standard_B1ms'
param postgresStorageSizeGB = 32
param postgresBackupRetentionDays = 7
param postgresAdministratorLogin = 'duora_admin'
param appDatabaseRole = 'duora_app'
param postgresAdminPassword = readEnvironmentVariable('DUORA_PG_ADMIN_PASSWORD')
param postgresAppPassword = readEnvironmentVariable('DUORA_PG_APP_PASSWORD')

param apiCpu = '0.5'
param apiMemory = '1Gi'
param apiMinReplicas = 0
param apiMaxReplicas = 1

// Tenant duoraapp (docs/adr/0001 e 0002): identificadores públicos, não segredos.
param auth = {
  issuerUri: 'https://d3e03557-0f3b-4474-9586-7ad43938423d.ciamlogin.com/d3e03557-0f3b-4474-9586-7ad43938423d/v2.0'
  jwkSetUri: 'https://duoraapp.ciamlogin.com/d3e03557-0f3b-4474-9586-7ad43938423d/discovery/v2.0/keys'
  audience: 'a1034ddf-f390-4d2c-829b-f6b821b31f5d'
  authority: 'https://duoraapp.ciamlogin.com/d3e03557-0f3b-4474-9586-7ad43938423d'
  webClientId: '4118e7b8-b42a-4be4-aad8-b08a2aaef652'
}
param entraWebClientSecret = readEnvironmentVariable('DUORA_ENTRA_WEB_CLIENT_SECRET')

// Sem proteção contra purga em homologação, para poder recriar o cofre com o mesmo nome.
param keyVaultPurgeProtection = false
// Telemetria OTLP para o Application Insights: desligada até a API exportar e o custo ser aprovado.
param enableOpenTelemetry = bool(readEnvironmentVariable('DUORA_ENABLE_OPENTELEMETRY', 'false'))
param logDailyQuotaGb = '0.2'

param alertEmail = readEnvironmentVariable('DUORA_ALERT_EMAIL')
// Na moeda de cobrança da assinatura: ajuste se não for dólar (docs/adr/0014, custos).
param monthlyBudget = 50
// Fixado no primeiro apply (primeiro dia daquele mês) e mantido: a API recusa início antigo demais.
param budgetStartDate = readEnvironmentVariable('DUORA_BUDGET_START_DATE')

param githubRepository = 'BipoXDXD/duora-api'
param githubEnvironment = 'homologacao'
