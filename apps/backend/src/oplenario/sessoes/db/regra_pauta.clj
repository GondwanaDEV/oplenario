(ns oplenario.sessoes.db.regra-pauta
  "Persistencia da REGRA DA PAUTA da Casa (ADR-0019 fatia 3, mig 20260930000120): quem publica e a antecedencia minima.
  Uma linha por Casa (RLS isola); sem linha vale o padrao (`logic/regra-pauta-padrao`). HoneySQL schema-qualified;
  ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas [:ente_id :quem_publica :antecedencia_minima_horas :atualizada_por :atualizado_em])

(defn buscar
  "A linha da Casa, ou nil (nunca configurou)."
  [tx ente-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx (sql/format {:select colunas :from [:sessoes.regra_pauta] :where [:= :ente_id ente-id]}))))

(defn definir!
  "Grava a regra da Casa (upsert: a primeira vez insere, depois troca). Devolve a linha como ficou."
  [tx ente-id {:keys [quem-publica antecedencia-minima-horas atualizada-por]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :sessoes.regra_pauta
                 :values [{:ente_id ente-id :quem_publica quem-publica
                           :antecedencia_minima_horas antecedencia-minima-horas :atualizada_por atualizada-por}]
                 :on-conflict [:ente_id]
                 :do-update-set {:quem_publica :excluded.quem_publica
                                 :antecedencia_minima_horas :excluded.antecedencia_minima_horas
                                 :atualizada_por :excluded.atualizada_por
                                 :atualizado_em [:now]}}))
  (buscar tx ente-id))
