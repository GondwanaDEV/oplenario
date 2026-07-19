#!/usr/bin/env sh
set -eu

# Task 2: o comando canônico único do harness — primeiro semeia (`semear.sh`, no host), depois roda o
# Playwright no container oficial (comando canônico da Task 1, documentado no README). Pré-requisito:
# a stack docker do projeto já de pé (`cd apps/backend && docker compose up -d --build`).

DIR="$(cd "$(dirname "$0")" && pwd)"

"$DIR/semear.sh"

docker run --rm --network host \
  -e PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
  -v "$DIR:/e2e" \
  -v oplenario_e2e_nm:/e2e/node_modules \
  -w /e2e \
  mcr.microsoft.com/playwright:v1.49.0-noble \
  sh -c "npm ci --ignore-scripts && npx playwright test"
