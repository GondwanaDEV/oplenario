#!/usr/bin/env sh
set -eu

# Task 2: orquestra os 3 seeds efêmeros de `seed_demo.clj` (backend) na ordem OBRIGATÓRIA —
# `materias`/`encarregado` leem o `.artifacts/demo-ids.edn` que `base` escreve; sem `base` primeiro,
# eles falham. Roda no HOST (mandato Docker: só `docker`/`git`/`sh` no host, nunca `clj` direto).
#
# Cada flag do `docker run` corrige uma armadilha real medida (ver o brief da Task 2):
#   - mount do backend `:ro` + CLJ_CACHE=/tmp/cpcache -> honra o guardrail de NUNCA mutar o mount vivo
#     (`apps/backend` é a fonte servida pelo `next dev`/app da stack; sem CLJ_CACHE, o tools-deps
#     escreveria `.cpcache/` dentro do mount).
#   - `-v oplenario_e2e_m2:/root/.m2` -> cache Maven em volume de CONTAINER; o host nunca é escrito.
#   - `--network host` -> alcança postgres :5544 / minio :9100 / valkey :6379 da stack já de pé.
#   - NÃO adiciona alias `:seed` em `apps/backend/deps.edn` — o `-Sdeps` inline já resolve (deps.edn
#     fica intocado, como o docstring do próprio `seed_demo.clj` documenta).

DIR="$(cd "$(dirname "$0")" && pwd)"
RAIZ="$(cd "$DIR/../../.." && pwd)"

mkdir -p "$DIR/.artifacts"

rodar_seed() {
  fn="$1"
  echo "==> seed-demo/$fn"
  docker run --rm --network host \
    -v "$RAIZ/apps/backend:/app:ro" \
    -v "$DIR/.artifacts:/demo-scratch" \
    -v oplenario_e2e_m2:/root/.m2 \
    -e CLJ_CACHE=/tmp/cpcache \
    -e DATABASE_URL=jdbc:postgresql://localhost:5544/oplenario \
    -e MINIO_ENDPOINT=http://localhost:9100 \
    -e VALKEY_URI=redis://localhost:6379 \
    -w /app clojure:temurin-21-tools-deps \
    clojure -Sdeps '{:aliases {:seed {:extra-paths ["demo"]}}}' -X:seed "seed-demo/$fn"
}

rodar_seed base
rodar_seed materias
rodar_seed encarregado

echo "==> seeds ok — artefato em $DIR/.artifacts/demo-ids.edn"
