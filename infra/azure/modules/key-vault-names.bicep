// Nomes dos segredos do Key Vault, numa fonte só: o cofre os cria e o app e o job os referenciam.

@export()
var vaultEntryNames = {
  postgresAdmin: 'postgres-admin-password'
  postgresApp: 'postgres-app-password'
  entraWebClient: 'entra-web-client-secret'
}
