// Identidade que o GitHub Actions assume por OIDC para implantar neste ambiente: credencial federada
// só para o environment do GitHub correspondente, sem segredo de longa duração. Um papel próprio, no
// resource group do ambiente, permite trocar a imagem do app e do job e disparar o job; não permite
// criar nem apagar recursos, nem ler o Key Vault.

param namePrefix string

param location string

@description('Repositório no formato dono/nome.')
param githubRepository string

@description('Environment do GitHub cujo token esta identidade aceita (homologacao ou producao).')
param githubEnvironment string

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

// O que infra/azure/deploy.sh usa. "join" no ambiente e "assign" nas identidades são exigidos pelo
// Azure ao reenviar app e job que usam o ambiente e identidades gerenciadas; listSecrets, porque o
// az containerapp update relê a configuração de segredos (aqui, só referências ao Key Vault).
resource deployerRole 'Microsoft.Authorization/roleDefinitions@2022-04-01' = {
  name: guid(resourceGroup().id, 'duora-deployer')
  properties: {
    roleName: 'Duora deployer (${resourceGroup().name})'
    description: 'Troca a imagem da API e do job de migração e dispara o job; sem criar nem apagar recursos.'
    type: 'CustomRole'
    assignableScopes: [resourceGroup().id]
    permissions: [
      {
        actions: [
          'Microsoft.Resources/subscriptions/resourceGroups/read'
          'Microsoft.App/managedEnvironments/read'
          'Microsoft.App/managedEnvironments/join/action'
          'Microsoft.App/containerApps/read'
          'Microsoft.App/containerApps/write'
          'Microsoft.App/containerApps/listSecrets/action'
          'Microsoft.App/containerApps/revisions/read'
          'Microsoft.App/jobs/read'
          'Microsoft.App/jobs/write'
          'Microsoft.App/jobs/start/action'
          'Microsoft.App/jobs/listSecrets/action'
          'Microsoft.App/jobs/executions/read'
          'Microsoft.ManagedIdentity/userAssignedIdentities/read'
          'Microsoft.ManagedIdentity/userAssignedIdentities/assign/action'
        ]
        notActions: []
      }
    ]
  }
}

resource deployerAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(resourceGroup().id, identity.id, deployerRole.id)
  properties: {
    roleDefinitionId: deployerRole.id
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

output clientId string = identity.properties.clientId
