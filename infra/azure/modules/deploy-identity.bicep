// Identidade que o GitHub Actions assume por OIDC para implantar neste ambiente: credencial federada
// só para o environment do GitHub correspondente, sem segredo de longa duração. Pode trocar a imagem
// do app e do job e disparar o job; não lê o Key Vault nem mexe no resto do resource group.

param namePrefix string

param location string

@description('Repositório no formato dono/nome.')
param githubRepository string

@description('Environment do GitHub cujo token esta identidade aceita (homologacao ou producao).')
param githubEnvironment string

param environmentName string

param migrationJobName string

@description('Vazio quando o app ainda não existe (primeiro apply em duas etapas).')
param apiAppName string

// Contributor só nestes três recursos: atualizar o app exige permissão de "join" no ambiente.
var contributorRoleId = 'b24988ac-6180-42a0-ab88-20f7382dd24c'
var contributorRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', contributorRoleId)

resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2024-11-30' = {
  name: 'id-${namePrefix}-deploy'
  location: location
}

resource githubFederation 'Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials@2024-11-30' = {
  parent: identity
  name: 'github-${githubEnvironment}'
  properties: {
    issuer: 'https://token.actions.githubusercontent.com'
    subject: 'repo:${githubRepository}:environment:${githubEnvironment}'
    audiences: ['api://AzureADTokenExchange']
  }
}

resource environment 'Microsoft.App/managedEnvironments@2025-07-01' existing = {
  name: environmentName
}

resource migrationJob 'Microsoft.App/jobs@2025-07-01' existing = {
  name: migrationJobName
}

resource api 'Microsoft.App/containerApps@2025-07-01' existing = if (!empty(apiAppName)) {
  name: apiAppName
}

resource joinsEnvironment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(environment.id, identity.id, contributorRoleId)
  scope: environment
  properties: {
    roleDefinitionId: contributorRole
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource updatesMigrationJob 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(migrationJob.id, identity.id, contributorRoleId)
  scope: migrationJob
  properties: {
    roleDefinitionId: contributorRole
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource updatesApi 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(apiAppName)) {
  name: guid(api.id, identity.id, contributorRoleId)
  scope: api
  properties: {
    roleDefinitionId: contributorRole
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

output clientId string = identity.properties.clientId
