// Produção do piloto: plano §6 (1 vCPU, 2 GiB, 1 a 3 réplicas; mínimo de uma réplica para as tarefas
// internas). Banco no menor Burstable até a medição pedir mais (docs/adr/0011). Valores secretos e os
// que só existem depois do primeiro apply vêm de variáveis de ambiente (infra/azure/README.md).
using 'main.bicep'

param environmentName = 'prod'
param deployApi = bool(readEnvironmentVariable('DUORA_DEPLOY_API', 'true'))

param registryName = readEnvironmentVariable('DUORA_REGISTRY_NAME')
param registryResourceGroup = 'duora-shared'
param apiImageTag = readEnvironmentVariable('DUORA_API_IMAGE_TAG')

param vnetAddressPrefix = '10.20.0.0/16'
param containerAppsSubnetPrefix = '10.20.0.0/24'
param postgresSubnetPrefix = '10.20.1.0/24'

param postgresSkuName = 'Standard_B1ms'
param postgresStorageSizeGB = 32
param postgresBackupRetentionDays = 14
param postgresAdministratorLogin = 'duora_admin'
param appDatabaseRole = 'duora_app'
param postgresAdminPassword = readEnvironmentVariable('DUORA_PG_ADMIN_PASSWORD')
param postgresAppPassword = readEnvironmentVariable('DUORA_PG_APP_PASSWORD')

param apiCpu = '1.0'
param apiMemory = '2Gi'
param apiMinReplicas = 1
param apiMaxReplicas = 3

// Tenant duoraapp (docs/adr/0001 e 0002): identificadores públicos, não segredos.
param auth = {
  issuerUri: 'https://d3e03557-0f3b-4474-9586-7ad43938423d.ciamlogin.com/d3e03557-0f3b-4474-9586-7ad43938423d/v2.0'
  jwkSetUri: 'https://duoraapp.ciamlogin.com/d3e03557-0f3b-4474-9586-7ad43938423d/discovery/v2.0/keys'
  audience: 'a1034ddf-f390-4d2c-829b-f6b821b31f5d'
  authority: 'https://duoraapp.ciamlogin.com/d3e03557-0f3b-4474-9586-7ad43938423d'
  webClientId: '4118e7b8-b42a-4be4-aad8-b08a2aaef652'
}
param entraWebClientSecret = readEnvironmentVariable('DUORA_ENTRA_WEB_CLIENT_SECRET')

param keyVaultPurgeProtection = true
param logDailyQuotaGb = '0.5'

param alertEmail = readEnvironmentVariable('DUORA_ALERT_EMAIL')
// Na moeda de cobrança da assinatura: ajuste se não for dólar (docs/adr/0011, custos).
param monthlyBudget = 150
param budgetStartDate = '2026-10-01'

param githubRepository = 'BipoXDXD/duora-api'
param githubEnvironment = 'producao'
