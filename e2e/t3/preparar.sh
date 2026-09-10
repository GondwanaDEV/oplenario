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
echo "== 2/3 preparar.mjs (tudo o que TEM rota HTTP) =="
docker run --rm --network host \
  -v "$E2E":/e2e \
  -v oplenario_e2e_nm:/e2e/node_modules \
  -w /e2e \
  mcr.microsoft.com/playwright:v1.49.0-noble \
  node /e2e/t3/preparar.mjs

echo
echo "== 3/3 T3-A2 — os dois texto_versao_id (votado vs. atual) — psql DIRETO, sem rota HTTP p/ isto =="
# `AutografoOut` e' o UNICO wire que expoe `texto-versao-id`; a votacao e a proposicao nao expoem esse id
# em rota nenhuma (ver adapters/out/{votacao,proposicao,ficha_materia}.clj). Mesmo precedente de
# fixtures.sql acima: leitura DIRETA por psql, nao uma rota que nao existe. So' SELECT — zero escrita.
ARQ_IDS="$E2E/t3/.artifacts/t3-ids.json"
ARQ_VERSOES="$E2E/t3/.artifacts/t3-versoes.json"
extrair_uuid() {
  # $1 = chave JSON (ex.: "proposicaoId"), dentro do bloco "textoTrocado": {...}
  grep -o "\"$1\": *\"[0-9a-f-]\{36\}\"" "$ARQ_IDS" | head -1 | grep -o '[0-9a-f-]\{36\}'
}
PROP_ID="$(extrair_uuid proposicaoTextoTrocadoId)"
VOTACAO_ID="$(extrair_uuid votacaoTextoTrocadoId)"
ENTE_ID="$(grep -o '"ente": *"[0-9a-f-]\{36\}"' "$ARQ_IDS" | head -1 | grep -o '[0-9a-f-]\{36\}')"
if [ -z "$PROP_ID" ] || [ -z "$VOTACAO_ID" ] || [ -z "$ENTE_ID" ]; then
  echo "AVISO: preparar.mjs nao deixou e7.textoTrocado (ver bloqueios em t3-ids.json) — pulando a prova das versoes."
  echo '{"nota":"e7.textoTrocado ausente — ver bloqueios em t3-ids.json"}' > "$ARQ_VERSOES"
else
  TEXTO_VERSAO_VOTADA_ID="$(docker exec -i oplenario-postgres-1 psql -U oplenario -d oplenario -tA -v ON_ERROR_STOP=1 \
    -c "select texto_versao_id from legislativo.votacoes where ente_id='$ENTE_ID' and id='$VOTACAO_ID';")"
  TEXTO_VERSAO_ATUAL_ID="$(docker exec -i oplenario-postgres-1 psql -U oplenario -d oplenario -tA -v ON_ERROR_STOP=1 \
    -c "select texto_vigente_versao_id from legislativo.proposicoes where ente_id='$ENTE_ID' and id='$PROP_ID';")"
  if [ -z "$TEXTO_VERSAO_VOTADA_ID" ] || [ -z "$TEXTO_VERSAO_ATUAL_ID" ]; then
    echo "ERRO: psql nao devolveu os dois texto_versao_id (votacao=$VOTACAO_ID prop=$PROP_ID) — nada gravado em t3-versoes.json." >&2
    exit 1
  fi
  cat > "$ARQ_VERSOES" <<EOF
{
  "proposicaoId": "$PROP_ID",
  "votacaoId": "$VOTACAO_ID",
  "textoVersaoVotadaId": "$TEXTO_VERSAO_VOTADA_ID",
  "textoVersaoAtualId": "$TEXTO_VERSAO_ATUAL_ID"
}
EOF
  echo "textoVersaoVotadaId=$TEXTO_VERSAO_VOTADA_ID  textoVersaoAtualId=$TEXTO_VERSAO_ATUAL_ID"
  if [ "$TEXTO_VERSAO_VOTADA_ID" = "$TEXTO_VERSAO_ATUAL_ID" ]; then
    echo "ERRO: as duas versoes saíram IGUAIS — a fixture nao criou divergencia nenhuma (preparar.mjs deveria ter falhado antes)." >&2
    exit 1
  fi
  echo "==> $ARQ_VERSOES"
fi
