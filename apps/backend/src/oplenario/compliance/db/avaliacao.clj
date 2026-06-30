(ns oplenario.compliance.db.avaliacao
  "Persistencia de 'compliance.compliance_avaliacao' (§22.7.7) — a prova de compliance APPEND-ONLY
  (Invariante 10): SO insert + select (sem UPDATE/DELETE; a mig 0009 nega o grant de UPDATE/DELETE e o
  caller nunca os emite). `obrigacao_id` e' NULL p/ regra continua / veredito 'inaplicavel'. `avaliado_em`
  fica a cargo do DEFAULT now() do banco (o instante real da avaliacao). HoneySQL schema-qualified."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :obrigacao_id :template_chave :registry_versao_ref :veredito :severidade
   :origem_avaliacao :detalhe :avaliado_em])

(defn registrar!
  "Audita uma avaliacao (append-only). `obrigacao-id`/`detalhe` opcionais (NULL p/ continua/inaplicavel).
  Devolve {:id}."
  [tx {:keys [id ente-id obrigacao-id template-chave registry-versao-ref veredito severidade
              origem-avaliacao detalhe]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :compliance.compliance_avaliacao
                 :values [{:id id :ente_id ente-id :obrigacao_id obrigacao-id :template_chave template-chave
                           :registry_versao_ref registry-versao-ref :veredito veredito
                           :severidade severidade :origem_avaliacao origem-avaliacao
                           :detalhe detalhe}]}))
  {:id id})

(defn listar-da-obrigacao
  "Historico de avaliacoes de UMA obrigacao em ordem cronologica (auditoria/prova). `obrigacao-id` NAO
  pode ser nil: `[:= :obrigacao_id nil]` viraria `IS NULL` e devolveria TODAS as avaliacoes de regra
  continua/inaplicavel do ente em silencio (review db MENOR-2)."
  [tx ente-id obrigacao-id]
  {:pre [(some? obrigacao-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:compliance.compliance_avaliacao]
                  :where [:and [:= :ente_id ente-id] [:= :obrigacao_id obrigacao-id]]
                  :order-by [[:avaliado_em :asc] [:id :asc]]}))))

(defn ultima-da-obrigacao
  "A avaliacao mais recente de UMA obrigacao (nil se ainda nao avaliada). O sweep a usa p/ carregar a
  `severidade` da regra + o `registry_versao_ref` na audita do vencimento (a obrigacao nao guarda esses
  campos; a ultima avaliacao e' a fonte fiel). NOTA: o tiebreaker `id DESC` (UUID aleatorio) e' arbitrario
  num empate de `avaliado_em` (= now() = inicio da tx, constante dentro de uma tx); irrelevante aqui pois
  esses campos da regra sao estaveis entre avaliacoes proximas. Backward index scan no idx (..., avaliado_em)."
  [tx ente-id obrigacao-id]
  {:pre [(some? obrigacao-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:compliance.compliance_avaliacao]
                  :where [:and [:= :ente_id ente-id] [:= :obrigacao_id obrigacao-id]]
                  :order-by [[:avaliado_em :desc] [:id :desc]] :limit 1}))))

(defn ultima-do-template
  "A avaliacao mais recente de um template (regra continua / painel). `obrigacao_id` pode ser NULL."
  [tx ente-id template-chave]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:compliance.compliance_avaliacao]
                  :where [:and [:= :ente_id ente-id] [:= :template_chave template-chave]]
                  :order-by [[:avaliado_em :desc] [:id :desc]] :limit 1}))))
