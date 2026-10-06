// Um ambiente do Duora (homologação ou produção) num resource group próprio: rede, banco privado,
// Key Vault, logs, ambiente do Container Apps, API, job de migração, identidade de deploy do GitHub e
// alertas. A imagem vem do registry compartilhado (shared.bicep). Decisões em docs/adr/0014;
// passo a passo em infra/azure/README.md.

targetScope = 'resourceGroup'

@description('Nome curto do ambiente, usado em todos os nomes.')
@allowed(['hml', 'prod'])
param environmentName string

param location string = resourceGroup().location

@description('false só na primeira etapa do primeiro apply: cria tudo menos a API, para o job migrar o banco antes.')
param deployApi bool = true

// --- Imagem e registry ---

@description('Registry compartilhado (shared.bicep).')
param registryName string

param registryResourceGroup string

@description('Repositório e tag da imagem no registry, como duora-api:<sha do commit>.')
param apiImageTag string

// --- Rede ---

param vnetAddressPrefix string

@description('Subnet do Container Apps; também é a faixa de proxies confiáveis da API.')
param containerAppsSubnetPrefix string

param postgresSubnetPrefix string

// --- Banco ---

param postgresSkuName string

param postgresStorageSizeGB int

param postgresBackupRetentionDays int

@description('Login de administração do servidor, usado só pelo job de migração.')
param postgresAdministratorLogin string

@description('Papel restrito com que a API conecta; o job de migração o cria e ajusta.')
param appDatabaseRole string

@secure()
param postgresAdminPassword string

@secure()
param postgresAppPassword string

// --- API ---

param apiCpu string

param apiMemory string

param apiMinReplicas int

param apiMaxReplicas int

param auth {
  issuerUri: string
  jwkSetUri: string
  audience: string
  authority: string
  webClientId: string
}

@secure()
param entraWebClientSecret string

// --- Operação ---

param keyVaultPurgeProtection bool

param logDailyQuotaGb string

@description('Liga o agente OpenTelemetry gerenciado do Container Apps (traces e logs OTLP para o Application Insights).')
param enableOpenTelemetry bool = false

param alertEmail string

param monthlyBudget int

param budgetStartDate string

// --- GitHub ---

param githubRepository string

param githubEnvironment string

var namePrefix = 'duora-${environmentName}'
var databaseName = 'duora'

resource apiIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2024-11-30' = {
  name: 'id-${namePrefix}-api'
  location: location
}

resource migratorIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2024-11-30' = {
  name: 'id-${namePrefix}-migrator'
  location: location
}

module network 'modules/network.bicep' = {
  name: 'network'
  params: {
    namePrefix: namePrefix
    location: location
    addressPrefix: vnetAddressPrefix
    containerAppsSubnetPrefix: containerAppsSubnetPrefix
    postgresSubnetPrefix: postgresSubnetPrefix
  }
}

module monitoring 'modules/monitoring.bicep' = {
  name: 'monitoring'
  params: {
    namePrefix: namePrefix
    location: location
    dailyQuotaGb: logDailyQuotaGb
    enableOpenTelemetry: enableOpenTelemetry
  }
}

module keyVault 'modules/key-vault.bicep' = {
  name: 'key-vault'
  params: {
    namePrefix: namePrefix
    location: location
    enablePurgeProtection: keyVaultPurgeProtection
    postgresAdminPassword: postgresAdminPassword
    postgresAppPassword: postgresAppPassword
    entraWebClientSecret: entraWebClientSecret
    apiPrincipalId: apiIdentity.properties.principalId
    migratorPrincipalId: migratorIdentity.properties.principalId
  }
}

module postgres 'modules/postgres.bicep' = {
  name: 'postgres'
  params: {
    namePrefix: namePrefix
    location: location
    skuName: postgresSkuName
    storageSizeGB: postgresStorageSizeGB
    backupRetentionDays: postgresBackupRetentionDays
    delegatedSubnetId: network.outputs.postgresSubnetId
    privateDnsZoneId: network.outputs.postgresDnsZoneId
    administratorLogin: postgresAdministratorLogin
    administratorPassword: postgresAdminPassword
    databaseName: databaseName
  }
}

// verify-full confere certificado e hostname. As raízes do Azure (DigiCert Global Root G2 e Microsoft
// RSA Root 2017) já estão no cacerts do JRE, que o DefaultJavaSSLFactory usa; o nome público resolve
// para o IP privado pela zona DNS da VNet, e o certificado vale para ele.
var jdbcUrl = 'jdbc:postgresql://${postgres.outputs.fullyQualifiedDomainName}:5432/${postgres.outputs.databaseName}?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory'

module registryPull 'modules/registry-pull.bicep' = {
  name: 'registry-pull-${environmentName}'
  scope: resourceGroup(registryResourceGroup)
  params: {
    registryName: registryName
    principalIds: [
      apiIdentity.properties.principalId
      migratorIdentity.properties.principalId
    ]
  }
}

module containerAppsEnvironment 'modules/container-apps-environment.bicep' = {
  name: 'container-apps-environment'
  params: {
    namePrefix: namePrefix
    location: location
    infrastructureSubnetId: network.outputs.containerAppsSubnetId
    logAnalyticsWorkspaceName: monitoring.outputs.workspaceName
    enableOpenTelemetry: enableOpenTelemetry
    appInsightsName: monitoring.outputs.appInsightsName
  }
}

module migrationJob 'modules/migration-job.bicep' = {
  name: 'migration-job'
  params: {
    namePrefix: namePrefix
    location: location
    environmentId: containerAppsEnvironment.outputs.environmentId
    image: '${registryPull.outputs.loginServer}/${apiImageTag}'
    registryLoginServer: registryPull.outputs.loginServer
    identityId: migratorIdentity.id
    jdbcUrl: jdbcUrl
    administratorLogin: postgresAdministratorLogin
    appDatabaseRole: appDatabaseRole
    vaultUri: keyVault.outputs.vaultUri
  }
}

module api 'modules/api-app.bicep' = if (deployApi) {
  name: 'api'
  params: {
    namePrefix: namePrefix
    location: location
    environmentId: containerAppsEnvironment.outputs.environmentId
    image: '${registryPull.outputs.loginServer}/${apiImageTag}'
    registryLoginServer: registryPull.outputs.loginServer
    identityId: apiIdentity.id
    cpu: apiCpu
    memory: apiMemory
    minReplicas: apiMinReplicas
    maxReplicas: apiMaxReplicas
    // O ingress conecta à réplica a partir da subnet do ambiente (docs/adr/0006 e 0014).
    trustedProxies: containerAppsSubnetPrefix
    jdbcUrl: jdbcUrl
    appDatabaseRole: appDatabaseRole
    vaultUri: keyVault.outputs.vaultUri
    auth: auth
  }
}

module deployIdentity 'modules/deploy-identity.bicep' = {
  name: 'deploy-identity'
  params: {
    namePrefix: namePrefix
    location: location
    githubRepository: githubRepository
    githubEnvironment: githubEnvironment
  }
}

module alerts 'modules/alerts.bicep' = {
  name: 'alerts'
  params: {
    namePrefix: namePrefix
    alertEmail: alertEmail
    monthlyBudget: monthlyBudget
    budgetStartDate: budgetStartDate
    apiAppId: deployApi ? api!.outputs.appId : ''
  }
}

// Valores que o workflow de deploy lê como variáveis do environment do GitHub (README).
output resourceGroupName string = resourceGroup().name
output deployClientId string = deployIdentity.outputs.clientId
output apiAppName string = deployApi ? api!.outputs.appName : ''
output apiFqdn string = deployApi ? api!.outputs.fqdn : ''
output migrationJobName string = migrationJob.outputs.jobName
output keyVaultName string = keyVault.outputs.vaultName
output postgresServerName string = postgres.outputs.serverName
