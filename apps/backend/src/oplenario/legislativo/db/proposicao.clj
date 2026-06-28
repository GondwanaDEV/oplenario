(ns oplenario.legislativo.db.proposicao
  "Persistencia da proposicao — funcoes sobre a `tx` do tenant (RLS isola). HoneySQL no schema
  'legislativo' (NAO e' port). `protocolar!` e' o ato atomico do gate eixo H: sequencial gapless
  (kernel/sequencial) + URN/LexML (logic) + insert, tudo na MESMA tx (rollback nao deixa buraco)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :tipo :ano :sequencial :urn_lex :ementa :autor_tipo :autor_id :autor_texto :estado
   :objeto_indicacao :destinatario_id :destinatario_texto :tipo_requerimento :categoria_mocao
   :atributos_especificos :texto_vigente_versao_id])

(defn- linha->proposicao [linha]
  (when linha
    (update (comum/linha->kebab linha) :atributos-especificos comum/jsonb->kw)))

(defn protocolar!
  "Protocola: gera o sequencial gapless (escopo 'tipo:ano' do ente da SESSAO), computa a URN/LexML
  (eixo H) e insere — atomico na tx. uf/municipio-nome = FATO do ente resolvido UPSTREAM (nao JOIN
  cross-schema, §22.10). Devolve {:id :sequencial :urn-lex} (o numero so existe pos-commit)."
  [tx {:keys [id ente-id tipo ano uf municipio-nome ementa autor-tipo autor-id autor-texto
              objeto-indicacao destinatario-id destinatario-texto tipo-requerimento categoria-mocao
              atributos-especificos created-by]}]
  (let [seq-val (sequencial/proximo! tx (str tipo ":" ano))
        urn     (logic/urn-lex {:uf uf :municipio-nome municipio-nome :tipo tipo :ano ano :sequencial seq-val})]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :legislativo.proposicoes
                   :values [{:id id :ente_id ente-id :tipo tipo :ano ano :sequencial seq-val :urn_lex urn
                             :ementa ementa :autor_tipo autor-tipo :autor_id autor-id :autor_texto autor-texto
                             :objeto_indicacao objeto-indicacao :destinatario_id destinatario-id
                             :destinatario_texto destinatario-texto :tipo_requerimento tipo-requerimento
                             :categoria_mocao categoria-mocao
                             :atributos_especificos (some-> atributos-especificos comum/->jsonb)
                             :created_by created-by :efetivado_em [:now]}]}))
    {:id id :sequencial seq-val :urn-lex urn}))

(defn buscar [tx id]
  (linha->proposicao
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.proposicoes] :where [:= :id id]}))))

(defn listar-por-estado [tx estado]
  (mapv linha->proposicao
        (jdbc/execute! tx
          (sql/format {:select colunas :from [:legislativo.proposicoes] :where [:= :estado estado]
                       :order-by [[:ano :desc] [:sequencial :desc]]}))))

(defn mudar-estado!
  "Transicao COARSE do estado (a maquina fina e' a tramitacao F3.3). CAS por `lock-version` (compare-and-swap
  honesto: o WHERE casa a versao esperada e o bump so vale se ninguem escreveu no meio — dois escritores do
  mesmo estado nao-terminal nao se sobrescrevem em silencio). O trigger trava transicoes a partir de estado
  terminal (exceto correcao auditada). Aqui so o set; o guard de regra/autorizacao e' do controller/motor.
  Lanca em conflito de versao OU row inexistente (0 linhas afetadas)."
  [tx {:keys [id estado updated-by lock-version]}]
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.proposicoes
                         :set {:estado estado :updated_by updated-by :atualizado_em [:now]
                               :lock_version [:+ :lock_version 1]}
                         :where [:and [:= :id id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "conflito de escrita (lock_version desatualizado) ou proposicao inexistente"
                      {:id id :lock-version lock-version})))
    r))
