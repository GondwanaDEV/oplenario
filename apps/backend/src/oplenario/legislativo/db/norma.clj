(ns oplenario.legislativo.db.norma
  "Persistencia da NORMA promulgada (F3.8b) — o ato legal que fecha 'da proposicao a' publicacao'. `promulgar!`
  e' atomico: numera gapless (kernel/sequencial, escopo 'norma:tipo:ano' do ente da SESSAO) + computa a
  URN-de-norma (logic/urn-norma) + insere 'promulgada', na MESMA tx. `publicar!` move promulgada -> publicada
  (UMA vez; o trigger congela o conteudo legal e so libera a publicacao). uf/municipio = FATO do ente
  resolvido UPSTREAM (nao JOIN cross-schema, §22.10). O guard de que o desfecho executivo e' PROMULGAVEL
  (logic/promulgavel?) e' do controller/sessao — aqui so o ato. Sobre a `tx` do tenant; ente_id em toda query."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :proposicao_id :autografo_id :tipo_norma :numero :ano :urn :ementa :texto_versao_id
   :estado :promulgado_em :promulgado_por :publicado_em :veiculo_publicacao :lock_version])

(defn promulgar!
  "Promulga a norma da proposicao aprovada: numera gapless (escopo 'norma:tipo:ano'), computa a URN-de-norma
  (data = LocalDate da promulgacao) e insere 'promulgada' — atomico na tx. `tipo-norma` deriva da especie da
  proposicao (logic/tipo-proposicao->tipo-norma, resolvido UPSTREAM). UNIQUE (proposicao) e UNIQUE (autografo)
  barram dupla promulgacao. Devolve {:id :numero :urn}."
  [tx {:keys [id ente-id proposicao-id autografo-id tipo-norma ano uf municipio-nome data-promulgacao
              ementa texto-versao-id promulgado-por created-by]}]
  (when (nil? texto-versao-id)
    (throw (ex-info "promulgar!: texto-versao-id e' obrigatorio (artefato legal nao-vazio)"
                    {:id id :tipo-norma tipo-norma})))
  (let [num (sequencial/proximo! tx (str "norma:" tipo-norma ":" ano))
        urn (logic/urn-norma {:uf uf :municipio-nome municipio-nome :tipo-norma tipo-norma
                              :data data-promulgacao :numero num})]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :legislativo.norma
                   :values [{:id id :ente_id ente-id :proposicao_id proposicao-id :autografo_id autografo-id
                             :tipo_norma tipo-norma :numero num :ano ano :urn urn :ementa ementa
                             :texto_versao_id texto-versao-id :estado "promulgada"
                             :promulgado_por promulgado-por :created_by created-by :efetivado_em [:now]}]}))
    {:id id :numero num :urn urn}))

(defn- estado+lock [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version] :from [:legislativo.norma]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn publicar!
  "Publica a norma: 'promulgada' -> 'publicada' com publicado_em (now) + veiculo_publicacao. Mutacao parcial
  unica (o trigger congela o resto). CAS por lock_version. Guard fail-closed: so se publica uma 'promulgada'
  (erro inspecionavel antes do trigger). Devolve {:id :estado}."
  [tx {:keys [id ente-id veiculo-publicacao updated-by lock-version]}]
  (let [{:keys [estado]} (estado+lock tx ente-id id)]
    (when (nil? estado)
      (throw (ex-info "publicar!: norma inexistente" {:id id :ente-id ente-id})))
    (when (not= "promulgada" estado)
      (throw (ex-info "publicar!: so se publica uma norma 'promulgada'" {:id id :estado estado})))
    (when (str/blank? veiculo-publicacao)
      (throw (ex-info "publicar!: veiculo-publicacao e' obrigatorio (prova da publicacao)" {:id id})))
    (let [r (jdbc/execute-one! tx
              (sql/format {:update :legislativo.norma
                           :set {:estado "publicada" :publicado_em [:now] :veiculo_publicacao veiculo-publicacao
                                 :updated_by updated-by :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                           :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info "publicar!: conflito de lock_version ou norma inexistente"
                        {:id id :lock-version lock-version})))
      {:id id :estado "publicada"})))

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.norma]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn buscar-por-proposicao [tx ente-id proposicao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.norma]
                  :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]}))))
