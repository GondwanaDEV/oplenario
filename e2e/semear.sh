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
#
# I5 (achado da revisão final): o HOST estava cravado em `localhost` — só as portas eram parametrizadas.
# `--network host` não é portátil (não existe em Docker Desktop pra Windows/algumas VMs Linux); com um
# host diferente (ex. `host.docker.internal`, documentado no README como fallback do container do
# Playwright), o seed não tinha escape. Agora o host de cada serviço também é env var, default =
# `localhost` (o valor que `--network host` resolve neste ambiente).
OPLENARIO_DB_HOST="${OPLENARIO_DB_HOST:-localhost}"
OPLENARIO_PG_PORT="${OPLENARIO_PG_PORT:-5544}"
OPLENARIO_MINIO_HOST="${OPLENARIO_MINIO_HOST:-localhost}"
OPLENARIO_MINIO_PORT="${OPLENARIO_MINIO_PORT:-9100}"
OPLENARIO_VALKEY_HOST="${OPLENARIO_VALKEY_HOST:-localhost}"
OPLENARIO_VALKEY_PORT="${OPLENARIO_VALKEY_PORT:-6379}"
# I3: host/porta do app servido (rota pública `/portal/casa/:ente/materias`) — usado só pela barreira de
# projeção no fim deste script (não pelos containers efêmeros de seed acima, que falam com Postgres/MinIO
# direto).
OPLENARIO_APP_HOST="${OPLENARIO_APP_HOST:-localhost}"
OPLENARIO_APP_PORT="${OPLENARIO_APP_PORT:-8888}"

DIR="$(cd "$(dirname "$0")" && pwd)"
RAIZ="$(cd "$DIR/.." && pwd)"

# M7 (achado da revisão final): sem isso, um `demo-ids.edn`/`ente.json` de uma rodada anterior sobrevive
# se este script falhar no meio, e um `npx playwright test` manual subsequente passa contra o ente da
# rodada velha (falso positivo). Limpa ANTES de semear, não depois — se este script falhar, os artefatos
# ficam ausentes (falha alta e óbvia no próximo `global-setup.ts`), nunca "meio-semeados".
rm -f "$DIR/.artifacts/demo-ids.edn" "$DIR/.artifacts/ente.json"
mkdir -p "$DIR/.artifacts"

rodar_seed() {
  fn="$1"
  echo "==> seed-demo/$fn"
  docker run --rm --network host \
    -v "$RAIZ/apps/backend:/app:ro" \
    -v "$DIR/.artifacts:/demo-scratch" \
    -v oplenario_e2e_m2:/root/.m2 \
    -e CLJ_CACHE=/tmp/cpcache \
    -e DATABASE_URL="jdbc:postgresql://${OPLENARIO_DB_HOST}:${OPLENARIO_PG_PORT}/oplenario" \
    -e MINIO_ENDPOINT="http://${OPLENARIO_MINIO_HOST}:${OPLENARIO_MINIO_PORT}" \
    -e VALKEY_URI="redis://${OPLENARIO_VALKEY_HOST}:${OPLENARIO_VALKEY_PORT}" \
    -w /app clojure:temurin-21-tools-deps \
    clojure -Sdeps '{:aliases {:seed {:extra-paths ["demo"]}}}' -X:seed "seed-demo/$fn"
}

rodar_seed base
rodar_seed materias
rodar_seed encarregado

echo "==> seeds ok — artefato em $DIR/.artifacts/demo-ids.edn"

# I3 (achado da revisão final): `seed-demo/materias` escreve via os Repo reais -> `shared.outbox`. Quem
# materializa `transparencia.materia` (o que a rota pública lê) é o relay do app SERVIDO — um loop
# assíncrono com intervalo default de 1000ms. Sem esta barreira, o Playwright poderia disparar contra a
# rota antes do relay materializar, e a rota devolve 200 com lista VAZIA (o assert #4 do spec só checa
# status === 200, que uma lista vazia já satisfaz — não protege). Poll bounded até a lista vir não-vazia,
# com falha alta e mensagem acionável (não fallback silencioso).
ENTE_ID="$(sed -n 's/.*:ente[[:space:]]*#uuid[[:space:]]*"\([0-9a-fA-F-]\{36\}\)".*/\1/p' "$DIR/.artifacts/demo-ids.edn")"
if [ -z "$ENTE_ID" ]; then
  echo "ERRO: não foi possível extrair \":ente #uuid ...\" de $DIR/.artifacts/demo-ids.edn" >&2
  exit 1
fi

URL="http://${OPLENARIO_APP_HOST}:${OPLENARIO_APP_PORT}/portal/casa/${ENTE_ID}/materias"
echo "==> aguardando a projeção assíncrona (relay) materializar as matérias em $URL"
TENTATIVAS=30
i=0
ok=0
while [ "$i" -lt "$TENTATIVAS" ]; do
  BODY="$(curl -sf "$URL" 2>/dev/null || true)"
  case "$BODY" in
    *'"proposicao-id"'*)
      ok=1
      break
      ;;
  esac
  i=$((i + 1))
  sleep 1
done
if [ "$ok" -ne 1 ]; then
  echo "ERRO: as matérias foram semeadas mas a projeção não chegou em ${TENTATIVAS}s — o container 'app' está de pé? ($URL)" >&2
  exit 1
fi
echo "==> projeção ok — matérias visíveis em $URL"
