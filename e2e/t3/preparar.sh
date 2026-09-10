#!/usr/bin/env bash
# e2e/t3/preparar.sh — deixa o mundo pronto para os 8 specs da Trilha 3.
#
# MANDATO DOCKER: nada roda no host. Duas etapas, ambas em container:
#   1) fixtures.sql  -> psql DENTRO de oplenario-postgres-1 (as 2 precondicoes sem rota HTTP)
#   2) preparar.mjs  -> node DENTRO da imagem oficial do Playwright, --network host
#
# NAO E DESTRUTIVO: nenhum down/-v, nenhum DROP/TRUNCATE/DELETE, nenhuma escrita no mount vivo
# de apps/frontend. O unico volume montado como rw e e2e/ (para gravar .artifacts/t3-ids.json).
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
E2E="$RAIZ/e2e"

echo "== 1/2 fixtures.sql (documento_modelo + notificacao_caixa) =="
docker exec -i oplenario-postgres-1 psql -U oplenario -d oplenario -v ON_ERROR_STOP=1 \
  < "$E2E/t3/fixtures.sql"

echo
echo "== 2/2 preparar.mjs (tudo o que TEM rota HTTP) =="
docker run --rm --network host \
  -v "$E2E":/e2e \
  -v oplenario_e2e_nm:/e2e/node_modules \
  -w /e2e \
  mcr.microsoft.com/playwright:v1.49.0-noble \
  node /e2e/t3/preparar.mjs
