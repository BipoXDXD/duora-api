// Ambiente do Container Apps com perfil de workload Consumption na VNet própria, ingress externo e
// logs no Log Analytics. Um ambiente por ambiente de deploy (homologação e produção não dividem).

param namePrefix string

param location string

param infrastructureSubnetId string

param logAnalyticsWorkspaceName string

@description('Liga o agente OpenTelemetry gerenciado do ambiente, com destino Application Insights.')
param enableOpenTelemetry bool

param appInsightsName string

resource workspace 'Microsoft.OperationalInsights/workspaces@2025-07-01' existing = {
  name: logAnalyticsWorkspaceName
}

resource appInsights 'Microsoft.Insights/components@2020-02-02' existing = {
  name: appInsightsName
}

// Versão preview de propósito: appInsightsConfiguration e openTelemetryConfiguration (agente
// OpenTelemetry gerenciado) ainda não existem nas versões GA, nem na 2026-01-01.
#disable-next-line use-recent-api-versions
resource environment 'Microsoft.App/managedEnvironments@2025-10-02-preview' = {
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
    // Agente gerenciado: o ambiente injeta OTEL_EXPORTER_OTLP_ENDPOINT nas réplicas e repassa traces e
    // logs OTLP ao Application Insights (docs/adr/0014). Desligado, nada é coletado nem cobrado.
    appInsightsConfiguration: enableOpenTelemetry
      ? {
          connectionString: appInsights.properties.ConnectionString
        }
      : null
    openTelemetryConfiguration: enableOpenTelemetry
      ? {
          tracesConfiguration: {
            destinations: ['appInsights']
          }
          logsConfiguration: {
            destinations: ['appInsights']
          }
        }
      : null
  }
}

output environmentId string = environment.id
output environmentName string = environment.name
