(ns oplenario.motor.db.calendario
  "Persistencia de 'motor.calendario_feriado' (B4 §22.7.6). Feriados nacional + municipal, lidos por
  proximo_dia_util/soma_dias_uteis. DOMINIO (sem ente_id); municipio_id cruza por guard p/
  cadastros.municipios (sem FK cross-schema). HoneySQL schema-qualified, IMPL atras do RepoMotor."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]))

(set! *warn-on-reflection* true)

(defn inserir!
  "Grava um feriado (nacional: municipio-id nil; municipal: o uuid do municipio). Carga semeada como
  DONO (sem ente_id; tabela de dominio) — moveis ja resolvidas para datas fixas na carga."
  [tx {:keys [id jurisdicao municipio-id data descricao]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :motor.calendario_feriado
                 :values [{:id id :jurisdicao jurisdicao :municipio_id municipio-id
                           :data data :descricao descricao}]})))

(defn feriados
  "Set de LocalDate dos feriados de uma jurisdicao (nacional: municipio-id nil; municipal: o uuid).
  Set p/ o lookup O(1) de util?/proximo_dia_util."
  [tx jurisdicao municipio-id]
  ;; coluna `date` ja volta como java.time.LocalDate (kernel/db-tipos ReadableColumn) — sem cast.
  (into #{}
        (map :calendario_feriado/data)
        (jdbc/execute! tx
          (sql/format {:select [:data] :from [:motor.calendario_feriado]
                       :where [:and [:= :jurisdicao jurisdicao]
                               [:= [:coalesce :municipio_id [:cast "00000000-0000-0000-0000-000000000000" :uuid]]
                                [:coalesce municipio-id [:cast "00000000-0000-0000-0000-000000000000" :uuid]]]]}))))
