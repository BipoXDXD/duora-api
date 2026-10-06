// Container Apps Job que migra o banco antes de cada revisão nova (plano §9, docs/adr/0011). Mesma
// imagem da API, outro processo: DatabaseMigration roda o Flyway com a credencial de administração e
// ajusta o papel restrito da aplicação. Disparo manual, pelo workflow de deploy.

import { vaultEntryNames } from 'key-vault-names.bicep'

param namePrefix string

param location string

param environmentId string

param image string

param registryLoginServer string

@description('Identidade gerenciada do job: puxa a imagem e lê as senhas do banco.')
param identityId string

@description('URI do Key Vault, terminada em barra.')
param vaultUri string

@description('URL JDBC do banco, com TLS verificado.')
param jdbcUrl string

param administratorLogin string

param appDatabaseRole string

resource job 'Microsoft.App/jobs@2025-07-01' = {
  name: 'caj-${namePrefix}-migrate'
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identityId}': {}
    }
  }
  properties: {
    environmentId: environmentId
    workloadProfileName: 'Consumption'
    configuration: {
      triggerType: 'Manual'
      manualTriggerConfig: {
        parallelism: 1
        replicaCompletionCount: 1
      }
      // Migrar é idempotente (Flyway com lock e histórico); uma nova tentativa cobre interrupção da plataforma.
      replicaRetryLimit: 1
      replicaTimeout: 600
      registries: [
        {
          server: registryLoginServer
          identity: identityId
        }
      ]
      secrets: [
        {
          name: vaultEntryNames.postgresAdmin
          keyVaultUrl: '${vaultUri}secrets/${vaultEntryNames.postgresAdmin}'
          identity: identityId
        }
        {
          name: vaultEntryNames.postgresApp
          keyVaultUrl: '${vaultUri}secrets/${vaultEntryNames.postgresApp}'
          identity: identityId
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'migrate'
          image: image
          // Substitui o ENTRYPOINT da imagem; o diretório de trabalho continua /app.
          command: ['java']
          args: [
            '-XX:MaxRAMPercentage=75'
            '-XX:+ExitOnOutOfMemoryError'
            '-cp'
            'app.jar'
            'bipo.tech.duoraapi.migration.DatabaseMigration'
          ]
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: [
            { name: 'DUORA_MIGRATION_JDBC_URL', value: jdbcUrl }
            { name: 'DUORA_MIGRATION_USERNAME', value: administratorLogin }
            { name: 'DUORA_MIGRATION_PASSWORD', secretRef: vaultEntryNames.postgresAdmin }
            { name: 'DUORA_APP_DB_USERNAME', value: appDatabaseRole }
            { name: 'DUORA_APP_DB_PASSWORD', secretRef: vaultEntryNames.postgresApp }
          ]
        }
      ]
    }
  }
}

output jobName string = job.name
output jobId string = job.id
