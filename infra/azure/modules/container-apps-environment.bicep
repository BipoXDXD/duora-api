// Ambiente do Container Apps com perfil de workload Consumption na VNet própria, ingress externo e
// logs no Log Analytics. Um ambiente por ambiente de deploy (homologação e produção não dividem).

param namePrefix string

param location string

param infrastructureSubnetId string

param logAnalyticsWorkspaceName string

resource workspace 'Microsoft.OperationalInsights/workspaces@2025-07-01' existing = {
  name: logAnalyticsWorkspaceName
}

resource environment 'Microsoft.App/managedEnvironments@2025-07-01' = {
  name: 'cae-${namePrefix}'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: workspace.properties.customerId
        sharedKey: workspace.listKeys().primarySharedKey
      }
    }
    vnetConfiguration: {
      infrastructureSubnetId: infrastructureSubnetId
      // Ingress público (HTTPS); o banco continua só na rede privada.
      internal: false
    }
    workloadProfiles: [
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
    zoneRedundant: false
  }
}

output environmentId string = environment.id
output environmentName string = environment.name
