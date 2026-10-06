// Rede do ambiente: uma subnet para o Container Apps (perfil de workload, delegada) e outra para o
// PostgreSQL com acesso privado. O banco não tem IP público; o nome resolve pela zona DNS privada.

@description('Prefixo dos nomes, como duora-hml.')
param namePrefix string

param location string

@description('Faixa da VNet, como 10.20.0.0/16.')
param addressPrefix string

@description('Subnet do ambiente do Container Apps. Mínimo /27; não muda depois de criada.')
param containerAppsSubnetPrefix string

@description('Subnet delegada ao PostgreSQL Flexible Server.')
param postgresSubnetPrefix string

var containerAppsSubnetName = 'snet-container-apps'
var postgresSubnetName = 'snet-postgres'

resource vnet 'Microsoft.Network/virtualNetworks@2025-05-01' = {
  name: 'vnet-${namePrefix}'
  location: location
  properties: {
    addressSpace: {
      addressPrefixes: [addressPrefix]
    }
    subnets: [
      {
        name: containerAppsSubnetName
        properties: {
          addressPrefix: containerAppsSubnetPrefix
          delegations: [
            {
              name: 'container-apps'
              properties: {
                serviceName: 'Microsoft.App/environments'
              }
            }
          ]
        }
      }
      {
        name: postgresSubnetName
        properties: {
          addressPrefix: postgresSubnetPrefix
          delegations: [
            {
              name: 'postgres'
              properties: {
                serviceName: 'Microsoft.DBforPostgreSQL/flexibleServers'
              }
            }
          ]
        }
      }
    ]
  }
}

// O nome precisa terminar em .private.postgres.database.azure.com para o acesso privado do Flexible Server.
resource postgresDnsZone 'Microsoft.Network/privateDnsZones@2024-06-01' = {
  name: '${namePrefix}.private.postgres.database.azure.com'
  location: 'global'
}

resource postgresDnsZoneLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = {
  parent: postgresDnsZone
  name: 'vnet-${namePrefix}'
  location: 'global'
  properties: {
    registrationEnabled: false
    virtualNetwork: {
      id: vnet.id
    }
  }
}

output containerAppsSubnetId string = resourceId(
  'Microsoft.Network/virtualNetworks/subnets',
  vnet.name,
  containerAppsSubnetName
)
output postgresSubnetId string = resourceId('Microsoft.Network/virtualNetworks/subnets', vnet.name, postgresSubnetName)
// A saída de um módulo só fica disponível quando o módulo inteiro termina, link incluído.
output postgresDnsZoneId string = postgresDnsZone.id
