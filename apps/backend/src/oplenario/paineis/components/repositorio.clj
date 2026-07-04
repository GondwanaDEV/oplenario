(ns oplenario.paineis.components.repositorio
  "Component de PERSISTENCIA do paineis — banco disponibilizado como Stuart Sierra Component (ADR-0001
  §3-bis). O db/ de um modulo so' e' importado por ESTE component (regra do import-lint, arquitetura-test);
  logo TANTO a escrita de PROJECAO (chamada pelo consumer, dentro da tx do relay) QUANTO a LEITURA interna
  (chamada pelo controller, via com-tenant*) moram aqui.

  `projetar-evento!` e' funcao PLANA (nao um metodo do protocolo/record) — o consumer roda dentro da tx do
  relay, que ja' e' a `tx`; nao ha datasource a abrir (`com-tenant*` seria redundante e trocaria o role, o
  que quebraria o UPDATE seguinte do relay em shared.outbox — mesmo racional de transparencia/repositorio).
  So' seta o GUC app.ente_id (kernel.tenancy/set-tenant!) e despacha para db/pendencia (F7 Slice 1, 'o que
  vence') OU db/tramitacao (F7 Slice 2, board interno) conforme o tipo do evento.

  Os TIPOS de evento sao STRINGS LITERAIS, NAO imports de `participacao.events.*` — §22.10 proibe import
  cross-modulo; o nome do evento e' o CONTRATO DE FIACAO do bus, nao um tipo compartilhado (mesmo padrao de
  transparencia/diplomat/consumers).

  TOLERANCIA A GAP DE PROJECAO (mesmo racional de transparencia/db/materia/atualizar-estado!, review
  architect HIGH-1): fechamento/vencimento/prorrogacao NUNCA lancam quando a pendencia ainda nao foi
  projetada — devolvem nil, e este ns so' LOGA um warning. O relay e' UM SO, compartilhado por TODOS os
  modulos consumidores; um handler que lanca faz o MESMO evento ser reprocessado a cada tick para sempre,
  bloqueando HEAD-OF-LINE todo evento de id maior no bus inteiro (nao so' desta projecao).

  TOLERANCIA A PAYLOAD MALFORMADO (review security HIGH): `projetar-evento!` envolve `despachar!` (o `case`
  de fato, funcao separada — ver sua docstring) num try/catch — `UUID/fromString`/`LocalDate/parse`/
  `Instant/parse` lancam em string invalida ANTES de qualquer SQL rodar (avaliacao de argumento precede a
  chamada; nenhum efeito
  parcial no banco quando o parse falha), entao capturar aqui e' seguro (a tx do relay nao e' abortada — nao
  houve comando SQL nesta branch). Sem este guard, um payload malformado (ex.: um produtor futuro de
  `participacao` gravando 'vence-em' num formato errado — os schemas Malli de origem tipam a data como
  :string cru, nao um padrao ISO validado) lancaria IllegalArgumentException/DateTimeParseException DENTRO
  da tx compartilhada e envenenaria o relay do sistema INTEIRO exatamente como um `throw` de dominio — o
  guard estrutural de marcar-concluida!/marcar-vencida!/atualizar-vence-em! (WHERE condicional, nunca lanca)
  so' cobre 'nao encontrado', nao 'payload nao parseavel'."
  (:require [clojure.tools.logging :as log]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.paineis.db.pendencia :as db-pendencia]
            [oplenario.paineis.db.tramitacao :as db-tramitacao])
  (:import (java.time Instant LocalDate)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(def ^:private teto-o-que-vence 100)
;; teto POR GRUPO de estado (review database HIGH, F7 Slice 2) — ver docstring de db.tramitacao/listar-board.
(def ^:private teto-tramitacao-board-por-estado 50)

(defn- protocolar!
  "Aplica inserir! p/ uma das 4 especies de participacao — extrai o campo comum entre os 4 branches
  'protocolad[oa]' (review clojure MEDIUM: eram copy-paste identico exceto objeto-tipo/id-key)."
  [tx ente-id objeto-tipo id-str protocolo vence-em-str]
  (db-pendencia/inserir! tx {:ente-id ente-id :objeto-tipo objeto-tipo
                             :objeto-id (UUID/fromString id-str)
                             :protocolo protocolo :vence-em (LocalDate/parse vence-em-str)}))

(defn- fechar!
  "Aplica marcar-concluida! e loga se a pendencia ainda nao existia (redrive fora de ordem / backlog)."
  [tx ente-id objeto-tipo objeto-id-str]
  (or (db-pendencia/marcar-concluida! tx {:ente-id ente-id :objeto-tipo objeto-tipo
                                          :objeto-id (UUID/fromString objeto-id-str)})
      (log/warn "paineis: fechamento sem pendencia projetada (protocolo ausente?)"
                {:ente-id ente-id :objeto-tipo objeto-tipo :objeto-id objeto-id-str})))

(defn- transicionar-tramitacao!
  "Aplica atualizar-estado! do board e loga se a materia ainda nao existia (redrive fora de ordem / backlog
  — mesmo racional de fechar! e de transparencia/db/materia/atualizar-estado!). `ocorrido-em-str` (F7 carry):
  o instante REAL da transicao (do evento, nao 'agora') — ver docstring de db.tramitacao/atualizar-estado!."
  [tx ente-id proposicao-id-str estado ocorrido-em-str]
  (or (db-tramitacao/atualizar-estado! tx {:ente-id ente-id :proposicao-id (UUID/fromString proposicao-id-str)
                                           :estado estado :transicionou-em (Instant/parse ocorrido-em-str)})
      (log/warn "paineis: transicao sem materia projetada no board (protocolo ausente?)"
                {:ente-id ente-id :proposicao-id proposicao-id-str :estado estado})))

(defn despachar!
  "O `case` de fato, SEM tolerancia — lanca em tipo sem branch (`case` sem default: 'No matching clause') OU
  em payload malformado (UUID/LocalDate invalidos). PUBLICA (nao `defn-`) DE PROPOSITO: e' o alvo direto do
  drift-guard de teste (`todo-tipo-consumido-tem-branch-de-projecao`), que precisa distinguir 'tipo sem
  branch' (bug de programador — deve ficar RUIDOSO, pego em CI antes de subir) de 'payload malformado em
  runtime' (dado externo — deve ser TOLERADO). `projetar-evento!` (abaixo) e' quem envolve ESTA fn num
  try/catch p/ a tolerancia de runtime; se o catch estivesse AQUI, um 'No matching clause' de um tipo
  registrado sem branch seria silenciosamente engolido (nunca propagaria ao teste), mascarando o drift em
  vez de barra-lo (review security HIGH, cuidado ao aplicar o fix)."
  [tx ente-id tipo payload]
  (case tipo
    "participacao.pedido_esic.protocolado"
    (protocolar! tx ente-id "pedido_esic" (:pedido-id payload) (:protocolo payload) (:vence-em payload))

    "participacao.recurso_esic.protocolado"
    (protocolar! tx ente-id "recurso_esic" (:recurso-id payload) (:protocolo payload) (:vence-em payload))

    "participacao.solicitacao_titular.protocolada"
    (protocolar! tx ente-id "solicitacao_titular" (:solicitacao-id payload) (:protocolo payload) (:vence-em payload))

    "participacao.manifestacao_ouvidoria.protocolada"
    (protocolar! tx ente-id "manifestacao_ouvidoria" (:manifestacao-id payload) (:protocolo payload) (:vence-em payload))

    "participacao.pedido_esic.respondido"
    (fechar! tx ente-id "pedido_esic" (:pedido-id payload))

    "participacao.recurso_esic.decidido"
    (fechar! tx ente-id "recurso_esic" (:recurso-id payload))

    "participacao.solicitacao_titular.respondida"
    (fechar! tx ente-id "solicitacao_titular" (:solicitacao-id payload))

    "participacao.manifestacao_ouvidoria.respondida"
    (fechar! tx ente-id "manifestacao_ouvidoria" (:manifestacao-id payload))

    "participacao.manifestacao_ouvidoria.arquivada"
    (fechar! tx ente-id "manifestacao_ouvidoria" (:manifestacao-id payload))

    "participacao.prazo.vencido"
    (or (db-pendencia/marcar-vencida! tx {:ente-id ente-id :objeto-tipo (:objeto-tipo payload)
                                          :objeto-id (UUID/fromString (:objeto-id payload))})
        (log/warn "paineis: vencimento sem pendencia projetada" {:ente-id ente-id :payload payload}))

    "participacao.prazo.prorrogado"
    (or (db-pendencia/atualizar-vence-em! tx {:ente-id ente-id :objeto-tipo (:objeto-tipo payload)
                                              :objeto-id (UUID/fromString (:objeto-id payload))
                                              :vence-em (LocalDate/parse (:para-data payload))})
        (log/warn "paineis: prorrogacao sem pendencia projetada" {:ente-id ente-id :payload payload}))

    "proposicao.protocolada"
    (db-tramitacao/inserir! tx {:ente-id ente-id :proposicao-id (UUID/fromString (:proposicao-id payload))
                                :tipo (:tipo payload) :ano (:ano payload) :sequencial (:sequencial payload)
                                :urn-lex (:urn-lex payload) :ementa (:ementa payload)
                                :autor-tipo (:autor-tipo payload) :autor-texto (:autor-texto payload)
                                :estado (:estado payload)})

    "proposicao.transicionou"
    (transicionar-tramitacao! tx ente-id (:proposicao-id payload) (:para payload) (:ocorrido-em payload))))

(defn projetar-evento!
  "Dispatch por tipo de evento -> a projecao de dominio, DENTRO da `tx` corrente (a do relay). Seta o GUC de
  tenant (sem trocar de role) e escreve em paineis.pendencia ou paineis.tramitacao. `payload` ja chegou com
  chaves KEYWORD kebab (outbox/jsonb-> usa keyword-keys-object-mapper) — EXCETO os campos :uuid e os de
  data (:vence-em/:para-data), que chegam como string (ver docstring de participacao/events/*). o ENTRY
  POINT REAL do consumer (§22.10 diplomat/consumers) — NUNCA lanca (review security HIGH — ver docstring do
  ns): envolve `despachar!` inteiro num try/catch, entao QUALQUER excecao de `despachar!` e' tolerada aqui
  (log + nil) — payload malformado (UUID/LocalDate invalidos) OU um tipo sem branch de dispatch (drift
  bus<->case), sem distincao (ambos sao igualmente inaceitaveis dentro do relay compartilhado em producao).
  A distincao entre as duas causas so' importa p/ o TESTE do drift-guard, que por isso chama `despachar!`
  DIRETO (nao este fn) — assim o drift ainda e' pego RUIDOSAMENTE em CI, antes de qualquer deploy chegar a
  rodar este caminho tolerante contra trafego real.

  CATCH Throwable, NAO Exception (review security HIGH, F7 Slice 2): as `:pre` de db/pendencia.clj e
  db/tramitacao.clj lancam `AssertionError` — um `Error`, IRMAO de `Exception` sob `Throwable`, NAO capturado
  por `(catch Exception ...)`. Um `:pre` falhando (payload com chave ausente/nil que uma validacao Malli
  upstream deveria ter barrado, mas §22.10 nao garante contrato compartilhado entre modulos) escaparia deste
  catch e envenenaria o relay exatamente como o caso que este ns existe p/ evitar. CARRY: o mesmo padrao de
  `:pre` (sem guarda de AssertionError) tambem existe em `transparencia.components.repositorio/
  projetar-evento!` — que ALEM DISSO nao tem NENHUM try/catch (nem de Exception): um alvo maior p/ correcao
  futura, fora do escopo deste modulo."
  [tx {:keys [tipo ente-id payload]}]
  (tenancy/set-tenant! tx ente-id)
  (try
    (despachar! tx ente-id tipo payload)
    (catch Throwable e
      (log/warn e "paineis: payload malformado ou falha de projecao — evento tolerado, nunca propaga p/ o relay compartilhado"
                {:tipo tipo :ente-id ente-id})
      nil)))

(defprotocol RepoPaineis
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant (com-tenant*) — leitura interna.")
  (o-que-vence [this ente-id] "Pendencias ABERTAS (pendente|vencido) do tenant, mais urgente primeiro.")
  (tramitacao-board [this ente-id] "TODAS as proposicoes do tenant, agrupadas por estado, mais estagnadas primeiro."))

(defrecord RepoPaineisPg [datasource]
  RepoPaineis
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (o-que-vence [this ente-id] (transacao this ente-id #(db-pendencia/listar-abertas % ente-id teto-o-que-vence)))
  (tramitacao-board [this ente-id] (transacao this ente-id #(db-tramitacao/listar-board % ente-id teto-tramitacao-board-por-estado))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoPaineisPg nil))
