(ns oplenario.legislativo.db.parecer
  "Persistencia do parecer_comissao (eixo F) — CRUD da entidade sobre a `tx` do tenant (RLS isola). O
  ENGINE de tramitacao do parecer mora em db/parecer-tramitacao (reusa o motor). HoneySQL schema-qualified.
  `criar!` deriva o estado inicial do template (template-driven), e valida (1) que o template e' de sujeito
  'parecer' (anti-misconfig cross-sujeito) e (2) que o objeto polimorfico EXISTE no mesmo tenant (disc.2 —
  ref sem FK declarativa, integridade no service)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :objeto_tipo :objeto_id :comissao_id :relator_id :voto_relator :estado
   :template_id :texto_vigente_versao_id :lock_version])

;; objeto_tipo -> tabela do objeto (p/ a prova de existencia same-tenant; disc.2)
(def ^:private objeto-tipo->tabela
  {"proposicao" :legislativo.proposicoes
   "emenda"     :legislativo.emendas})

(defn- objeto-existe?
  "True se o objeto polimorfico (objeto-tipo,objeto-id) existe no MESMO tenant. A RLS ja isola por ente,
  mas o ente_id entra no WHERE (defesa + indice). Tipo desconhecido -> false (fail-closed)."
  [tx ente-id objeto-tipo objeto-id]
  (when-let [tabela (objeto-tipo->tabela objeto-tipo)]
    (some? (jdbc/execute-one! tx
             (sql/format {:select [[[:inline 1] :um]] :from [tabela]
                          :where [:and [:= :ente_id ente-id] [:= :id objeto-id]]})))))

(defn- template-meta
  "Le estado_inicial + sujeito do template (p/ derivar o estado inicial e validar o sujeito)."
  [tx ente-id template-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:estado_inicial :sujeito] :from [:legislativo.template_tramitacao]
                  :where [:and [:= :ente_id ente-id] [:= :id template-id]]}))))

(defn criar!
  "Cria um parecer_comissao (eixo F). Estado inicial DERIVADO de template_tramitacao.estado_inicial
  (template-driven, nao hardcoded). Fail-closed: lanca se (1) template inexistente; (2) template nao e' de
  sujeito 'parecer' (anti-misconfig: parecer governado por template de proposicao); (3) o objeto polimorfico
  nao existe no tenant (disc.2). Devolve {:id :estado}."
  [tx {:keys [id ente-id objeto-tipo objeto-id comissao-id relator-id voto-relator template-id created-by]}]
  (let [{:keys [estado-inicial sujeito]} (template-meta tx ente-id template-id)]
    (when (nil? estado-inicial)
      (throw (ex-info "criar parecer: template inexistente" {:template-id template-id})))
    (when (not= "parecer" sujeito)
      (throw (ex-info "criar parecer: template nao e' de sujeito 'parecer' (anti-misconfig cross-sujeito)"
                      {:template-id template-id :sujeito sujeito})))
    ;; tipo desconhecido e objeto-orfao sao erros DISTINTOS (review F3.6a clojure-MAJOR): valida o
    ;; vocabulario ANTES da prova de existencia (senao nil de objeto-existe? funde os dois casos).
    (when-not (logic/objetos-parecer objeto-tipo)
      (throw (ex-info "criar parecer: objeto-tipo desconhecido (vocabulario fechado disc.2)"
                      {:objeto-tipo objeto-tipo :vocabulario logic/objetos-parecer})))
    (when-not (objeto-existe? tx ente-id objeto-tipo objeto-id)
      (throw (ex-info "criar parecer: objeto polimorfico inexistente no tenant (disc.2 prova de existencia)"
                      {:objeto-tipo objeto-tipo :objeto-id objeto-id})))
    (jdbc/execute-one! tx
      (sql/format {:insert-into :legislativo.pareceres
                   :values [{:id id :ente_id ente-id :objeto_tipo objeto-tipo :objeto_id objeto-id
                             :comissao_id comissao-id :relator_id relator-id :voto_relator voto-relator
                             :estado estado-inicial :template_id template-id
                             :created_by created-by :efetivado_em [:now]}]}))
    {:id id :estado estado-inicial}))

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.pareceres]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-por-objeto
  "Pareceres sobre um objeto (proposicao|emenda) — usa idx_pareceres_objeto (comeca por objeto_tipo)."
  [tx ente-id objeto-tipo objeto-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:legislativo.pareceres]
                  :where [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo] [:= :objeto_id objeto-id]]
                  :order-by [[:criado_em :asc]]}))))

(defn mudar-estado!
  "Transicao COARSE do estado do parecer (a maquina fina e' o motor, via parecer-tramitacao). CAS por
  `lock-version`; o trigger trava a partir de estado terminal (exceto correcao auditada). Lanca em conflito
  de versao OU row inexistente. (Nao faz a maquina — e' o `set` que o engine compoe.)"
  [tx {:keys [id ente-id estado updated-by lock-version]}]
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.pareceres
                         :set {:estado estado :updated_by updated-by :atualizado_em [:now]
                               :lock_version [:+ :lock_version 1]}
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "conflito de escrita (lock_version desatualizado) ou parecer inexistente"
                      {:id id :lock-version lock-version})))
    r))

(defn designar-relator!
  "Designa o relator do parecer (CAS por lock_version). Ato auditado de autoria; a transicao de estado
  correlata ('aguardando_designacao'->'com_relator') e' do engine."
  [tx {:keys [id ente-id relator-id updated-by lock-version]}]
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.pareceres
                         :set {:relator_id relator-id :updated_by updated-by :atualizado_em [:now]
                               :lock_version [:+ :lock_version 1]}
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "conflito de escrita (lock_version desatualizado) ou parecer inexistente"
                      {:id id :lock-version lock-version})))
    r))

;; ---- fila de relatores pendentes (read-model barato, FE Onda A1 §16.11) ----

(defn relatores-pendentes
  "Pareceres 'aguardando_designacao' (a designacao de relator ainda nao aconteceu — designar-relator!
  transiciona daqui p/ 'com_relator'), join com a proposicao p/ mostrar ementa/urn-lex (o objeto e'
  SEMPRE 'proposicao' nesta fatia — emenda fica fora, YAGNI). Mais antigo primeiro (fila FIFO)."
  [tx ente-id teto]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:pc.id [:pc.objeto_id :proposicao_id] :p.tipo :p.ano :p.sequencial
                           :p.urn_lex :p.ementa :pc.criado_em]
                  :from [[:legislativo.pareceres :pc]]
                  :join [[:legislativo.proposicoes :p]
                         [:and [:= :p.id :pc.objeto_id] [:= :p.ente_id :pc.ente_id]]]
                  :where [:and [:= :pc.ente_id ente-id] [:= :pc.objeto_tipo [:inline "proposicao"]]
                          [:= :pc.estado [:inline "aguardando_designacao"]]]
                  :order-by [[:pc.criado_em :asc]]
                  :limit teto}))))
