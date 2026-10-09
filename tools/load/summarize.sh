#!/usr/bin/env bash
# Resume os resultados de uma execução do run.sh em uma tabela Markdown, para colar no RESULTS.md:
# latência da operação medida em cada cenário (ms) e o que o banco e a API fizeram durante ele.
# Uso: tools/load/summarize.sh <diretório de resultados>
set -euo pipefail

dir="${1:?uso: $0 <diretório de resultados>}"

echo "| Cenário | Operação | p50 | p95 | p99 | máx | 503 documentados | respostas inesperadas | conexões no banco (máx) | esperando lock (máx) | CPU da API (máx / média) |"
echo "|---|---|---|---|---|---|---|---|---|---|---|"
for summary in "$dir"/*-summary.json; do
  scenario="$(basename "$summary" -summary.json)"
  [[ "$scenario" == seed ]] && continue
  pg="$dir/$scenario-pg-activity.csv"
  stats="$dir/$scenario-api-stats.csv"
  database="$(awk -F, 'BEGIN { t = w = 0 } { if ($1 > t) t = $1; if ($3 > w) w = $3 } END { printf "%d|%d", t, w }' "$pg")"
  cpu="$(awk -F, '{ gsub("%", "", $1); v = $1 + 0; if (v > max) max = v; sum += v; n++ } END { printf "%.0f%% / %.0f%%", max, (n ? sum / n : 0) }' "$stats")"
  jq -r --arg scenario "$scenario" --arg database "$database" --arg cpu "$cpu" '
    (.metrics.unexpected_responses.count // 0) as $unexpected
    | ([.metrics | to_entries[] | select(.key | endswith("_503")) | .value.count] | add // 0) as $busy
    | .metrics | to_entries[]
    | select(.key | test("^http_req_duration\\{name:(PUT|GET chat|POST chat)"))
    | [$scenario, (.key | ltrimstr("http_req_duration{name:") | rtrimstr("}")),
       (.value.med | round), (.value["p(95)"] | round), (.value["p(99)"] | round), (.value.max | round),
       $busy, $unexpected, ($database | split("|")[0]), ($database | split("|")[1]), $cpu]
    | "| " + (map(tostring) | join(" | ")) + " |"' "$summary"
done

# O que a API pediu ao banco no cenário do chat: requisições por segundo no k6 e comandos por segundo no
# PostgreSQL (pg_stat_statements, sem BEGIN/COMMIT). A duração vem do contador de polls do k6 (contagem / taxa).
for summary in "$dir"/chat*-summary.json; do
  [[ -e "$summary" ]] || continue
  scenario="$(basename "$summary" -summary.json)"
  totals="$dir/$scenario-pg-statement-totals.txt"
  transactions="$dir/$scenario-pg-transactions.txt"
  [[ -s "$totals" && -s "$transactions" ]] || continue
  seconds="$(jq -r '.metrics.chat_polls.count / .metrics.chat_polls.rate' "$summary")"
  database_cpu="$(awk -F, '{ gsub("%", "", $1); v = $1 + 0; if (v > max) max = v; sum += v; n++ } END { printf "%.0f%% / %.0f%%", max, (n ? sum / n : 0) }' "$dir/$scenario-db-stats.csv" 2>/dev/null || echo "n/d")"
  echo
  echo "Carga do $scenario sobre o banco (CPU do container do banco, máx / média: $database_cpu):"
  jq -r '"- k6: \(.metrics.chat_polls.count) polls (\(.metrics.chat_polls.rate * 100 | round / 100)/s) e \(.metrics.chat_sends.count) envios (\(.metrics.chat_sends.rate * 100 | round / 100)/s)"' "$summary"
  awk -F'|' -v s="$seconds" -v t="$(cat "$transactions")" \
    '{ printf "- PostgreSQL: %.1f comandos/s (mais %.1f/s de BEGIN/COMMIT) e %.1f transações/s, estas com o amostrador (~4/s)\n", $1 / s, $2 / s, t / s }' "$totals"
done
