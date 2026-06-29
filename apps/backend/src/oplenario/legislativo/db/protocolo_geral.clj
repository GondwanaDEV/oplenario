(ns oplenario.legislativo.db.protocolo-geral
  "Persistencia do PROTOCOLO GERAL (F3.9a) — o livro institucional, APPEND-ONLY puro (o trigger congela; nao
  se rasura). `protocolar!` e' atomico: numera gapless (kernel/sequencial, escopo 'protocolo_geral:ano' do
  ente da SESSAO, reinicio anual) + insere, na MESMA tx (rollback nao deixa buraco). Objeto POLIMORFICO
  (disc.2: objeto_tipo/objeto_id sem FK). Sobre a `tx` do tenant; HoneySQL schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.sequencial :as sequencial]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :numero :ano :objeto_tipo :objeto_id :sentido :assunto :interessado_texto :interessado_id
   :protocolado_em :protocolado_por])

(defn protocolar!
  "Protocola no livro geral: numera gapless (escopo 'protocolo_geral:ano') e insere — atomico na tx. O objeto
  e' polimorfico (objeto-tipo obrigatorio; objeto-id NULL quando o protocolo descreve papel externo). Devolve
  {:id :numero} (o numero so existe pos-commit). CARRY: protocolo de data historica (origem/origem_importado_em/
  protocolado_em via importacao_legado) nao e' parametrizado — fundacao #2 (staging), quando o fluxo existir."
  [tx {:keys [id ente-id ano objeto-tipo objeto-id sentido assunto interessado-texto interessado-id
              protocolado-por created-by]}]
  (let [num (sequencial/proximo! tx (str "protocolo_geral:" ano))]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :legislativo.protocolo_geral
                   :values [{:id id :ente_id ente-id :numero num :ano ano :objeto_tipo objeto-tipo
                             :objeto_id objeto-id :sentido sentido :assunto assunto
                             :interessado_texto interessado-texto :interessado_id interessado-id
                             :protocolado_por protocolado-por :created_by created-by :efetivado_em [:now]}]}))
    {:id id :numero num}))

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.protocolo_geral]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn buscar-por-objeto
  "Os protocolos de um objeto (disc.2). Em geral 1, mas a lista cobre re-protocolos (ex.: recebido + expedido).
  objeto-id nil = protocolos de papel externo sem objeto interno (IS NULL, nao `= NULL` que retornaria vazio)."
  [tx ente-id objeto-tipo objeto-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:legislativo.protocolo_geral]
                  :where (cond-> [:and [:= :ente_id ente-id] [:= :objeto_tipo objeto-tipo]]
                           (some? objeto-id) (conj [:= :objeto_id objeto-id])
                           (nil? objeto-id)  (conj [:is :objeto_id nil]))
                  :order-by [[:protocolado_em :asc]]}))))

(defn listar-por-ano
  "O livro do protocolo de um ano, ordenado por numero (a sequencia institucional do ente)."
  [tx ente-id ano]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select colunas :from [:legislativo.protocolo_geral]
                  :where [:and [:= :ente_id ente-id] [:= :ano ano]]
                  :order-by [[:numero :asc]]}))))
