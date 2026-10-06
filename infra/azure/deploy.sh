#!/usr/bin/env bash
# Implanta uma imagem num ambiente já criado pelo Bicep, na ordem do plano §9: migra o banco com o
# job e só então troca a imagem da API. Para na primeira falha; a revisão anterior continua no ar
# se a nova não ficar pronta (single revision mode).
# Uso: infra/azure/deploy.sh <resource-group> <container-app> <migration-job> <imagem>
set -euo pipefail

resource_group="${1:?uso: $0 <resource-group> <container-app> <migration-job> <imagem>}"
app="${2:?container app}"
job="${3:?job de migração}"
image="${4:?imagem}"

# Acima do pior caso do job: replicaTimeout de 600 s com uma nova tentativa.
migration_timeout_seconds=1300
revision_timeout_seconds=600
# Homologação escala a zero: a primeira requisição espera a JVM subir.
readiness_timeout_seconds=240
poll_seconds=10

echo "::group::Migração ($job)"
az containerapp job update --resource-group "$resource_group" --name "$job" --image "$image" --output none
execution="$(az containerapp job start --resource-group "$resource_group" --name "$job" --query name --output tsv)"
echo "execução: $execution"
deadline=$((SECONDS + migration_timeout_seconds))
while true; do
  status="$(az containerapp job execution show --resource-group "$resource_group" --name "$job" \
    --job-execution-name "$execution" --query properties.status --output tsv)"
  case "$status" in
    Succeeded) break ;;
    Failed | Stopped | Degraded)
      echo "::error::Migração terminou como $status. Logs: az containerapp job logs show -g $resource_group -n $job --execution $execution --container migrate" >&2
      exit 1
      ;;
    *) ;;
  esac
  ((SECONDS < deadline)) || { echo "::error::Migração sem terminar em ${migration_timeout_seconds}s (status $status)" >&2; exit 1; }
  sleep "$poll_seconds"
done
echo "ok: migração concluída"
echo "::endgroup::"

echo "::group::Revisão nova ($app)"
az containerapp update --resource-group "$resource_group" --name "$app" --image "$image" --output none
revision="$(az containerapp show --resource-group "$resource_group" --name "$app" \
  --query properties.latestRevisionName --output tsv)"
echo "revisão: $revision"
deadline=$((SECONDS + revision_timeout_seconds))
# Pronta é a revisão que passou startup e readiness e recebe o tráfego; enquanto isso, a anterior atende.
until [[ "$(az containerapp show --resource-group "$resource_group" --name "$app" \
  --query properties.latestReadyRevisionName --output tsv)" == "$revision" ]]; do
  state="$(az containerapp revision show --resource-group "$resource_group" --name "$app" \
    --revision "$revision" --query properties.runningState --output tsv)"
  [[ "$state" != "Failed" ]] || { echo "::error::Revisão $revision falhou; a anterior continua no ar." >&2; exit 1; }
  ((SECONDS < deadline)) || { echo "::error::Revisão $revision não ficou pronta em ${revision_timeout_seconds}s (estado $state)" >&2; exit 1; }
  sleep "$poll_seconds"
done
echo "ok: revisão pronta"
echo "::endgroup::"

echo "::group::Teste rápido"
fqdn="$(az containerapp show --resource-group "$resource_group" --name "$app" \
  --query properties.configuration.ingress.fqdn --output tsv)"
deadline=$((SECONDS + readiness_timeout_seconds))
until curl -fsS --max-time 10 "https://$fqdn/actuator/health/readiness" 2>/dev/null | grep -q '"status":"UP"'; do
  ((SECONDS < deadline)) || { echo "::error::https://$fqdn/actuator/health/readiness sem UP em ${readiness_timeout_seconds}s" >&2; exit 1; }
  sleep "$poll_seconds"
done
echo "ok: https://$fqdn pronta com $image"
echo "::endgroup::"
