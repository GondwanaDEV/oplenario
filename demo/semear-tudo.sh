#!/usr/bin/env sh
set -eu

# Task 0.7 (docs/superpowers/plans/2026-09-07-prontidao-de-apresentacao.md) — orquestra as 4 sementes
# NARRATIVAS de `apps/backend/demo/` na ordem OBRIGATORIA `casa -> acervo -> sessoes -> participacao`
# (cada uma depende da anterior — ver o cabecalho de `apps/backend/demo/semear_tudo.clj`). Roda no HOST
# (mandato Docker: so' `docker`/`git`/`sh`/`curl` no host, nunca `clj` direto) — mesmo padrao de
# `e2e/semear.sh`, que este script REUSA em vez de duplicar (mesmas flags de docker run, mesmas env vars
# de porta/host, mesma barreira de projecao por poll — ampliada aqui com o read-model do perfil do
# vereador, alem das materias que `e2e/semear.sh` ja cobre).
#
# As 4 funcoes `semear!` (`casa`/`acervo`/`sessoes`/`participacao`) sao BIBLIOTECA, nao entry-points
# `-X` proprios (recebem `sistema`, um sistema Component ja BOOTADO, e tem de rodar as 4 sobre o MESMO
# boot — reabrir o sistema entre chamadas quebraria a garantia de idempotencia de `casa/semear!`, que so'
# rele o cadastro se `ja-semeada?` achar a legislatura no MESMO tenant). Por isso este script chama UM
# UNICO entry-point `-X`, `semear-tudo/semear-tudo!` (apps/backend/demo/semear_tudo.clj), que boota o
# sistema uma vez e sequencia as 4 por dentro.
#
# CARRY reconciliado (Task 0.2, citado no briefing da Task 0.7): `casa/semear!` grava o artefato em
# `<DEMO_ARTIFACTS_DIR>/demo-ids.edn` (default `.artifacts`, RELATIVO ao CWD do container — ou seja
# `apps/backend/.artifacts/`, porque o `docker run` roda com `-w /app` sobre o mount `:ro` de
# `apps/backend`). A sonda da Task 1.1 (ainda nao escrita) e `e2e/semear.sh` leem de `e2e/.artifacts/`.
# Em vez de duplicar o arquivo em dois lugares (duas fontes de verdade que divergem na primeira falha),
# apontamos `DEMO_ARTIFACTS_DIR=/demo-scratch` para dentro do container, montado a partir do MESMO
# `e2e/.artifacts` que o resto do ferramental ja le — uma unica escrita, um unico arquivo.
#
# Mesmas env vars de host/porta que `e2e/semear.sh` (default = o valor que ESTE dev usa, nao o default
# da compose sem `.env` — ver o comentario equivalente em `e2e/semear.sh` p/ o racional completo).
OPLENARIO_DB_HOST="${OPLENARIO_DB_HOST:-localhost}"
OPLENARIO_PG_PORT="${OPLENARIO_PG_PORT:-5544}"
OPLENARIO_MINIO_HOST="${OPLENARIO_MINIO_HOST:-localhost}"
OPLENARIO_MINIO_PORT="${OPLENARIO_MINIO_PORT:-9100}"
OPLENARIO_VALKEY_HOST="${OPLENARIO_VALKEY_HOST:-localhost}"
OPLENARIO_VALKEY_PORT="${OPLENARIO_VALKEY_PORT:-6379}"
# Host/porta do app SERVIDO (rotas publicas `/portal/casa/:ente/...`) — usado so' pela barreira de
# projecao no fim deste script, nunca pelo container efemero da semente (que fala com Postgres direto).
OPLENARIO_APP_HOST="${OPLENARIO_APP_HOST:-localhost}"
OPLENARIO_APP_PORT="${OPLENARIO_APP_PORT:-8888}"

DIR="$(cd "$(dirname "$0")" && pwd)"      # .../demo (raiz do projeto/demo)
RAIZ="$(cd "$DIR/.." && pwd)"
ARTEFATOS="$RAIZ/e2e/.artifacts"

mkdir -p "$ARTEFATOS"

echo "==> semeando a Casa (casa -> acervo -> sessoes -> participacao)"
docker run --rm --network host \
  -v "$RAIZ/apps/backend:/app:ro" \
  -v "$ARTEFATOS:/demo-scratch" \
  -v oplenario_e2e_m2:/root/.m2 \
  -e CLJ_CACHE=/tmp/cpcache \
  -e DEMO_ARTIFACTS_DIR=/demo-scratch \
  -e DATABASE_URL="jdbc:postgresql://${OPLENARIO_DB_HOST}:${OPLENARIO_PG_PORT}/oplenario" \
  -e MINIO_ENDPOINT="http://${OPLENARIO_MINIO_HOST}:${OPLENARIO_MINIO_PORT}" \
  -e VALKEY_URI="redis://${OPLENARIO_VALKEY_HOST}:${OPLENARIO_VALKEY_PORT}" \
  -w /app clojure:temurin-21-tools-deps \
  clojure -Sdeps '{:aliases {:seed {:extra-paths ["demo"]}}}' -X:seed "semear-tudo/semear-tudo!"

echo "==> semente ok — artefato em $ARTEFATOS/demo-ids.edn"

# ---------- a barreira de projecao ----------
# `acervo/semear!` e `participacao/semear!` escrevem via os Repo reais -> `shared.outbox`. Quem
# materializa `transparencia.materia` (a lista publica) E o read-model do perfil do vereador (que LE
# `transparencia.materia` por autor, mesma tabela) e' o relay do app SERVIDO — loop assincrono, intervalo
# default 1000ms. Sem esta barreira, a rota publica devolve 200 com lista/contagem VAZIA (um assert de
# status nao pega isso). Poll bounded, falha alta com mensagem acionavel — nunca fallback silencioso.
ENTE_ID="$(sed -n 's/.*:ente[[:space:]]*#uuid[[:space:]]*"\([0-9a-fA-F-]\{36\}\)".*/\1/p' "$ARTEFATOS/demo-ids.edn")"
if [ -z "$ENTE_ID" ]; then
  echo "ERRO: não foi possível extrair \":ente #uuid ...\" de $ARTEFATOS/demo-ids.edn" >&2
  exit 1
fi
# O 1º `:id #uuid ...` do arquivo e' sempre o do 1º vereador do vetor `:vereadores` (idx 0, o presidente
# da Mesa — `casa.clj:88`) porque nenhuma outra chave do topo do EDN se chama `:id` (as demais sao
# `:ente`/`:legislatura`/`:identidades`; `:mandato-id` dentro de cada vereador tem outro nome). A
# autoria das materias em `acervo.clj` e' round-robin sobre os 17 vereadores (`acervo.clj:218`), entao o
# vereador idx 0 SEMPRE autora pelo menos 1 materia (idx 0 e 17 de 24) — `materias-total` dele nunca fica
# em 0 depois que a projecao materializa.
VEREADOR_ID="$(sed -n 's/.*:id[[:space:]]*#uuid[[:space:]]*"\([0-9a-fA-F-]\{36\}\)".*/\1/p' "$ARTEFATOS/demo-ids.edn" | head -1)"
if [ -z "$VEREADOR_ID" ]; then
  echo "ERRO: não foi possível extrair o \":id\" do 1º vereador de $ARTEFATOS/demo-ids.edn" >&2
  exit 1
fi

MATERIAS_URL="http://${OPLENARIO_APP_HOST}:${OPLENARIO_APP_PORT}/portal/casa/${ENTE_ID}/materias"
PERFIL_URL="http://${OPLENARIO_APP_HOST}:${OPLENARIO_APP_PORT}/portal/casa/${ENTE_ID}/vereadores/${VEREADOR_ID}"

echo "==> aguardando a projecao assincrona (relay) materializar as materias em $MATERIAS_URL"
TENTATIVAS=30
i=0
ok_materias=0
while [ "$i" -lt "$TENTATIVAS" ]; do
  BODY="$(curl -sf "$MATERIAS_URL" 2>/dev/null || true)"
  case "$BODY" in
    *'"proposicao-id"'*)
      ok_materias=1
      break
      ;;
  esac
  i=$((i + 1))
  sleep 1
done
if [ "$ok_materias" -ne 1 ]; then
  echo "ERRO: as materias foram semeadas mas a projecao não chegou em ${TENTATIVAS}s — o container 'app' (o relay) está de pé? ($MATERIAS_URL)" >&2
  exit 1
fi
echo "==> projecao das materias ok — visiveis em $MATERIAS_URL"

echo "==> aguardando a projecao materializar o read-model do perfil do vereador em $PERFIL_URL"
i=0
ok_perfil=0
while [ "$i" -lt "$TENTATIVAS" ]; do
  BODY="$(curl -sf "$PERFIL_URL" 2>/dev/null || true)"
  case "$BODY" in
    *'"materias-total":0'*) ;;
    *'"materias-total":'*)
      ok_perfil=1
      break
      ;;
  esac
  i=$((i + 1))
  sleep 1
done
if [ "$ok_perfil" -ne 1 ]; then
  echo "ERRO: o perfil do vereador foi semeado mas a projecao do read-model não chegou em ${TENTATIVAS}s — o container 'app' (o relay) está de pé? ($PERFIL_URL)" >&2
  exit 1
fi
echo "==> projecao do perfil do vereador ok — visivel em $PERFIL_URL"
echo "==> semear-tudo.sh OK"
