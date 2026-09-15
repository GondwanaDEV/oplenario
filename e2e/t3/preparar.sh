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
# O id da identidade :vereador e' resolvido de demo-ids.edn (nao cravado em fixtures.sql): num seed
# fresco ele e' novo/aleatorio. Mesma extracao por regex que preparar.mjs faz do bloco :identidades.
DEMO_IDS="$E2E/.artifacts/demo-ids.edn"
[ -f "$DEMO_IDS" ] || { echo "ERRO: $DEMO_IDS ausente — rode a semente CHEIA (demo/semear-tudo.sh) antes." >&2; exit 1; }
VEREADOR_IDENTIDADE="$(grep -oE ':vereador #uuid "[0-9a-fA-F-]{36}"' "$DEMO_IDS" | head -1 | grep -oE '[0-9a-fA-F-]{36}')"
[ -n "$VEREADOR_IDENTIDADE" ] || { echo "ERRO: nao achei a identidade :vereador em $DEMO_IDS" >&2; exit 1; }
echo "   vereador_identidade (de demo-ids.edn) = $VEREADOR_IDENTIDADE"
docker exec -i oplenario-postgres-1 psql -U oplenario -d oplenario -v ON_ERROR_STOP=1 \
  -v vereador_identidade="$VEREADOR_IDENTIDADE" \
  < "$E2E/t3/fixtures.sql"

echo
echo "== 2/3 preparar.mjs (tudo o que TEM rota HTTP) =="
# `--user $(id -u):$(id -g)`: sem isto o container escreve `.artifacts/t3-ids.json` como ROOT (uid 0),
# e o passo 3/3 (que roda no HOST/runner como usuario nao-root) nao consegue criar t3-versoes.json no
# mesmo diretorio -> "Permission denied" (medido no 1o run de CI do job t3-e2e). Com --user o artefato
# nasce dono do chamador. No dev local (root no OrbStack) `id -u`=0 e o comportamento nao muda.
docker run --rm --network host \
  --user "$(id -u):$(id -g)" \
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
