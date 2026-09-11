(ns oplenario.legislativo.wire.in.proposicao
  "Representacao EXTERNA de ENTRADA da proposicao (§22.10 wire/in, ADR-0001, Onda B Slice 2) — os corpos de
  POST/PATCH. `:closed true` recusa campo extra; tenant/autor NAO vem do corpo (vem do ator resolvido na
  auth); o `id` (PATCH) vem do path. Enums saem de legislativo.logic (fonte unica; espelham os CHECK das
  migrations 20260620000013/20260620000015)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.legislativo.logic :as logic]))

;; Limites de tamanho: mesmo rigor dos filtros de GET nesta borda de escrita (o body-cap global de 256KiB
;; nao substitui bound por campo). autor-id/destinatario-id sao UUID em string (36 chars); o adapter ainda
;; os converte e rejeita shape invalido. `texto` nao ganha :max aqui — o limite REAL e' 32KB em BYTES,
;; imposto por logic/decidir-armazenamento no Repo (spec §6), nao em caracteres.
(def ^:private campos-metadados-opcionais
  "Campos de metadados opcionais compartilhados por criar/editar (mesmos limites; evita drift)."
  [[:autor-tipo {:optional true} [:maybe (km/enum-de logic/autor-tipos)]]
   [:autor-id {:optional true} [:maybe [:string {:max 36}]]]
   [:autor-texto {:optional true} [:maybe [:string {:max 300}]]]
   [:objeto-indicacao {:optional true} [:maybe [:string {:max 500}]]]
   [:destinatario-id {:optional true} [:maybe [:string {:max 36}]]]
   [:destinatario-texto {:optional true} [:maybe [:string {:max 300}]]]
   [:tipo-requerimento {:optional true} [:maybe [:string {:max 200}]]]
   [:categoria-mocao {:optional true} [:maybe [:string {:max 200}]]]
   [:texto {:optional true} [:maybe :string]]])

(def CriarProposicao
  "Corpo de POST /legislativo/proposicoes. `texto` e' OPCIONAL (corpo integral markdown, inline <=32KB —
  overflow p/ objeto_store fica de carry, spec §6)."
  (into [:map {:closed true}
         [:tipo (km/enum-de logic/tipos)]
         [:ano [:int {:min 1900 :max 2200}]]
         [:ementa [:string {:max 2000}]]]
        campos-metadados-opcionais))

(def EditarProposicao
  "Corpo de PATCH /legislativo/proposicoes/:id. PATCH parcial: so' os campos presentes mudam.
  `lock-version` e' obrigatorio (CAS). Se `texto` presente, promove uma nova versao (origem 'edicao')."
  (into [:map {:closed true}
         [:lock-version :int]
         [:ementa {:optional true} [:maybe [:string {:max 2000}]]]]
        campos-metadados-opcionais))

;; ---------- Fatia 2: a borda da TRAMITACAO (eixo C) ----------

(def ^:private contexto-max-chaves
  "Teto de chaves do `contexto`. O contexto e' ARGUMENTO DE GUARD (o avaliador le' `contexto.x`), nao um
  saco de anexos: 20 chaves cobrem qualquer guard plausivel e impedem que a borda vire um canal de blob
  para dentro de `proposicao_transicao_historico.contexto` (jsonb, append-only — o que entra la' nunca sai)."
  20)

(def ^:private ChaveContexto
  "Chave de contexto: minuscula, ASCII, <=60 chars. NAO e' cosmetica — o adapters/in INTERNA cada chave
  como keyword (o avaliador acessa campo por keyword, motor/runtime `:campo`), e internar string arbitraria
  de cliente e' superficie de abuso. O charset fechado limita o alfabeto ao que um guard consegue nomear."
  [:re #"^[a-z][a-z0-9_-]{0,59}$"])

(def ^:private ValorContexto
  "Valor de contexto: ESCALAR (ou nulo). Aninhamento fica de fora de proposito — o avaliador da DSL so'
  sabe acessar UM nivel (`contexto.x`), entao mapa/vetor aqui seria dado que nenhum guard consegue ler e
  que so' engorda o historico. String limitada a 500 chars pelo mesmo motivo."
  [:or [:string {:max 500}] :int :double :boolean :nil])

(def TramitarProposicao
  "Corpo de POST /legislativo/proposicoes/:id/tramitacao.

  `gatilho` e' o UNICO verbo — string livre (:min 1), NUNCA enum: o vocabulario de gatilhos e' DADO do
  tenant (`legislativo.template_transicao.gatilho`), Inv.4. Cravar um enum aqui quebraria a primeira Casa
  cujo regimento nomeia o ato de outro jeito.

  O QUE ESTE SCHEMA DELIBERADAMENTE NAO TEM: estado-destino, sob qualquer nome (`para`, `estado`,
  `para-estado`) — e `template-id`. Quem decide PARA ONDE a materia vai e' o template avaliando o guard; o
  rito sob o qual ela corre e' a coluna da propria linha. Deixar o cliente nomear qualquer um dos dois e' a
  classe de defeito do T3-A (o chamador escolhendo a regra). `:closed true` faz a recusa ser mecanica: o
  campo extra e' 400 e a engine nunca roda.

  `contexto` (opcional) e' a carga do gatilho que o GUARD pode ler e que o historico persiste — entrada
  DE CLIENTE que alimenta a avaliacao da regra. O schema limita a FORMA (escalares, teto de chaves,
  charset); quem limita o USO e' quem escreve o rito, e e' assim que tem de ser (Inv.4: o codigo nao
  decide o regimento).

  O QUE A FATIA 4 FEZ COM ISSO, ja' que proibir nao era opcao: tornou a confianca VISIVEL na expressao.
  Este campo chega ao guard sob o nome `alegado`, nunca `contexto` — `adapters/in/contexto->alegado` faz a
  troca, e a chave `contexto` NAO EXISTE mais no ambiente de avaliacao. Um rito escrito como
  `alegado.aprovado == verdadeiro` continua permitido, e continua deixando o operador afirmar a propria
  precondicao; a diferenca e' que agora quem le' o rito ve' a palavra `alegado` e sabe disso, em vez de
  ler `contexto` e supor apuracao. Um rito antigo que ainda diga `contexto.aprovado` nao le' o corpo do
  cliente em silencio: o avaliador lanca e a materia NAO tramita. Ao lado de `alegado`, o guard tem
  `proposicao` (a linha, lida pelo servidor) e os fatos por nome (`aprovada_em_votacao(proposicao.id)` —
  decisao 3-B), que sao os canais APURADOS. Ver `db/tramitacao/transicionar!`, secao OS DOIS CANAIS."
  [:map {:closed true}
   [:gatilho [:string {:min 1 :max 100}]]
   [:contexto {:optional true} [:maybe [:map-of {:max contexto-max-chaves} ChaveContexto ValorContexto]]]])
