#!/usr/bin/env sh
set -eu

# Semente OPCIONAL para ver as telas AO VIVO (apps/backend/demo/ao_vivo.clj) — roda POR CIMA de `demo/semear-tudo.sh`,
# nunca no lugar dele, e NAO faz parte do que o CI/homologacao/producao semeiam. Cria uma sessao NOVA em curso com:
#   (a) uma materia aprovada em plenario e ainda sem autografo  -> /pos-aprovacao/:id (formulario do prazo de sancao/veto)
#   (b) uma votacao nominal ABERTA com votos ja' dados          -> telao /sessoes/:id/plenario e TV /sessoes/:id/tv
#   (c) uma votacao nominal ENCERRADA na mesma sessao           -> o resultado, depois de recarregar o telao
#
# ATENCAO: a demo ja' tem uma sessao aberta (a de `semear-tudo`). Com esta, sao DUAS sessoes em curso: o cockpit do
# vereador (/votar) abre a mais recente (esta) e oferece a troca, e o dashboard da Mesa passa a contar mais uma sessao
# em curso. Quem for rodar a Trilha 3 de e2e (`./e2e/rodar.sh`) NAO deve ter rodado este script antes no mesmo banco.
#
# Idempotente: rodar de novo RELE (nao duplica sessao, materia, voto nem votacao) e reimprime as URLs.
# Pre-condicao: `demo/semear-tudo.sh` ja' rodou (falha alto se nao). Mandato Docker: so' `docker`/`sh` no host.
#
# Mesmas env vars de host/porta de `semear-tudo.sh` (default = o valor que ESTE dev usa). `OPLENARIO_FRONTEND_URL`
# (default http://localhost:3000) so' compoe as URLs impressas; `OPLENARIO_DB_NAME` (default oplenario) permite
# apontar para outro banco (ex.: uma copia descartavel da demo).
OPLENARIO_DB_HOST="${OPLENARIO_DB_HOST:-localhost}"
OPLENARIO_PG_PORT="${OPLENARIO_PG_PORT:-5544}"
OPLENARIO_DB_NAME="${OPLENARIO_DB_NAME:-oplenario}"
OPLENARIO_MINIO_HOST="${OPLENARIO_MINIO_HOST:-localhost}"
OPLENARIO_MINIO_PORT="${OPLENARIO_MINIO_PORT:-9100}"
OPLENARIO_VALKEY_HOST="${OPLENARIO_VALKEY_HOST:-localhost}"
OPLENARIO_VALKEY_PORT="${OPLENARIO_VALKEY_PORT:-6379}"
OPLENARIO_FRONTEND_URL="${OPLENARIO_FRONTEND_URL:-http://localhost:3000}"

DIR="$(cd "$(dirname "$0")" && pwd)"      # .../demo
RAIZ="$(cd "$DIR/.." && pwd)"
ARTEFATOS="$RAIZ/e2e/.artifacts"

mkdir -p "$ARTEFATOS"

echo "==> semeando a sessao ao vivo (por cima da Casa de semear-tudo.sh)"
docker run --rm --network host \
  -v "$RAIZ/apps/backend:/app:ro" \
  -v "$ARTEFATOS:/demo-scratch" \
  -v oplenario_e2e_m2:/root/.m2 \
  -e CLJ_CACHE=/tmp/cpcache \
  -e DEMO_ARTIFACTS_DIR=/demo-scratch \
  -e OPLENARIO_FRONTEND_URL="$OPLENARIO_FRONTEND_URL" \
  -e DATABASE_URL="jdbc:postgresql://${OPLENARIO_DB_HOST}:${OPLENARIO_PG_PORT}/${OPLENARIO_DB_NAME}" \
  -e MINIO_ENDPOINT="http://${OPLENARIO_MINIO_HOST}:${OPLENARIO_MINIO_PORT}" \
  -e VALKEY_URI="redis://${OPLENARIO_VALKEY_HOST}:${OPLENARIO_VALKEY_PORT}" \
  -w /app clojure:temurin-21-tools-deps \
  clojure -Sdeps '{:aliases {:seed {:extra-paths ["demo"]}}}' -X:seed "ao-vivo/semear-ao-vivo!"

echo "==> semear-ao-vivo.sh OK"
