(ns oplenario.motor.db.template-compliance
  "Persistencia de 'motor.template_compliance' (B1 §22.7.6 — a DEFINICAO da regra). Tabela de DOMINIO
  (sem ente_id); HoneySQL schema-qualified, NAO e' port — e' a IMPL atras do RepoMotor (ADR-0001 §3-bis).
  Versionada por copia integral; INVALIDA nunca vira 'vigente' (Inv. 4). Resolucao 'por escopo'
  (dominio, chave_dominio) — federal usa chave_dominio NULL, tribunal_de_contas usa o codigo do TCE."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn inserir!
  "Grava uma DEFINICAO (ja type-checada VALIDA pelo verificador — o caller garante). jsonb p/
  forma_compilada/assinatura_parametros."
  [tx {:keys [id chave-template versao template-pai-id dominio chave-dominio descricao severidade
              referencia-normativa fonte-yaml forma-compilada assinatura-parametros
              registry-versao-ref estado-versao]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :motor.template_compliance
                 :values [{:id id :chave_template chave-template :versao versao
                           :template_pai_id template-pai-id :dominio dominio :chave_dominio chave-dominio
                           :descricao descricao :severidade severidade
                           :referencia_normativa referencia-normativa :fonte_yaml fonte-yaml
                           :forma_compilada (comum/->jsonb forma-compilada)
                           :assinatura_parametros (comum/->jsonb assinatura-parametros)
                           :registry_versao_ref registry-versao-ref :estado_versao estado-versao}]})))

(defn- linha->template [row]
  (when row
    (-> (comum/linha->kebab row)
        (update :forma-compilada comum/jsonb->kw)
        (update :assinatura-parametros comum/jsonb->kw))))

(defn vigentes-por-dominio
  "As DEFINICOES 'vigente' de um escopo (dominio, chave_dominio). chave_dominio NULL (federal) casa via
  COALESCE com a sentinela '' — NULL != NULL deixaria escapar o federal (a armadilha do index UNIQUE)."
  [tx dominio chave-dominio]
  (mapv linha->template
    (jdbc/execute! tx
      (sql/format {:select [:id :chave_template :versao :dominio :chave_dominio :severidade
                            :forma_compilada :assinatura_parametros :registry_versao_ref]
                   :from [:motor.template_compliance]
                   :where [:and [:= :estado_versao "vigente"] [:= :dominio dominio]
                           [:= [:coalesce :chave_dominio ""] [:coalesce chave-dominio ""]]]}))))

(defn por-chave-versao
  "A linha exata (chave_template, versao) — proveniencia/pin de versao (versao_fixada_id resolve aqui)."
  [tx chave-template versao]
  (linha->template
    (jdbc/execute-one! tx
      (sql/format {:select [:id :chave_template :versao :dominio :chave_dominio :severidade
                            :forma_compilada :assinatura_parametros :registry_versao_ref :estado_versao]
                   :from [:motor.template_compliance]
                   :where [:and [:= :chave_template chave-template] [:= :versao versao]]}))))
