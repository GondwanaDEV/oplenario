#!/usr/bin/env sh
set -eu

# Semente de CREDENCIAIS Keycloak — a 5a semente da demo (apps/backend/demo/personas.clj), no molde de
# `semear-tudo.sh` (docker run com -Sdeps inline, :extra-paths ["demo"], falha alto e claro). SEPARADA
# das 4 sementes NARRATIVAS de proposito: o Keycloak so' existe sob `--profile auth`; embutir isto em
# `semear-tudo.sh` quebraria a semente narrativa p/ quem roda sem auth.
#
# DIFERENCA DELIBERADA em relacao a `semear-tudo.sh` (que usa `--network host` + localhost:<porta
# publicada>): esta semente PRECISA alcancar o Keycloak pelo DNS do compose (`http://keycloak:8080`),
# NUNCA por localhost/porta publicada — armadilha ja' paga neste projeto (o token admin do KC 26 vive
# so' ~56s; a semente `personas.clj` ja' se defende disso, mas so' se ela FALAR com o KC certo). Um
# container em `--network host` nao resolve nomes de servico do compose; por isso este script entra na
# rede do PROPRIO compose (`oplenario_default`, o mesmo nome que o comando de suite deste repo ja' usa)
# e fala com TODOS os servicos pelo nome DNS interno (postgres/valkey/minio/keycloak) — nunca por porta
# publicada no host, que so' faria sentido em `--network host`.
#
# PRE-REQUISITO 1: a stack de pe' com o profile auth (Keycloak + Mailpit):
#   OPLENARIO_APP_ENV=production docker compose --profile auth up -d
# O Keycloak demora ~1 MINUTO p/ bootar num `up` frio — enquanto sobe ele responde com 400 nas chamadas
# admin, e isso NAO E' erro de credencial. Este script checa a porta ANTES de tentar provisionar e diz
# ao operador exatamente o que rodar se o Keycloak nao estiver de pe'.
#
# PRE-REQUISITO 2: a Casa da demo ja' semeada (`./demo/semear-tudo.sh`) — `personas.clj` SO' LE a Casa
# pelos CPFs fixos, nunca a cria; sem ela, a semente falha alto com uma mensagem acionavel (nao um erro
# de infra obscuro).

REDE="${OPLENARIO_DOCKER_NETWORK:-oplenario_default}"
KEYCLOAK_BASE_URL="${KEYCLOAK_BASE_URL:-http://keycloak:8080}"
KC_HOST="$(printf '%s' "$KEYCLOAK_BASE_URL" | sed -E 's#^https?://##; s#/.*$##; s#:.*$##')"
KC_PORT="$(printf '%s' "$KEYCLOAK_BASE_URL" | sed -E 's#^https?://[^:/]+(:([0-9]+))?.*#\2#')"
KC_PORT="${KC_PORT:-8080}"

echo "==> checando se o Keycloak responde em $KEYCLOAK_BASE_URL (rede docker $REDE, host=$KC_HOST porta=$KC_PORT)"
TENTATIVAS=30
i=0
ok_kc=0
while [ "$i" -lt "$TENTATIVAS" ]; do
  # Checagem HTTP crua via bash /dev/tcp (a imagem base nao tem curl/wget) — qualquer linha de status
  # HTTP (200, 401, 403, e ATE' 400 durante o boot) prova que o Keycloak esta' escutando e respondendo
  # protocolo HTTP; so' a AUSENCIA de resposta (timeout/conexao recusada) significa "ainda nao subiu".
  LINHA_STATUS="$(docker run --rm --network "$REDE" --entrypoint bash clojure:temurin-21-tools-deps -c \
    "exec 3<>/dev/tcp/${KC_HOST}/${KC_PORT} && printf 'GET /realms/master HTTP/1.0\r\nHost: ${KC_HOST}\r\n\r\n' >&3 && head -1 <&3" \
    2>/dev/null || true)"
  case "$LINHA_STATUS" in
    HTTP/*) ok_kc=1; break ;;
  esac
  i=$((i + 1))
  sleep 2
done
if [ "$ok_kc" -ne 1 ]; then
  echo "ERRO: o Keycloak nao respondeu em $KEYCLOAK_BASE_URL apos $((TENTATIVAS * 2))s." >&2
  echo "       suba a stack com o profile auth:" >&2
  echo "         OPLENARIO_APP_ENV=production docker compose --profile auth up -d" >&2
  echo "       o Keycloak demora ~1 min p/ bootar num 'up' frio — um 400 nesse meio tempo NAO e' erro de credencial." >&2
  exit 1
fi
echo "==> Keycloak respondendo ($LINHA_STATUS)"

echo "==> semeando as credenciais das 4 personas (secretaria/presidente/vereador/cidada)"
docker run --rm --network "$REDE" \
  -v "$(cd "$(dirname "$0")/../apps/backend" && pwd):/app:ro" \
  -v oplenario_e2e_m2:/root/.m2 \
  -e CLJ_CACHE=/tmp/cpcache \
  -e DATABASE_URL="jdbc:postgresql://postgres:5432/oplenario" \
  -e DB_USER=oplenario -e DB_PASSWORD=dev \
  -e MINIO_ENDPOINT="http://minio:9000" \
  -e VALKEY_URI="redis://valkey:6379" \
  -e KEYCLOAK_BASE_URL="$KEYCLOAK_BASE_URL" \
  -w /app clojure:temurin-21-tools-deps \
  clojure -Sdeps '{:aliases {:seed {:extra-paths ["demo"]}}}' -X:seed "personas/semear-credenciais!"

echo "==> semear-credenciais.sh OK"
