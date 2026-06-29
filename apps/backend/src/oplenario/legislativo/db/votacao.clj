(ns oplenario.legislativo.db.votacao
  "Persistencia da votacao (eixo G) — funcoes sobre a `tx` do tenant (RLS isola). HoneySQL schema-qualified;
  ente_id em toda query. `abrir!` cria a votacao 'aberta'; `registrar-voto!`/`registrar-voto-secreto!` sao
  APPEND-ONLY (o trigger congela; correcao = nova votacao); `encerrar!` apura (votos OU votos_secretos
  conforme a modalidade), computa o resultado pela aritmetica EXATA do quorum (logic) e grava o snapshot
  (CAS por lock_version). `anular!` leva a 'anulada' (terminal) — usado na correcao (nova votacao aponta a
  corrigida via votacao_corrige_id)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :objeto_tipo :objeto_id :modalidade :quorum_tipo :estado :resultado
   :total_sim :total_nao :total_abstencao :base_membros :votacao_corrige_id
   :sessao_id :pauta_item_id :lock_version])

(defn abrir!
  "Abre uma votacao (estado 'aberta') sobre o objeto polimorfico (objeto-tipo,objeto-id). `sessao-id` +
  `pauta-item-id` (ambos forward-ref a sessoes, §22.10) sao CONTEXTO TEMPORAL — a votacao e' sobre a
  MATERIA (objeto), nao sobre o item (§22.6 eixo B). Devolve {:id}."
  [tx {:keys [id ente-id objeto-tipo objeto-id modalidade quorum-tipo votacao-corrige-id
              sessao-id pauta-item-id created-by]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.votacoes
                 :values [{:id id :ente_id ente-id :objeto_tipo objeto-tipo :objeto_id objeto-id
                           :modalidade modalidade :quorum_tipo quorum-tipo :estado "aberta"
                           :votacao_corrige_id votacao-corrige-id :sessao_id sessao-id
                           :pauta_item_id pauta-item-id :created_by created-by :efetivado_em [:now]}]}))
  {:id id})

(defn registrar-voto!
  "Registra um voto NOMINAL (atribuido). Append-only; a UNIQUE (ente_id,votacao_id,vereador_id) barra voto
  duplo do mesmo vereador. Devolve {:id}."
  [tx {:keys [id ente-id votacao-id vereador-id voto created-by]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.votos
                 :values [{:id id :ente_id ente-id :votacao_id votacao-id :vereador_id vereador-id
                           :voto voto :created_by created-by :efetivado_em [:now]}]}))
  {:id id})

(defn registrar-voto-secreto!
  "Registra um voto SECRETO (anonimo — a tabela nao tem vereador_id/created_by). Append-only. Devolve {:id}."
  [tx {:keys [id ente-id votacao-id voto]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.votos_secretos
                 :values [{:id id :ente_id ente-id :votacao_id votacao-id :voto voto :efetivado_em [:now]}]}))
  {:id id})

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.votacoes]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn votos-da-votacao
  "Os votos NOMINAIS (atribuidos) da votacao. NOMINAL apenas — voto secreto NAO e' atribuivel por design
  (sigilo no schema; votos_secretos nao tem vereador_id). P/ votacao secreta devolve [] (sem votos nominais)."
  [tx ente-id votacao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :votacao_id :vereador_id :voto :registrado_em]
                  :from [:legislativo.votos]
                  :where [:and [:= :ente_id ente-id] [:= :votacao_id votacao-id]]
                  :order-by [[:registrado_em :asc]]}))))

(defn- apurar
  "Apura {:sim n :nao n :abstencao n} contando os votos da `tabela` (votos | votos_secretos) por valor.
  Usa linhas->kebab (padrao do modulo): a chave do `voto` fica `:voto` independente da tabela de origem —
  robusto vs. o namespace por-tabela do next.jdbc (review F3.7 clojure-MAJOR)."
  [tx ente-id votacao-id tabela]
  (let [por-voto (->> (jdbc/execute! tx
                        (sql/format {:select [:voto [[:count :*] :n]] :from [tabela]
                                     :where [:and [:= :ente_id ente-id] [:= :votacao_id votacao-id]]
                                     :group-by [:voto]}))
                      comum/linhas->kebab
                      (into {} (map (juxt :voto :n))))]
    {:sim (get por-voto "sim" 0) :nao (get por-voto "nao" 0) :abstencao (get por-voto "abstencao" 0)}))

(defn- votacao+lock
  [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version :modalidade :quorum_tipo] :from [:legislativo.votacoes]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn encerrar!
  "Encerra a votacao (estado 'encerrada'): apura os votos (nominal->votos, secreta->votos_secretos), computa
  o resultado pela aritmetica EXATA do quorum (logic/resultado-votacao com `base-membros` = composicao da
  Casa, resolvida UPSTREAM e passada explicita — nao JOIN), e grava o snapshot (totais+base+resultado) com
  CAS por lock_version. Modalidade 'simbolica' (aclamacao) nao apura individual: passe `:resultado` explicito.
  Lanca em conflito de lock OU votacao inexistente. Fail-closed: parecer terminal nao reabre (trigger)."
  [tx {:keys [id ente-id base-membros resultado updated-by lock-version]}]
  (let [{:keys [estado modalidade quorum-tipo]} (votacao+lock tx ente-id id)]
    (when (nil? estado)
      (throw (ex-info "encerrar!: votacao inexistente" {:id id :ente-id ente-id})))
    ;; fail-closed (review F3.7 clojure-MENOR): votacao terminal nao reabre — erro inspecionavel em vez de
    ;; um "conflito de lock" enganoso (o trigger tambem barra, mas a mensagem aqui e' a causa real).
    (when (contains? logic/estados-votacao-terminais estado)
      (throw (ex-info "encerrar!: votacao ja em estado terminal" {:id id :estado estado})))
    (let [tally (case modalidade
                  "nominal" (apurar tx ente-id id :legislativo.votos)
                  "secreta" (apurar tx ente-id id :legislativo.votos_secretos)
                  "simbolica" nil)
          res (if (= "simbolica" modalidade)
                (or resultado (throw (ex-info "encerrar!: votacao simbolica exige :resultado explicito" {:id id})))
                (logic/resultado-votacao quorum-tipo tally base-membros))
          r (jdbc/execute-one! tx
              (sql/format {:update :legislativo.votacoes
                           :set {:estado "encerrada" :resultado res
                                 :total_sim (:sim tally) :total_nao (:nao tally) :total_abstencao (:abstencao tally)
                                 :base_membros base-membros :updated_by updated-by :atualizado_em [:now]
                                 :lock_version [:+ :lock_version 1]}
                           :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info "encerrar!: conflito de lock_version ou votacao inexistente"
                        {:id id :lock-version lock-version})))
      (merge {:id id :estado "encerrada" :resultado res :base-membros base-membros} tally))))

(defn anular!
  "Anula a votacao (estado 'anulada', terminal) com CAS. Usada na correcao: anula a votacao errada e abre-se
  uma NOVA apontando a corrigida (votacao_corrige_id). O trigger trava anular uma JA terminal."
  [tx {:keys [id ente-id updated-by lock-version]}]
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.votacoes
                         :set {:estado "anulada" :updated_by updated-by :atualizado_em [:now]
                               :lock_version [:+ :lock_version 1]}
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "anular!: conflito de lock_version ou votacao inexistente"
                      {:id id :lock-version lock-version})))
    {:id id :estado "anulada"}))
