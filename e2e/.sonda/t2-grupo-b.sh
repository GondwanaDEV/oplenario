#!/usr/bin/env bash
# Sonda da Trilha 2 / Grupo B — as 25 rotas de escrita restantes, por HTTP direto (sem UI).
#
# Cobre o que o grupo A (`t2-grupo-a.sh`, condução de sessão) deixou de fora:
#   B — participacao, servidor  (8) : responder e-SIC · decidir recurso · responder LGPD ·
#                                     encarregado · ouvidoria (responder/arquivar/prorrogar) · moderar
#   C — cidadao, portal         (6) : abrir e-SIC · recurso · LGPD · ouvidoria · comentar · denunciar
#   C — transparencia           (2) : acompanhar materia (POST/DELETE)
#   D — compliance/TCE          (3) : validar · submeter · resposta
#   E — identidade              (3) : identidade · acesso · convite
#   F — cadastros               (2) : vincular identidade · reassuncao
#   G — legislativo             (1) : apreciacao de tramitacao executiva
#
# Por que bash+curl+psql no HOST: identico ao grupo A — o alvo e' o BACKEND por HTTP puro, nenhum
# app-code do projeto roda aqui. O mandato Docker cobre `node`/`npx`/`clj`, nao `curl`/`docker exec`.
#
# DIFERENCA DE DESENHO em relacao ao grupo A: o grupo A percorria uma CADEIA de mao unica (a sessao
# …0212 agendada->aberta->encerrada) e por isso nao era re-rodavel. Aqui a maioria das rotas e'
# independente, e o grupo C CRIA as entidades sobre as quais o grupo B age — entao a sonda SEMEIA A
# SI MESMA: abre um pedido e-SIC pelo portal e responde a esse, em vez de gastar o …0401 da demo.
# As entidades de id fixo da semente (…0401/0404/0410/0420/0433) sao usadas so' onde a sonda precisa
# de um estado que ela nao consegue produzir sozinha, e o script diz explicitamente quando gasta uma.
#
# Sai != 0 se qualquer QUEBRA ou FRAGIL foi observado. COSMETICO/GAP nunca derrubam o exit code.
set -u

DIR="$(cd "$(dirname "$0")" && pwd)"          # .../e2e/.sonda
RAIZ="$(cd "$DIR/../.." && pwd)"
ARTEFATO="$RAIZ/e2e/.artifacts/demo-ids.edn"

BACKEND="${OPLENARIO_BACKEND:-http://localhost:8888}"
PG_CONTAINER="${OPLENARIO_PG_CONTAINER:-oplenario-postgres-1}"

if [ ! -f "$ARTEFATO" ]; then
  echo "ERRO: $ARTEFATO nao existe — rode ./demo/semear-tudo.sh antes." >&2
  exit 1
fi

# ---------- ids da semente (nunca cravados a mao — mesmo principio do grupo A) ----------
EDN="$(cat "$ARTEFATO")"
py() { python3 -c "$1" "$EDN"; }

ENTE=$(py '
import sys, re
m = re.search(r":ente\s+#uuid\s+\"([0-9a-fA-F-]{36})\"", sys.argv[1]); print(m.group(1))')
VEREADOR1=$(py '
import sys, re
m = re.search(r":vereadores\s+\[\{:id\s+#uuid\s+\"([0-9a-fA-F-]{36})\"", sys.argv[1]); print(m.group(1))')
IDENT_SECRETARIA=$(py '
import sys, re
bloco = re.search(r":identidades\s*\{([\s\S]*)\}\s*\}?\s*$", sys.argv[1]).group(1)
print(re.search(r":secretaria\s+#uuid\s+\"([0-9a-fA-F-]{36})\"", bloco).group(1))')
IDENT_VEREADOR=$(py '
import sys, re
bloco = re.search(r":identidades\s*\{([\s\S]*)\}\s*\}?\s*$", sys.argv[1]).group(1)
print(re.search(r":vereador\s+#uuid\s+\"([0-9a-fA-F-]{36})\"", bloco).group(1))')

if [ -z "$ENTE" ] || [ -z "$VEREADOR1" ] || [ -z "$IDENT_SECRETARIA" ] || [ -z "$IDENT_VEREADOR" ]; then
  echo "ERRO: nao consegui extrair ente/vereadores/identidades de $ARTEFATO — formato mudou?" >&2
  exit 1
fi

TSEC="{\"identidade-id\":\"$IDENT_SECRETARIA\",\"ente-id\":\"$ENTE\",\"papeis\":[\"secretario\"]}"
TVER="{\"identidade-id\":\"$IDENT_VEREADOR\",\"ente-id\":\"$ENTE\",\"papeis\":[\"vereador\"]}"

# ---------- Casa errada: um intruso REAL (identidade+vinculo+papel ativos em OUTRO ente) ----------
# Mesmo racional do grupo A: `identidade.autenticacao/resolver-sessao` IGNORA o campo "papeis" do
# token e RESOLVE do snapshot do banco. Token com ente-id forjado sem vinculo REAL cai em 401 "sem
# vinculo ativo" e nao testa isolamento nenhum. Fixture real, idempotente, ids fixos.
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
LOG="$DIR/t2-grupo-b-ultima-corrida.jsonl"
: > "$LOG"

registrar() {
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

esperar_status() {
  esperado="$1"; real="$2"; rota="$3"; desc="$4"; ev="${5:-}"
  if [ "$real" = "$esperado" ]; then
    registrar OK "$rota" "$desc -> $real (esperado)" "$ev"
  else
    registrar QUEBRA "$rota" "$desc -> $real (esperava $esperado)" "$ev"
  fi
}

# esperar_um_de "403 404" STATUS_REAL ROTA DESC [EV] — para checagens de isolamento, onde 403 e 404
# sao ambos fail-closed legitimos (o grupo A ja tratava assim; aqui virou helper em vez de if solto).
esperar_um_de() {
  esperados="$1"; real="$2"; rota="$3"; desc="$4"; ev="${5:-}"
  for e in $esperados; do
    if [ "$real" = "$e" ]; then registrar OK "$rota" "$desc -> $real (aceito: $esperados)" "$ev"; return; fi
  done
  registrar QUEBRA "$rota" "$desc -> $real (esperava um de: $esperados)" "$ev"
}

# http METODO PATH TOKEN_JSON BODY_JSON  -> $ST = status, $BODY = corpo
# TOKEN_JSON vazio ("") = requisicao ANONIMA (sem header Authorization) — necessario para as rotas
# de portal do grupo C, onde a pergunta central e' justamente se o backend aceita anonimo.
http() {
  metodo="$1"; path="$2"; tok="$3"; body="${4:-}"
  tmp=$(mktemp)
  set -- -s -o "$tmp" -w '%{http_code}' -X "$metodo" "$BACKEND$path"
  [ -n "$tok" ] && set -- "$@" -H "Authorization: Bearer $tok"
  [ -n "$body" ] && set -- "$@" -H "Content-Type: application/json" --data "$body"
  ST=$(curl "$@")
  BODY=$(cat "$tmp"); rm -f "$tmp"
}

jget() { echo "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get(sys.argv[1],''))" "$1" 2>/dev/null; }
jhas() { echo "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if sys.argv[1] in d else 1)" "$1" 2>/dev/null; }
dbval() { docker exec "$PG_CONTAINER" psql -U oplenario -d oplenario -qtA -c "$1" | tr -d '[:space:]'; }

# exigir NOME_VAR... — 0 se todos os ids existem; senao registra UM achado e devolve 1.
# Por que existe: na 1a corrida desta sonda, UM fixture que nao nasceu (o pedido e-SIC, barrado por um
# 500 de contador) produziu 40 achados derivados — todos "esperava 200, veio 400 Ambiguous URI empty
# segment", porque o id vazio montava `/esic/pedidos//resposta`. Quarenta linhas de ruido escondendo o
# unico defeito real. Uma sonda que multiplica um achado por N mede a propria cascata, nao o produto.
exigir() {
  faltando=""
  for n in "$@"; do
    eval "v=\${$n:-}"
    [ -z "$v" ] && faltando="$faltando $n"
  done
  if [ -n "$faltando" ]; then
    registrar QUEBRA "sonda (dependencia)" "BLOCO PULADO: o fixture de origem nao nasceu e estes ids ficaram vazios ->$faltando. O defeito esta na criacao, acima; as rotas deste bloco NAO foram exercidas"
    return 1
  fi
  return 0
}

echo "=== T2 grupo B — as 25 escritas restantes por HTTP ==="
echo "ente=$ENTE"
docker ps --format '{{.Names}} {{.Status}}' | grep -E 'oplenario-(app|postgres)-1'
echo

# =========================================================================================
# FIXTURE DE CIDADAO — um vinculo REAL tipo 'cidadao' no ente da demo, SEM papel nenhum
# =========================================================================================
# Por que precisa existir: as 8 rotas do grupo C sao "SO-auth, sem papel" — a LAI diz que QUALQUER
# um pede. Mas `identidade.autenticacao/resolver-sessao` exige VINCULO ATIVO no ente, e o ente da
# demo NAO TEM NENHUM vinculo 'cidadao' (medido: `select ... where tipo='cidadao'` = 0 linhas).
# Exercer o grupo C com o token da secretaria mediria a rota do lado errado do balcao. Fixture real,
# idempotente, id fixo — mesma tecnica do INTRUSO.
CIDADAO_IDENTIDADE="cccccccc-0000-0000-0000-0000000000c1"
CIDADAO_VINCULO="cccccccc-0000-0000-0000-0000000000c2"
TCID="{\"identidade-id\":\"$CIDADAO_IDENTIDADE\",\"ente-id\":\"$ENTE\",\"papeis\":[]}"

dbexec "INSERT INTO identidade.identidade (id,cpf,nome) VALUES ('$CIDADAO_IDENTIDADE','SONDA-T2-CIDADAO','Sonda T2 Cidada') ON CONFLICT (id) DO NOTHING;" >/dev/null
dbexec "INSERT INTO identidade.vinculo (ente_id,id,identidade_id,tipo,estado) VALUES ('$ENTE','$CIDADAO_VINCULO','$CIDADAO_IDENTIDADE','cidadao','ativo') ON CONFLICT (ente_id,id) DO NOTHING;" >/dev/null

# Um SEGUNDO cidadao — para provar que o recurso de e-SIC so' e' interposto pelo PROPRIO solicitante.
CIDADAO2_IDENTIDADE="cccccccc-0000-0000-0000-0000000000d1"
CIDADAO2_VINCULO="cccccccc-0000-0000-0000-0000000000d2"
TCID2="{\"identidade-id\":\"$CIDADAO2_IDENTIDADE\",\"ente-id\":\"$ENTE\",\"papeis\":[]}"
dbexec "INSERT INTO identidade.identidade (id,cpf,nome) VALUES ('$CIDADAO2_IDENTIDADE','SONDA-T2-CIDADAO2','Sonda T2 Cidadao Dois') ON CONFLICT (id) DO NOTHING;" >/dev/null
dbexec "INSERT INTO identidade.vinculo (ente_id,id,identidade_id,tipo,estado) VALUES ('$ENTE','$CIDADAO2_VINCULO','$CIDADAO2_IDENTIDADE','cidadao','ativo') ON CONFLICT (ente_id,id) DO NOTHING;" >/dev/null

# Token com papel de admin_ente (rotas de identidade/vinculo). A semente cria UMA identidade com
# admin_ente (junto com vereador) — lida do banco, nunca cravada.
IDENT_ADMIN=$(dbval "select identidade_id from identidade.usuario_papel where ente_id='$ENTE' and papel='admin_ente' limit 1;")
if [ -z "$IDENT_ADMIN" ]; then
  echo "ERRO: nenhuma identidade com papel admin_ente no ente $ENTE — a semente mudou?" >&2
  exit 1
fi
TADM="{\"identidade-id\":\"$IDENT_ADMIN\",\"ente-id\":\"$ENTE\",\"papeis\":[\"admin_ente\"]}"

# ---------- observacao estrutural, medida antes de qualquer escrita ----------
SOLICITANTE_0401=$(dbval "select solicitante_identidade_id from participacao.pedido_esic where id='10000000-0000-0000-0000-000000000401';")
VINC_0401=$(dbval "select count(*) from identidade.vinculo where identidade_id='$SOLICITANTE_0401' and estado='ativo';")
if [ "$VINC_0401" = "0" ]; then
  # NAO e' QUEBRA da plataforma: a plataforma funciona (esta sonda abre e-SIC/LGPD/ouvidoria pela borda,
  # com um cidadao que TEM vinculo — 100% verde). E' fidelidade do DADO DE DEMO. `demo/casa.clj:106`
  # decide de proposito que "o cidadao fica SEM vinculo (a leitura publica do portal nao exige login)" —
  # correto para LEITURA. Mas a semente usa essa MESMA identidade como SOLICITANTE de 3 pedidos e-SIC,
  # que e' ESCRITA autenticada: o dado semeado esta num estado que a borda nao consegue produzir, e o
  # cidadao da demo nao conseguiria acompanhar o proprio pedido. Decisao de narrativa da demo (dar
  # vinculo 'cidadao' aos solicitantes, ou assumir que sao acervo historico) — do Daouda, nao minha.
  registrar GAP "semente/participacao" "o solicitante do e-SIC semeado ($SOLICITANTE_0401) nao tem vinculo ativo em ente nenhum: o dado semeado esta num estado que a borda nao produz, e esse cidadao nao poderia abrir nem acompanhar o proprio pedido. `demo/casa.clj:106` decide o 'sem vinculo' para LEITURA publica; a semente estendeu a mesma identidade a uma ESCRITA autenticada. Decisao de narrativa da demo, pendente" "select count(*) from identidade.vinculo where identidade_id='$SOLICITANTE_0401' and estado='ativo' -> 0"
else
  registrar OK "semente/participacao" "o solicitante do e-SIC semeado tem vinculo ativo ($VINC_0401)"
fi

# =========================================================================================
# GRUPO C — o cidadao, pelo portal (8 rotas). Roda ANTES do grupo B: e' quem cria as entidades
# sobre as quais o servidor age, o que torna esta sonda RE-RODAVEL sem gastar a semente.
# =========================================================================================
echo "--- Rota C1: POST /portal/esic/pedidos ---"

# erro: ANONIMO (sem header Authorization) — a pergunta central do grupo C
http POST "/portal/esic/pedidos" "" '{"assunto":"sonda anonima","descricao":"sem token"}'
if [ "$ST" = "401" ]; then
  registrar OK "POST /portal/esic/pedidos" "anonimo recusado -> 401 (fail-closed). CONFIRMA: as rotas de portal exigem sessao; sem broker gov.br vivo, nenhum cidadao real chega aqui — o GAP e' de IDENTIDADE, nao de interface"
else
  registrar QUEBRA "POST /portal/esic/pedidos" "anonimo recebeu $ST (esperava 401)" "$BODY"
fi

# erro: corpo invalido (falta descricao)
http POST "/portal/esic/pedidos" "$TCID" '{"assunto":"so assunto"}'
esperar_status 400 "$ST" "POST /portal/esic/pedidos" "corpo invalido (falta descricao)" "$BODY"

# erro: campo extra (schema :closed true)
http POST "/portal/esic/pedidos" "$TCID" '{"assunto":"a","descricao":"d","solicitante":"forjado"}'
if [ "$ST" = "400" ] || [ "$ST" = "201" ]; then
  registrar OK "POST /portal/esic/pedidos" "campo forjado 'solicitante' -> $ST (allowlist descarta ou schema recusa; o que importa e o proximo check)"
else
  registrar QUEBRA "POST /portal/esic/pedidos" "campo forjado -> $ST inesperado" "$BODY"
fi

# feliz
http POST "/portal/esic/pedidos" "$TCID" '{"assunto":"Sonda T2B — despesas com diarias","descricao":"Solicito a relacao de diarias pagas no exercicio corrente."}'
esperar_status 201 "$ST" "POST /portal/esic/pedidos" "feliz: cidadao com vinculo abre pedido" "$BODY"
PROTO_ESIC=$(jget protocolo)
PEDIDO_A=$(dbval "select id from participacao.pedido_esic where ente_id='$ENTE' and protocolo='$PROTO_ESIC';")
if [ -n "$PEDIDO_A" ]; then
  registrar OK "POST /portal/esic/pedidos" "banco confirma a linha (protocolo=$PROTO_ESIC id=$PEDIDO_A)"
else
  registrar QUEBRA "POST /portal/esic/pedidos" "resposta 201 com protocolo=$PROTO_ESIC mas NENHUMA linha em participacao.pedido_esic"
fi
# anti-forge: o solicitante gravado tem de ser o ATOR do token, nunca o campo do corpo
DONO_A=$(dbval "select solicitante_identidade_id from participacao.pedido_esic where id='$PEDIDO_A';")
if [ "$DONO_A" = "$CIDADAO_IDENTIDADE" ]; then
  registrar OK "POST /portal/esic/pedidos" "anti-forge: solicitante gravado = ator do token, nao o campo do corpo"
else
  registrar QUEBRA "POST /portal/esic/pedidos" "solicitante gravado ($DONO_A) NAO e' o ator do token ($CIDADAO_IDENTIDADE)"
fi
# nao-idempotente por contrato: 2a chamada identica cria OUTRO pedido (registrado, nao acusado)
http POST "/portal/esic/pedidos" "$TCID" '{"assunto":"Sonda T2B — despesas com diarias","descricao":"Solicito a relacao de diarias pagas no exercicio corrente."}'
PEDIDO_B=$(dbval "select id from participacao.pedido_esic where ente_id='$ENTE' and protocolo='$(jget protocolo)';")
if [ -n "$PEDIDO_B" ] && [ "$PEDIDO_B" != "$PEDIDO_A" ]; then
  registrar OK "POST /portal/esic/pedidos" "2a chamada identica cria pedido NOVO ($PEDIDO_B) — nao idempotente por contrato (LAI: cada pedido e' um pedido)"
else
  registrar QUEBRA "POST /portal/esic/pedidos" "2a chamada nao produziu pedido novo (id=$PEDIDO_B)"
fi
echo
echo "--- Rota C2: POST /portal/esic/pedidos/:id/recursos ---"
if exigir PEDIDO_A; then

# erro: pedido ainda 'protocolado' (nao ha desfecho para recorrer)
http POST "/portal/esic/pedidos/$PEDIDO_A/recursos" "$TCID" '{"motivo":"recorro antes da resposta"}'
esperar_status 409 "$ST" "POST /portal/.../recursos" "pedido sem desfecho ainda (protocolado) -> conflito" "$BODY"

# erro: pedido inexistente
http POST "/portal/esic/pedidos/00000000-0000-0000-0000-000000000000/recursos" "$TCID" '{"motivo":"x"}'
esperar_status 404 "$ST" "POST /portal/.../recursos" "pedido inexistente" "$BODY"

# erro: corpo invalido
http POST "/portal/esic/pedidos/$PEDIDO_A/recursos" "$TCID" '{}'
esperar_status 400 "$ST" "POST /portal/.../recursos" "corpo invalido (falta motivo)" "$BODY"

fi
echo
echo "--- Rota C3: POST /portal/lgpd/solicitacoes ---"
http POST "/portal/lgpd/solicitacoes" "$TCID" '{"tipo":"inventado"}'
esperar_status 400 "$ST" "POST /portal/lgpd/solicitacoes" "tipo fora do vocabulario da LGPD" "$BODY"
http POST "/portal/lgpd/solicitacoes" "" '{"tipo":"acessar"}'
esperar_status 401 "$ST" "POST /portal/lgpd/solicitacoes" "anonimo recusado" "$BODY"
http POST "/portal/lgpd/solicitacoes" "$TCID" '{"tipo":"acessar","detalhe":"Sonda T2B — quais dados meus a Casa guarda"}'
esperar_status 201 "$ST" "POST /portal/lgpd/solicitacoes" "feliz: titular pede acesso" "$BODY"
SOLIC_A=$(dbval "select id from participacao.solicitacao_titular where ente_id='$ENTE' and protocolo='$(jget protocolo)';")
if [ -n "$SOLIC_A" ]; then registrar OK "POST /portal/lgpd/solicitacoes" "banco confirma (id=$SOLIC_A)"; else registrar QUEBRA "POST /portal/lgpd/solicitacoes" "201 sem linha no banco"; fi
DONO_S=$(dbval "select titular_identidade_id from participacao.solicitacao_titular where id='$SOLIC_A';")
if [ "$DONO_S" = "$CIDADAO_IDENTIDADE" ]; then registrar OK "POST /portal/lgpd/solicitacoes" "anti-forge: titular = ator do token"; else registrar QUEBRA "POST /portal/lgpd/solicitacoes" "titular gravado ($DONO_S) != ator ($CIDADAO_IDENTIDADE)"; fi

echo
echo "--- Rota C4: POST /portal/ouvidoria/manifestacoes ---"
http POST "/portal/ouvidoria/manifestacoes" "$TCID" '{"tipo":"xingamento","assunto":"a","descricao":"d"}'
esperar_status 400 "$ST" "POST /portal/ouvidoria/manifestacoes" "tipo fora do vocabulario da 13.460" "$BODY"
http POST "/portal/ouvidoria/manifestacoes" "" '{"tipo":"denuncia","assunto":"a","descricao":"d","anonima":true}'
if [ "$ST" = "401" ]; then
  registrar OK "POST /portal/ouvidoria/manifestacoes" "manifestacao ANONIMA sem token -> 401. Confirma o contrato: 'anonima' e' sobre o que se PERSISTE, nunca sobre dispensar auth"
else
  registrar QUEBRA "POST /portal/ouvidoria/manifestacoes" "anonima sem token recebeu $ST (esperava 401)" "$BODY"
fi
# 3 manifestacoes: uma p/ responder, uma p/ arquivar, uma p/ prorrogar (o grupo B consome as 3)
for alvo in RESP ARQ PROR; do
  http POST "/portal/ouvidoria/manifestacoes" "$TCID" "{\"tipo\":\"reclamacao\",\"assunto\":\"Sonda T2B $alvo\",\"descricao\":\"manifestacao criada pela sonda para exercer o lado do servidor\"}"
  esperar_status 201 "$ST" "POST /portal/ouvidoria/manifestacoes" "feliz: cria manifestacao ($alvo)" "$BODY"
  eval "MANIF_$alvo=\$(dbval \"select id from participacao.manifestacao_ouvidoria where ente_id='$ENTE' and protocolo='$(jget protocolo)';\")"
done
# anonima=true nao pode gravar o manifestante
http POST "/portal/ouvidoria/manifestacoes" "$TCID" '{"tipo":"denuncia","assunto":"Sonda T2B anonima","descricao":"denuncia anonima de verdade","anonima":true}'
esperar_status 201 "$ST" "POST /portal/ouvidoria/manifestacoes" "feliz: anonima=true" "$BODY"
MANIF_ANON=$(dbval "select id from participacao.manifestacao_ouvidoria where ente_id='$ENTE' and protocolo='$(jget protocolo)';")
ANON_SUJ=$(dbval "select coalesce(manifestante_identidade_id::text,'NULO') from participacao.manifestacao_ouvidoria where id='$MANIF_ANON';")
if [ "$ANON_SUJ" = "NULO" ]; then
  registrar OK "POST /portal/ouvidoria/manifestacoes" "anonima=true NAO persiste o manifestante (o anonimato e' real no banco, nao so' na tela)"
else
  registrar QUEBRA "POST /portal/ouvidoria/manifestacoes" "anonima=true GRAVOU o manifestante ($ANON_SUJ) — o anonimato prometido nao existe no banco"
fi

echo
echo "--- Rota C5: POST /portal/materias/:proposicao_id/comentarios ---"
PROP=$(curl -s "$BACKEND/portal/casa/$ENTE/materias" | python3 -c "
import json,sys
ms = json.load(sys.stdin); print(ms[0]['proposicao-id'])")
echo "proposicao usada = $PROP"
http POST "/portal/materias/$PROP/comentarios" "$TCID" '{"corpo":""}'
esperar_status 400 "$ST" "POST /portal/.../comentarios" "corpo vazio" "$BODY"
http POST "/portal/materias/$PROP/comentarios" "" '{"corpo":"anonimo"}'
esperar_status 401 "$ST" "POST /portal/.../comentarios" "anonimo recusado" "$BODY"
# 3 comentarios: aprovar, rejeitar, denunciar
for alvo in APROVAR REJEITAR DENUNCIAR; do
  http POST "/portal/materias/$PROP/comentarios" "$TCID" "{\"corpo\":\"Sonda T2B — comentario para $alvo\"}"
  esperar_status 201 "$ST" "POST /portal/.../comentarios" "feliz: cria comentario ($alvo)" "$BODY"
  if [ "$(jget estado)" != "pendente" ]; then
    registrar QUEBRA "POST /portal/.../comentarios" "comentario nasce em '$(jget estado)', nao 'pendente' — entraria publicado sem moderacao"
  fi
  eval "COM_$alvo=\$(jget id)"
done

echo
echo "--- Rota C6: POST /portal/comentarios/:id/denunciar ---"
if exigir COM_DENUNCIAR; then
http POST "/portal/comentarios/00000000-0000-0000-0000-000000000000/denunciar" "$TCID" '{"motivo":"x"}'
esperar_status 404 "$ST" "POST /portal/.../denunciar" "comentario inexistente" "$BODY"
http POST "/portal/comentarios/$COM_DENUNCIAR/denunciar" "$TCID2" '{"motivo":"ofensivo"}'
esperar_status 200 "$ST" "POST /portal/.../denunciar" "feliz: 2o cidadao denuncia" "$BODY"
N_DEN=$(dbval "select count(*) from participacao.denuncia_comentario where comentario_id='$COM_DENUNCIAR';")
http POST "/portal/comentarios/$COM_DENUNCIAR/denunciar" "$TCID2" '{"motivo":"ofensivo"}'
esperar_status 200 "$ST" "POST /portal/.../denunciar" "repeticao do MESMO denunciante -> 200 (idempotente por contrato, nao vaza se ja havia)" "$BODY"
N_DEN2=$(dbval "select count(*) from participacao.denuncia_comentario where comentario_id='$COM_DENUNCIAR';")
if [ "$N_DEN" = "$N_DEN2" ]; then
  registrar OK "POST /portal/.../denunciar" "idempotencia REAL: a 2a denuncia nao criou linha ($N_DEN -> $N_DEN2)"
else
  registrar QUEBRA "POST /portal/.../denunciar" "200 idempotente na resposta mas DUPLICOU no banco ($N_DEN -> $N_DEN2)"
fi

fi
echo
echo "--- Rotas C7/C8: POST e DELETE /portal/materias/:proposicao_id/acompanhar ---"
http POST "/portal/materias/$PROP/acompanhar" "" ""
esperar_status 401 "$ST" "POST /portal/.../acompanhar" "anonimo recusado" "$BODY"
http POST "/portal/materias/00000000-0000-0000-0000-000000000000/acompanhar" "$TCID" ""
esperar_status 404 "$ST" "POST /portal/.../acompanhar" "materia inexistente" "$BODY"
http POST "/portal/materias/$PROP/acompanhar" "$TCID" ""
esperar_status 201 "$ST" "POST /portal/.../acompanhar" "feliz: cidadao segue a materia" "$BODY"
http GET "/portal/acompanhamentos" "$TCID"
if echo "$BODY" | grep -q "$PROP"; then
  registrar OK "GET /portal/acompanhamentos" "leitura subsequente confirma o acompanhamento"
else
  registrar QUEBRA "GET /portal/acompanhamentos" "seguiu com 201 mas a leitura subsequente NAO devolve a materia" "$BODY"
fi
# isolamento por seguidor: o 2o cidadao nao ve o acompanhamento do 1o
http GET "/portal/acompanhamentos" "$TCID2"
if echo "$BODY" | grep -q "$PROP"; then
  registrar QUEBRA "GET /portal/acompanhamentos" "VAZAMENTO: o 2o cidadao ve o acompanhamento do 1o" "$BODY"
else
  registrar OK "GET /portal/acompanhamentos" "escopo por seguidor: o 2o cidadao nao ve o acompanhamento do 1o"
fi
http POST "/portal/materias/$PROP/acompanhar" "$TCID" ""
esperar_status 201 "$ST" "POST /portal/.../acompanhar" "re-seguir -> 201 (UPSERT idempotente por contrato)" "$BODY"
http DELETE "/portal/materias/$PROP/acompanhar" "$TCID" ""
esperar_status 200 "$ST" "DELETE /portal/.../acompanhar" "feliz: deixa de seguir" "$BODY"
http GET "/portal/acompanhamentos" "$TCID"
if echo "$BODY" | grep -q "$PROP"; then
  registrar QUEBRA "DELETE /portal/.../acompanhar" "200 no cancelamento mas a leitura subsequente AINDA devolve a materia" "$BODY"
else
  registrar OK "DELETE /portal/.../acompanhar" "leitura subsequente confirma o cancelamento"
fi
http DELETE "/portal/materias/$PROP/acompanhar" "$TCID" ""
esperar_status 200 "$ST" "DELETE /portal/.../acompanhar" "repeticao sem seguir -> 200 (retirar consentimento e' sempre seguro)" "$BODY"
echo
# =========================================================================================
# GRUPO B — o servidor responde (8 rotas). Age sobre o que o grupo C acabou de criar.
# =========================================================================================
echo "--- Rota B1: POST /esic/pedidos/:id/resposta ---"
if exigir PEDIDO_A; then
http POST "/esic/pedidos/$PEDIDO_A/resposta" "$TVER" '{"corpo":"resposta do vereador"}'
esperar_status 403 "$ST" "POST /esic/pedidos/:id/resposta" "papel errado (vereador)" "$BODY"
http POST "/esic/pedidos/$PEDIDO_A/resposta" "$TCID" '{"corpo":"resposta do cidadao"}'
esperar_status 403 "$ST" "POST /esic/pedidos/:id/resposta" "papel errado (cidadao, sem papel)" "$BODY"
http POST "/esic/pedidos/$PEDIDO_A/resposta" "$TINTRUSO" '{"corpo":"resposta de outra Casa"}'
esperar_um_de "403 404" "$ST" "POST /esic/pedidos/:id/resposta" "isolamento multi-tenant (intruso REAL de outro ente)" "$BODY"
http POST "/esic/pedidos/$PEDIDO_A/resposta" "$TSEC" '{}'
esperar_status 400 "$ST" "POST /esic/pedidos/:id/resposta" "corpo invalido (falta corpo)" "$BODY"
http POST "/esic/pedidos/00000000-0000-0000-0000-000000000000/resposta" "$TSEC" '{"corpo":"x"}'
esperar_status 404 "$ST" "POST /esic/pedidos/:id/resposta" "pedido inexistente" "$BODY"
http POST "/esic/pedidos/$PEDIDO_A/resposta" "$TSEC" '{"corpo":"Segue em anexo a relacao de diarias do exercicio."}'
esperar_status 200 "$ST" "POST /esic/pedidos/:id/resposta" "feliz: secretaria responde" "$BODY"
EST_A=$(dbval "select estado from participacao.pedido_esic where id='$PEDIDO_A';")
if [ "$EST_A" = "respondido" ]; then registrar OK "POST /esic/pedidos/:id/resposta" "banco confirma estado=respondido"; else registrar QUEBRA "POST /esic/pedidos/:id/resposta" "banco diverge: estado=$EST_A"; fi
N_RESP=$(dbval "select count(*) from participacao.resposta_esic where pedido_id='$PEDIDO_A';")
if [ "$N_RESP" = "1" ]; then registrar OK "POST /esic/pedidos/:id/resposta" "banco confirma 1 resposta gravada"; else registrar QUEBRA "POST /esic/pedidos/:id/resposta" "esperava 1 resposta gravada, achou $N_RESP"; fi
http POST "/esic/pedidos/$PEDIDO_A/resposta" "$TSEC" '{"corpo":"resposta repetida"}'
esperar_status 409 "$ST" "POST /esic/pedidos/:id/resposta" "repeticao sobre pedido ja respondido" "$BODY"
N_RESP2=$(dbval "select count(*) from participacao.resposta_esic where pedido_id='$PEDIDO_A';")
if [ "$N_RESP" = "$N_RESP2" ]; then registrar OK "POST /esic/pedidos/:id/resposta" "a repeticao recusada NAO gravou 2a resposta"; else registrar QUEBRA "POST /esic/pedidos/:id/resposta" "recusou com 409 mas GRAVOU resposta ($N_RESP -> $N_RESP2)"; fi

fi
echo
echo "--- Rota C2 (retomada): recurso agora que o pedido tem desfecho ---"
if exigir PEDIDO_A; then
http POST "/portal/esic/pedidos/$PEDIDO_A/recursos" "$TCID2" '{"motivo":"recurso de quem nao e o solicitante"}'
esperar_status 403 "$ST" "POST /portal/.../recursos" "recurso por quem NAO e' o solicitante" "$BODY"
http POST "/portal/esic/pedidos/$PEDIDO_A/recursos" "$TCID" '{"motivo":"A resposta nao contemplou o periodo solicitado."}'
esperar_status 201 "$ST" "POST /portal/.../recursos" "feliz: o proprio solicitante recorre" "$BODY"
RECURSO_A=$(dbval "select id from participacao.recurso_esic where pedido_id='$PEDIDO_A' order by criado_em desc limit 1;")
if [ -n "$RECURSO_A" ]; then registrar OK "POST /portal/.../recursos" "banco confirma o recurso (id=$RECURSO_A)"; else registrar QUEBRA "POST /portal/.../recursos" "201 sem linha em participacao.recurso_esic"; fi
http POST "/portal/esic/pedidos/$PEDIDO_A/recursos" "$TCID" '{"motivo":"segundo recurso da mesma instancia"}'
esperar_status 409 "$ST" "POST /portal/.../recursos" "2o recurso na MESMA instancia -> UNIQUE vira 409, nao 500 cru" "$BODY"

fi
echo
echo "--- Rota B2: POST /esic/recursos/:id/decisao ---"
if exigir RECURSO_A; then
http POST "/esic/recursos/$RECURSO_A/decisao" "$TVER" '{"corpo":"x"}'
esperar_status 403 "$ST" "POST /esic/recursos/:id/decisao" "papel errado (vereador)" "$BODY"
http POST "/esic/recursos/$RECURSO_A/decisao" "$TINTRUSO" '{"corpo":"x"}'
esperar_um_de "403 404" "$ST" "POST /esic/recursos/:id/decisao" "isolamento multi-tenant" "$BODY"
http POST "/esic/recursos/00000000-0000-0000-0000-000000000000/decisao" "$TSEC" '{"corpo":"x"}'
esperar_status 404 "$ST" "POST /esic/recursos/:id/decisao" "recurso inexistente" "$BODY"
http POST "/esic/recursos/$RECURSO_A/decisao" "$TSEC" '{"corpo":"Recurso provido: segue o periodo faltante."}'
esperar_status 200 "$ST" "POST /esic/recursos/:id/decisao" "feliz: autoridade superior decide" "$BODY"
EST_R=$(dbval "select estado from participacao.recurso_esic where id='$RECURSO_A';")
if [ "$EST_R" = "decidido" ]; then registrar OK "POST /esic/recursos/:id/decisao" "banco confirma estado=decidido"; else registrar QUEBRA "POST /esic/recursos/:id/decisao" "banco diverge: estado=$EST_R"; fi
http POST "/esic/recursos/$RECURSO_A/decisao" "$TSEC" '{"corpo":"decisao repetida"}'
esperar_status 409 "$ST" "POST /esic/recursos/:id/decisao" "repeticao sobre recurso ja decidido" "$BODY"

fi
echo
echo "--- Rota B3: POST /lgpd/solicitacoes/:id/resposta ---"
if exigir SOLIC_A; then
http POST "/lgpd/solicitacoes/$SOLIC_A/resposta" "$TCID" '{"corpo":"x"}'
esperar_status 403 "$ST" "POST /lgpd/solicitacoes/:id/resposta" "papel errado (cidadao)" "$BODY"
http POST "/lgpd/solicitacoes/$SOLIC_A/resposta" "$TINTRUSO" '{"corpo":"x"}'
esperar_um_de "403 404" "$ST" "POST /lgpd/solicitacoes/:id/resposta" "isolamento multi-tenant" "$BODY"
http POST "/lgpd/solicitacoes/$SOLIC_A/resposta" "$TSEC" '{}'
esperar_status 400 "$ST" "POST /lgpd/solicitacoes/:id/resposta" "corpo invalido" "$BODY"
http POST "/lgpd/solicitacoes/$SOLIC_A/resposta" "$TSEC" '{"corpo":"A Casa mantem os dados X, Y e Z sobre o titular."}'
esperar_status 200 "$ST" "POST /lgpd/solicitacoes/:id/resposta" "feliz" "$BODY"
EST_S=$(dbval "select estado from participacao.solicitacao_titular where id='$SOLIC_A';")
if [ "$EST_S" = "respondida" ]; then registrar OK "POST /lgpd/solicitacoes/:id/resposta" "banco confirma estado=respondida"; else registrar QUEBRA "POST /lgpd/solicitacoes/:id/resposta" "banco diverge: estado=$EST_S"; fi
http POST "/lgpd/solicitacoes/$SOLIC_A/resposta" "$TSEC" '{"corpo":"repetida"}'
esperar_status 409 "$ST" "POST /lgpd/solicitacoes/:id/resposta" "repeticao" "$BODY"

fi
echo
echo "--- Rota B4: PUT /lgpd/encarregado ---"
http PUT "/lgpd/encarregado" "$TCID" '{"nome":"x","rotulo":"y","email":"a@b.c"}'
esperar_status 403 "$ST" "PUT /lgpd/encarregado" "papel errado (cidadao)" "$BODY"
http PUT "/lgpd/encarregado" "$TSEC" '{"nome":"Sem rotulo"}'
esperar_status 400 "$ST" "PUT /lgpd/encarregado" "corpo incompleto (falta rotulo/email)" "$BODY"
http PUT "/lgpd/encarregado" "$TSEC" '{"nome":"Sonda T2B Encarregada","rotulo":"Encarregada de Dados","email":"dpo.sonda@camara.exemplo.br"}'
esperar_status 200 "$ST" "PUT /lgpd/encarregado" "feliz: define encarregado" "$BODY"
N_ENC=$(dbval "select count(*) from participacao.encarregado where ente_id='$ENTE';")
http PUT "/lgpd/encarregado" "$TSEC" '{"nome":"Sonda T2B Encarregada II","rotulo":"Encarregada de Dados","email":"dpo2.sonda@camara.exemplo.br"}'
esperar_status 200 "$ST" "PUT /lgpd/encarregado" "repeticao -> 200 (UPSERT: config, nao ciclo de vida)" "$BODY"
N_ENC2=$(dbval "select count(*) from participacao.encarregado where ente_id='$ENTE';")
if [ "$N_ENC" = "$N_ENC2" ]; then registrar OK "PUT /lgpd/encarregado" "UPSERT real: 2a chamada nao duplicou a linha ($N_ENC)"; else registrar QUEBRA "PUT /lgpd/encarregado" "UPSERT duplicou a linha ($N_ENC -> $N_ENC2) — o ente passa a ter 2 encarregados"; fi
NOME_ENC=$(dbval "select nome from participacao.encarregado where ente_id='$ENTE';")
case "$NOME_ENC" in
  *II) registrar OK "PUT /lgpd/encarregado" "leitura subsequente reflete a 2a escrita (nome=$NOME_ENC)" ;;
  *) registrar QUEBRA "PUT /lgpd/encarregado" "a 2a escrita respondeu 200 mas o banco guarda o valor ANTIGO (nome=$NOME_ENC)" ;;
esac

echo
echo "--- Rotas B5/B6/B7: ouvidoria (resposta / arquivar / prorrogar) ---"
if exigir MANIF_RESP MANIF_ARQ MANIF_PROR; then
http POST "/ouvidoria/manifestacoes/$MANIF_RESP/resposta" "$TCID" '{"corpo":"x"}'
esperar_status 403 "$ST" "POST /ouvidoria/.../resposta" "papel errado (cidadao)" "$BODY"
http POST "/ouvidoria/manifestacoes/$MANIF_RESP/resposta" "$TINTRUSO" '{"corpo":"x"}'
esperar_um_de "403 404" "$ST" "POST /ouvidoria/.../resposta" "isolamento multi-tenant" "$BODY"
http POST "/ouvidoria/manifestacoes/$MANIF_RESP/resposta" "$TSEC" '{"corpo":"A Secretaria de Obras foi acionada e respondeu no prazo."}'
esperar_status 200 "$ST" "POST /ouvidoria/.../resposta" "feliz" "$BODY"
EST_M=$(dbval "select estado from participacao.manifestacao_ouvidoria where id='$MANIF_RESP';")
if [ "$EST_M" = "respondida" ]; then registrar OK "POST /ouvidoria/.../resposta" "banco confirma estado=respondida"; else registrar QUEBRA "POST /ouvidoria/.../resposta" "banco diverge: estado=$EST_M"; fi
http POST "/ouvidoria/manifestacoes/$MANIF_RESP/resposta" "$TSEC" '{"corpo":"repetida"}'
esperar_status 409 "$ST" "POST /ouvidoria/.../resposta" "repeticao" "$BODY"

http POST "/ouvidoria/manifestacoes/$MANIF_ARQ/arquivar" "$TSEC" '{}'
esperar_status 400 "$ST" "POST /ouvidoria/.../arquivar" "arquivar SEM motivo (a lei exige justificar)" "$BODY"
http POST "/ouvidoria/manifestacoes/$MANIF_ARQ/arquivar" "$TSEC" '{"motivo":"Duplicidade da manifestacao anterior."}'
esperar_status 200 "$ST" "POST /ouvidoria/.../arquivar" "feliz" "$BODY"
EST_MA=$(dbval "select estado from participacao.manifestacao_ouvidoria where id='$MANIF_ARQ';")
if [ "$EST_MA" = "arquivada" ]; then registrar OK "POST /ouvidoria/.../arquivar" "banco confirma estado=arquivada"; else registrar QUEBRA "POST /ouvidoria/.../arquivar" "banco diverge: estado=$EST_MA"; fi
http POST "/ouvidoria/manifestacoes/$MANIF_ARQ/resposta" "$TSEC" '{"corpo":"responder o que ja foi arquivado"}'
esperar_status 409 "$ST" "POST /ouvidoria/.../resposta" "responder manifestacao JA ARQUIVADA" "$BODY"

http POST "/ouvidoria/manifestacoes/$MANIF_PROR/prorrogar" "$TSEC" '{}'
esperar_status 400 "$ST" "POST /ouvidoria/.../prorrogar" "prorrogar SEM justificativa" "$BODY"
http POST "/ouvidoria/manifestacoes/$MANIF_PROR/prorrogar" "$TSEC" '{"justificativa":"Necessaria diligencia junto a Secretaria de Financas."}'
esperar_status 200 "$ST" "POST /ouvidoria/.../prorrogar" "feliz: 1a prorrogacao" "$BODY"
http POST "/ouvidoria/manifestacoes/$MANIF_PROR/prorrogar" "$TSEC" '{"justificativa":"segunda prorrogacao"}'
esperar_status 409 "$ST" "POST /ouvidoria/.../prorrogar" "2a prorrogacao recusada (13.460 art.10 permite UMA, 'por igual periodo')" "$BODY"
N_PROR=$(dbval "select count(*) from participacao.prorrogacao p join participacao.manifestacao_ouvidoria m on m.id='$MANIF_PROR' where p.ente_id='$ENTE';")
registrar OK "POST /ouvidoria/.../prorrogar" "prorrogacoes registradas no ente apos a recusa da 2a: $N_PROR (contagem informativa)"

fi
echo
echo "--- Rota B8: POST /comentarios/:id/moderar ---"
if exigir COM_APROVAR COM_REJEITAR; then
http POST "/comentarios/$COM_APROVAR/moderar" "$TCID" '{"acao":"aprovado"}'
esperar_status 403 "$ST" "POST /comentarios/:id/moderar" "papel errado (cidadao modera)" "$BODY"
http POST "/comentarios/$COM_APROVAR/moderar" "$TINTRUSO" '{"acao":"aprovado"}'
esperar_um_de "403 404" "$ST" "POST /comentarios/:id/moderar" "isolamento multi-tenant" "$BODY"
http POST "/comentarios/$COM_APROVAR/moderar" "$TSEC" '{"acao":"pendente"}'
esperar_status 400 "$ST" "POST /comentarios/:id/moderar" "acao 'pendente' nao e' moderacao" "$BODY"
http POST "/comentarios/$COM_REJEITAR/moderar" "$TSEC" '{"acao":"rejeitado"}'
esperar_status 400 "$ST" "POST /comentarios/:id/moderar" "rejeitar SEM motivo-rejeicao" "$BODY"
http POST "/comentarios/$COM_REJEITAR/moderar" "$TSEC" '{"acao":"rejeitado","motivo-rejeicao":"porque sim"}'
esperar_status 400 "$ST" "POST /comentarios/:id/moderar" "motivo fora do vocabulario fixo de 5" "$BODY"
http POST "/comentarios/$COM_APROVAR/moderar" "$TSEC" '{"acao":"aprovado"}'
esperar_status 200 "$ST" "POST /comentarios/:id/moderar" "feliz: aprova" "$BODY"
EST_C=$(dbval "select estado from participacao.comentario where id='$COM_APROVAR';")
if [ "$EST_C" = "aprovado" ]; then registrar OK "POST /comentarios/:id/moderar" "banco confirma estado=aprovado"; else registrar QUEBRA "POST /comentarios/:id/moderar" "banco diverge: estado=$EST_C"; fi
http POST "/comentarios/$COM_REJEITAR/moderar" "$TSEC" '{"acao":"rejeitado","motivo-rejeicao":"ofensivo"}'
esperar_status 200 "$ST" "POST /comentarios/:id/moderar" "feliz: rejeita com motivo do vocabulario" "$BODY"
http POST "/comentarios/$COM_APROVAR/moderar" "$TSEC" '{"acao":"rejeitado","motivo-rejeicao":"spam"}'
esperar_status 409 "$ST" "POST /comentarios/:id/moderar" "re-moderar comentario ja moderado" "$BODY"
# modera tambem o 3o comentario (o denunciado). Duas razoes: (1) e' um caso real — moderar algo que ja
# foi denunciado; (2) sem isto a sonda DEIXA um pendente por corrida na fila de moderacao da Casa da
# demo, e `demo.participacao-test` afirma a contagem exata dessa fila. O banco de participacao e'
# APPEND-ONLY (trigger `imut_append_only` barra DELETE em moderacao/denuncia), entao rastro deixado
# aqui e' PERMANENTE ate' um `down -v`. Uma sonda que suja de forma irreversivel um dado sobre o qual
# a suite afirma precisa, no minimo, nao aumentar a sujeira a cada corrida.
http POST "/comentarios/$COM_DENUNCIAR/moderar" "$TSEC" '{"acao":"rejeitado","motivo-rejeicao":"ofensivo"}'
esperar_status 200 "$ST" "POST /comentarios/:id/moderar" "modera um comentario JA DENUNCIADO (a denuncia nao bloqueia a moderacao)" "$BODY"

# a materia publica so' pode mostrar o aprovado
COMS_PUB=$(curl -s "$BACKEND/portal/casa/$ENTE/materias/$PROP")
if echo "$COMS_PUB" | grep -q "$COM_REJEITAR"; then
  registrar QUEBRA "GET /portal/casa/:ente/materias/:id" "o comentario REJEITADO aparece na ficha publica da materia" "$COM_REJEITAR"
else
  registrar OK "GET /portal/casa/:ente/materias/:id" "o comentario rejeitado NAO aparece na ficha publica"
fi
fi
echo
# =========================================================================================
# GRUPO D — compliance / remessa ao TCE (3 rotas). M6, dado como fechado e nunca exercido pela borda.
# =========================================================================================
echo "--- Rotas D1/D2/D3: POST /compliance/remessas/:id/{validar,submeter,resposta} ---"

# [GAP] estrutural, medido antes de escrever: o ente da demo nao tem NENHUMA remessa, e NAO EXISTE
# rota HTTP que CRIE uma. `gerar-remessa!` mora no Repo e nao esta ligado a nenhuma rota. As 3 rotas
# de escrita do modulo so' TRANSICIONAM uma remessa que ja existe — pela borda, a cadeia do M6 e'
# inalcancavel de ponta a ponta. Por isso a sonda planta o fixture por psql (e diz que plantou).
N_REM_ENTE=$(dbval "select count(*) from compliance.remessa_gerada where ente_id='$ENTE';")
if [ "$N_REM_ENTE" = "0" ]; then
  registrar QUEBRA "compliance (cadeia M6)" "o ente da demo nao tem NENHUMA remessa e nao existe rota HTTP que crie uma — validar/submeter/resposta so' transicionam o que ja' existe, entao a cadeia do M6 e' INALCANCAVEL pela borda (gerar-remessa! nao esta wired em rota)" "select count(*) from compliance.remessa_gerada where ente_id='$ENTE' -> 0"
else
  registrar OK "compliance (cadeia M6)" "o ente da demo ja tem $N_REM_ENTE remessa(s)"
fi

REM_FELIZ="dddddddd-0000-0000-0000-0000000000f1"
REM_ORDEM="dddddddd-0000-0000-0000-0000000000f2"
plantar_remessa() {
  dbexec "INSERT INTO compliance.remessa_gerada
    (id,ente_id,template_chave,sistema,competencia,versao,spec_layout_versao,registry_versao_ref,hash,objeto_store_ref,estado)
    VALUES ('$1','$ENTE','remessa_mensal_sim','SIM','$2',1,'fixture-sim-v0','registry-v1@2026-06-20','sha256:sonda-t2b','remessas/sonda-t2b.bin','rascunho')
    ON CONFLICT (ente_id,id) DO UPDATE SET estado='rascunho', submetida_em=NULL, resposta_em=NULL;" >/dev/null 2>&1 \
  || dbexec "INSERT INTO compliance.remessa_gerada
    (id,ente_id,template_chave,sistema,competencia,versao,spec_layout_versao,registry_versao_ref,hash,objeto_store_ref,estado)
    VALUES ('$1','$ENTE','remessa_mensal_sim','SIM','$2',1,'fixture-sim-v0','registry-v1@2026-06-20','sha256:sonda-t2b','remessas/sonda-t2b.bin','rascunho')
    ON CONFLICT (id) DO UPDATE SET estado='rascunho', submetida_em=NULL, resposta_em=NULL;" >/dev/null
}
plantar_remessa "$REM_FELIZ" "2098-01"
plantar_remessa "$REM_ORDEM" "2098-02"
registrar GAP "compliance (fixture)" "sonda plantou 2 remessas 'rascunho' por psql ($REM_FELIZ, $REM_ORDEM) porque nao ha rota que crie — o fixture e' da sonda, o resto do exercicio e' HTTP puro"

# erros de borda
http POST "/compliance/remessas/$REM_FELIZ/validar" "$TVER" ""
esperar_status 403 "$ST" "POST /compliance/remessas/:id/validar" "papel errado (vereador)" "$BODY"
http POST "/compliance/remessas/$REM_FELIZ/validar" "$TINTRUSO" ""
esperar_um_de "403 404" "$ST" "POST /compliance/remessas/:id/validar" "isolamento multi-tenant" "$BODY"
http POST "/compliance/remessas/00000000-0000-0000-0000-000000000000/validar" "$TSEC" ""
esperar_status 404 "$ST" "POST /compliance/remessas/:id/validar" "remessa inexistente" "$BODY"

# ORDEM: submeter sem validar, e resposta sem submeter — a cadeia tem de ser recusada fora de ordem
http POST "/compliance/remessas/$REM_ORDEM/submeter" "$TSEC" ""
esperar_status 409 "$ST" "POST /compliance/remessas/:id/submeter" "submeter SEM validar (ainda rascunho)" "$BODY"
http POST "/compliance/remessas/$REM_ORDEM/resposta" "$TSEC" '{"estado":"aceita"}'
esperar_status 409 "$ST" "POST /compliance/remessas/:id/resposta" "registrar resposta SEM submeter" "$BODY"

# cadeia feliz: rascunho -> validada -> submetida -> aceita
http POST "/compliance/remessas/$REM_FELIZ/validar" "$TSEC" ""
esperar_status 200 "$ST" "POST /compliance/remessas/:id/validar" "feliz: rascunho -> validada" "$BODY"
if [ "$(jget estado)" = "validada" ]; then registrar OK "POST .../validar" "a resposta ja devolve estado=validada"; else registrar QUEBRA "POST .../validar" "resposta 200 com estado=$(jget estado)"; fi
EST_REM=$(dbval "select estado from compliance.remessa_gerada where id='$REM_FELIZ';")
if [ "$EST_REM" = "validada" ]; then registrar OK "POST .../validar" "banco confirma estado=validada"; else registrar QUEBRA "POST .../validar" "banco diverge: estado=$EST_REM"; fi
http POST "/compliance/remessas/$REM_FELIZ/validar" "$TSEC" ""
esperar_status 409 "$ST" "POST /compliance/remessas/:id/validar" "repeticao (ja validada)" "$BODY"

http POST "/compliance/remessas/$REM_FELIZ/submeter" "$TSEC" ""
esperar_status 200 "$ST" "POST /compliance/remessas/:id/submeter" "feliz: validada -> submetida" "$BODY"
SUB_EM=$(dbval "select coalesce(submetida_em::text,'NULO') from compliance.remessa_gerada where id='$REM_FELIZ';")
if [ "$SUB_EM" = "NULO" ]; then registrar QUEBRA "POST .../submeter" "estado virou submetida mas submetida_em ficou NULO — sem carimbo nao ha prova de tempestividade ao TCE"; else registrar OK "POST .../submeter" "banco carimbou submetida_em=$SUB_EM"; fi
http POST "/compliance/remessas/$REM_FELIZ/submeter" "$TSEC" ""
esperar_status 409 "$ST" "POST /compliance/remessas/:id/submeter" "repeticao (ja submetida)" "$BODY"

http POST "/compliance/remessas/$REM_FELIZ/resposta" "$TSEC" '{"estado":"perdida"}'
esperar_status 400 "$ST" "POST /compliance/remessas/:id/resposta" "estado fora do vocabulario (so aceita/rejeitada)" "$BODY"
http POST "/compliance/remessas/$REM_FELIZ/resposta" "$TSEC" '{}'
esperar_status 400 "$ST" "POST /compliance/remessas/:id/resposta" "corpo sem 'estado'" "$BODY"
http POST "/compliance/remessas/$REM_FELIZ/resposta" "$TSEC" '{"estado":"aceita"}'
esperar_status 200 "$ST" "POST /compliance/remessas/:id/resposta" "feliz: submetida -> aceita" "$BODY"
RESP_EM=$(dbval "select coalesce(resposta_em::text,'NULO') from compliance.remessa_gerada where id='$REM_FELIZ';")
if [ "$RESP_EM" = "NULO" ]; then registrar QUEBRA "POST .../resposta" "estado virou aceita mas resposta_em ficou NULO"; else registrar OK "POST .../resposta" "banco carimbou resposta_em=$RESP_EM"; fi
http POST "/compliance/remessas/$REM_FELIZ/resposta" "$TSEC" '{"estado":"rejeitada"}'
esperar_status 409 "$ST" "POST /compliance/remessas/:id/resposta" "repeticao sobre estado terminal" "$BODY"

# leitura subsequente: o painel e' a UNICA leitura HTTP que mostra remessa
http GET "/compliance/painel" "$TSEC"
if echo "$BODY" | grep -q "$REM_FELIZ"; then
  registrar OK "GET /compliance/painel" "leitura subsequente devolve a remessa exercida"
else
  registrar QUEBRA "GET /compliance/painel" "a remessa transicionada nao aparece no painel (unica leitura HTTP do modulo)" "$REM_FELIZ"
fi
echo
# =========================================================================================
# GRUPO E — identidade (3 rotas). Papel exigido: admin_ente.
# =========================================================================================
echo "--- Rota E1: POST /identidade/identidades ---"
# CPF valido GERADO a cada corrida. Cravar um CPF fixo tornava a sonda nao-re-rodavel: a 2a corrida
# reusava a MESMA identidade, que ja estava ligada a um vereador, e o PATCH de F1 dava 409 legitimo
# que a sonda lia como defeito do produto. O digito verificador e calculado, nao chutado.
CPF_NOVO=$(python3 -c "
import random
b=[random.randint(0,9) for _ in range(9)]
for n in (9,10):
    s=sum(b[i]*((n+1)-i) for i in range(n)); b.append(s*10%11%10)
print(''.join(map(str,b)))")
http POST "/identidade/identidades" "$TSEC" "{\"cpf\":\"$CPF_NOVO\",\"nome\":\"Sonda T2B\"}"
esperar_status 403 "$ST" "POST /identidade/identidades" "papel errado (secretario nao concede identidade)" "$BODY"
http POST "/identidade/identidades" "$TADM" '{"cpf":"12345678901","nome":"Digito invalido"}'
esperar_status 400 "$ST" "POST /identidade/identidades" "CPF com digito verificador invalido" "$BODY"
http POST "/identidade/identidades" "$TADM" '{"cpf":"529.982.247-25","nome":"Com pontuacao"}'
esperar_status 400 "$ST" "POST /identidade/identidades" "CPF pontuado (schema exige 11 digitos crus)" "$BODY"
http POST "/identidade/identidades" "$TADM" "{\"cpf\":\"$CPF_NOVO\",\"nome\":\"Sonda T2B Pessoa\",\"papeis\":[\"admin_ente\"]}"
esperar_status 400 "$ST" "POST /identidade/identidades" "campo extra recusado (:closed true) — anti-escalada" "$BODY"
http POST "/identidade/identidades" "$TADM" "{\"cpf\":\"$CPF_NOVO\",\"nome\":\"Sonda T2B Pessoa\"}"
esperar_status 201 "$ST" "POST /identidade/identidades" "feliz: cria identidade" "$BODY"
IDENT_NOVA=$(jget identidade-id)
DB_IDENT=$(dbval "select id from identidade.identidade where cpf='$CPF_NOVO';")
if [ "$DB_IDENT" = "$IDENT_NOVA" ]; then registrar OK "POST /identidade/identidades" "banco confirma a identidade ($IDENT_NOVA)"; else registrar QUEBRA "POST /identidade/identidades" "201 devolveu $IDENT_NOVA mas o banco tem $DB_IDENT"; fi
CPF_ARMAZENADO=$(dbval "select cpf from identidade.identidade where id='$IDENT_NOVA';")
if [ "$CPF_ARMAZENADO" = "$CPF_NOVO" ]; then
  # GAP, nao QUEBRA: e' o carry 'CPF-cifra' JA REGISTRADO desde a F1, nao uma regressao desta frente.
  # Fica visivel em toda corrida de proposito (cifrar CPF e' decisao de cripto + migracao, do Daouda),
  # mas nao derruba o exit code — senao este gate nunca ficaria verde e pararia de detectar regressao.
  registrar GAP "POST /identidade/identidades" "o CPF fica em TEXTO PURO no banco (cpf='$CPF_ARMAZENADO') — carry 'CPF-cifra' aberto desde a F1. Dado pessoal sensivel da LGPD sob custodia da plataforma que vende conformidade; cifrar exige decisao de cripto + migracao" "select cpf from identidade.identidade where id='$IDENT_NOVA'"
else
  registrar OK "POST /identidade/identidades" "o CPF nao esta em texto puro no banco"
fi
http POST "/identidade/identidades" "$TADM" "{\"cpf\":\"$CPF_NOVO\",\"nome\":\"Sonda T2B Pessoa Renomeada\"}"
esperar_status 201 "$ST" "POST /identidade/identidades" "repeticao com o MESMO CPF (idempotente por CPF)" "$BODY"
if [ "$(jget identidade-id)" = "$IDENT_NOVA" ]; then registrar OK "POST /identidade/identidades" "idempotencia por CPF: devolve o MESMO id, nao duplica pessoa"; else registrar QUEBRA "POST /identidade/identidades" "o mesmo CPF gerou 2 identidades ($IDENT_NOVA vs $(jget identidade-id)) — a pessoa duplicou"; fi

echo
echo "--- Rota E2: POST /identidade/acessos ---"
http POST "/identidade/acessos" "$TSEC" "{\"identidade-id\":\"$IDENT_NOVA\",\"tipo\":\"vereador\",\"papeis\":[\"vereador\"],\"email\":\"sonda@camara.exemplo.br\"}"
esperar_status 403 "$ST" "POST /identidade/acessos" "papel errado (secretario)" "$BODY"
http POST "/identidade/acessos" "$TADM" "{\"identidade-id\":\"$IDENT_NOVA\",\"tipo\":\"vereador\",\"papeis\":[\"admin_ente\"],\"email\":\"sonda@camara.exemplo.br\"}"
esperar_status 400 "$ST" "POST /identidade/acessos" "tentativa de conceder admin_ente por esta rota (anti-escalada)" "$BODY"
http POST "/identidade/acessos" "$TADM" "{\"identidade-id\":\"$IDENT_NOVA\",\"tipo\":\"vereador\",\"papeis\":[\"vereador\"],\"email\":\"nao-e-email\"}"
esperar_status 400 "$ST" "POST /identidade/acessos" "email malformado" "$BODY"
http POST "/identidade/acessos" "$TADM" "{\"identidade-id\":\"$IDENT_NOVA\",\"tipo\":\"vereador\",\"papeis\":[\"vereador\"],\"email\":\"sonda@camara.exemplo.br\"}"
if [ "$ST" = "201" ]; then
  registrar OK "POST /identidade/acessos" "feliz: concede acesso -> 201" "$BODY"
  VINC_NOVO=$(dbval "select id from identidade.vinculo where ente_id='$ENTE' and identidade_id='$IDENT_NOVA';")
  if [ -n "$VINC_NOVO" ]; then registrar OK "POST /identidade/acessos" "banco confirma o vinculo ($VINC_NOVO)"; else registrar QUEBRA "POST /identidade/acessos" "201 sem linha em identidade.vinculo"; fi
elif [ "$ST" = "500" ]; then
  # RECLASSIFICADO de QUEBRA para GAP depois de ler a fonte (mesmo metodo do grupo A: investigar a
  # INTENCAO antes de acusar). O 500 aqui NAO e' descuido: `conceder-acesso-handler` documenta
  # "Erro de infra do KC PROPAGA -> 500 (nunca 401)" e existe teste `conceder-acesso-keycloak-fora-do-
  # ar-500` que o exige. O "banco antes do Keycloak" tambem e' deliberado e explicado ("sobra vinculo
  # sem credencial -> ninguem entra -> repetir conserta", fail-closed). O que sobra e' o carry F1.4
  # conhecido: em APP_ENV=dev o `idp-dev` e' stub e LANCA. Nao ha' defeito novo a consertar aqui.
  VINC_NOVO=$(dbval "select coalesce((select id::text from identidade.vinculo where ente_id='$ENTE' and identidade_id='$IDENT_NOVA'),'NENHUM');")
  registrar GAP "POST /identidade/acessos" "500 por dependencia de IdP indisponivel (APP_ENV=dev -> idp-dev e stub e lanca) — carry F1.4 conhecido, e o 500 e DECISAO documentada+testada do handler, nao descuido. Vinculo criado antes da falha: $VINC_NOVO (fail-closed por desenho: vinculo sem credencial nao deixa ninguem entrar, e repetir conserta). CAMINHO FELIZ NAO EXERCIDO — so com Keycloak de pe (--profile auth)" "$BODY"
else
  registrar QUEBRA "POST /identidade/acessos" "status inesperado $ST" "$BODY"
fi

echo
echo "--- Rota E3: POST /identidade/acessos/:identidade-id/convite ---"
http POST "/identidade/acessos/$IDENT_NOVA/convite" "$TSEC" ""
esperar_status 403 "$ST" "POST /identidade/acessos/:id/convite" "papel errado (secretario)" "$BODY"
http POST "/identidade/acessos/nao-e-uuid/convite" "$TADM" ""
esperar_status 404 "$ST" "POST /identidade/acessos/:id/convite" "identidade-id malformado (nunca alcanca o IdP)" "$BODY"
http POST "/identidade/acessos/$IDENT_NOVA/convite" "$TADM" ""
if [ "$ST" = "200" ]; then
  registrar OK "POST /identidade/acessos/:id/convite" "feliz: convite reenviado -> 200" "$BODY"
elif [ "$ST" = "404" ]; then
  registrar GAP "POST /identidade/acessos/:id/convite" "404 'identidade nao encontrada' porque nao ha usuario provisionado no realm (o E2 nao completou em dev) — comportamento coerente, nao e' defeito desta rota" "$BODY"
elif [ "$ST" = "500" ]; then
  registrar GAP "POST /identidade/acessos/:id/convite" "500 por 'idp-dev nao envia convite (use o KeycloakIdp)' — mesmo carry F1.4. O handler ja trata :idp/usuario-inexistente -> 404 e RE-LANCA o resto de proposito (docstring: nunca mascarar degradacao real). CAMINHO FELIZ NAO EXERCIDO sem Keycloak de pe" "$BODY"
else
  registrar QUEBRA "POST /identidade/acessos/:id/convite" "status inesperado $ST" "$BODY"
fi

echo
# =========================================================================================
# GRUPO F — cadastros (2 rotas).
# =========================================================================================
echo "--- Rota F1: PATCH /cadastros/vereadores/:id/identidade ---"
if exigir IDENT_NOVA; then
VER_SEM_IDENT=$(dbval "select id from cadastros.vereador where ente_id='$ENTE' and identidade_id is null limit 1;")
if [ -z "$VER_SEM_IDENT" ]; then
  VER_SEM_IDENT="$VEREADOR1"
  registrar GAP "PATCH /cadastros/vereadores/:id/identidade" "nenhum vereador da Casa esta sem identidade ligada — usando $VEREADOR1 (o PATCH e' idempotente e vai trocar o vinculo dele)"
fi
http PATCH "/cadastros/vereadores/$VER_SEM_IDENT/identidade" "$TSEC" "{\"identidade-id\":\"$IDENT_NOVA\"}"
esperar_status 403 "$ST" "PATCH /cadastros/vereadores/:id/identidade" "papel errado (secretario — ligar identidade e' ato de admin_ente)" "$BODY"
http PATCH "/cadastros/vereadores/$VER_SEM_IDENT/identidade" "$TADM" '{"identidade-id":"00000000-0000-0000-0000-000000000000"}'
esperar_status 404 "$ST" "PATCH /cadastros/vereadores/:id/identidade" "identidade inexistente (404 anti-oracle, colapsado com vereador inexistente)" "$BODY"
http PATCH "/cadastros/vereadores/00000000-0000-0000-0000-000000000000/identidade" "$TADM" "{\"identidade-id\":\"$IDENT_NOVA\"}"
esperar_status 404 "$ST" "PATCH /cadastros/vereadores/:id/identidade" "vereador inexistente" "$BODY"
http PATCH "/cadastros/vereadores/$VER_SEM_IDENT/identidade" "$TINTRUSO" "{\"identidade-id\":\"$IDENT_NOVA\"}"
esperar_um_de "403 404" "$ST" "PATCH /cadastros/vereadores/:id/identidade" "isolamento multi-tenant" "$BODY"
http PATCH "/cadastros/vereadores/$VER_SEM_IDENT/identidade" "$TADM" "{\"identidade-id\":\"$IDENT_NOVA\"}"
esperar_status 200 "$ST" "PATCH /cadastros/vereadores/:id/identidade" "feliz: liga identidade ao vereador" "$BODY"
DB_LIG=$(dbval "select coalesce(identidade_id::text,'NULO') from cadastros.vereador where id='$VER_SEM_IDENT';")
if [ "$DB_LIG" = "$IDENT_NOVA" ]; then registrar OK "PATCH /cadastros/vereadores/:id/identidade" "banco confirma o vinculo"; else registrar QUEBRA "PATCH /cadastros/vereadores/:id/identidade" "200 mas o banco guarda identidade_id=$DB_LIG"; fi
http PATCH "/cadastros/vereadores/$VER_SEM_IDENT/identidade" "$TADM" "{\"identidade-id\":\"$IDENT_NOVA\"}"
esperar_status 200 "$ST" "PATCH /cadastros/vereadores/:id/identidade" "religar a MESMA identidade -> no-op valido" "$BODY"
# a MESMA identidade em OUTRO vereador da mesma Casa tem de dar 409 (UNIQUE parcial), nunca 500
OUTRO_VER=$(dbval "select id from cadastros.vereador where ente_id='$ENTE' and id<>'$VER_SEM_IDENT' limit 1;")
http PATCH "/cadastros/vereadores/$OUTRO_VER/identidade" "$TADM" "{\"identidade-id\":\"$IDENT_NOVA\"}"
esperar_status 409 "$ST" "PATCH /cadastros/vereadores/:id/identidade" "mesma identidade em 2 vereadores da Casa -> UNIQUE vira 409, nao 500 cru" "$BODY"

fi
echo
echo "--- Rota F2: POST /cadastros/vereadores/:id/reassuncao ---"
VER_LIC=$(dbval "select vereador_id from cadastros.mandato where ente_id='$ENTE' and estado='licenciado' limit 1;")

# Fidelidade da semente, medida ANTES de a sonda plantar qualquer coisa: um mandato 'licenciado' sem
# NENHUMA linha em `cadastros.mandato_licenca` esta licenciado sem o ato que o explique. Nao derruba o
# gate (e' dado de demo, e a rota funciona), mas fica registrado — a reassuncao de um mandato assim
# devolve `fim: null`, porque nao havia licenca aberta para fechar.
N_LICENCA_SEMENTE=$(dbval "select count(*) from cadastros.mandato_licenca l join cadastros.mandato m on m.id=l.mandato_id and m.ente_id=l.ente_id where m.ente_id='$ENTE';")
if [ "$N_LICENCA_SEMENTE" = "0" ] && [ -n "$VER_LIC" ]; then
  registrar GAP "semente/cadastros" "mandato com estado='licenciado' e ZERO linhas em cadastros.mandato_licenca: a semente marca o ESTADO sem gravar o ATO da licenca. A reassuncao funciona, mas devolve fim=null (nao ha licenca aberta para fechar) — dado de demo incompleto, nao defeito de rota" "select count(*) from cadastros.mandato_licenca (ente demo) -> 0"
fi

if [ -z "$VER_LIC" ]; then
  # A semente cria UM mandato licenciado e uma reassuncao bem-sucedida o consome — sem isto a rota so'
  # seria exercivel uma vez por reconstrucao de cadastro. A sonda licencia um mandato vigente (fixture
  # declarado, como as remessas de compliance) e a propria reassuncao o devolve a 'vigente': net zero.
  MAND_FIXTURE=$(dbval "select id from cadastros.mandato where ente_id='$ENTE' and estado='vigente' order by vigencia_inicio limit 1;")
  if [ -n "$MAND_FIXTURE" ]; then
    dbexec "UPDATE cadastros.mandato SET estado='licenciado' WHERE ente_id='$ENTE' AND id='$MAND_FIXTURE';" >/dev/null
    VER_LIC=$(dbval "select vereador_id from cadastros.mandato where id='$MAND_FIXTURE';")
    registrar GAP "POST /cadastros/vereadores/:id/reassuncao" "sonda licenciou o mandato $MAND_FIXTURE por psql (a semente so' traz um licenciado, e a 1a reassuncao o consome) — a propria reassuncao devolve o mandato a 'vigente'"
  fi
fi
if [ -z "$VER_LIC" ]; then
  registrar GAP "POST /cadastros/vereadores/:id/reassuncao" "nenhum mandato no ente para licenciar — bloco pulado, rota NAO exercida"
else
# (a verificacao da fidelidade da semente ja rodou ACIMA, sobre o estado PRE-fixture — repeti-la aqui
# faria a sonda acusar a semente pelo mandato que ELA MESMA acabou de licenciar por psql)
http POST "/cadastros/vereadores/$VER_LIC/reassuncao" "$TVER" '{"reassumiu-em":"2026-01-10"}'
esperar_status 403 "$ST" "POST /cadastros/vereadores/:id/reassuncao" "papel errado (vereador)" "$BODY"
http POST "/cadastros/vereadores/$VER_LIC/reassuncao" "$TSEC" '{"reassumiu-em":"10/01/2026"}'
esperar_status 400 "$ST" "POST /cadastros/vereadores/:id/reassuncao" "data fora do formato ISO" "$BODY"
http POST "/cadastros/vereadores/$VER_LIC/reassuncao" "$TSEC" '{"reassumiu-em":"2099-01-01"}'
esperar_status 400 "$ST" "POST /cadastros/vereadores/:id/reassuncao" "reassuncao no FUTURO" "$BODY"
http POST "/cadastros/vereadores/00000000-0000-0000-0000-000000000000/reassuncao" "$TSEC" '{"reassumiu-em":"2026-01-10"}'
esperar_status 404 "$ST" "POST /cadastros/vereadores/:id/reassuncao" "vereador inexistente" "$BODY"
http POST "/cadastros/vereadores/$VER_LIC/reassuncao" "$TINTRUSO" '{"reassumiu-em":"2026-01-10"}'
esperar_um_de "403 404" "$ST" "POST /cadastros/vereadores/:id/reassuncao" "isolamento multi-tenant" "$BODY"
http POST "/cadastros/vereadores/$VER_LIC/reassuncao" "$TSEC" '{"reassumiu-em":"2026-01-10"}'
if [ "$ST" = "200" ]; then
  registrar OK "POST /cadastros/vereadores/:id/reassuncao" "feliz: vereador licenciado reassume" "$BODY"
  EST_MAND=$(dbval "select estado from cadastros.mandato where ente_id='$ENTE' and vereador_id='$VER_LIC' order by vigencia_inicio desc limit 1;")
  if [ "$EST_MAND" = "vigente" ]; then registrar OK "POST .../reassuncao" "banco confirma mandato estado=vigente"; else registrar QUEBRA "POST .../reassuncao" "200 mas o mandato continua estado=$EST_MAND"; fi
  http POST "/cadastros/vereadores/$VER_LIC/reassuncao" "$TSEC" '{"reassumiu-em":"2026-01-10"}'
  esperar_status 409 "$ST" "POST /cadastros/vereadores/:id/reassuncao" "repeticao (ja nao ha mandato licenciado)" "$BODY"
elif [ "$ST" = "409" ]; then
  registrar OK "POST /cadastros/vereadores/:id/reassuncao" "409 conflito: nao ha licenca aberta que a reassuncao possa fechar — coerente com a semente sem linha em mandato_licenca (fail-closed, nao 500)" "$BODY"
else
  registrar QUEBRA "POST /cadastros/vereadores/:id/reassuncao" "status inesperado $ST no caminho feliz" "$BODY"
fi
fi

echo
# =========================================================================================
# GRUPO G — legislativo: apreciacao de veto (1 rota).
# =========================================================================================
echo "--- Rota G1: POST /legislativo/tramitacoes-executivas/:id/apreciacao ---"
# CUSTO DECLARADO: para haver o que apreciar e' preciso VETAR um autografo da demo. A sonda escolhe
# o autografo cuja tramitacao ainda esta 'aguardando' e o leva a 'vetado' -> apreciado. Isso muda o
# dado da demo (uma materia sai de 'aguardando resposta do Executivo' e vira veto apreciado).
# Prefere uma tramitacao 'aguardando' (exercita a cadeia inteira, inclusive a sondagem de "apreciar
# sem haver veto"). Se nao houver — porque uma corrida anterior desta sonda ja' consumiu a unica —
# aceita uma que ja' esteja em 'vetado': o caminho feliz da apreciacao continua exercivel, so' a
# sondagem do estado errado fica de fora. E' o que torna este bloco re-rodavel.
TRAM=$(dbval "select id from legislativo.tramitacao_executiva where ente_id='$ENTE' and estado='aguardando' limit 1;")
TRAM_PRECISA_VETO=1
if [ -z "$TRAM" ]; then
  TRAM=$(dbval "select id from legislativo.tramitacao_executiva where ente_id='$ENTE' and estado='vetado' limit 1;")
  TRAM_PRECISA_VETO=0
  [ -n "$TRAM" ] && registrar GAP "POST .../apreciacao" "nenhuma tramitacao 'aguardando' sobrou (corrida anterior consumiu) — usando uma ja' 'vetado'; a sondagem de 'apreciar sem haver veto' nao roda nesta corrida"
fi
if [ -z "$TRAM" ]; then
  # Todas as tramitacoes da Casa estao em estado TERMINAL (a semente traz uma 'aguardando' e a 1a corrida
  # desta sonda a consome). O trigger `trg_exec_imut_estado` barra UPDATE em terminal, e nao ha proposicao
  # 'aprovada' sem autografo para gerar uma tramitacao nova pela borda — entao a sonda RECRIA a linha:
  # DELETE + INSERT como 'aguardando'. Nada referencia `tramitacao_executiva` (zero FKs de entrada,
  # conferido), entao o delete e' isolado. Fixture DECLARADO, como as remessas de compliance.
  TRAM_ALVO=$(dbval "select id from legislativo.tramitacao_executiva where ente_id='$ENTE' limit 1;")
  if [ -n "$TRAM_ALVO" ]; then
    AUTOG_ALVO=$(dbval "select autografo_id from legislativo.tramitacao_executiva where id='$TRAM_ALVO';")
    dbexec "DELETE FROM legislativo.tramitacao_executiva WHERE ente_id='$ENTE' AND id='$TRAM_ALVO';" >/dev/null
    dbexec "INSERT INTO legislativo.tramitacao_executiva (ente_id,id,autografo_id,estado,origem,efetivado_em,lock_version)
            VALUES ('$ENTE','$TRAM_ALVO','$AUTOG_ALVO','aguardando','nativa',now(),0);" >/dev/null
    TRAM=$(dbval "select id from legislativo.tramitacao_executiva where ente_id='$ENTE' and estado='aguardando' limit 1;")
    TRAM_PRECISA_VETO=1
    [ -n "$TRAM" ] && registrar GAP "POST .../apreciacao" "sonda recriou a tramitacao $TRAM_ALVO como 'aguardando' por psql (todas estavam terminais e o trigger barra UPDATE em terminal) — sem isso a rota so seria exercivel uma vez por reconstrucao do acervo"
  fi
fi
AUTOGRAFO=$(dbval "select autografo_id from legislativo.tramitacao_executiva where id='$TRAM';")
PROP_TRAM=$(dbval "select proposicao_id from legislativo.autografo where id='$AUTOGRAFO';")
echo "tramitacao=$TRAM autografo=$AUTOGRAFO proposicao=$PROP_TRAM"

if [ -z "$TRAM" ]; then
  registrar GAP "POST /legislativo/tramitacoes-executivas/:id/apreciacao" "nenhuma tramitacao 'aguardando' no ente — nao ha como produzir um veto para apreciar sem sujar mais a demo"
else
  # SONDA CENTRAL: apreciar ANTES de haver veto (estado 'aguardando'). Contrato diz que a excecao
  # do db nao carrega :tipo — se o interceptor global nao a reconhecer, isso vira 500, nao 409.
  LV_T=$(dbval "select lock_version from legislativo.tramitacao_executiva where id='$TRAM';")
  if [ "$TRAM_PRECISA_VETO" = "1" ]; then
  http POST "/legislativo/tramitacoes-executivas/$TRAM/apreciacao" "$TSEC" "{\"lock-version\":$LV_T,\"resultado\":\"veto_mantido\",\"veto-votacao-id\":\"$(uuidgen | tr 'A-Z' 'a-z')\"}"
  if [ "$ST" = "500" ]; then
    registrar QUEBRA "POST /legislativo/tramitacoes-executivas/:id/apreciacao" "apreciar veto de tramitacao que NAO esta 'vetado' (estado=aguardando) -> 500 'erro interno'. O db/tramitacao_executiva lanca ex-info SEM :tipo, e o interceptor global cai no :else. Deveria ser 409, como o resto da base faz para conflito de estado" "$BODY"
  else
    esperar_status 409 "$ST" "POST .../apreciacao" "apreciar sem haver veto (estado=aguardando)" "$BODY"
  fi
  fi

  # erros de borda
  http POST "/legislativo/tramitacoes-executivas/$TRAM/apreciacao" "$TVER" "{\"lock-version\":$LV_T,\"resultado\":\"veto_mantido\",\"veto-votacao-id\":\"$(uuidgen | tr 'A-Z' 'a-z')\"}"
  esperar_status 403 "$ST" "POST .../apreciacao" "papel errado (vereador)" "$BODY"
  http POST "/legislativo/tramitacoes-executivas/$TRAM/apreciacao" "$TSEC" "{\"lock-version\":$LV_T,\"resultado\":\"veto_engavetado\",\"veto-votacao-id\":\"$(uuidgen | tr 'A-Z' 'a-z')\"}"
  esperar_status 400 "$ST" "POST .../apreciacao" "resultado fora do enum" "$BODY"
  http POST "/legislativo/tramitacoes-executivas/$TRAM/apreciacao" "$TSEC" "{\"resultado\":\"veto_mantido\",\"veto-votacao-id\":\"$(uuidgen | tr 'A-Z' 'a-z')\"}"
  esperar_status 400 "$ST" "POST .../apreciacao" "sem lock-version (CAS obrigatorio)" "$BODY"
  http POST "/legislativo/tramitacoes-executivas/00000000-0000-0000-0000-000000000000/apreciacao" "$TSEC" "{\"lock-version\":0,\"resultado\":\"veto_mantido\",\"veto-votacao-id\":\"$(uuidgen | tr 'A-Z' 'a-z')\"}"
  esperar_status 404 "$ST" "POST .../apreciacao" "tramitacao inexistente" "$BODY"

  # setup: registrar o VETO do Executivo (rota irma, fora das 25 — usada so' como preparo)
  if [ "$TRAM_PRECISA_VETO" = "1" ]; then
  LV_T=$(dbval "select lock_version from legislativo.tramitacao_executiva where id='$TRAM';")
  http POST "/legislativo/autografos/$AUTOGRAFO/resposta" "$TSEC" "{\"lock-version\":$LV_T,\"resultado\":\"vetado\",\"veto-tipo\":\"total\",\"veto-razoes\":\"Veto plantado pela sonda T2B para exercer a apreciacao.\"}"
  if [ "$ST" = "200" ]; then
    registrar OK "POST /legislativo/autografos/:id/resposta" "preparo: Executivo veta (aguardando -> vetado)" "$BODY"
  else
    registrar QUEBRA "POST /legislativo/autografos/:id/resposta" "preparo do veto falhou -> $ST; a apreciacao nao pode ser exercida" "$BODY"
  fi
  fi

  EST_T=$(dbval "select estado from legislativo.tramitacao_executiva where id='$TRAM';")
  if [ "$EST_T" = "vetado" ]; then
    LV_T=$(dbval "select lock_version from legislativo.tramitacao_executiva where id='$TRAM';")
    # CAS: lock-version errado tem de ser 409, nao 500
    http POST "/legislativo/tramitacoes-executivas/$TRAM/apreciacao" "$TSEC" "{\"lock-version\":999,\"resultado\":\"veto_derrubado\",\"veto-votacao-id\":\"$(uuidgen | tr 'A-Z' 'a-z')\"}"
    if [ "$ST" = "500" ]; then
      registrar QUEBRA "POST .../apreciacao" "lock-version divergente -> 500 'erro interno' em vez de 409. Conflito de CAS e' o caso mais banal de escrita concorrente; devolver 500 faz o cliente tratar corrida como bug do servidor" "$BODY"
    else
      esperar_status 409 "$ST" "POST .../apreciacao" "lock-version divergente (CAS)" "$BODY"
    fi

    # votacao REAL desta Casa: `veto_votacao_id` TEM FK contra legislativo.votacoes (a docstring do
    # wire dizia que nao tinha — o banco desmente). Mandar UUID aleatorio aqui media a sonda, nao o
    # produto. O caso do id inexistente virou caminho de ERRO explicito, logo abaixo.
    VOTACAO_VETO=$(dbval "select id from legislativo.votacoes where ente_id='$ENTE' limit 1;")
    if [ -z "$VOTACAO_VETO" ]; then
      registrar GAP "POST .../apreciacao" "nenhuma votacao no ente para carimbar a apreciacao — caminho feliz nao exercido"
    else
    http POST "/legislativo/tramitacoes-executivas/$TRAM/apreciacao" "$TSEC" "{\"lock-version\":$LV_T,\"resultado\":\"veto_derrubado\",\"veto-votacao-id\":\"$(uuidgen | tr 'A-Z' 'a-z')\"}"
    esperar_status 400 "$ST" "POST .../apreciacao" "veto-votacao-id que nao existe (FK real) -> erro de CORPO, nao 500 cru" "$BODY"
    http POST "/legislativo/tramitacoes-executivas/$TRAM/apreciacao" "$TSEC" "{\"lock-version\":$LV_T,\"resultado\":\"veto_derrubado\",\"veto-votacao-id\":\"$VOTACAO_VETO\"}"
    esperar_status 200 "$ST" "POST .../apreciacao" "feliz: camara derruba o veto" "$BODY"
    if jhas lock-version; then registrar OK "POST .../apreciacao" "a resposta devolve lock-version para a proxima escrita (ao contrario das 7 rotas do grupo A)"; else registrar QUEBRA "POST .../apreciacao" "CAS obrigatorio e a resposta NAO devolve lock-version"; fi
    EST_T2=$(dbval "select estado from legislativo.tramitacao_executiva where id='$TRAM';")
    if [ "$EST_T2" = "veto_derrubado" ]; then registrar OK "POST .../apreciacao" "banco confirma estado=veto_derrubado"; else registrar QUEBRA "POST .../apreciacao" "200 mas o banco guarda estado=$EST_T2"; fi
    http GET "/legislativo/proposicoes/$PROP_TRAM/pos-aprovacao" "$TSEC"
    if echo "$BODY" | grep -q "veto_derrubado"; then
      registrar OK "GET /legislativo/proposicoes/:id/pos-aprovacao" "leitura subsequente confirma a apreciacao"
    else
      registrar QUEBRA "GET /legislativo/proposicoes/:id/pos-aprovacao" "a leitura subsequente NAO reflete a apreciacao" "$BODY"
    fi
    # repeticao sobre estado terminal
    LV_T3=$(dbval "select lock_version from legislativo.tramitacao_executiva where id='$TRAM';")
    http POST "/legislativo/tramitacoes-executivas/$TRAM/apreciacao" "$TSEC" "{\"lock-version\":$LV_T3,\"resultado\":\"veto_mantido\",\"veto-votacao-id\":\"$VOTACAO_VETO\"}"
    if [ "$ST" = "500" ]; then
      registrar QUEBRA "POST .../apreciacao" "repeticao sobre estado terminal (veto_derrubado) -> 500 em vez de 409" "$BODY"
    else
      esperar_status 409 "$ST" "POST .../apreciacao" "repeticao sobre estado terminal" "$BODY"
    fi
    fi
  else
    registrar GAP "POST .../apreciacao" "o preparo nao levou a tramitacao a 'vetado' (estado=$EST_T) — caminho feliz da apreciacao nao exercido"
  fi
fi


echo
# ---------- afirmacao de COBERTURA ----------
# "Zero achados" e "nao rodou" tem a mesma saida se ninguem afirmar o VOLUME inspecionado. Esta sonda
# ficou verde uma vez cobrindo 24 das 25 rotas (a apreciacao de veto sem fixture) — e o exit 0 nao
# distinguia. Agora a cobertura e' uma assercao: se uma rota nao teve NENHUMA checagem OK, o gate cai.
ROTAS_ESPERADAS=25
ROTAS_COBERTAS=$(python3 -c "
import json,sys,re
alvos = [
 'POST /esic/pedidos/:id/resposta','POST /esic/recursos/:id/decisao',
 'POST /lgpd/solicitacoes/:id/resposta','PUT /lgpd/encarregado',
 'POST /ouvidoria/.../resposta','POST /ouvidoria/.../arquivar','POST /ouvidoria/.../prorrogar',
 'POST /comentarios/:id/moderar',
 'POST /portal/esic/pedidos','POST /portal/.../recursos','POST /portal/lgpd/solicitacoes',
 'POST /portal/ouvidoria/manifestacoes','POST /portal/.../comentarios','POST /portal/.../denunciar',
 'POST /portal/.../acompanhar','DELETE /portal/.../acompanhar',
 'POST /compliance/remessas/:id/validar','POST /compliance/remessas/:id/submeter',
 'POST /compliance/remessas/:id/resposta',
 'POST /identidade/identidades','POST /identidade/acessos','POST /identidade/acessos/:id/convite',
 'PATCH /cadastros/vereadores/:id/identidade','POST /cadastros/vereadores/:id/reassuncao',
 'POST /legislativo/tramitacoes-executivas/:id/apreciacao',
]
# rotulos curtos usados em alguns registrar() sao sufixo do rotulo canonico
def casa(rota, alvo):
    return rota == alvo or (rota.startswith('POST .../') and alvo.endswith(rota[len('POST .../'):]))
vistos = set()
for l in open('$LOG'):
    d = json.loads(l)
    if d['classe'] != 'OK': continue
    for a in alvos:
        if casa(d['rota'], a): vistos.add(a)
print(len(vistos))
print(','.join(sorted(set(alvos) - vistos)) or '-', file=sys.stderr)
" 2>/private/tmp/rotas-descobertas.txt)
DESCOBERTAS=$(cat /private/tmp/rotas-descobertas.txt 2>/dev/null); rm -f /private/tmp/rotas-descobertas.txt
if [ "${ROTAS_COBERTAS:-0}" -lt "$ROTAS_ESPERADAS" ]; then
  registrar QUEBRA "sonda (cobertura)" "so' $ROTAS_COBERTAS de $ROTAS_ESPERADAS rotas tiveram ao menos UMA checagem OK nesta corrida — o resto nao foi exercido, e um gate verde cobrindo menos que o escopo mente. Sem cobertura: $DESCOBERTAS"
else
  registrar OK "sonda (cobertura)" "$ROTAS_COBERTAS de $ROTAS_ESPERADAS rotas exercidas com ao menos uma checagem OK"
fi

echo
echo "=== VEREDICTO T2 GRUPO B ==="
echo "ok=$N_OK  QUEBRA=$N_QUEBRA  FRAGIL=$N_FRAGIL  cosmetico=$N_COSMETICO  GAP=$N_GAP"
echo "log completo: $LOG"

if [ "$N_QUEBRA" -gt 0 ] || [ "$N_FRAGIL" -gt 0 ]; then
  echo "REPROVADO — $((N_QUEBRA+N_FRAGIL)) achado(s) de QUEBRA/FRAGIL"
  exit 1
fi
echo "OK — nenhum QUEBRA/FRAGIL"
exit 0
