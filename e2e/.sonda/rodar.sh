#!/usr/bin/env sh
set -eu

# Embrulha o comando canônico da sonda (README.md) — chamado por nome de `docs/superpowers/plans/
# 2026-09-07-prontidao-de-apresentacao.md` §8 (Fase 5) e pela própria Task 1.1/1.2. Roda no HOST
# (mandato Docker: só `docker`/`git`/`sh`/`curl` no host, nunca `node` direto) — mesmo padrão de
# `demo/semear-tudo.sh` e `e2e/rodar.sh`.
#
# Stack de pé + demo semeada são pré-condição (ver README.md); a sonda falha alto e sozinha se
# `e2e/.artifacts/demo-ids.edn` não existir.
DIR="$(cd "$(dirname "$0")" && pwd)"       # .../e2e/.sonda
RAIZ="$(cd "$DIR/../.." && pwd)"

docker run --rm --network host --shm-size=1g \
  -e PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
  -v "$RAIZ/e2e:/e2e" -v oplenario_e2e_nm:/e2e/node_modules -w /e2e \
  mcr.microsoft.com/playwright:v1.49.0-noble node .sonda/sonda.mjs
