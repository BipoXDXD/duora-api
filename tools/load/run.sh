#!/usr/bin/env bash
# Teste de carga manual com k6 (docs/adr/0022): sobe um PostgreSQL descartável e a imagem da API, como o
# infra/docker/contract-test.sh, e roda os cenários que medem as disputas das ADRs 0016, 0017 e 0019:
#   registration  100 contas se inscrevem ao mesmo tempo num evento de capacidade 50
#   rounds        o ADMIN inicia a rodada em 50 eventos em paralelo (cada pedido em dobro)
#   decisions     100 pares (200 contas) decidem ao mesmo tempo
#
# Não roda no CI (custo de minutos): é manual, antes de mexer em lock, pool ou limite, e para atualizar o
# tools/load/RESULTS.md. Precisa de docker, curl, openssl, xxd e jq.
#
# A autenticação é a do contract-test.sh: um par de chaves RSA da execução, o JWKS num container local e
# tokens Bearer assinados na hora, um por conta sintética (oid load-user-NNN) e um para o ADMIN.
#
# Uso: tools/load/run.sh <imagem> [registration|rounds|decisions|all]
# Variáveis: ACCOUNTS (200 contas sintéticas), REGISTRATION_ACCOUNTS (100) e REGISTRATION_CAPACITY (50), do
# cenário da inscrição; REPEAT (1), quantas vezes cada cenário roda na mesma subida (a segunda em diante
# acha a JVM aquecida); API_CPUS (1) e API_MEMORY (2g), que são os da produção (ADR 0014);
# DB_CPUS (2) e DB_MEMORY (4g), um B2s hipotético (plano §6); LOAD_RESULTS_DIR (tools/load/results/<data>);
# API_ENV_FILE (nenhum), um arquivo do `docker run --env-file` com variáveis a mais para a API, para comparar
# configurações (pool, timeouts) sem reconstruir a imagem.
set -euo pipefail

image="${1:?uso: $0 <imagem> [registration|rounds|decisions|all]}"
selected="${2:-all}"
here="$(cd "$(dirname "$0")" && pwd)"

k6_image="grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34"
jwks_image="busybox:1.37@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e"

accounts="${ACCOUNTS:-200}"
registration_accounts="${REGISTRATION_ACCOUNTS:-100}"
registration_capacity="${REGISTRATION_CAPACITY:-50}"
repeat="${REPEAT:-1}"
api_cpus="${API_CPUS:-1}"
api_memory="${API_MEMORY:-2g}"
db_cpus="${DB_CPUS:-2}"
db_memory="${DB_MEMORY:-4g}"
api_env_file="${API_ENV_FILE:-}"
rooms=50
people_per_room=4
startup_timeout_seconds=90

run_id="duora-load-$$"
network="$run_id"
database="$run_id-db"
jwks="$run_id-jwks"
api="$run_id-api"
workdir="$(mktemp -d)"
results="${LOAD_RESULTS_DIR:-$here/results/$(date +%Y%m%dT%H%M%S)}"
mkdir -p "$results"
[[ -n "$api_env_file" ]] && cp "$api_env_file" "$results/api.env"
sampler_pids=()

# Os mesmos valores vão para a API e para os tokens; nenhum vale fora deste teste.
issuer="https://login.duora.test/tenant/v2.0"
audience="duora-api-load"
key_id="load-test"

stop_samplers() {
  for pid in "${sampler_pids[@]:-}"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
  sampler_pids=()
}

cleanup() {
  stop_samplers
  docker logs "$api" > "$results/api.log" 2>&1 || true
  docker ps -aq --filter "name=$run_id-k6-" | xargs docker rm -f >/dev/null 2>&1 || true
  docker rm -f "$api" "$jwks" "$database" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf "$workdir"
}
trap cleanup EXIT

fail() {
  echo "FALHOU: $1" >&2
  docker logs "$api" 2>&1 | tail -50 >&2 || true
  exit 1
}

base64url() {
  openssl base64 -A | tr '+/' '-_' | tr -d '='
}

psql_db() {
  docker exec -i "$database" psql -U duora -d duora -X -q -v ON_ERROR_STOP=1 -At "$@"
}

# --- Chave, JWKS e tokens ---------------------------------------------------------------------------
openssl genrsa -out "$workdir/key.pem" 2048 2>/dev/null
modulus="$(openssl rsa -in "$workdir/key.pem" -noout -modulus | cut -d= -f2 | sed 's/^\(00\)*//' | xxd -r -p | base64url)"
mkdir "$workdir/www"
printf '{"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"%s","n":"%s","e":"AQAB"}]}' \
  "$key_id" "$modulus" > "$workdir/www/jwks.json"

# sign_token <oid> <roles em JSON>: access token de 2 horas, o bastante para a execução inteira.
sign_token() {
  local now header payload signature
  now="$(date +%s)"
  header="$(printf '{"alg":"RS256","typ":"JWT","kid":"%s"}' "$key_id" | base64url)"
  payload="$(printf '{"iss":"%s","aud":"%s","oid":"%s","name":"Carga","roles":%s,"iat":%d,"nbf":%d,"exp":%d}' \
    "$issuer" "$audience" "$1" "$2" "$now" "$now" "$((now + 7200))" | base64url)"
  signature="$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$workdir/key.pem" | base64url)"
  printf '%s.%s.%s' "$header" "$payload" "$signature"
}

mkdir "$workdir/k6"
{
  printf '{"admin":"%s","users":[' "$(sign_token load-admin '["ADMIN"]')"
  for ((i = 1; i <= accounts; i++)); do
    subject="$(printf 'load-user-%03d' "$i")"
    ((i > 1)) && printf ','
    printf '{"subject":"%s","token":"%s"}' "$subject" "$(sign_token "$subject" '[]')"
  done
  printf ']}'
} > "$workdir/k6/tokens.json"
cp "$here"/*.js "$workdir/k6/"
echo "ok: $accounts tokens de conta e 1 de ADMIN assinados"

# --- Ambiente ---------------------------------------------------------------------------------------
docker network create "$network" >/dev/null
docker run -d --name "$database" --network "$network" --cpus "$db_cpus" --memory "$db_memory" \
  -e POSTGRES_DB=duora -e POSTGRES_USER=duora -e POSTGRES_PASSWORD=load-test-only \
  postgres:18-alpine >/dev/null

docker create --name "$jwks" --network "$network" "$jwks_image" httpd -f -p 8000 -h /www >/dev/null
docker cp "$workdir/www" "$jwks:/www"
docker start "$jwks" >/dev/null

# Limites do rate limit (ADR 0006), elevados só onde o cenário gastaria o saldo real:
# - rodadas: o limite é por conta ADMIN (30/h) e o cenário usa uma conta só para 200 pedidos de rodada.
#   Um 429 aqui mediria o limite, que já tem teste próprio (RoundRateLimitIT), e não o sorteio.
# - decisões: o limite por conta (ADR 0019, ramo do rate limit da decisão) ainda não está em main; a variável
#   é inofensiva enquanto não existir e evita que o 429 apareça no dia em que existir.
# A inscrição fica com o limite de produção (60/h por conta): cada conta faz no máximo uns 10 pedidos.
# CPU e memória da API são os da produção (1 vCPU, 2 GiB; ADR 0014), com o pool e o Tomcat no padrão.
docker run -d --name "$api" --network "$network" -p 127.0.0.1::8080 \
  --cpus "$api_cpus" --memory "$api_memory" \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://$database:5432/duora" \
  -e SPRING_DATASOURCE_USERNAME=duora \
  -e SPRING_DATASOURCE_PASSWORD=load-test-only \
  -e DUORA_AUTH_ISSUER_URI="$issuer" \
  -e DUORA_AUTH_JWK_SET_URI="http://$jwks:8000/jwks.json" \
  -e DUORA_AUTH_AUDIENCE="$audience" \
  -e DUORA_AUTH_AUTHORITY=https://login.duora.test/tenant \
  -e DUORA_AUTH_WEB_CLIENT_ID=duora-web-load \
  -e DUORA_AUTH_WEB_CLIENT_SECRET=load-test-only \
  -e DUORA_TRUSTED_PROXIES=192.0.2.0/24 \
  -e DUORA_MATCHING_ROUNDRATELIMIT_CAPACITY=1000000 \
  -e DUORA_CONNECTIONS_DECISIONRATELIMIT_CAPACITY=1000000 \
  ${api_env_file:+--env-file "$api_env_file"} \
  "$image" >/dev/null

port="$(docker port "$api" 8080/tcp | head -1 | cut -d: -f2)"
deadline=$((SECONDS + startup_timeout_seconds))
until curl -fsS "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
  [[ "$(docker inspect -f '{{.State.Running}}' "$api")" == "true" ]] || fail "o container parou durante a subida"
  ((SECONDS < deadline)) || fail "health não ficou UP em ${startup_timeout_seconds}s"
  sleep 2
done
echo "ok: API no ar"

# --- Execução do k6 ---------------------------------------------------------------------------------
# run_k6 <nome> <script>: o k6 roda dentro da rede do teste, com scripts e dados copiados (docker cp, como no
# contract-test.sh). Devolve o código de saída do k6 (99 = threshold violado).
run_k6() {
  local name="$1" script="$2" container="$run_id-k6-$1" code=0
  docker create --name "$container" --network "$network" -e BASE_URL="http://$api:8080" \
    -e REGISTRATION_ACCOUNTS="$registration_accounts" -e REGISTRATION_CAPACITY="$registration_capacity" "$k6_image" \
    run "/work/$script" --no-color \
    --summary-trend-stats "avg,min,med,p(90),p(95),p(99),max" \
    --summary-export /tmp/summary.json >/dev/null
  docker cp "$workdir/k6" "$container:/work"
  docker start -a "$container" 2>&1 | tee "$results/$name.log" || code=${PIPESTATUS[0]}
  docker cp "$container:/tmp/summary.json" "$results/$name-summary.json" >/dev/null 2>&1 || true
  docker rm -f "$container" >/dev/null
  return "$code"
}

export_data() {
  psql_db -f - < "$here/sql/export-data.sql" > "$workdir/k6/data.json"
}

# Amostras do banco e da API durante o cenário: o máximo de conexões e de esperas por lock do lado do
# PostgreSQL, e CPU e memória do container da API.
start_samplers() {
  local name="$1"
  (while true; do
    psql_db -F, -f - < "$here/sql/activity.sql" >> "$results/$name-pg-activity.csv" 2>/dev/null || true
    sleep 0.25
  done) &
  sampler_pids+=($!)
  (while true; do
    docker stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}}' "$api" >> "$results/$name-api-stats.csv" 2>/dev/null || true
    sleep 0.5
  done) &
  sampler_pids+=($!)
}

check_invariants() {
  local file="$1" broken=0 name expected found
  while IFS='|' read -r name expected found; do
    if [[ "$expected" == "$found" ]]; then
      echo "  ok: $name = $found"
    else
      echo "  QUEBRADA: $name (esperado $expected, encontrado $found)" >&2
      broken=1
    fi
  done < <(psql_db -v capacity="$registration_capacity" -f - < "$here/sql/$file")
  return "$broken"
}

failed=()

# run_scenario <cenário> <preparo> <arquivo de invariantes> <rótulo>: o rótulo dá nome aos arquivos de
# resultado (cenário, ou cenário-N quando REPEAT > 1).
run_scenario() {
  local scenario="$1" prepare="$2" invariants="$3" name="$4" code=0
  echo
  echo "=== $name ==="
  psql_db -f - < "$here/sql/reset.sql"
  [[ "$prepare" == "in-progress" ]] && psql_db -v rooms="$rooms" -v people="$people_per_room" -f - < "$here/sql/in-progress-events.sql"
  export_data
  : > "$results/$name-pg-activity.csv"
  : > "$results/$name-api-stats.csv"
  start_samplers "$name"
  run_k6 "$name" "$scenario.js" || code=$?
  stop_samplers
  echo "invariantes no banco:"
  check_invariants "$invariants" || code=1
  ((code == 0)) || failed+=("$name")
}

# O preparo das contas é parte do ambiente (seed.js), não de um cenário: tem o próprio k6, sem medição.
export_data
run_k6 seed seed.js || fail "o preparo das contas falhou"
accounts_created="$(psql_db -c "select count(*) from account where subject like 'load-user-%'")"
[[ "$accounts_created" == "$accounts" ]] || fail "esperava $accounts contas sintéticas, o banco tem $accounts_created"
echo "ok: $accounts contas com perfil completo"

case "$selected" in
  registration | rounds | decisions | all) ;;
  *) fail "cenário desconhecido: $selected" ;;
esac
for ((n = 1; n <= repeat; n++)); do
  suffix=""
  ((repeat > 1)) && suffix="-$n"
  if [[ "$selected" == registration || "$selected" == all ]]; then
    run_scenario registration none invariants-registration.sql "registration$suffix"
  fi
  if [[ "$selected" == rounds || "$selected" == all ]]; then
    run_scenario rounds in-progress invariants-rounds.sql "rounds$suffix"
  fi
  if [[ "$selected" == decisions || "$selected" == all ]]; then
    run_scenario decisions in-progress invariants-decisions.sql "decisions$suffix"
  fi
done

echo
echo "Resultados em $results"
"$here/summarize.sh" "$results"
if ((${#failed[@]} > 0)); then
  echo "FALHARAM: ${failed[*]}" >&2
  exit 1
fi
echo "ok: thresholds e invariantes de todos os cenários"
