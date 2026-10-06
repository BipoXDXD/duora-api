// Recursos compartilhados entre homologação e produção, num resource group próprio: o registry onde
// cada commit vira uma imagem, promovida igual entre os ambientes, e a identidade que o GitHub
// Actions assume por OIDC para publicar nele. Decisões em docs/adr/0011.

targetScope = 'resourceGroup'

param location string = resourceGroup().location

@description('Repositório no formato dono/nome.')
param githubRepository string

@description('Só esta branch publica imagens.')
param publishBranch string = 'main'

var acrPushRoleId = '8311e382-0749-4cb8-b61a-304f252e45ec'
// AcrPush só dá acesso às imagens; ler o recurso (az acr show, az acr login) pede Reader nele.
var readerRoleId = 'acdd72a7-3385-48ef-bd42-f606fba81ae7'

resource registry 'Microsoft.ContainerRegistry/registries@2025-04-01' = {
  // Só letras e números, único no Azure inteiro.
  name: 'crduora${uniqueString(resourceGroup().id)}'
  location: location
  sku: {
    name: 'Basic'
  }
  properties: {
    // Sem usuário e senha do registry: quem publica e quem puxa usa identidade do Entra.
    adminUserEnabled: false
    anonymousPullEnabled: false
    // AcrPull e AcrPush só valem no modo de permissões "RBAC Registry Permissions", o padrão; o modo
    // ABAC os ignora (a versão GA da API não expõe a escolha). O README pede a conferência.
    publicNetworkAccess: 'Enabled'
  }
}

resource publisher 'Microsoft.ManagedIdentity/userAssignedIdentities@2024-11-30' = {
  name: 'id-duora-publisher'
  location: location
}

resource publisherFederation 'Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials@2024-11-30' = {
  parent: publisher
  name: 'github-${publishBranch}'
  properties: {
    issuer: 'https://token.actions.githubusercontent.com'
    subject: 'repo:${githubRepository}:ref:refs/heads/${publishBranch}'
    audiences: ['api://AzureADTokenExchange']
  }
}

resource publisherPushes 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(registry.id, publisher.id, acrPushRoleId)
  scope: registry
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', acrPushRoleId)
    principalId: publisher.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource publisherReadsRegistry 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(registry.id, publisher.id, readerRoleId)
  scope: registry
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', readerRoleId)
    principalId: publisher.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

output registryName string = registry.name
output registryLoginServer string = registry.properties.loginServer
output publisherClientId string = publisher.properties.clientId
