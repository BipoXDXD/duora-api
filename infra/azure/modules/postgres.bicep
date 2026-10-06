// PostgreSQL Flexible Server 18 com acesso privado (VNet), sem IP público e sem HA no piloto.
// Autenticação por senha: o login de administração só é usado pelo job de migração; a aplicação
// conecta com um papel restrito que o próprio job cria (DatabaseMigration, docs/adr/0011).

param namePrefix string

param location string

@description('SKU Burstable, como Standard_B1ms.')
param skuName string

@minValue(32)
param storageSizeGB int

@minValue(7)
@maxValue(35)
param backupRetentionDays int

param delegatedSubnetId string

param privateDnsZoneId string

param administratorLogin string

@secure()
param administratorPassword string

@description('Nome do banco da aplicação.')
param databaseName string

resource server 'Microsoft.DBforPostgreSQL/flexibleServers@2025-08-01' = {
  name: 'psql-${namePrefix}-${uniqueString(resourceGroup().id)}'
  location: location
  sku: {
    name: skuName
    tier: 'Burstable'
  }
  properties: {
    version: '18'
    administratorLogin: administratorLogin
    administratorLoginPassword: administratorPassword
    authConfig: {
      activeDirectoryAuth: 'Disabled'
      passwordAuth: 'Enabled'
    }
    storage: {
      storageSizeGB: storageSizeGB
      // Disco cheio deixa o banco só leitura; crescer custa por GB, e o alerta de orçamento avisa.
      autoGrow: 'Enabled'
    }
    backup: {
      backupRetentionDays: backupRetentionDays
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: {
      mode: 'Disabled'
    }
    network: {
      delegatedSubnetResourceId: delegatedSubnetId
      privateDnsZoneArmResourceId: privateDnsZoneId
      publicNetworkAccess: 'Disabled'
    }
  }
}

resource database 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2025-08-01' = {
  parent: server
  name: databaseName
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}

// Nenhuma transação fica parada segurando locks e conexão (regra SQL 13): 60 s bastam para a API.
resource idleInTransactionTimeout 'Microsoft.DBforPostgreSQL/flexibleServers/configurations@2025-08-01' = {
  parent: server
  // O servidor recusa duas operações ao mesmo tempo; o banco é criado antes.
  dependsOn: [database]
  name: 'idle_in_transaction_session_timeout'
  properties: {
    value: '60000'
    source: 'user-override'
  }
}

output serverName string = server.name
output fullyQualifiedDomainName string = server.properties.fullyQualifiedDomainName
output databaseName string = database.name
