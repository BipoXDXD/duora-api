// Key Vault com RBAC: guarda as senhas do banco e o segredo do cliente web do Entra. Cada identidade
// recebe "Key Vault Secrets User" só nos segredos que usa, não no cofre inteiro.

import { vaultEntryNames } from 'key-vault-names.bicep'

param namePrefix string

param location string

@description('Proteção contra purga: irreversível depois de ligada; o nome fica preso por 90 dias após apagar.')
param enablePurgeProtection bool

@secure()
param postgresAdminPassword string

@secure()
param postgresAppPassword string

@secure()
param entraWebClientSecret string

@description('Identidade da API: lê a senha do papel da aplicação e o segredo do Entra.')
param apiPrincipalId string

@description('Identidade do job de migração: lê as duas senhas do banco.')
param migratorPrincipalId string

var keyVaultSecretsUserRoleId = '4633458b-17de-408a-b874-0445c86b69e6'
var keyVaultSecretsUserRole = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  keyVaultSecretsUserRoleId
)

resource vault 'Microsoft.KeyVault/vaults@2024-11-01' = {
  // 24 caracteres no máximo e nome único no Azure inteiro.
  name: take('kv-${namePrefix}-${uniqueString(resourceGroup().id)}', 24)
  location: location
  properties: {
    tenantId: subscription().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 90
    // A API só aceita true ou ausente.
    enablePurgeProtection: enablePurgeProtection ? true : null
    publicNetworkAccess: 'Enabled'
  }
}

resource postgresAdminPasswordSecret 'Microsoft.KeyVault/vaults/secrets@2024-11-01' = {
  parent: vault
  name: vaultEntryNames.postgresAdmin
  properties: {
    value: postgresAdminPassword
  }
}

resource postgresAppPasswordSecret 'Microsoft.KeyVault/vaults/secrets@2024-11-01' = {
  parent: vault
  name: vaultEntryNames.postgresApp
  properties: {
    value: postgresAppPassword
  }
}

resource entraWebClientSecretSecret 'Microsoft.KeyVault/vaults/secrets@2024-11-01' = {
  parent: vault
  name: vaultEntryNames.entraWebClient
  properties: {
    value: entraWebClientSecret
  }
}

resource apiReadsAppPassword 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(postgresAppPasswordSecret.id, apiPrincipalId, keyVaultSecretsUserRoleId)
  scope: postgresAppPasswordSecret
  properties: {
    roleDefinitionId: keyVaultSecretsUserRole
    principalId: apiPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource apiReadsEntraSecret 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(entraWebClientSecretSecret.id, apiPrincipalId, keyVaultSecretsUserRoleId)
  scope: entraWebClientSecretSecret
  properties: {
    roleDefinitionId: keyVaultSecretsUserRole
    principalId: apiPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource migratorReadsAdminPassword 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(postgresAdminPasswordSecret.id, migratorPrincipalId, keyVaultSecretsUserRoleId)
  scope: postgresAdminPasswordSecret
  properties: {
    roleDefinitionId: keyVaultSecretsUserRole
    principalId: migratorPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource migratorReadsAppPassword 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(postgresAppPasswordSecret.id, migratorPrincipalId, keyVaultSecretsUserRoleId)
  scope: postgresAppPasswordSecret
  properties: {
    roleDefinitionId: keyVaultSecretsUserRole
    principalId: migratorPrincipalId
    principalType: 'ServicePrincipal'
  }
}

output vaultName string = vault.name
// Quem referencia um segredo monta a URI sem versão: o Container Apps relê o valor (em até 30 min)
// quando ele muda.
output vaultUri string = vault.properties.vaultUri
