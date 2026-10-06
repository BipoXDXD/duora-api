#!/usr/bin/env bash
# Validação offline dos Bicep, sem login nem chamada à Azure: compila os templates e os parâmetros,
# roda o linter (regras em bicepconfig.json; erro falha, aviso só aparece) e confere a formatação.
# Uso: infra/azure/validate.sh
set -euo pipefail

cd "$(dirname "$0")"

# Os .bicepparam leem segredos e valores do primeiro apply do ambiente; aqui bastam marcadores.
export DUORA_REGISTRY_NAME=crduoravalidate
export DUORA_API_IMAGE_TAG=duora-api:validate
export DUORA_PG_ADMIN_PASSWORD=validate-only
export DUORA_PG_APP_PASSWORD=validate-only
export DUORA_ENTRA_WEB_CLIENT_SECRET=validate-only
export DUORA_ALERT_EMAIL=alerts@example.com

for template in main.bicep shared.bicep; do
  az bicep build --file "$template" --stdout >/dev/null
  az bicep lint --file "$template"
  echo "ok: $template"
done

for parameters in hml.bicepparam prod.bicepparam shared.bicepparam; do
  az bicep build-params --file "$parameters" --stdout >/dev/null
  echo "ok: $parameters"
done
# O primeiro apply cria o ambiente sem a API (README); essa variação também precisa compilar.
DUORA_DEPLOY_API=false az bicep build-params --file hml.bicepparam --stdout >/dev/null
echo "ok: hml.bicepparam sem a API"

# Formata uma cópia (o --stdout acrescenta uma quebra de linha no fim) e compara com o original.
formatted="$(mktemp -d)"
trap 'rm -rf "$formatted"' EXIT
unformatted=()
while IFS= read -r -d '' file; do
  copy="$formatted/$(basename "$file")"
  az bicep format --file "$file" --outfile "$copy"
  diff -q "$copy" "$file" >/dev/null || unformatted+=("$file")
done < <(find . \( -name '*.bicep' -o -name '*.bicepparam' \) -print0)
if ((${#unformatted[@]} > 0)); then
  echo "Sem formatação do bicep format: ${unformatted[*]}" >&2
  exit 1
fi
echo "ok: formatação"
