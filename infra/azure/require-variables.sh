#!/usr/bin/env bash
# Falha cedo, com a lista do que falta, quando variáveis do GitHub ainda não foram configuradas
# (infra/azure/README.md). O workflow de deploy passa cada `vars.X` como variável de ambiente.
# Uso: infra/azure/require-variables.sh NOME [NOME...]
set -euo pipefail

missing=()
for name in "$@"; do
  [[ -n "${!name:-}" ]] || missing+=("$name")
done

if ((${#missing[@]} > 0)); then
  echo "::error::Variáveis do GitHub não configuradas: ${missing[*]}. Veja infra/azure/README.md." >&2
  exit 1
fi
echo "ok: $*"
