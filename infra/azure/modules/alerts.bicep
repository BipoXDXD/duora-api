// Alertas mínimos do piloto: orçamento do resource group (70%, 90% e 100%, plano §6) e réplica da
// API reiniciando demais (docs/adr/0008: um supervisor que reinicia demais esconde o problema).
// Orçamento avisa, não bloqueia consumo.

param namePrefix string

@description('E-mail que recebe os alertas.')
param alertEmail string

@description('Orçamento mensal do resource group, na moeda da assinatura.')
@minValue(1)
param monthlyBudget int

@description('Primeiro dia do mês em que o orçamento começa a valer (AAAA-MM-01).')
param budgetStartDate string

@description('Id do Container App da API; vazio enquanto ele não existe.')
param apiAppId string

resource actionGroup 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: 'ag-${namePrefix}'
  location: 'global'
  properties: {
    groupShortName: take(replace(namePrefix, '-', ''), 12)
    enabled: true
    emailReceivers: [
      {
        name: 'owner'
        emailAddress: alertEmail
        useCommonAlertSchema: true
      }
    ]
  }
}

resource budget 'Microsoft.Consumption/budgets@2026-06-01' = {
  name: 'budget-${namePrefix}'
  properties: {
    category: 'Cost'
    amount: monthlyBudget
    timeGrain: 'Monthly'
    timePeriod: {
      startDate: budgetStartDate
    }
    notifications: {
      actual70: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 70
        thresholdType: 'Actual'
        contactEmails: [alertEmail]
      }
      actual90: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 90
        thresholdType: 'Actual'
        contactEmails: [alertEmail]
      }
      actual100: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 100
        thresholdType: 'Actual'
        contactEmails: [alertEmail]
      }
    }
  }
}

resource frequentRestarts 'Microsoft.Insights/metricAlerts@2026-01-01' = if (!empty(apiAppId)) {
  name: 'alert-${namePrefix}-api-restarts'
  location: 'global'
  properties: {
    // RestartCount é acumulado por réplica desde a criação: o máximo passar de 3 indica uma réplica
    // presa em ciclo de reinício (liveness falhando, OOM ou queda). Somar (Total) contaria o mesmo
    // reinício a cada minuto da janela.
    description: 'Uma réplica da API passou de 3 reinícios (liveness falhando, OOM ou queda).'
    severity: 2
    enabled: true
    scopes: [apiAppId]
    evaluationFrequency: 'PT5M'
    windowSize: 'PT15M'
    criteria: {
      'odata.type': 'Microsoft.Azure.Monitor.SingleResourceMultipleMetricCriteria'
      allOf: [
        {
          criterionType: 'StaticThresholdCriterion'
          name: 'restarts'
          metricNamespace: 'Microsoft.App/containerApps'
          metricName: 'RestartCount'
          operator: 'GreaterThan'
          threshold: 3
          timeAggregation: 'Maximum'
        }
      ]
    }
    actions: [
      {
        actionGroupId: actionGroup.id
      }
    ]
  }
}
