#!/usr/bin/env bash
# Falha se docs/openapi.json tiver breaking change em relação à spec de uma revisão base (no CI, a
# main), salvo se a mudança também registrar a quebra em docs/api-changelog.md (docs/adr/0012).
# Uso: tools/contract/check-breaking.sh [revisão-base]   (padrão: origin/main)
set -euo pipefail

base="${1:-origin/main}"
oasdiff_image="tufin/oasdiff:v1.33.0@sha256:6263a96dd2ef0726c54e21fea9b8e1607eac4841add0079324b424c1f52b819c"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
spec="docs/openapi.json"
changelog="docs/api-changelog.md"
workdir="$repo/target/oasdiff"

cd "$repo"
if ! git cat-file -e "$base:$spec" 2>/dev/null; then
  echo "ok: $base não tem $spec; nada a comparar"
  exit 0
fi

mkdir -p "$workdir"
git show "$base:$spec" > "$workdir/base.json"
cp "$spec" "$workdir/head.json"

breaking=0
docker run --rm -v "$workdir:/specs:ro" "$oasdiff_image" \
  breaking /specs/base.json /specs/head.json --fail-on ERR || breaking=$?
if [[ "$breaking" == "0" ]]; then
  echo "ok: nenhuma breaking change em relação a $base"
  exit 0
fi

# A quebra consciente vem acompanhada da entrada no changelog, no mesmo PR.
if ! git diff --quiet "$base...HEAD" -- "$changelog"; then
  echo "aviso: breaking change aceita, registrada em $changelog"
  exit 0
fi
echo "FALHOU: breaking change no contrato. Se ela é intencional, registre-a em $changelog neste PR." >&2
exit 1
