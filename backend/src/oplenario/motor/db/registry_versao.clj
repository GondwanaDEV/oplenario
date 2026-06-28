(ns oplenario.motor.db.registry-versao
  "Persistencia de 'motor.registry_catalogo_versao' (B3 §22.7.6 — log de versao do catalogo). Da
  referente ao registry_versao_ref carimbado em template_compliance e dirige o passe de re-validacao no
  deploy quando uma assinatura muda. DOMINIO (sem ente_id). HoneySQL schema-qualified, IMPL atras do RepoMotor."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn inserir!
  "Registra uma versao do catalogo. Idempotente (ON CONFLICT em versao) — re-deploy do mesmo catalogo
  nao duplica nem falha."
  [tx {:keys [id versao hash descricao]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :motor.registry_catalogo_versao
                 :values [{:id id :versao versao :hash hash :descricao descricao}]
                 :on-conflict [:versao] :do-nothing true})))

(defn por-versao
  "A linha do catalogo por `versao` (string @data) — nil se nao registrada. Resolve a proveniencia do
  registry_versao_ref carimbado em template_compliance."
  [tx versao]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :versao :hash :descricao]
                   :from [:motor.registry_catalogo_versao] :where [:= :versao versao]}))))
