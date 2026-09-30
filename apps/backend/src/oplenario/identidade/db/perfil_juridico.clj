(ns oplenario.identidade.db.perfil-juridico
  "Persistencia TENANT (FORCE RLS) do PERFIL do papel `juridico` (ADR-0019, mig `identidade-perfil-juridico`): a
  qualificacao e a OAB do servidor, um perfil por (Casa, identidade). Funcoes sobre a `tx` do tenant."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn salvar!
  "UPSERT do perfil por (ente, identidade): reconceder o papel atualiza qualificacao e OAB (o `criado_em` fica)."
  [tx ente-id identidade-id {:keys [qualificacao oab]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :identidade.perfil_juridico
                 :values [{:ente_id ente-id :identidade_id identidade-id
                           :qualificacao qualificacao :oab oab}]
                 :on-conflict [:ente_id :identidade_id]
                 :do-update-set {:qualificacao :excluded.qualificacao
                                 :oab :excluded.oab
                                 :atualizado_em [:now]}}))
  nil)

(defn buscar
  "O perfil da identidade NESTA Casa ({:qualificacao :oab}) ou nil."
  [tx ente-id identidade-id]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:qualificacao :oab]
                   :from [:identidade.perfil_juridico]
                   :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id]]}))))
