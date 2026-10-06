#!/usr/bin/env bash
# Sobe a imagem da API e roda o Schemathesis (--checks all) contra ela, a partir da spec versionada
# em docs/openapi.json, com a configuração de tools/contract/schemathesis.toml (docs/adr/0011):
# nenhum 500, status, headers e corpos dentro do contrato, entrada inválida recusada e autenticação
# aplicada.
#
# A porta bearer é exercida com um token de verdade: o script gera um par de chaves RSA, publica a
# chave pública num JWKS local e assina um access token com o papel ADMIN. A porta de sessão (BFF)
# fica de fora, porque exige o login interativo no Entra.
# Uso: infra/docker/contract-test.sh <imagem>
set -euo pipefail

image="${1:?uso: $0 <imagem>}"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
schemathesis_image="schemathesis/schemathesis:4.29.3@sha256:39dfcd9f15942fcf760187ae1b7e3875d5ca0ae81afa51dbd184761d127a7fde"
jwks_image="busybox:1.37@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e"

run_id="duora-contract-$$"
network="$run_id"
database="$run_id-db"
jwks="$run_id-jwks"
api="$run_id-api"
fuzzer="$run_id-schemathesis"
workdir="$(mktemp -d)"
startup_timeout_seconds=90

# Os mesmos valores vão para a API e para o token; nenhum vale fora deste teste.
issuer="https://login.duora.test/tenant/v2.0"
audience="duora-api-contract"
key_id="contract-test"

cleanup() {
  docker rm -f "$fuzzer" "$api" "$jwks" "$database" >/dev/null 2>&1 || true
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

# Chave e JWKS: o módulo sai em hexadecimal do openssl e vira base64url sem zeros à esquerda.
openssl genrsa -out "$workdir/key.pem" 2048 2>/dev/null
modulus="$(openssl rsa -in "$workdir/key.pem" -noout -modulus | cut -d= -f2 | sed 's/^\(00\)*//' | xxd -r -p | base64url)"
mkdir "$workdir/www"
printf '{"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"%s","n":"%s","e":"AQAB"}]}' \
  "$key_id" "$modulus" > "$workdir/www/jwks.json"

now="$(date +%s)"
header="$(printf '{"alg":"RS256","typ":"JWT","kid":"%s"}' "$key_id" | base64url)"
payload="$(printf '{"iss":"%s","aud":"%s","oid":"contract-test-admin","name":"Contract Test","roles":["ADMIN"],"iat":%d,"nbf":%d,"exp":%d}' \
  "$issuer" "$audience" "$now" "$now" "$((now + 3600))" | base64url)"
signature="$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$workdir/key.pem" | base64url)"
token="$header.$payload.$signature"

docker network create "$network" >/dev/null
docker run -d --name "$database" --network "$network" \
  -e POSTGRES_DB=duora -e POSTGRES_USER=duora -e POSTGRES_PASSWORD=contract-test-only \
  postgres:18-alpine >/dev/null

# docker cp, e não volume: funciona igual no runner e no Docker Desktop.
docker create --name "$jwks" --network "$network" "$jwks_image" httpd -f -p 8000 -h /www >/dev/null
docker cp "$workdir/www" "$jwks:/www"
docker start "$jwks" >/dev/null

# Limite de inscrições alto: o fuzzing precisa chegar ao controller, e o 429 já tem teste próprio
# (JoinWaitlistIT).
docker run -d --name "$api" --network "$network" -p 127.0.0.1::8080 \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://$database:5432/duora" \
  -e SPRING_DATASOURCE_USERNAME=duora \
  -e SPRING_DATASOURCE_PASSWORD=contract-test-only \
  -e DUORA_AUTH_ISSUER_URI="$issuer" \
  -e DUORA_AUTH_JWK_SET_URI="http://$jwks:8000/jwks.json" \
  -e DUORA_AUTH_AUDIENCE="$audience" \
  -e DUORA_AUTH_AUTHORITY=https://login.duora.test/tenant \
  -e DUORA_AUTH_WEB_CLIENT_ID=duora-web-contract \
  -e DUORA_AUTH_WEB_CLIENT_SECRET=contract-test-only \
  -e DUORA_TRUSTED_PROXIES=192.0.2.0/24 \
  -e DUORA_WAITLIST_JOINRATELIMIT_CAPACITY=1000000 \
  "$image" >/dev/null

port="$(docker port "$api" 8080/tcp | head -1 | cut -d: -f2)"
deadline=$((SECONDS + startup_timeout_seconds))
until curl -fsS "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
  [[ "$(docker inspect -f '{{.State.Running}}' "$api")" == "true" ]] || fail "o container parou durante a subida"
  ((SECONDS < deadline)) || fail "health não ficou UP em ${startup_timeout_seconds}s"
  sleep 2
done

# Controle: sem isso, um token recusado faria o fuzzing ver só 401 e passar sem testar nada.
status="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" \
  "http://127.0.0.1:$port/api/admin/waitlist/stats")"
[[ "$status" == "200" ]] || fail "o token de teste não autentica como ADMIN (status $status)"
echo "ok: token de teste aceito como ADMIN"

docker create --name "$fuzzer" --network "$network" "$schemathesis_image" \
  --config-file /spec/schemathesis.toml \
  run /spec/openapi.json \
  --url "http://$api:8080" \
  --checks all \
  --header "Authorization: Bearer $token" \
  --max-examples 100 \
  --generation-database none \
  --output-sanitize true >/dev/null
docker cp "$repo/docs" "$fuzzer:/spec"
docker cp "$repo/tools/contract/schemathesis.toml" "$fuzzer:/spec/schemathesis.toml"
schemathesis_exit=0
docker start -a "$fuzzer" || schemathesis_exit=$?
[[ "$schemathesis_exit" == "0" ]] || fail "o Schemathesis encontrou violações do contrato"
echo "ok: Schemathesis sem violações"
