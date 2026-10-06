// Container App da API: ingress HTTPS externo, uma revisão ativa por vez (a nova só recebe tráfego
// depois de passar startup e readiness), segredos lidos do Key Vault pela identidade gerenciada.

import { vaultEntryNames } from 'key-vault-names.bicep'

param namePrefix string

param location string

param environmentId string

@description('Imagem completa com a tag do commit, como crduora123.azurecr.io/duora-api:<sha>.')
param image string

param registryLoginServer string

@description('Identidade gerenciada (user-assigned) da API: puxa a imagem e lê os segredos.')
param identityId string

@description('vCPU por réplica, em texto (combinações fixas do Consumption: 0.5 com 1Gi, 1.0 com 2Gi...).')
param cpu string

param memory string

@minValue(0)
param minReplicas int

@minValue(1)
param maxReplicas int

@description('Faixa de onde o ingress conecta à aplicação (DUORA_TRUSTED_PROXIES, docs/adr/0006).')
param trustedProxies string

@description('URI do Key Vault, terminada em barra.')
param vaultUri string

@description('URL JDBC do banco, com TLS verificado.')
param jdbcUrl string

param appDatabaseRole string

@description('Valores públicos do tenant Entra External ID (docs/adr/0001 e 0002).')
param auth {
  issuerUri: string
  jwkSetUri: string
  audience: string
  authority: string
  webClientId: string
}

var containerPort = 8080

resource api 'Microsoft.App/containerApps@2025-07-01' = {
  name: 'ca-${namePrefix}-api'
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
      activeRevisionsMode: 'Single'
      ingress: {
        external: true
        targetPort: containerPort
        transport: 'http'
        allowInsecure: false
        traffic: [
          {
            latestRevision: true
            weight: 100
          }
        ]
      }
      registries: [
        {
          server: registryLoginServer
          identity: identityId
        }
      ]
      secrets: [
        {
          name: vaultEntryNames.postgresApp
          keyVaultUrl: '${vaultUri}secrets/${vaultEntryNames.postgresApp}'
          identity: identityId
        }
        {
          name: vaultEntryNames.entraWebClient
          keyVaultUrl: '${vaultUri}secrets/${vaultEntryNames.entraWebClient}'
          identity: identityId
        }
      ]
    }
    template: {
      // Igual ao padrão da plataforma, e acima dos 20 s do spring.lifecycle.timeout-per-shutdown-phase.
      terminationGracePeriodSeconds: 30
      containers: [
        {
          name: 'api'
          image: image
          resources: {
            cpu: json(cpu)
            memory: memory
          }
          env: [
            { name: 'SPRING_DATASOURCE_URL', value: jdbcUrl }
            { name: 'SPRING_DATASOURCE_USERNAME', value: appDatabaseRole }
            { name: 'SPRING_DATASOURCE_PASSWORD', secretRef: vaultEntryNames.postgresApp }
            // Quem migra é o job, com outra credencial; o papel da aplicação nem teria permissão.
            { name: 'SPRING_FLYWAY_ENABLED', value: 'false' }
            { name: 'DUORA_TRUSTED_PROXIES', value: trustedProxies }
            { name: 'DUORA_AUTH_ISSUER_URI', value: auth.issuerUri }
            { name: 'DUORA_AUTH_JWK_SET_URI', value: auth.jwkSetUri }
            { name: 'DUORA_AUTH_AUDIENCE', value: auth.audience }
            { name: 'DUORA_AUTH_AUTHORITY', value: auth.authority }
            { name: 'DUORA_AUTH_WEB_CLIENT_ID', value: auth.webClientId }
            { name: 'DUORA_AUTH_WEB_CLIENT_SECRET', secretRef: vaultEntryNames.entraWebClient }
          ]
          probes: [
            {
              // Até ~110 s para a JVM subir; enquanto a startup roda, as outras esperam.
              type: 'Startup'
              httpGet: {
                path: '/actuator/health/liveness'
                port: containerPort
              }
              initialDelaySeconds: 10
              periodSeconds: 10
              failureThreshold: 10
              timeoutSeconds: 3
            }
            {
              // Só o estado interno: reiniciar não conserta dependência externa.
              type: 'Liveness'
              httpGet: {
                path: '/actuator/health/liveness'
                port: containerPort
              }
              periodSeconds: 10
              failureThreshold: 3
              timeoutSeconds: 3
            }
            {
              // Inclui o banco. 30 s de tolerância, para um soluço do banco não tirar todas as réplicas do ar.
              type: 'Readiness'
              httpGet: {
                path: '/actuator/health/readiness'
                port: containerPort
              }
              periodSeconds: 5
              failureThreshold: 6
              timeoutSeconds: 3
            }
          ]
        }
      ]
      scale: {
        minReplicas: minReplicas
        maxReplicas: maxReplicas
        rules: [
          {
            name: 'http-concurrency'
            http: {
              metadata: {
                concurrentRequests: '50'
              }
            }
          }
        ]
      }
    }
  }
}

output appName string = api.name
output fqdn string = api.properties.configuration.ingress.fqdn
output appId string = api.id
