(ns oplenario.sessoes.db.sessao
  "Persistencia da SESSAO plenaria (§22.6 eixo A) — funcoes sobre a `tx` do tenant (RLS isola). `agendar!` e'
  atomico: numera gapless (kernel/sequencial, escopo 'sessao:<leg>:<tipo>' do ente da SESSAO, reset por sessao
  legislativa) + resolve capabilities (default do tipo + override) + insere 'agendada'. `transicionar!` move
  o estado pela maquina (logic/transicao-valida?, fail-closed) com CAS por lock_version, carimbando o marco
  temporal do alvo. HoneySQL schema-qualified; ente_id em toda query."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :sessao_legislativa_id :tipo_sessao :numero_sequencial :estado :modalidade
   :delibera :transmite_publica :gera_ata_regimental :permite_voto_secreto :permite_modalidade_remota
   :agendada_para :aberta_em :encerrada_em :motivo_nao_realizada :lock_version])

(defn agendar!
  "Agenda uma sessao: numera gapless (escopo por sessao legislativa+tipo), resolve capabilities (default do
  tipo + `override` parcial) e insere 'agendada'. `sessao-legislativa-id` = forward-ref a cadastros (sem FK).
  Devolve {:id :numero :ocorrido-em} — `ocorrido-em` (F7 E3, RETURNING de `efetivado_em`, o instante do ato de
  agendar) e' o que o evento sessao.agendada carrega p/ semear `transicionou_em` do SLI (sempre <= qualquer
  transicao futura -> o gate de monotonicidade absorve a ordem). Valida tipo/modalidade (fail-closed)."
  [tx {:keys [id ente-id sessao-legislativa-id tipo-sessao modalidade agendada-para
              capabilities-override created-by]}]
  (logic/validar-tipo tipo-sessao)
  (logic/validar-modalidade modalidade)
  (let [num  (sequencial/proximo! tx (logic/escopo-numeracao sessao-legislativa-id tipo-sessao))
        caps (logic/resolver-capabilities tipo-sessao (or capabilities-override {}))
        row  (comum/linha->kebab
              (jdbc/execute-one! tx
                (sql/format {:insert-into :sessoes.sessao
                             :values [{:id id :ente_id ente-id :sessao_legislativa_id sessao-legislativa-id
                                       :tipo_sessao tipo-sessao :numero_sequencial num :estado "agendada"
                                       :modalidade (or modalidade "presencial")
                                       :delibera (:delibera caps) :transmite_publica (:transmite-publica caps)
                                       :gera_ata_regimental (:gera-ata-regimental caps)
                                       :permite_voto_secreto (:permite-voto-secreto caps)
                                       :permite_modalidade_remota (:permite-modalidade-remota caps)
                                       :agendada_para agendada-para :created_by created-by :efetivado_em [:now]}]
                             :returning [:efetivado_em]})))]
    {:id id :numero num :ocorrido-em (:efetivado-em row)}))

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:sessoes.sessao]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-por-sessao-legislativa [tx ente-id sessao-legislativa-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:sessoes.sessao]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_legislativa_id sessao-legislativa-id]]
                  :order-by [[:tipo_sessao :asc] [:numero_sequencial :asc]]}))))

(defn- estado+lock [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version :aberta_em] :from [:sessoes.sessao]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn transicionar!
  "Move o estado da sessao para `para` (maquina logic/transicao-valida?, fail-closed) com CAS por lock_version.
  Carimba o marco temporal do alvo: aberta -> aberta_em (so na 1a abertura); encerrada/nao_realizada ->
  encerrada_em; nao_realizada exige `motivo`. Lanca em transicao invalida, conflito de lock ou inexistente.
  Devolve {:de :para :ocorrido-em} — `ocorrido-em` (F7 E3, RETURNING de `atualizado_em`) e' o instante REAL
  da transicao no dominio, que o evento sessao.transicionou carrega p/ o SLI de janela de sessao (paineis)
  carimbar a janela DAQUI, nao do momento em que projeta. CARRY (mesmo do legislativo): `atualizado_em` usa
  o DEFAULT `[:now]`, que em Postgres congela no INICIO da tx — sob concorrencia real na MESMA sessao a
  ordem numerica pode divergir da causal; a verdade canonica (`sessoes.sessao.estado`, via CAS de
  lock_version) NUNCA e' afetada, so' a VISTA best-effort do SLI. Fix definitivo (`clock_timestamp()`) e'
  decisao maior, fora do escopo desta fatia."
  [tx {:keys [id ente-id para motivo updated-by lock-version]}]
  (let [{:keys [estado aberta-em] db-lock :lock-version} (estado+lock tx ente-id id)]
    (when (nil? estado)
      ;; :tipo p/ consistencia com os irmaos (review clojure LOW): se a sessao sumir entre o buscar do controller
      ;; e o FOR UPDATE aqui (TOCTOU; sem DELETE no dominio, na pratica inalcancavel), mapeia 409, nunca 500.
      (throw (ex-info "transicionar!: sessao inexistente" {:tipo :conflito/transicao :id id :ente-id ente-id})))
    ;; conflito de concorrencia ANTES da validacao de maquina: um caller com lock stale (estado ja avancou)
    ;; recebe "conflito de lock" — diagnostico correto p/ retry — em vez de "transicao invalida" (review F4.1).
    (when (not= db-lock lock-version)
      (throw (ex-info "transicionar!: conflito de lock_version"
                      {:tipo :conflito/transicao :id id :esperado lock-version :atual db-lock})))
    (when-not (logic/transicao-valida? estado para)
      (throw (ex-info "transicionar!: transicao de estado invalida"
                      {:tipo :conflito/transicao :id id :de estado :para para})))
    (when (and (= "nao_realizada" para) (str/blank? motivo))
      (throw (ex-info "transicionar!: 'nao_realizada' exige motivo" {:tipo :validacao/invalido :id id})))
    (let [sets (cond-> {:estado para :updated_by updated-by :atualizado_em [:now]
                        :lock_version [:+ :lock_version 1]}
                 (and (= "aberta" para) (nil? aberta-em)) (assoc :aberta_em [:now])
                 (contains? #{"encerrada" "nao_realizada"} para) (assoc :encerrada_em [:now])
                 (= "nao_realizada" para) (assoc :motivo_nao_realizada motivo))
          r (comum/linha->kebab
              (jdbc/execute-one! tx
                (sql/format {:update :sessoes.sessao :set sets
                             :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]
                             :returning [:atualizado_em]})))]
      ;; com RETURNING, execute-one! devolve a LINHA (ou nil se 0 linhas casaram o WHERE) — nil = conflito.
      (when (nil? r)
        (throw (ex-info "transicionar!: conflito de lock_version ou sessao inexistente"
                        {:tipo :conflito/transicao :id id :lock-version lock-version})))
      {:de estado :para para :ocorrido-em (:atualizado-em r)})))
