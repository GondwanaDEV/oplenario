#!/usr/bin/env bash
# Sonda da Trilha 2 / Grupo A — as 17 rotas de CONDUCAO DE SESSAO por HTTP direto (sem UI).
#
# Por que bash+curl+psql no HOST, e nao node/clj em container: esta sonda nao renderiza nada (a
# irma `sonda.mjs` usa Playwright porque testa o FRONTEND); aqui o alvo e' o BACKEND por HTTP puro.
# O mandato Docker deste projeto proibe `node`/`npx`/`clj` direto no host — nao proibe `curl`/`docker
# exec`/`sh`, que sao as mesmas ferramentas de orquestracao que `e2e/semear.sh` ja usa no host para
# falar com Postgres/MinIO. Nao ha app-code do projeto rodando aqui, so' chamadas HTTP + psql.
#
# Usa a sessao AGENDADA da demo (…0212) — NUNCA a …0211 (aberta, com fala aberta de proposito para
# o telao). E' escrita: NAO e' re-rodavel sem reseed (agendada->aberta->encerrada e' via de mao
# unica) — rode depois de `./demo/semear-tudo.sh` numa stack fresca, como a T2 do plano manda.
#
# Sai != 0 se qualquer QUEBRA ou FRAGIL foi observado. COSMETICO/GAP nunca derrubam o exit code.
set -u

DIR="$(cd "$(dirname "$0")" && pwd)"          # .../e2e/.sonda
RAIZ="$(cd "$DIR/../.." && pwd)"
ARTEFATO="$RAIZ/e2e/.artifacts/demo-ids.edn"

BACKEND="${OPLENARIO_BACKEND:-http://localhost:8888}"
PG_CONTAINER="${OPLENARIO_PG_CONTAINER:-oplenario-postgres-1}"
APP_CONTAINER="${OPLENARIO_APP_CONTAINER:-oplenario-app-1}"
SESSAO="10000000-0000-0000-0000-000000000212"          # AGENDADA — a sessao desta sonda
SESSAO_ABERTA_DEMO="10000000-0000-0000-0000-000000000211"  # NUNCA escrita aqui — so leitura

if [ ! -f "$ARTEFATO" ]; then
  echo "ERRO: $ARTEFATO nao existe — rode ./demo/semear-tudo.sh antes." >&2
  exit 1
fi

# ---------- ids da semente (nunca cravados a mao — mesmo principio da sonda.mjs) ----------
EDN="$(cat "$ARTEFATO")"
py() { python3 -c "$1" "$EDN"; }

ENTE=$(py '
import sys, re
edn = sys.argv[1]
m = re.search(r":ente\s+#uuid\s+\"([0-9a-fA-F-]{36})\"", edn)
print(m.group(1))')
VEREADOR1=$(py '
import sys, re
edn = sys.argv[1]
m = re.search(r":vereadores\s+\[\{:id\s+#uuid\s+\"([0-9a-fA-F-]{36})\"", edn)
print(m.group(1))')
VEREADOR2=$(py '
import sys, re
edn = sys.argv[1]
ms = re.findall(r":id\s+#uuid\s+\"([0-9a-fA-F-]{36})\"", edn)
# ms[0] = 1o vereador (idx0 do vetor :vereadores); ms[1] = 2o vereador — mesma leitura posicional
# que a sonda.mjs ja faz para o 1o.
print(ms[1])')
IDENT_SECRETARIA=$(py '
import sys, re
edn = sys.argv[1]
bloco = re.search(r":identidades\s*\{([\s\S]*)\}\s*\}?\s*$", edn).group(1)
print(re.search(r":secretaria\s+#uuid\s+\"([0-9a-fA-F-]{36})\"", bloco).group(1))')
IDENT_VEREADOR=$(py '
import sys, re
edn = sys.argv[1]
bloco = re.search(r":identidades\s*\{([\s\S]*)\}\s*\}?\s*$", edn).group(1)
print(re.search(r":vereador\s+#uuid\s+\"([0-9a-fA-F-]{36})\"", bloco).group(1))')

if [ -z "$ENTE" ] || [ -z "$VEREADOR1" ] || [ -z "$IDENT_SECRETARIA" ] || [ -z "$IDENT_VEREADOR" ]; then
  echo "ERRO: nao consegui extrair ente/vereadores/identidades de $ARTEFATO — formato mudou?" >&2
  exit 1
fi

TSEC="{\"identidade-id\":\"$IDENT_SECRETARIA\",\"ente-id\":\"$ENTE\",\"papeis\":[\"secretario\"]}"
TVER="{\"identidade-id\":\"$IDENT_VEREADOR\",\"ente-id\":\"$ENTE\",\"papeis\":[\"vereador\"]}"

# ---------- Casa errada: um intruso REAL (identidade+vinculo+papel ativos em OUTRO ente) ----------
# `idp-dev` so decodifica o JSON (confianca total, §22.5); mas `identidade.autenticacao/resolver-
# sessao` IGNORA o campo "papeis" do token e RESOLVE os papeis do snapshot do banco
# (identidade.vinculo + identidade.usuario_papel) para (ente-id, identidade-id) — lido em
# apps/backend/src/oplenario/identidade/autenticacao.clj. Um token com ente-id forjado sem vinculo
# REAL nesse ente cai em 401 "sem vinculo ativo", nao testa isolamento nenhum. Por isso a sonda cria
# um fixture REAL (idempotente, ids fixos, ente claramente de teste) antes de forjar o token.
INTRUSO_ENTE="aaaaaaaa-0000-0000-0000-0000000000e1"
INTRUSO_IDENTIDADE="aaaaaaaa-0000-0000-0000-0000000000e2"
INTRUSO_VINCULO="aaaaaaaa-0000-0000-0000-0000000000e3"
INTRUSO_PAPEL="aaaaaaaa-0000-0000-0000-0000000000e4"
TINTRUSO="{\"identidade-id\":\"$INTRUSO_IDENTIDADE\",\"ente-id\":\"$INTRUSO_ENTE\",\"papeis\":[\"secretario\"]}"

dbexec() { docker exec "$PG_CONTAINER" psql -U oplenario -d oplenario -qtA -c "$1"; }

dbexec "INSERT INTO identidade.identidade (id,cpf,nome) VALUES ('$INTRUSO_IDENTIDADE','SONDA-T2-INTRUSO','Sonda T2 Intruso') ON CONFLICT (id) DO NOTHING;" >/dev/null
dbexec "INSERT INTO identidade.vinculo (ente_id,id,identidade_id,tipo,estado) VALUES ('$INTRUSO_ENTE','$INTRUSO_VINCULO','$INTRUSO_IDENTIDADE','servidor','ativo') ON CONFLICT (ente_id,id) DO NOTHING;" >/dev/null
dbexec "INSERT INTO identidade.usuario_papel (ente_id,id,identidade_id,papel) VALUES ('$INTRUSO_ENTE','$INTRUSO_PAPEL','$INTRUSO_IDENTIDADE','secretario') ON CONFLICT (ente_id,id) DO NOTHING;" >/dev/null

# ---------- contadores + log ----------
N_OK=0; N_QUEBRA=0; N_FRAGIL=0; N_COSMETICO=0; N_GAP=0
LOG="$DIR/t2-grupo-a-ultima-corrida.jsonl"
: > "$LOG"

registrar() {
  # registrar CLASSE ROTA DESCRICAO EVIDENCIA
  classe="$1"; rota="$2"; desc="$3"; ev="${4:-}"
  ts=$(date -u +%FT%TZ)
  printf '{"ts":"%s","classe":"%s","rota":"%s","desc":"%s","evidencia":"%s"}\n' \
    "$ts" "$classe" "$rota" "$(echo "$desc" | tr -d '\n')" "$(echo "$ev" | tr -d '\n' | tr '"' "'")" >> "$LOG"
  case "$classe" in
    OK) N_OK=$((N_OK+1)); marca="ok " ;;
    QUEBRA) N_QUEBRA=$((N_QUEBRA+1)); marca="QUEBRA" ;;
    FRAGIL) N_FRAGIL=$((N_FRAGIL+1)); marca="FRAGIL" ;;
    COSMETICO) N_COSMETICO=$((N_COSMETICO+1)); marca="cosm" ;;
    GAP) N_GAP=$((N_GAP+1)); marca="GAP " ;;
    *) marca="???" ;;
  esac
  echo "[$marca] $rota — $desc${ev:+ ($ev)}"
}

# esperar-status STATUS_ESPERADO STATUS_REAL ROTA DESC [EVIDENCIA]
esperar_status() {
  esperado="$1"; real="$2"; rota="$3"; desc="$4"; ev="${5:-}"
  if [ "$real" = "$esperado" ]; then
    registrar OK "$rota" "$desc -> $real (esperado)" "$ev"
  else
    registrar QUEBRA "$rota" "$desc -> $real (esperava $esperado)" "$ev"
  fi
}

# http METODO PATH TOKEN_JSON BODY_JSON  -> escreve status em $ST e corpo em $BODY
# O token JSON (`{"identidade-id":"...","ente-id":"...","papeis":[...]}`) e' valor de header HTTP
# valido sem encoding nenhum (RFC 7230 aceita aspas/colchetes em field-value) — nao precisa de
# url-encode/decode, que so' arriscava corromper o JSON.
http() {
  metodo="$1"; path="$2"; tok="$3"; body="${4:-}"
  tmp=$(mktemp)
  if [ -n "$body" ]; then
    ST=$(curl -s -o "$tmp" -w '%{http_code}' -X "$metodo" "$BACKEND$path" \
      -H "Authorization: Bearer $tok" \
      -H "Content-Type: application/json" --data "$body")
  else
    ST=$(curl -s -o "$tmp" -w '%{http_code}' -X "$metodo" "$BACKEND$path" \
      -H "Authorization: Bearer $tok")
  fi
  BODY=$(cat "$tmp"); rm -f "$tmp"
}

# jget CAMPO -> le um campo string de $BODY (json simples, um nivel ou aninhado por chave unica)
jget() { echo "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get(sys.argv[1],''))" "$1" 2>/dev/null; }
jhas() { echo "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if sys.argv[1] in d else 1)" "$1" 2>/dev/null; }

dbval() { docker exec "$PG_CONTAINER" psql -U oplenario -d oplenario -qtA -c "$1" | tr -d '[:space:]'; }

echo "=== T2 grupo A — condução de sessão por HTTP (sessão $SESSAO) ==="
echo "ente=$ENTE vereador1=$VEREADOR1 vereador2=$VEREADOR2"
docker ps --format '{{.Names}} {{.Status}}' | grep -E 'oplenario-(app|postgres)-1'
echo

# =========================================================================================
# ROTA 1 — POST /sessoes/:id/transicao
# =========================================================================================
echo "--- Rota 1: POST /sessoes/:id/transicao ---"

http GET "/sessoes/$SESSAO" "$TSEC"
ESTADO_INICIAL=$(jget estado)
echo "estado inicial (lido por GET) = $ESTADO_INICIAL"
if jhas lock-version; then
  registrar QUEBRA "GET /sessoes/:id" "lock-version vazou na leitura (nao deveria — ver adapters/out/sessao.clj)"
else
  registrar OK "GET /sessoes/:id" "confirma: lock-version NUNCA exposto por nenhuma leitura (achado central desta sonda)"
fi

# achado central: lock-version e' CAS obrigatorio na escrita e NENHUM endpoint de leitura o expoe
# (adapters/out/sessao.clj FILTRA de proposito). Bootstrap via DB so' porque a sonda PRECISA avancar
# a cadeia — um cliente real (FE, um 2o operador, um reload) nao tem como saber este numero.
LV_SESSAO=$(dbval "select lock_version from sessoes.sessao where id='$SESSAO';")
echo "lock_version real (lido via psql, NUNCA via API) = $LV_SESSAO"
registrar QUEBRA "sessao (CAS geral)" "lock-version exigido em transicao/pauta-reorder/pauta-remover/votacao-encerramento/gravacao-vincular NUNCA e' devolvido por GET nem pelo recibo de criacao — nenhum cliente real (FE incl.) consegue montar a 2a chamada sem ler o banco direto" "GET /sessoes/:id, GET /sessoes/:id/pauta, POST .../pauta/itens, POST .../votacoes, POST .../gravacao/:id/vincular-recibo — todos omitem lock-version"

# erro: papel errado (vereador tentando conduzir)
http POST "/sessoes/$SESSAO/transicao" "$TVER" '{"para":"aberta","lock-version":0}'
esperar_status 403 "$ST" "POST /transicao" "papel errado (vereador)" "$BODY"

# erro: corpo invalido (falta 'para')
http POST "/sessoes/$SESSAO/transicao" "$TSEC" '{"lock-version":0}'
esperar_status 400 "$ST" "POST /transicao" "corpo invalido (falta 'para')" "$BODY"

# erro: id inexistente
http POST "/sessoes/00000000-0000-0000-0000-000000000000/transicao" "$TSEC" '{"para":"aberta","lock-version":0}'
esperar_status 404 "$ST" "POST /transicao" "sessao inexistente" "$BODY"

# erro: Casa errada (intruso de outro ente)
http POST "/sessoes/$SESSAO/transicao" "$TINTRUSO" '{"para":"aberta","lock-version":0}'
if [ "$ST" = "404" ] || [ "$ST" = "403" ]; then
  registrar OK "POST /transicao" "isolamento multi-tenant: intruso de outro ente recebe $ST (fail-closed)" "$BODY"
else
  registrar QUEBRA "POST /transicao" "ISOLAMENTO MULTI-TENANT VIOLADO: intruso de outro ente recebeu $ST" "$BODY"
fi

# feliz: agendada -> aberta (so' se ainda estiver agendada — reentrancia de reruns)
if [ "$ESTADO_INICIAL" = "agendada" ]; then
  http POST "/sessoes/$SESSAO/transicao" "$TSEC" "{\"para\":\"aberta\",\"lock-version\":$LV_SESSAO}"
  esperar_status 200 "$ST" "POST /transicao" "feliz: agendada -> aberta" "$BODY"
  LV_SESSAO=$((LV_SESSAO+1))
  ESTADO_ATUAL="aberta"
else
  registrar GAP "POST /transicao" "sessao ja nao estava 'agendada' (corrida anterior nao resetada) — pulando o caminho feliz agendada->aberta"
  ESTADO_ATUAL="$ESTADO_INICIAL"
fi

# verificar nos 3 lugares: resposta (acima) + banco + leitura subsequente
DB_ESTADO=$(dbval "select estado from sessoes.sessao where id='$SESSAO';")
if [ "$DB_ESTADO" = "$ESTADO_ATUAL" ]; then
  registrar OK "POST /transicao" "banco confirma estado=$DB_ESTADO"
else
  registrar QUEBRA "POST /transicao" "banco diverge: esperava $ESTADO_ATUAL, achou $DB_ESTADO"
fi
http GET "/sessoes/$SESSAO" "$TSEC"
if [ "$(jget estado)" = "$ESTADO_ATUAL" ]; then
  registrar OK "GET /sessoes/:id" "leitura subsequente confirma estado=$ESTADO_ATUAL"
else
  registrar QUEBRA "GET /sessoes/:id" "leitura subsequente NAO reflete a escrita (estado=$(jget estado), esperava $ESTADO_ATUAL)"
fi

# erro: repeticao / conflito de estado — reenviar a MESMA transicao com o lock-version JA CONSUMIDO
http POST "/sessoes/$SESSAO/transicao" "$TSEC" "{\"para\":\"aberta\",\"lock-version\":$((LV_SESSAO-1))}"
esperar_status 409 "$ST" "POST /transicao" "repeticao com lock-version consumido -> nao duplica, nao e' idempotente" "$BODY"

# erro: transicao ilegal pela maquina (aberta -> arquivada direto)
http POST "/sessoes/$SESSAO/transicao" "$TSEC" "{\"para\":\"arquivada\",\"lock-version\":$LV_SESSAO}"
esperar_status 409 "$ST" "POST /transicao" "transicao proibida pela maquina (aberta->arquivada)" "$BODY"

echo

# =========================================================================================
# ROTAS 2/3/4 — pauta de itens
# =========================================================================================
echo "--- Rotas 2-4: POST/PATCH/DELETE /sessoes/:id/pauta/itens ---"

# proposicao real (rota publica, mesma tecnica da sonda.mjs)
MATERIAS_JSON=$(curl -s "$BACKEND/portal/casa/$ENTE/materias")
PROP=$(echo "$MATERIAS_JSON" | python3 -c "
import json,sys
# achado MENOR da revisao adversarial (frente 'truncamento-familia'): a rota devolve
# {materias, materias-total}, nao mais um array cru — sem o ['materias'] o for iterava as
# CHAVES do dict (strings) e o python morria com AttributeError, deixando PROP vazio.
ms = json.load(sys.stdin)['materias']
aprov = [m for m in ms if m.get('estado')=='aprovada']
alvo = aprov[0] if aprov else ms[0]
print(alvo['proposicao-id'])")
echo "proposicao usada = $PROP"

# erro: corpo invalido (tipo proposicao sem proposicao-id)
http POST "/sessoes/$SESSAO/pauta/itens" "$TSEC" '{"fase":"ordem_do_dia","tipo-item":"proposicao"}'
esperar_status 400 "$ST" "POST /pauta/itens" "corpo invalido: proposicao sem proposicao-id" "$BODY"

# erro: id de sessao inexistente
http POST "/sessoes/00000000-0000-0000-0000-000000000000/pauta/itens" "$TSEC" '{"fase":"ordem_do_dia","tipo-item":"leitura","texto-descricao":"x"}'
esperar_status 404 "$ST" "POST /pauta/itens" "sessao inexistente" "$BODY"

# erro: papel errado
http POST "/sessoes/$SESSAO/pauta/itens" "$TVER" '{"fase":"ordem_do_dia","tipo-item":"leitura","texto-descricao":"x"}'
esperar_status 403 "$ST" "POST /pauta/itens" "papel errado (vereador)" "$BODY"

# erro: Casa errada
http POST "/sessoes/$SESSAO/pauta/itens" "$TINTRUSO" '{"fase":"ordem_do_dia","tipo-item":"leitura","texto-descricao":"x"}'
if [ "$ST" = "404" ] || [ "$ST" = "403" ]; then
  registrar OK "POST /pauta/itens" "isolamento multi-tenant: $ST" "$BODY"
else
  registrar QUEBRA "POST /pauta/itens" "ISOLAMENTO VIOLADO: $ST" "$BODY"
fi

# feliz: item 1 (leitura, sem proposicao)
http POST "/sessoes/$SESSAO/pauta/itens" "$TSEC" '{"fase":"expediente","tipo-item":"leitura","texto-descricao":"Sonda T2 grupo A — leitura de expediente"}'
esperar_status 201 "$ST" "POST /pauta/itens" "feliz: item 'leitura'" "$BODY"
ITEM1=$(jget id)

# feliz: item 2 (proposicao real, para a votacao mais adiante)
http POST "/sessoes/$SESSAO/pauta/itens" "$TSEC" "{\"fase\":\"ordem_do_dia\",\"tipo-item\":\"proposicao\",\"proposicao-id\":\"$PROP\"}"
esperar_status 201 "$ST" "POST /pauta/itens" "feliz: item 'proposicao'" "$BODY"
ITEM2=$(jget id)

# verificacao nos 3 lugares
DB_N_ITENS=$(dbval "select count(*) from sessoes.pauta_item pi join sessoes.pauta_sessao ps on ps.id=pi.pauta_sessao_id where ps.sessao_id='$SESSAO' and pi.ativo;")
if [ "$DB_N_ITENS" -ge 2 ]; then registrar OK "pauta_item" "banco tem >= 2 itens ativos ($DB_N_ITENS)"; else registrar QUEBRA "pauta_item" "banco NAO tem os itens esperados ($DB_N_ITENS)"; fi
http GET "/sessoes/$SESSAO/pauta" "$TSEC"
N_ITENS_API=$(echo "$BODY" | python3 -c "import json,sys;print(len(json.load(sys.stdin).get('itens',[])))" 2>/dev/null)
if [ "$N_ITENS_API" -ge 2 ] 2>/dev/null; then registrar OK "GET /pauta" "leitura subsequente devolve $N_ITENS_API itens"; else registrar QUEBRA "GET /pauta" "leitura subsequente nao reflete os itens ($N_ITENS_API)"; fi
if echo "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(1 if any('lock-version' in it for it in d.get('itens',[])) else 0)" 2>/dev/null; then
  registrar OK "GET /pauta" "confirma: lock-version tambem filtrado na leitura de itens (mesmo achado central)"
else
  registrar QUEBRA "GET /pauta" "lock-version vazou na leitura de itens"
fi

# bootstrap (mesmo achado central) via DB para poder reordenar/remover
LV_ITEM1=$(dbval "select lock_version from sessoes.pauta_item where id='$ITEM1';")
LV_ITEM2=$(dbval "select lock_version from sessoes.pauta_item where id='$ITEM2';")

# erro: reordenar item de OUTRA sessao (anti confused-deputy) — le (so' GET) um item da 0211
http GET "/sessoes/$SESSAO_ABERTA_DEMO/pauta" "$TSEC"
ITEM_ALHEIO=$(echo "$BODY" | python3 -c "import json,sys; it=json.load(sys.stdin).get('itens',[]); print(it[0]['id'] if it else '')" 2>/dev/null)
if [ -n "$ITEM_ALHEIO" ]; then
  http PATCH "/sessoes/$SESSAO/pauta/itens/$ITEM_ALHEIO" "$TSEC" '{"nova-ordem":1,"lock-version":0}'
  esperar_status 404 "$ST" "PATCH /pauta/itens/:id" "confused-deputy: item de outra sessao (so leitura na 0211, nao escreveu la)" "$BODY"
else
  registrar GAP "PATCH /pauta/itens/:id" "sessao 0211 sem item de pauta para o teste de confused-deputy — pulado"
fi

# feliz: reordenar item 1
http PATCH "/sessoes/$SESSAO/pauta/itens/$ITEM1" "$TSEC" "{\"nova-ordem\":2,\"lock-version\":$LV_ITEM1}"
esperar_status 200 "$ST" "PATCH /pauta/itens/:id" "feliz: reordenar item1 (lock-version lido via psql — API nunca o devolveu)" "$BODY"

# erro: reordenar com lock-version repetido/stale
http PATCH "/sessoes/$SESSAO/pauta/itens/$ITEM1" "$TSEC" "{\"nova-ordem\":3,\"lock-version\":$LV_ITEM1}"
esperar_status 409 "$ST" "PATCH /pauta/itens/:id" "repeticao com lock-version stale" "$BODY"

# erro: corpo invalido (falta lock-version)
http PATCH "/sessoes/$SESSAO/pauta/itens/$ITEM2" "$TSEC" '{"nova-ordem":1}'
esperar_status 400 "$ST" "PATCH /pauta/itens/:id" "corpo invalido: falta lock-version" "$BODY"

# feliz: remover item 2 (tipo exclusao)
http DELETE "/sessoes/$SESSAO/pauta/itens/$ITEM2" "$TSEC" "{\"tipo\":\"exclusao\",\"lock-version\":$LV_ITEM2}"
esperar_status 200 "$ST" "DELETE /pauta/itens/:id" "feliz: remover item2" "$BODY"
DB_ATIVO_ITEM2=$(dbval "select ativo from sessoes.pauta_item where id='$ITEM2';")
if [ "$DB_ATIVO_ITEM2" = "f" ]; then registrar OK "pauta_item" "banco confirma ativo=false (soft-delete)"; else registrar QUEBRA "pauta_item" "banco NAO marcou ativo=false ($DB_ATIVO_ITEM2)"; fi

# erro: remover de novo (repeticao — ja removido)
http DELETE "/sessoes/$SESSAO/pauta/itens/$ITEM2" "$TSEC" "{\"tipo\":\"exclusao\",\"lock-version\":$LV_ITEM2}"
esperar_status 409 "$ST" "DELETE /pauta/itens/:id" "repeticao: item ja removido" "$BODY"

# erro: tipo de remocao invalido
http DELETE "/sessoes/$SESSAO/pauta/itens/$ITEM1" "$TSEC" "{\"tipo\":\"motivo_qualquer\",\"lock-version\":$((LV_ITEM1+1))}"
esperar_status 400 "$ST" "DELETE /pauta/itens/:id" "tipo de remocao invalido" "$BODY"

echo

# =========================================================================================
# ROTAS 5/6/7 — votacao
# =========================================================================================
echo "--- Rotas 5-7: POST /sessoes/:id/votacoes(/:id/votos|/:id/encerramento) ---"

# erro: corpo invalido (quorum-tipo invalido)
http POST "/sessoes/$SESSAO/votacoes" "$TSEC" "{\"objeto-tipo\":\"proposicao\",\"objeto-id\":\"$PROP\",\"modalidade\":\"nominal\",\"quorum-tipo\":\"maioria_boa_vontade\"}"
esperar_status 400 "$ST" "POST /votacoes" "corpo invalido: quorum-tipo desconhecido" "$BODY"

# erro: papel errado
http POST "/sessoes/$SESSAO/votacoes" "$TVER" "{\"objeto-tipo\":\"proposicao\",\"objeto-id\":\"$PROP\",\"modalidade\":\"nominal\",\"quorum-tipo\":\"maioria_simples\"}"
esperar_status 403 "$ST" "POST /votacoes" "papel errado (vereador)" "$BODY"

# erro: Casa errada
http POST "/sessoes/$SESSAO/votacoes" "$TINTRUSO" "{\"objeto-tipo\":\"proposicao\",\"objeto-id\":\"$PROP\",\"modalidade\":\"nominal\",\"quorum-tipo\":\"maioria_simples\"}"
if [ "$ST" = "404" ] || [ "$ST" = "403" ]; then registrar OK "POST /votacoes" "isolamento multi-tenant: $ST" "$BODY"; else registrar QUEBRA "POST /votacoes" "ISOLAMENTO VIOLADO: $ST" "$BODY"; fi

# achado (ver logic/pode-dirigir-votacao?): nao ha check de ESTADO da sessao — abrir votacao numa
# sessao 'aberta' funciona (esperado), mas o controller aceitaria o MESMO numa 'agendada'/'encerrada'.
# feliz: abrir votacao nominal sobre a proposicao do item2 (ja removida da pauta, mas a votacao e'
# sobre a MATERIA, nao o item — pauta-item-id e' so contexto temporal opcional, por design).
http POST "/sessoes/$SESSAO/votacoes" "$TSEC" "{\"objeto-tipo\":\"proposicao\",\"objeto-id\":\"$PROP\",\"modalidade\":\"nominal\",\"quorum-tipo\":\"maioria_simples\"}"
esperar_status 201 "$ST" "POST /votacoes" "feliz: abrir votacao nominal" "$BODY"
VOTACAO=$(jget id)

DB_VOTACAO=$(dbval "select estado from legislativo.votacoes where id='$VOTACAO';")
if [ "$DB_VOTACAO" = "aberta" ]; then registrar OK "votacoes" "banco confirma estado=aberta"; else registrar QUEBRA "votacoes" "banco nao confirma abertura ($DB_VOTACAO)"; fi

# erro: voto com enum invalido
http POST "/sessoes/$SESSAO/votacoes/$VOTACAO/votos" "$TSEC" "{\"voto\":\"talvez\",\"vereador-id\":\"$VEREADOR1\"}"
esperar_status 400 "$ST" "POST /votacoes/:id/votos" "corpo invalido: voto fora do enum" "$BODY"

# erro: votacao inexistente
http POST "/sessoes/$SESSAO/votacoes/00000000-0000-0000-0000-000000000000/votos" "$TSEC" "{\"voto\":\"sim\",\"vereador-id\":\"$VEREADOR1\"}"
esperar_status 404 "$ST" "POST /votacoes/:id/votos" "votacao inexistente" "$BODY"

# erro: papel errado
http POST "/sessoes/$SESSAO/votacoes/$VOTACAO/votos" "$TVER" "{\"voto\":\"sim\",\"vereador-id\":\"$VEREADOR1\"}"
esperar_status 403 "$ST" "POST /votacoes/:id/votos" "papel errado (vereador)" "$BODY"

# feliz: voto de vereador1
http POST "/sessoes/$SESSAO/votacoes/$VOTACAO/votos" "$TSEC" "{\"voto\":\"sim\",\"vereador-id\":\"$VEREADOR1\"}"
esperar_status 201 "$ST" "POST /votacoes/:id/votos" "feliz: voto sim de vereador1" "$BODY"

DB_N_VOTOS=$(dbval "select count(*) from legislativo.votos where votacao_id='$VOTACAO';")
if [ "$DB_N_VOTOS" = "1" ]; then registrar OK "votos" "banco tem 1 voto"; else registrar QUEBRA "votos" "banco nao tem o voto esperado ($DB_N_VOTOS)"; fi

# erro: repeticao — MESMO vereador vota de novo na MESMA votacao (UNIQUE ente/votacao/vereador).
# achado por leitura de fonte (db/votacao.clj + diplomat/http/in.clj linha ~74-76): o 409
# ":conflito/voto-duplicado" SO existe no caminho self-service (/meu-voto); a rota da Mesa
# (voto-handler) nao tem try/catch nenhum — a excecao da UNIQUE sobe crua ate' o interceptor global.
http POST "/sessoes/$SESSAO/votacoes/$VOTACAO/votos" "$TSEC" "{\"voto\":\"nao\",\"vereador-id\":\"$VEREADOR1\"}"
if [ "$ST" = "409" ]; then
  registrar OK "POST /votacoes/:id/votos" "repeticao (mesmo vereador) corretamente recusada -> 409" "$BODY"
else
  registrar QUEBRA "POST /votacoes/:id/votos" "repeticao (mesmo vereador) NAO vira 409 -> $ST (achado por leitura de fonte: voto-handler nao tem catch para UNIQUE violation, so' meu-voto tem)" "$BODY"
fi

http POST "/sessoes/$SESSAO/votacoes/$VOTACAO/votos" "$TSEC" "{\"voto\":\"abstencao\",\"vereador-id\":\"$VEREADOR2\"}"
esperar_status 201 "$ST" "POST /votacoes/:id/votos" "feliz: voto de vereador2" "$BODY"

# rota 7 — encerramento
LV_VOTACAO=$(dbval "select lock_version from legislativo.votacoes where id='$VOTACAO';")

# erro: corpo invalido (falta lock-version)
http POST "/sessoes/$SESSAO/votacoes/$VOTACAO/encerramento" "$TSEC" '{}'
esperar_status 400 "$ST" "POST /votacoes/:id/encerramento" "corpo invalido: falta lock-version" "$BODY"

# erro: papel errado
http POST "/sessoes/$SESSAO/votacoes/$VOTACAO/encerramento" "$TVER" "{\"lock-version\":$LV_VOTACAO,\"base-membros\":17}"
esperar_status 403 "$ST" "POST /votacoes/:id/encerramento" "papel errado (vereador)" "$BODY"

# feliz: encerrar
http POST "/sessoes/$SESSAO/votacoes/$VOTACAO/encerramento" "$TSEC" "{\"lock-version\":$LV_VOTACAO,\"base-membros\":17}"
esperar_status 200 "$ST" "POST /votacoes/:id/encerramento" "feliz: encerrar (base-membros=17, lock-version lido via psql)" "$BODY"
echo "  totais: $BODY"

DB_ESTADO_VOT=$(dbval "select estado from legislativo.votacoes where id='$VOTACAO';")
if [ "$DB_ESTADO_VOT" = "encerrada" ]; then registrar OK "votacoes" "banco confirma estado=encerrada"; else registrar QUEBRA "votacoes" "banco NAO confirma encerramento ($DB_ESTADO_VOT)"; fi

# erro: repeticao — encerrar de novo (ja terminal). Achado por leitura de fonte + confirmado ao
# vivo: `legislativo.controllers/encerrar-votacao` tem um guard EXPLICITO que mapeia "votacao ja em
# estado terminal" para `:tipo :validacao/invalido` -> 400 (nao 409). E' DELIBERADO (docstring:
# "em vez de propagarem como 500 do db") mas inconsistente com o resto do modulo: TODO outro
# "ja terminal"/"repeticao" deste grupo (transicao, pauta remover, inscricao desistir, fala
# encerrar, gravacao vincular) responde 409 pela MESMA semantica de conflito de estado — so' esta
# rota usa 400. 400 = "conserte seu pedido"; 409 = "seu pedido era valido, o recurso mudou" — a
# 2a e' a correta aqui (o mesmo corpo teria funcionado segundos antes).
http POST "/sessoes/$SESSAO/votacoes/$VOTACAO/encerramento" "$TSEC" "{\"lock-version\":$LV_VOTACAO,\"base-membros\":17}"
if [ "$ST" = "409" ]; then
  registrar OK "POST /votacoes/:id/encerramento" "repeticao (ja encerrada) corretamente recusada -> 409" "$BODY"
else
  registrar QUEBRA "POST /votacoes/:id/encerramento" "repeticao (ja encerrada) vira $ST, nao 409 (legislativo.controllers/encerrar-votacao mapeia 'estado terminal' para :validacao/invalido -> 400, inconsistente com os outros 5 conflitos de estado deste grupo, todos 409)" "$BODY"
fi

echo

# =========================================================================================
# ROTAS 8/9 — inscricoes (tribuna, intencao)
# =========================================================================================
echo "--- Rotas 8-9: POST /sessoes/:id/inscricoes(/:id/desistir) ---"

# erro: corpo invalido (origem-inscricao fora do enum)
http POST "/sessoes/$SESSAO/inscricoes" "$TSEC" "{\"vereador-id\":\"$VEREADOR1\",\"origem-inscricao\":\"grito_da_plateia\",\"fase\":\"ordem_do_dia\"}"
esperar_status 400 "$ST" "POST /inscricoes" "corpo invalido: origem-inscricao invalida" "$BODY"

# erro: papel errado
http POST "/sessoes/$SESSAO/inscricoes" "$TVER" "{\"vereador-id\":\"$VEREADOR1\",\"origem-inscricao\":\"pre_sessao_secretaria\",\"fase\":\"ordem_do_dia\"}"
esperar_status 403 "$ST" "POST /inscricoes" "papel errado (vereador)" "$BODY"

# erro: Casa errada
http POST "/sessoes/$SESSAO/inscricoes" "$TINTRUSO" "{\"vereador-id\":\"$VEREADOR1\",\"origem-inscricao\":\"pre_sessao_secretaria\",\"fase\":\"ordem_do_dia\"}"
if [ "$ST" = "404" ] || [ "$ST" = "403" ]; then registrar OK "POST /inscricoes" "isolamento multi-tenant: $ST" "$BODY"; else registrar QUEBRA "POST /inscricoes" "ISOLAMENTO VIOLADO: $ST" "$BODY"; fi

# feliz: inscreve vereador1
http POST "/sessoes/$SESSAO/inscricoes" "$TSEC" "{\"vereador-id\":\"$VEREADOR1\",\"origem-inscricao\":\"pre_sessao_secretaria\",\"fase\":\"ordem_do_dia\"}"
esperar_status 201 "$ST" "POST /inscricoes" "feliz: inscreve vereador1" "$BODY"
INSCRICAO1=$(jget id)

# feliz: inscreve vereador2 (para ter fila com >1)
http POST "/sessoes/$SESSAO/inscricoes" "$TSEC" "{\"vereador-id\":\"$VEREADOR2\",\"origem-inscricao\":\"pre_sessao_secretaria\",\"fase\":\"ordem_do_dia\"}"
esperar_status 201 "$ST" "POST /inscricoes" "feliz: inscreve vereador2" "$BODY"
INSCRICAO2=$(jget id)

DB_N_INSC=$(dbval "select count(*) from sessoes.inscricao_oradores where sessao_id='$SESSAO';")
if [ "$DB_N_INSC" -ge 2 ]; then registrar OK "inscricao_oradores" "banco tem >=2 inscricoes ($DB_N_INSC)"; else registrar QUEBRA "inscricao_oradores" "banco nao confirma as inscricoes ($DB_N_INSC)"; fi
http GET "/sessoes/$SESSAO/tribuna" "$TSEC"
N_INSCRITOS_API=$(echo "$BODY" | python3 -c "import json,sys;print(len(json.load(sys.stdin).get('inscritos',[])))" 2>/dev/null)
if [ "$N_INSCRITOS_API" -ge 2 ] 2>/dev/null; then registrar OK "GET /tribuna" "leitura subsequente devolve $N_INSCRITOS_API inscritos"; else registrar QUEBRA "GET /tribuna" "leitura subsequente nao reflete as inscricoes ($N_INSCRITOS_API)"; fi

# lock-version do inscricao2 (bootstrap via DB — mesmo achado central)
LV_INSC2=$(dbval "select lock_version from sessoes.inscricao_oradores where id='$INSCRICAO2';")

# erro: corpo invalido (falta lock-version)
http POST "/sessoes/$SESSAO/inscricoes/$INSCRICAO2/desistir" "$TSEC" '{}'
esperar_status 400 "$ST" "POST /inscricoes/:id/desistir" "corpo invalido: falta lock-version" "$BODY"

# feliz: vereador2 desiste
http POST "/sessoes/$SESSAO/inscricoes/$INSCRICAO2/desistir" "$TSEC" "{\"lock-version\":$LV_INSC2}"
esperar_status 200 "$ST" "POST /inscricoes/:id/desistir" "feliz: vereador2 desiste" "$BODY"

DB_ESTADO_INSC2=$(dbval "select estado from sessoes.inscricao_oradores where id='$INSCRICAO2';")
if [ "$DB_ESTADO_INSC2" = "desistencia" ]; then registrar OK "inscricao_oradores" "banco confirma estado=desistencia"; else registrar QUEBRA "inscricao_oradores" "banco NAO confirma desistencia ($DB_ESTADO_INSC2)"; fi

# erro: repeticao (ja desistiu — terminal)
http POST "/sessoes/$SESSAO/inscricoes/$INSCRICAO2/desistir" "$TSEC" "{\"lock-version\":$LV_INSC2}"
esperar_status 409 "$ST" "POST /inscricoes/:id/desistir" "repeticao: ja desistiu" "$BODY"

# erro: inscricao inexistente
http POST "/sessoes/$SESSAO/inscricoes/00000000-0000-0000-0000-000000000000/desistir" "$TSEC" '{"lock-version":0}'
esperar_status 409 "$ST" "POST /inscricoes/:id/desistir" "inscricao inexistente" "$BODY"

echo

# =========================================================================================
# ROTAS 10/11/12 — falas (tribuna, execucao)
# =========================================================================================
echo "--- Rotas 10-12: POST /sessoes/:id/falas(/:id/cronometro|/:id/encerrar) ---"

AGORA=$(python3 -c "import datetime;print(datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z'))")

# erro: corpo invalido (tipo-fala fora do enum)
http POST "/sessoes/$SESSAO/falas" "$TSEC" "{\"orador-id\":\"$VEREADOR1\",\"tipo-fala\":\"grito\",\"fase\":\"ordem_do_dia\",\"iniciou-em\":\"$AGORA\"}"
esperar_status 400 "$ST" "POST /falas" "corpo invalido: tipo-fala invalido" "$BODY"

# erro: papel errado
http POST "/sessoes/$SESSAO/falas" "$TVER" "{\"orador-id\":\"$VEREADOR1\",\"tipo-fala\":\"principal\",\"fase\":\"ordem_do_dia\",\"iniciou-em\":\"$AGORA\"}"
esperar_status 403 "$ST" "POST /falas" "papel errado (vereador)" "$BODY"

# erro: Casa errada
http POST "/sessoes/$SESSAO/falas" "$TINTRUSO" "{\"orador-id\":\"$VEREADOR1\",\"tipo-fala\":\"principal\",\"fase\":\"ordem_do_dia\",\"iniciou-em\":\"$AGORA\"}"
if [ "$ST" = "404" ] || [ "$ST" = "403" ]; then registrar OK "POST /falas" "isolamento multi-tenant: $ST" "$BODY"; else registrar QUEBRA "POST /falas" "ISOLAMENTO VIOLADO: $ST" "$BODY"; fi

# feliz: inicia fala principal do vereador1, cumprindo a inscricao1
http POST "/sessoes/$SESSAO/falas" "$TSEC" "{\"orador-id\":\"$VEREADOR1\",\"tipo-fala\":\"principal\",\"fase\":\"ordem_do_dia\",\"iniciou-em\":\"$AGORA\",\"inscricao-id\":\"$INSCRICAO1\"}"
esperar_status 201 "$ST" "POST /falas" "feliz: inicia fala principal (cumpre inscricao1)" "$BODY"
FALA1=$(jget fala-id)

DB_FALA1=$(dbval "select count(*) from sessoes.fala_executada where id='$FALA1';")
if [ "$DB_FALA1" = "1" ]; then registrar OK "fala_executada" "banco tem a fala"; else registrar QUEBRA "fala_executada" "banco nao tem a fala"; fi
http GET "/sessoes/$SESSAO/tribuna" "$TSEC"
ORADOR_ATUAL_FALA_ID=$(echo "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); oa=d.get('orador-atual'); print(oa['fala-id'] if oa else '')" 2>/dev/null)
if [ "$ORADOR_ATUAL_FALA_ID" = "$FALA1" ]; then
  registrar OK "GET /tribuna" "leitura subsequente mostra orador-atual = fala1"
else
  registrar QUEBRA "GET /tribuna" "leitura subsequente NAO mostra o orador atual esperado (achou '$ORADOR_ATUAL_FALA_ID')"
fi

# cronometro (rota 11)
# erro: coerencia tipo<->segundos (tempo_adicional_concedido exige segundos>0)
http POST "/sessoes/$SESSAO/falas/$FALA1/cronometro" "$TSEC" "{\"tipo\":\"tempo_adicional_concedido\",\"ocorrido-em\":\"$AGORA\"}"
esperar_status 400 "$ST" "POST /falas/:id/cronometro" "coerencia tipo<->segundos: tempo_adicional sem segundos-adicionais" "$BODY"

# erro: fala de outra sessao (confused-deputy) — le fala aberta da 0211 (so leitura, sem escrever la)
http GET "/sessoes/$SESSAO_ABERTA_DEMO/tribuna" "$TSEC"
FALA_ALHEIA=$(echo "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); oa=d.get('orador-atual'); print(oa['fala-id'] if oa else '')" 2>/dev/null)
if [ -n "$FALA_ALHEIA" ]; then
  http POST "/sessoes/$SESSAO/falas/$FALA_ALHEIA/cronometro" "$TSEC" "{\"tipo\":\"pausada\",\"ocorrido-em\":\"$AGORA\"}"
  esperar_status 404 "$ST" "POST /falas/:id/cronometro" "confused-deputy: fala de outra sessao (so leitura na 0211)" "$BODY"
else
  registrar GAP "POST /falas/:id/cronometro" "sessao 0211 sem orador-atual no momento da sonda — pulado o teste de confused-deputy"
fi

# feliz: pausa + retoma
http POST "/sessoes/$SESSAO/falas/$FALA1/cronometro" "$TSEC" "{\"tipo\":\"pausada\",\"ocorrido-em\":\"$AGORA\"}"
esperar_status 201 "$ST" "POST /falas/:id/cronometro" "feliz: pausada" "$BODY"
http POST "/sessoes/$SESSAO/falas/$FALA1/cronometro" "$TSEC" "{\"tipo\":\"retomada\",\"ocorrido-em\":\"$AGORA\"}"
esperar_status 201 "$ST" "POST /falas/:id/cronometro" "feliz: retomada" "$BODY"

DB_N_CRONO=$(dbval "select count(*) from sessoes.fala_cronometro_evento where fala_id='$FALA1';")
if [ "$DB_N_CRONO" -ge 2 ]; then registrar OK "fala_cronometro_evento" "banco tem os eventos ($DB_N_CRONO)"; else registrar QUEBRA "fala_cronometro_evento" "banco nao confirma os eventos ($DB_N_CRONO)"; fi

# encerrar fala (rota 12) — bootstrap lock-version via DB (mesmo achado central)
LV_FALA1=$(dbval "select lock_version from sessoes.fala_executada where id='$FALA1';")

# erro: corpo invalido (falta lock-version)
http POST "/sessoes/$SESSAO/falas/$FALA1/encerrar" "$TSEC" "{\"encerrou-em\":\"$AGORA\"}"
esperar_status 400 "$ST" "POST /falas/:id/encerrar" "corpo invalido: falta lock-version" "$BODY"

# feliz: encerra
http POST "/sessoes/$SESSAO/falas/$FALA1/encerrar" "$TSEC" "{\"encerrou-em\":\"$AGORA\",\"lock-version\":$LV_FALA1}"
esperar_status 200 "$ST" "POST /falas/:id/encerrar" "feliz: encerra fala1" "$BODY"

DB_ENCERROU=$(dbval "select (encerrou_em is not null) from sessoes.fala_executada where id='$FALA1';")
if [ "$DB_ENCERROU" = "t" ]; then registrar OK "fala_executada" "banco confirma encerrou_em preenchido"; else registrar QUEBRA "fala_executada" "banco NAO confirma o encerramento"; fi

# erro: repeticao (ja encerrada)
http POST "/sessoes/$SESSAO/falas/$FALA1/encerrar" "$TSEC" "{\"encerrou-em\":\"$AGORA\",\"lock-version\":$LV_FALA1}"
esperar_status 409 "$ST" "POST /falas/:id/encerrar" "repeticao: ja encerrada" "$BODY"

echo

# =========================================================================================
# ROTA 13 — decisoes-mesa
# =========================================================================================
echo "--- Rota 13: POST /sessoes/:id/decisoes-mesa ---"

# erro: corpo invalido (questao vazia apos trim)
http POST "/sessoes/$SESSAO/decisoes-mesa" "$TSEC" "{\"questao\":\"   \",\"decisao\":\"deferida\",\"decidido-em\":\"$AGORA\"}"
esperar_status 400 "$ST" "POST /decisoes-mesa" "corpo invalido: questao vazia apos trim" "$BODY"

# erro: papel errado
http POST "/sessoes/$SESSAO/decisoes-mesa" "$TVER" "{\"questao\":\"prazo de tribuna\",\"decisao\":\"mantido\",\"decidido-em\":\"$AGORA\"}"
esperar_status 403 "$ST" "POST /decisoes-mesa" "papel errado (vereador)" "$BODY"

# erro: fala de outra sessao (confused-deputy)
if [ -n "$FALA_ALHEIA" ]; then
  http POST "/sessoes/$SESSAO/decisoes-mesa" "$TSEC" "{\"questao\":\"q\",\"decisao\":\"d\",\"decidido-em\":\"$AGORA\",\"fala-id\":\"$FALA_ALHEIA\"}"
  esperar_status 404 "$ST" "POST /decisoes-mesa" "confused-deputy: fala-id de outra sessao" "$BODY"
fi

# feliz: decisao sem fala-id
http POST "/sessoes/$SESSAO/decisoes-mesa" "$TSEC" "{\"questao\":\"Pode o vereador falar por mais 2 minutos?\",\"decisao\":\"Deferido, +2min, por acordo de lideranca.\",\"decidido-em\":\"$AGORA\"}"
esperar_status 201 "$ST" "POST /decisoes-mesa" "feliz: decisao sem fala-id" "$BODY"
DECISAO1=$(jget id)

# feliz: decisao vinculada a fala1 (desta sessao)
http POST "/sessoes/$SESSAO/decisoes-mesa" "$TSEC" "{\"questao\":\"Questao de ordem sobre a fala encerrada\",\"decisao\":\"Mantida a ordem\",\"decidido-em\":\"$AGORA\",\"fala-id\":\"$FALA1\"}"
esperar_status 201 "$ST" "POST /decisoes-mesa" "feliz: decisao vinculada a fala1" "$BODY"

DB_N_DECISAO=$(dbval "select count(*) from sessoes.decisao_mesa where sessao_id='$SESSAO';")
if [ "$DB_N_DECISAO" -ge 2 ]; then registrar OK "decisao_mesa" "banco tem >=2 decisoes ($DB_N_DECISAO)"; else registrar QUEBRA "decisao_mesa" "banco nao confirma as decisoes ($DB_N_DECISAO)"; fi
DB_PRESIDENTE=$(dbval "select presidente_id from sessoes.decisao_mesa where id='$DECISAO1';")
if [ "$DB_PRESIDENTE" = "$IDENT_SECRETARIA" ]; then registrar OK "decisao_mesa" "presidente-id injetado do ator (nao do corpo)"; else registrar QUEBRA "decisao_mesa" "presidente-id nao bate com o ator ($DB_PRESIDENTE)"; fi

echo

# =========================================================================================
# ROTA 14 — incidentes
# =========================================================================================
echo "--- Rota 14: POST /sessoes/:id/incidentes ---"

# erro: corpo invalido (tipo fora do enum)
http POST "/sessoes/$SESSAO/incidentes" "$TSEC" "{\"tipo\":\"tumulto\",\"resultado\":\"deferido\",\"descricao\":\"x\",\"ocorrido-em\":\"$AGORA\"}"
esperar_status 400 "$ST" "POST /incidentes" "corpo invalido: tipo invalido" "$BODY"

# erro: coerencia objeto-tipo/objeto-id (so um dos dois)
http POST "/sessoes/$SESSAO/incidentes" "$TSEC" "{\"tipo\":\"urgencia\",\"resultado\":\"deferido\",\"descricao\":\"pedido de urgencia\",\"ocorrido-em\":\"$AGORA\",\"objeto-tipo\":\"proposicao\"}"
esperar_status 400 "$ST" "POST /incidentes" "corpo invalido: objeto-tipo sem objeto-id" "$BODY"

# erro: papel errado
http POST "/sessoes/$SESSAO/incidentes" "$TVER" "{\"tipo\":\"urgencia\",\"resultado\":\"deferido\",\"descricao\":\"x\",\"ocorrido-em\":\"$AGORA\"}"
esperar_status 403 "$ST" "POST /incidentes" "papel errado (vereador)" "$BODY"

# feliz: incidente vinculado a proposicao real
http POST "/sessoes/$SESSAO/incidentes" "$TSEC" "{\"tipo\":\"urgencia\",\"resultado\":\"deferido\",\"descricao\":\"Pedido de urgencia sobre a materia em pauta\",\"ocorrido-em\":\"$AGORA\",\"objeto-tipo\":\"proposicao\",\"objeto-id\":\"$PROP\"}"
esperar_status 201 "$ST" "POST /incidentes" "feliz: urgencia deferida" "$BODY"

DB_N_INCID=$(dbval "select count(*) from sessoes.incidente_processual where sessao_id='$SESSAO';")
if [ "$DB_N_INCID" -ge 1 ]; then registrar OK "incidente_processual" "banco tem o incidente"; else registrar QUEBRA "incidente_processual" "banco nao confirma o incidente"; fi

echo

# =========================================================================================
# ROTA 15 — minha-justificativa
# =========================================================================================
echo "--- Rota 15: POST /sessoes/:id/minha-justificativa ---"

# erro: papel errado (secretario tentando usar a porta self-service do vereador)
http POST "/sessoes/$SESSAO/minha-justificativa" "$TSEC" '{"motivo":"tentativa de forja"}'
esperar_status 403 "$ST" "POST /minha-justificativa" "papel errado (secretario na porta self-service)" "$BODY"

# erro: corpo invalido (motivo vazio)
http POST "/sessoes/$SESSAO/minha-justificativa" "$TVER" '{"motivo":"   "}'
esperar_status 400 "$ST" "POST /minha-justificativa" "corpo invalido: motivo vazio" "$BODY"

# feliz
http POST "/sessoes/$SESSAO/minha-justificativa" "$TVER" '{"motivo":"Consulta medica de urgencia — sonda T2 grupo A"}'
if [ "$ST" = "201" ]; then
  registrar OK "POST /minha-justificativa" "feliz: vereador justifica a propria falta" "$BODY"
  DB_JUST=$(dbval "select count(*) from sessoes.justificativa_ausencia where sessao_id='$SESSAO' and vereador_id=(select id from cadastros.vereador where identidade_id='$IDENT_VEREADOR');" 2>/dev/null)
  if [ "$DB_JUST" -ge 1 ] 2>/dev/null; then registrar OK "justificativa_ausencia" "banco tem a justificativa"; else registrar FRAGIL "justificativa_ausencia" "201 mas nao achei a linha pelo vereador resolvido do identidade_id (checar coluna/join)"; fi
elif [ "$ST" = "409" ]; then
  registrar OK "POST /minha-justificativa" "409: repeticao ou fora do roster desta data — comportamento fail-closed aceitavel" "$BODY"
elif [ "$ST" = "404" ]; then
  registrar GAP "POST /minha-justificativa" "identidade :vereador do artefato sem cadastro de vereador vinculado neste ente (resolver-vereador nil) — nao e' defeito desta rota, e' dado da semente" "$BODY"
else
  registrar QUEBRA "POST /minha-justificativa" "feliz esperava 201/409/404, veio $ST" "$BODY"
fi

echo

# =========================================================================================
# ROTAS 16/17 — gravacoes
# =========================================================================================
echo "--- Rotas 16-17: POST /gravacoes ; POST /sessoes/:id/gravacao/:seg-id/vincular ---"

TMPWAV=$(mktemp)
printf 'SONDA-T2-GRUPO-A-AUDIO-FAKE' > "$TMPWAV"

# erro: papel errado
ST=$(curl -s -o /tmp/t2-grav-body -w '%{http_code}' -X POST \
  "$BACKEND/gravacoes?fonte-ingestao=gravacao_local_pos_sessao&motivo-inicio=inicio_sessao&iniciou-em=$AGORA" \
  -H "Authorization: Bearer $TVER" -H "Content-Type: application/octet-stream" --data-binary "@$TMPWAV")
BODY=$(cat /tmp/t2-grav-body)
esperar_status 403 "$ST" "POST /gravacoes" "papel errado (vereador)" "$BODY"

# erro: corpo/metadata invalida (motivo-inicio fora do enum)
ST=$(curl -s -o /tmp/t2-grav-body -w '%{http_code}' -X POST \
  "$BACKEND/gravacoes?fonte-ingestao=gravacao_local_pos_sessao&motivo-inicio=porque_sim&iniciou-em=$AGORA" \
  -H "Authorization: Bearer $TSEC" -H "Content-Type: application/octet-stream" --data-binary "@$TMPWAV")
BODY=$(cat /tmp/t2-grav-body)
esperar_status 400 "$ST" "POST /gravacoes" "metadata invalida: motivo-inicio desconhecido" "$BODY"

# feliz: ingestao agnostica de sessao, vinculando ja' na query (link-at-ingest)
ST=$(curl -s -o /tmp/t2-grav-body -w '%{http_code}' -X POST \
  "$BACKEND/gravacoes?fonte-ingestao=gravacao_local_pos_sessao&motivo-inicio=inicio_sessao&iniciou-em=$AGORA&sessao-id=$SESSAO" \
  -H "Authorization: Bearer $TSEC" -H "Content-Type: application/octet-stream" --data-binary "@$TMPWAV")
BODY=$(cat /tmp/t2-grav-body)
esperar_status 201 "$ST" "POST /gravacoes" "feliz: ingestao com link-at-ingest" "$BODY"
SEGMENTO=$(jget id)
rm -f "$TMPWAV" /tmp/t2-grav-body

DB_SEG=$(dbval "select count(*) from sessoes.gravacao_segmento where id='$SEGMENTO';")
if [ "$DB_SEG" = "1" ]; then registrar OK "gravacao_segmento" "banco tem o segmento"; else registrar QUEBRA "gravacao_segmento" "banco nao confirma o segmento"; fi
http GET "/sessoes/$SESSAO/gravacao" "$TSEC"
N_SEG_API=$(echo "$BODY" | python3 -c "import json,sys;print(len(json.load(sys.stdin).get('segmentos',[])))" 2>/dev/null)
if [ "$N_SEG_API" -ge 1 ] 2>/dev/null; then registrar OK "GET /gravacao" "leitura subsequente devolve $N_SEG_API segmento(s)"; else registrar QUEBRA "GET /gravacao" "leitura subsequente nao reflete o segmento"; fi

# rota 17 — vincular (o segmento acima ja' veio linkado na ingestao; testamos vincular OUTRO)
TMPWAV2=$(mktemp); printf 'SONDA-T2-GRUPO-A-AUDIO-FAKE-2' > "$TMPWAV2"
ST=$(curl -s -o /tmp/t2-grav-body -w '%{http_code}' -X POST \
  "$BACKEND/gravacoes?fonte-ingestao=gravacao_local_pos_sessao&motivo-inicio=divisao_manual&iniciou-em=$AGORA" \
  -H "Authorization: Bearer $TSEC" -H "Content-Type: application/octet-stream" --data-binary "@$TMPWAV2")
BODY=$(cat /tmp/t2-grav-body); rm -f "$TMPWAV2" /tmp/t2-grav-body
SEGMENTO2=$(jget id)

LV_SEG2=$(dbval "select lock_version from sessoes.gravacao_segmento where id='$SEGMENTO2';")

# erro: corpo invalido (falta lock-version)
http POST "/sessoes/$SESSAO/gravacao/$SEGMENTO2/vincular" "$TSEC" '{}'
esperar_status 400 "$ST" "POST /gravacao/:id/vincular" "corpo invalido: falta lock-version" "$BODY"

# erro: papel errado
http POST "/sessoes/$SESSAO/gravacao/$SEGMENTO2/vincular" "$TVER" "{\"lock-version\":$LV_SEG2}"
esperar_status 403 "$ST" "POST /gravacao/:id/vincular" "papel errado (vereador)" "$BODY"

# feliz: vincula
http POST "/sessoes/$SESSAO/gravacao/$SEGMENTO2/vincular" "$TSEC" "{\"lock-version\":$LV_SEG2}"
esperar_status 200 "$ST" "POST /gravacao/:id/vincular" "feliz: vincula segmento2 (lock-version lido via psql)" "$BODY"

DB_SESSAO_SEG2=$(dbval "select sessao_id from sessoes.gravacao_segmento where id='$SEGMENTO2';")
if [ "$DB_SESSAO_SEG2" = "$SESSAO" ]; then registrar OK "gravacao_segmento" "banco confirma o vinculo"; else registrar QUEBRA "gravacao_segmento" "banco NAO confirma o vinculo ($DB_SESSAO_SEG2)"; fi

# erro: repeticao (ja vinculado)
http POST "/sessoes/$SESSAO/gravacao/$SEGMENTO2/vincular" "$TSEC" "{\"lock-version\":$LV_SEG2}"
esperar_status 409 "$ST" "POST /gravacao/:id/vincular" "repeticao: ja vinculado / lock stale" "$BODY"

echo

# =========================================================================================
# fecho: encerrar a sessao — e sondar se rotas SEM check de estado ainda aceitam escrita numa
# sessao ja fechada (achado por leitura de fonte: nenhum controller de pauta/tribuna/incidente/
# decisao-mesa/votacao checa o `estado` da sessao — so' `pode-ver-sessao?`, mesma-casa).
# =========================================================================================
echo "--- Fecho: POST /sessoes/:id/transicao (aberta -> encerrada) + sondagem pos-fechamento ---"

http GET "/sessoes/$SESSAO" "$TSEC"
ESTADO_PRE_FECHO="$(jget estado)"
if [ "$ESTADO_PRE_FECHO" = "aberta" ] || [ "$ESTADO_PRE_FECHO" = "suspensa" ]; then
  LV_SESSAO=$(dbval "select lock_version from sessoes.sessao where id='$SESSAO';")
  http POST "/sessoes/$SESSAO/transicao" "$TSEC" "{\"para\":\"encerrada\",\"lock-version\":$LV_SESSAO}"
  esperar_status 200 "$ST" "POST /transicao" "feliz: $ESTADO_PRE_FECHO -> encerrada" "$BODY"
  DB_ESTADO_FINAL=$(dbval "select estado from sessoes.sessao where id='$SESSAO';")
  if [ "$DB_ESTADO_FINAL" = "encerrada" ]; then registrar OK "sessao" "banco confirma estado=encerrada"; else registrar QUEBRA "sessao" "banco NAO confirma o fechamento ($DB_ESTADO_FINAL)"; fi

  # sondagem: pauta/tribuna/votacao continuam aceitando escrita numa sessao ENCERRADA?
  http POST "/sessoes/$SESSAO/pauta/itens" "$TSEC" '{"fase":"expediente","tipo-item":"comunicado","texto-descricao":"pos-encerramento"}'
  if [ "$ST" = "201" ]; then
    registrar QUEBRA "POST /pauta/itens" "ACEITA item novo numa sessao JA ENCERRADA (nenhum controller de pauta checa o estado da sessao — so' mesma-casa)" "$BODY"
  else
    registrar OK "POST /pauta/itens" "recusa item pos-encerramento -> $ST" "$BODY"
  fi

  http POST "/sessoes/$SESSAO/votacoes" "$TSEC" "{\"objeto-tipo\":\"proposicao\",\"objeto-id\":\"$PROP\",\"modalidade\":\"simbolica\",\"quorum-tipo\":\"maioria_simples\"}"
  if [ "$ST" = "201" ]; then
    registrar QUEBRA "POST /votacoes" "ACEITA abrir votacao numa sessao JA ENCERRADA (pode-dirigir-votacao? so' checa mesma-casa)" "$BODY"
  else
    registrar OK "POST /votacoes" "recusa abrir votacao pos-encerramento -> $ST" "$BODY"
  fi
else
  registrar GAP "POST /transicao" "sessao nao estava 'aberta'/'suspensa' no fecho (estado=$ESTADO_PRE_FECHO) — pulando o fechamento e a sondagem pos-encerramento (corrida anterior nao resetada?)"
fi

echo
echo "=== VEREDICTO T2 GRUPO A ==="
echo "ok=$N_OK  QUEBRA=$N_QUEBRA  FRAGIL=$N_FRAGIL  cosmetico=$N_COSMETICO  GAP=$N_GAP"
echo "log completo: $LOG"

if [ "$N_QUEBRA" -gt 0 ] || [ "$N_FRAGIL" -gt 0 ]; then
  echo "REPROVADO — $((N_QUEBRA+N_FRAGIL)) achado(s) de QUEBRA/FRAGIL"
  exit 1
fi
echo "OK — nenhum QUEBRA/FRAGIL"
exit 0
