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
#   - `--network host` -> alcança postgres/minio/valkey da stack já de pé.
#   - NÃO adiciona alias `:seed` em `apps/backend/deps.edn` — o `-Sdeps` inline já resolve (deps.edn
#     fica intocado, como o docstring do próprio `seed_demo.clj` documenta).
#
# Task 4 (carry de CI): portas parametrizadas por env var, default = valor deste dev (5544/9100) — o
# `docker-compose.yml` do backend usa `OPLENARIO_PG_PORT`/`OPLENARIO_MINIO_PORT` (default 5432/9000 sem
# `.env`); reusamos o MESMO nome de variável aqui de propósito, mas com default DIFERENTE (o valor que
# este dev já usa), porque sem `.env` a compose cairia nos defaults dela (5432/9000), não nos deste dev.
# Uma CI futura sem `.env` exportaria `OPLENARIO_PG_PORT=5432 OPLENARIO_MINIO_PORT=9000` antes de chamar
# este script para casar com a compose. Valkey fica sem parametrizar: o default da compose (6379) já é
# o mesmo valor que este dev usa, não há divergência a cobrir.
OPLENARIO_PG_PORT="${OPLENARIO_PG_PORT:-5544}"
OPLENARIO_MINIO_PORT="${OPLENARIO_MINIO_PORT:-9100}"

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
    -e DATABASE_URL="jdbc:postgresql://localhost:${OPLENARIO_PG_PORT}/oplenario" \
    -e MINIO_ENDPOINT="http://localhost:${OPLENARIO_MINIO_PORT}" \
    -e VALKEY_URI=redis://localhost:6379 \
    -w /app clojure:temurin-21-tools-deps \
    clojure -Sdeps '{:aliases {:seed {:extra-paths ["demo"]}}}' -X:seed "seed-demo/$fn"
}

rodar_seed base
rodar_seed materias
rodar_seed encarregado

echo "==> seeds ok — artefato em $DIR/.artifacts/demo-ids.edn"
