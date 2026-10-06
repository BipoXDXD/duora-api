# Infraestrutura do piloto na Azure

Bicep dos dois ambientes do Duora (homologação e produção) e dos recursos compartilhados. As
decisões, as alternativas e a **estimativa de custo** estão na
[ADR 0011](../../docs/adr/0011-infraestrutura-do-piloto-na-azure.md).

> **Antes do primeiro apply:** aprovar o custo da ADR 0011. Nada aqui foi aplicado ainda; só a
> validação offline roda no CI.

| Arquivo | O que cria | Onde |
|---|---|---|
| `shared.bicep` + `shared.bicepparam` | Container Registry Basic e a identidade que o GitHub usa para publicar imagens | resource group `duora-shared` |
| `main.bicep` + `hml.bicepparam` | Homologação: VNet, PostgreSQL 18 privado, Key Vault, logs, Container Apps (API e job de migração), identidade de deploy, alertas | resource group `duora-hml` |
| `main.bicep` + `prod.bicepparam` | Produção, com os mesmos recursos | resource group `duora-prod` |
| `modules/` | Um módulo por recurso, usados pelo `main.bicep` | |
| `validate.sh` | Build, lint e formatação, offline (job `Bicep` do CI) | |
| `deploy.sh` | Migra o banco e troca a imagem da API (usado pelo workflow de deploy) | |

## Validar sem tocar na Azure

```bash
az bicep install          # uma vez
infra/azure/validate.sh
```

## Primeiro apply (feito à mão, uma vez)

Os comandos abaixo criam recursos cobrados. Rode cada `what-if` e leia o resultado antes do
`create` correspondente.

### 1. Assinatura e provedores

```bash
az login
az account set --subscription "Azure subscription 1"
for namespace in Microsoft.App Microsoft.ContainerRegistry Microsoft.DBforPostgreSQL Microsoft.KeyVault \
    Microsoft.OperationalInsights Microsoft.Insights Microsoft.ManagedIdentity Microsoft.Network \
    Microsoft.Consumption; do
  az provider register --namespace "$namespace"
done
```

O login é o da assinatura (conta da Azure), não o do tenant `duoraapp` do Entra External ID.

### 2. Resource groups

```bash
for group in duora-shared duora-hml duora-prod; do
  az group create --name "$group" --location brazilsouth
done
```

### 3. Recursos compartilhados

```bash
az deployment group what-if --resource-group duora-shared \
  --template-file infra/azure/shared.bicep --parameters infra/azure/shared.bicepparam
az deployment group create --resource-group duora-shared --name shared \
  --template-file infra/azure/shared.bicep --parameters infra/azure/shared.bicepparam \
  --query properties.outputs
```

Guarde as saídas `registryName`, `registryLoginServer` e `publisherClientId`.

### 4. Primeira imagem

O ambiente precisa de uma imagem no registry para o job e a API. Em Mac com Apple Silicon, a imagem
tem de ser `linux/amd64`, a arquitetura do Container Apps:

```bash
registry=<registryName>
az acr login --name "$registry"
tag="duora-api:$(git rev-parse HEAD)"
docker buildx build --platform linux/amd64 --tag "$(az acr show -n "$registry" --query loginServer -o tsv)/$tag" --push .
```

Depois do primeiro ambiente, as imagens saem pelo workflow de deploy.

### 5. Segredos do ambiente

Um arquivo por ambiente, fora do repositório e só legível por você (repita para `prod` com outros
valores):

```bash
umask 077
cat > ~/.config/duora/hml.env <<EOF
DUORA_REGISTRY_NAME=$registry
DUORA_API_IMAGE_TAG=$tag
DUORA_PG_ADMIN_PASSWORD=$(openssl rand -base64 33)
DUORA_PG_APP_PASSWORD=$(openssl rand -base64 33)
DUORA_ENTRA_WEB_CLIENT_SECRET=<segredo do cliente duora-web para este ambiente>
DUORA_ALERT_EMAIL=<e-mail que recebe orçamento e alertas>
EOF
```

- As senhas do banco vão para o Key Vault no apply; o arquivo local é a cópia que permite reaplicar.
  A senha de administração só é usada pelo job de migração.
- O segredo do cliente web é do registro `duora-web` no tenant `duoraapp`. Crie um por ambiente (ou um
  registro por ambiente, como pede o plano §6), com validade de até 180 dias e lembrete para renovar.
- Antes do primeiro login web, registre o redirect URI do ambiente no tenant
  (`https://<fqdn>/login/oauth2/code/entra` e `https://<fqdn>/` para o logout), rodando
  `infra/entra/configure-tenant.sh` com `WEB_ORIGINS` incluindo `https://<fqdn>` além das origens locais.

### 6. Ambiente em duas etapas

Com o Flyway desligado na API, ela não sobe num banco vazio. Por isso o primeiro apply cria tudo
menos a API, o job migra o banco e só então o apply completo cria a API.

```bash
set -a; source ~/.config/duora/hml.env; set +a

# Etapa 1: sem a API
DUORA_DEPLOY_API=false az deployment group what-if --resource-group duora-hml \
  --template-file infra/azure/main.bicep --parameters infra/azure/hml.bicepparam
DUORA_DEPLOY_API=false az deployment group create --resource-group duora-hml --name hml \
  --template-file infra/azure/main.bicep --parameters infra/azure/hml.bicepparam

# Migração (cria o papel duora_app e o schema)
execution=$(az containerapp job start -g duora-hml -n caj-duora-hml-migrate --query name -o tsv)
az containerapp job execution show -g duora-hml -n caj-duora-hml-migrate \
  --job-execution-name "$execution" --query properties.status -o tsv   # repita até Succeeded

# Etapa 2: com a API
az deployment group what-if --resource-group duora-hml \
  --template-file infra/azure/main.bicep --parameters infra/azure/hml.bicepparam
az deployment group create --resource-group duora-hml --name hml \
  --template-file infra/azure/main.bicep --parameters infra/azure/hml.bicepparam \
  --query properties.outputs
```

Guarde as saídas `deployClientId`, `apiAppName`, `migrationJobName` e `apiFqdn`. Repita com
`duora-prod`, `prod.env` e `prod.bicepparam`.

Se o primeiro apply falhar ao ler um segredo do Key Vault, é a atribuição de papel recém-criada ainda
se propagando: espere alguns minutos e rode o `create` de novo.

### 7. GitHub

```bash
repo=BipoXDXD/duora-api
gh variable set AZURE_TENANT_ID --repo "$repo" --body "$(az account show --query tenantId -o tsv)"
gh variable set AZURE_SUBSCRIPTION_ID --repo "$repo" --body "$(az account show --query id -o tsv)"
gh variable set DUORA_REGISTRY_NAME --repo "$repo" --body "<registryName>"
gh variable set DUORA_PUBLISHER_CLIENT_ID --repo "$repo" --body "<publisherClientId>"

for environment in homologacao producao; do
  gh api --method PUT "repos/$repo/environments/$environment" >/dev/null
done
gh variable set AZURE_CLIENT_ID --repo "$repo" --env homologacao --body "<deployClientId de hml>"
gh variable set DUORA_RESOURCE_GROUP --repo "$repo" --env homologacao --body duora-hml
gh variable set DUORA_CONTAINER_APP --repo "$repo" --env homologacao --body ca-duora-hml-api
gh variable set DUORA_MIGRATION_JOB --repo "$repo" --env homologacao --body caj-duora-hml-migrate
# o mesmo para producao, com os valores de duora-prod
```

No environment `producao`, ligue **Required reviewers** (Settings → Environments) e restrinja a
branch a `main`: é a aprovação de produção do plano §9.

Nenhum desses valores é segredo: o login no Azure é por OIDC, com credenciais federadas que só aceitam
tokens da `main` (publicação) e do environment correspondente (deploy).

### 8. Conferências do primeiro deploy

- **Probes e TLS do banco:** `curl https://<fqdn>/actuator/health/readiness` responde
  `{"status":"UP"}`. Se a readiness ficar `DOWN`, veja os logs da réplica
  (`az containerapp logs show -g duora-hml -n ca-duora-hml-api`); um erro de certificado aponta para o
  `sslmode=verify-full` (ADR 0011).
- **Proxy confiável (`DUORA_TRUSTED_PROXIES`):** de uma rede, faça 11 `POST /api/waitlist` com e-mails
  diferentes: o 11º recebe `429`. Logo depois, de outra rede (o celular fora do Wi-Fi), um `POST` tem de
  receber `202`. Se receber `429`, o ingress não conecta a partir da subnet, todos os clientes estão no
  mesmo bucket e a faixa precisa mudar (ADR 0006 e 0011).
- **Orçamento:** confira em Cost Management que os orçamentos `budget-duora-hml` e
  `budget-duora-prod` existem, na moeda da assinatura.

## Deploy de cada commit

Pelo workflow **Deploy** (Actions → Deploy → Run workflow), na `main`, com o CI verde no commit.
`target: homologacao` publica a imagem e implanta em homologação; `target: producao` faz o mesmo e,
depois da aprovação do environment, implanta em produção. A ordem em cada ambiente é: job de migração
→ revisão nova da API → readiness pelo ingress (`infra/azure/deploy.sh`).

## Reaplicar o Bicep depois do primeiro deploy

O apply define a imagem da API e do job. Para não voltar a uma imagem antiga, informe a que está em
uso:

```bash
export DUORA_API_IMAGE_TAG="$(az containerapp show -g duora-hml -n ca-duora-hml-api \
  --query 'properties.template.containers[0].image' -o tsv | cut -d/ -f2)"
```

## Rollback

- **API:** reative a revisão anterior (`az containerapp revision list -g duora-hml -n ca-duora-hml-api`,
  depois `az containerapp revision activate --revision <nome>` e
  `az containerapp ingress traffic set --revision-weight <nome>=100`), ou rode
  `infra/azure/deploy.sh` com a imagem anterior.
- **Banco:** não volta sozinho. Toda migration é compatível com a revisão anterior (plano §9); para
  desfazer dados, restauração point-in-time para um servidor novo.

## Custos no dia a dia

- Homologação escala a API a zero sem tráfego; o banco pode parar quando não estiver em uso:
  `az postgres flexible-server stop -g duora-hml -n <postgresServerName>` (volta sozinho em 7 dias).
- Apagar um ambiente: `az group delete --name duora-hml`. O Key Vault fica 90 dias em soft delete;
  em produção, com proteção contra purga, o nome fica preso nesse período.
