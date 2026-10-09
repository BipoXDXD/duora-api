#!/usr/bin/env bash
# Sobe a imagem da API ao lado de um PostgreSQL descartável e confere o que o build não vê
# (docs/adr/0008 e 0014), na ordem do deploy: o job de migração roda com a credencial de
# administração, a aplicação sobe com o papel restrito, fica saudável nas probes, roda sem root e
# encerra limpo com SIGTERM.
# Uso: infra/docker/smoke-test.sh <imagem>
set -euo pipefail

image="${1:?uso: $0 <imagem>}"
run_id="duora-smoke-$$"
network="$run_id"
database="$run_id-db"
api="$run_id-api"
startup_timeout_seconds=90
shutdown_timeout_seconds=40

cleanup() {
  docker rm -f "$api" "$database" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "FALHOU: $1" >&2
  docker logs "$api" 2>&1 | tail -50 >&2 || true
  exit 1
}

docker network create "$network" >/dev/null
docker run -d --name "$database" --network "$network" \
  -e POSTGRES_DB=duora -e POSTGRES_USER=duora_admin -e POSTGRES_PASSWORD=smoke-test-only \
  postgres:18-alpine >/dev/null

deadline=$((SECONDS + startup_timeout_seconds))
# Por TCP: na primeira subida a imagem roda um servidor temporário só no socket Unix (initdb) e reinicia;
# pelo socket o pg_isready diria "pronto" antes de o servidor final aceitar a conexão TCP da migração.
until docker exec "$database" pg_isready -h 127.0.0.1 -U duora_admin -d duora >/dev/null 2>&1; do
  ((SECONDS < deadline)) || fail "o PostgreSQL não ficou pronto em ${startup_timeout_seconds}s"
  sleep 1
done

# Mesmo comando do Container Apps Job (infra/azure/modules/migration-job.bicep).
docker run --rm --network "$network" \
  -e DUORA_MIGRATION_JDBC_URL="jdbc:postgresql://$database:5432/duora" \
  -e DUORA_MIGRATION_USERNAME=duora_admin \
  -e DUORA_MIGRATION_PASSWORD=smoke-test-only \
  -e DUORA_APP_DB_USERNAME=duora_app \
  -e DUORA_APP_DB_PASSWORD=smoke-test-app-only \
  --entrypoint java "$image" -cp app.jar bipo.tech.duoraapi.migration.DatabaseMigration \
  || fail "o job de migração falhou"
echo "ok: migração com a credencial de administração"

# Valores fictícios: o smoke test não valida tokens, só exige que a aplicação suba.
docker run -d --name "$api" --network "$network" -p 127.0.0.1::8080 \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://$database:5432/duora" \
  -e SPRING_DATASOURCE_USERNAME=duora_app \
  -e SPRING_DATASOURCE_PASSWORD=smoke-test-app-only \
  -e SPRING_FLYWAY_ENABLED=false \
  -e DUORA_AUTH_ISSUER_URI=https://login.duora.test/tenant/v2.0 \
  -e DUORA_AUTH_JWK_SET_URI=https://login.duora.test/tenant/discovery/v2.0/keys \
  -e DUORA_AUTH_AUDIENCE=duora-api-smoke \
  -e DUORA_AUTH_AUTHORITY=https://login.duora.test/tenant \
  -e DUORA_AUTH_WEB_CLIENT_ID=duora-web-smoke \
  -e DUORA_AUTH_WEB_CLIENT_SECRET=smoke-test-only \
  -e DUORA_TRUSTED_PROXIES=192.0.2.0/24 \
  "$image" >/dev/null

port="$(docker port "$api" 8080/tcp | head -1 | cut -d: -f2)"
deadline=$((SECONDS + startup_timeout_seconds))
until curl -fsS "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
  [[ "$(docker inspect -f '{{.State.Running}}' "$api")" == "true" ]] || fail "o container parou durante a subida"
  ((SECONDS < deadline)) || fail "health não ficou UP em ${startup_timeout_seconds}s"
  sleep 2
done
echo "ok: health UP com o papel restrito"

for probe in liveness readiness; do
  curl -fsS "http://127.0.0.1:$port/actuator/health/$probe" | grep -q '"status":"UP"' || fail "probe $probe sem UP"
done
echo "ok: probes de liveness e readiness sem credencial"

uid="$(docker exec "$api" id -u)"
[[ "$uid" != "0" ]] || fail "o processo roda como root"
echo "ok: roda como uid $uid"

docker stop --timeout "$shutdown_timeout_seconds" "$api" >/dev/null
# 143 = 128 + SIGTERM: a JVM recebeu o sinal e saiu sozinha, sem o SIGKILL do fim do prazo (137).
exit_code="$(docker inspect -f '{{.State.ExitCode}}' "$api")"
[[ "$exit_code" == "143" || "$exit_code" == "0" ]] || fail "encerramento com código $exit_code"
# Lê o log inteiro antes de procurar: com pipefail, o grep -q sai no primeiro acerto e o SIGPIPE no
# docker logs faria o pipeline falhar mesmo com a linha presente.
api_logs="$(docker logs "$api" 2>&1)"
[[ "$api_logs" == *"Graceful shutdown complete"* ]] || fail "sem graceful shutdown no log"
echo "ok: encerrou com SIGTERM (código $exit_code) e graceful shutdown"
