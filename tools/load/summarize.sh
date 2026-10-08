#!/usr/bin/env bash
# Resume os resultados de uma execução do run.sh em uma tabela Markdown, para colar no RESULTS.md:
# latência da operação medida em cada cenário (ms) e o que o banco e a API fizeram durante ele.
# Uso: tools/load/summarize.sh <diretório de resultados>
set -euo pipefail

dir="${1:?uso: $0 <diretório de resultados>}"

echo "| Cenário | Operação | p50 | p95 | p99 | máx | 503 documentados | respostas inesperadas | conexões no banco (máx) | esperando lock (máx) | CPU da API (máx) |"
echo "|---|---|---|---|---|---|---|---|---|---|---|"
for summary in "$dir"/*-summary.json; do
  scenario="$(basename "$summary" -summary.json)"
  [[ "$scenario" == seed ]] && continue
  pg="$dir/$scenario-pg-activity.csv"
  stats="$dir/$scenario-api-stats.csv"
  database="$(awk -F, 'BEGIN { t = w = 0 } { if ($1 > t) t = $1; if ($3 > w) w = $3 } END { printf "%d|%d", t, w }' "$pg")"
  cpu="$(awk -F, '{ gsub("%", "", $1); if ($1 + 0 > cpu) cpu = $1 + 0 } END { printf "%.0f%%", cpu }' "$stats")"
  jq -r --arg scenario "$scenario" --arg database "$database" --arg cpu "$cpu" '
    (.metrics.unexpected_responses.count // 0) as $unexpected
    | ([.metrics | to_entries[] | select(.key | endswith("_503")) | .value.count] | add // 0) as $busy
    | .metrics | to_entries[]
    | select(.key | startswith("http_req_duration{name:PUT"))
    | [$scenario, (.key | ltrimstr("http_req_duration{name:") | rtrimstr("}")),
       (.value.med | round), (.value["p(95)"] | round), (.value["p(99)"] | round), (.value.max | round),
       $busy, $unexpected, ($database | split("|")[0]), ($database | split("|")[1]), $cpu]
    | "| " + (map(tostring) | join(" | ")) + " |"' "$summary"
done
