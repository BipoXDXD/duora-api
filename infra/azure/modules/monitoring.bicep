// Logs do ambiente (stdout das réplicas e dos jobs) e Application Insights apoiado no mesmo workspace.
// O teto diário de ingestão limita o custo: passado o teto, a coleta para até o dia seguinte.

param namePrefix string

param location string

@description('Dias de retenção. Até 31 dias a retenção do workspace não é cobrada.')
@minValue(30)
@maxValue(730)
param retentionInDays int = 30

@description('Teto diário de ingestão em GB (-1 desliga o teto).')
param dailyQuotaGb string

resource workspace 'Microsoft.OperationalInsights/workspaces@2025-07-01' = {
  name: 'log-${namePrefix}'
  location: location
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: retentionInDays
    workspaceCapping: {
      dailyQuotaGb: json(dailyQuotaGb)
    }
  }
}

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: 'appi-${namePrefix}'
  location: location
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: workspace.id
    IngestionMode: 'LogAnalytics'
    // Sem a chave de instrumentação local: só ingestão autenticada, quando o agente entrar.
    DisableLocalAuth: true
  }
}

output workspaceName string = workspace.name
output appInsightsName string = appInsights.name
