(ns oplenario.legislativo.db.tramitacao-executiva
  "Persistencia da TRAMITACAO NO EXECUTIVO (F3.8a, sancao/veto) — o PROCESSO que evolui (state machine +
  CAS por lock_version; trava terminal nivel b pelo trigger). `iniciar!` cria 'aguardando' p/ um autografo;
  `registrar-resposta!` move aguardando -> {sancionado|sancao_tacita|vetado}; `apreciar-veto!` move
  vetado -> {veto_mantido|veto_derrubado} (a apreciacao e' uma VOTACAO do eixo G, maioria absoluta, cujo id
  e' carimbado aqui). Guards de dominio fail-closed (erro inspecionavel antes do trigger). Sobre a `tx` do
  tenant; HoneySQL schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :autografo_id :estado :veto_tipo :veto_razoes :veto_votacao_id
   :respondido_em :apreciado_em :lock_version])

(defn iniciar!
  "Abre a tramitacao executiva (estado 'aguardando') p/ o autografo enviado. A UNIQUE (ente_id, autografo_id)
  barra duas tramitacoes p/ o mesmo autografo. Devolve {:id}."
  [tx {:keys [id ente-id autografo-id created-by]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.tramitacao_executiva
                 :values [{:id id :ente_id ente-id :autografo_id autografo-id :estado "aguardando"
                           :created_by created-by :efetivado_em [:now]}]}))
  {:id id})

(defn- estado+lock [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version] :from [:legislativo.tramitacao_executiva]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn registrar-resposta!
  "Registra a resposta do Executivo: 'aguardando' -> resultado in {sancionado|sancao_tacita|vetado}. Para
  'vetado' exige `:veto-tipo` (total|parcial) — o CHECK do banco tambem barra, mas o guard aqui da' erro
  inspecionavel. CAS por lock_version. Devolve {:id :estado}."
  [tx {:keys [id ente-id resultado veto-tipo veto-razoes updated-by lock-version]}]
  (let [{:keys [estado]} (estado+lock tx ente-id id)]
    (when (nil? estado)
      (throw (ex-info "registrar-resposta!: tramitacao executiva inexistente" {:id id :ente-id ente-id})))
    (when (not= "aguardando" estado)
      (throw (ex-info "registrar-resposta!: so se responde uma tramitacao 'aguardando'"
                      {:id id :estado estado})))
    (when (not (contains? logic/estados-resposta-executivo resultado))
      (throw (ex-info "registrar-resposta!: resultado invalido (sancionado|sancao_tacita|vetado)"
                      {:resultado resultado})))
    (when (and (= "vetado" resultado) (not (contains? logic/tipos-veto veto-tipo)))
      (throw (ex-info "registrar-resposta!: veto-tipo invalido ou ausente (total|parcial)"
                      {:id id :veto-tipo veto-tipo})))
    (let [r (jdbc/execute-one! tx
              (sql/format {:update :legislativo.tramitacao_executiva
                           :set {:estado resultado :veto_tipo veto-tipo :veto_razoes veto-razoes
                                 :respondido_em [:now] :updated_by updated-by :atualizado_em [:now]
                                 :lock_version [:+ :lock_version 1]}
                           :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info "registrar-resposta!: conflito de lock_version ou inexistente"
                        {:id id :lock-version lock-version})))
      {:id id :estado resultado})))

(defn apreciar-veto!
  "Registra a apreciacao do veto pela camara (votacao do eixo G, maioria absoluta): 'vetado' -> resultado in
  {veto_mantido|veto_derrubado}; carimba a `:veto-votacao-id` (a votacao que decidiu). CAS por lock_version.
  Devolve {:id :estado}."
  [tx {:keys [id ente-id resultado veto-votacao-id updated-by lock-version]}]
  (let [{:keys [estado]} (estado+lock tx ente-id id)]
    (when (nil? estado)
      (throw (ex-info "apreciar-veto!: tramitacao executiva inexistente" {:id id :ente-id ente-id})))
    (when (not= "vetado" estado)
      (throw (ex-info "apreciar-veto!: so se aprecia o veto de uma tramitacao 'vetado'"
                      {:id id :estado estado})))
    (when (not (contains? logic/estados-apreciacao-veto resultado))
      (throw (ex-info "apreciar-veto!: resultado invalido (veto_mantido|veto_derrubado)"
                      {:resultado resultado})))
    (let [r (jdbc/execute-one! tx
              (sql/format {:update :legislativo.tramitacao_executiva
                           :set {:estado resultado :veto_votacao_id veto-votacao-id
                                 :apreciado_em [:now] :updated_by updated-by :atualizado_em [:now]
                                 :lock_version [:+ :lock_version 1]}
                           :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info "apreciar-veto!: conflito de lock_version ou inexistente"
                        {:id id :lock-version lock-version})))
      {:id id :estado resultado})))

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.tramitacao_executiva]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn buscar-por-autografo [tx ente-id autografo-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.tramitacao_executiva]
                  :where [:and [:= :ente_id ente-id] [:= :autografo_id autografo-id]]}))))
