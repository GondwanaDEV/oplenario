(ns oplenario.migracao-test
  "Integracao: o pipeline Migratus aplica as migrations 1–6 contra o Postgres real e cria os
  schemas-por-modulo (§22.10). Idempotente (migratus rastreia aplicadas)."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as ds]
            [oplenario.migracao :as migracao]
            [next.jdbc :as jdbc]))

(deftest migrar-cria-os-schemas-e-tabelas
  (let [c (component/start (ds/datasource (config/carregar)))]
    (try
      (migracao/migrar! (:ds c))
      (is (true? (:existe (jdbc/execute-one! (:ds c)
                            ["SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name=?) AS existe"
                             "legislativo"])))
          "mig 1 cria o schema legislativo")
      (is (true? (:existe (jdbc/execute-one! (:ds c)
                            ["SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=? AND table_name=?) AS existe"
                             "admin_sistema" "ente"])))
          "mig 3 cria admin_sistema.ente — prova que o pipeline aplica varias migrations")
      (finally (component/stop c)))))

(deftest comentario-de-presenca-parlamentar-tipo-documenta-o-vocabulario-real
  ;; A mig 0064 declarou `tipo text NOT NULL, -- presente|ausente|... (vocabulario de sessoes)`. Esse
  ;; comentario e' a FONTE DOCUMENTAL do bug corrigido na fatia 1 do carry I-5: 'presente'/'ausente' nao
  ;; existem em `sessoes.logic/tipos-evento-presenca` (entrada|saida|retorno|mudanca_modalidade) e a coluna
  ;; nao tem CHECK. A fatia 1 varreu o literal do `src/` mas deixou a DDL de pe' — o proximo dev que abrir a
  ;; migration para descobrir o dominio da coluna le o vocabulario ficticio e escreve `WHERE tipo = 'presente'`
  ;; de novo, num numero publico e nominal. Migrations aplicadas sao IMUTAVEIS: a correcao e' um
  ;; `COMMENT ON COLUMN` em migration NOVA, que passa a viver no CATALOGO (nao so' no .sql).
  (let [c (component/start (ds/datasource (config/carregar)))]
    (try
      (migracao/migrar! (:ds c))
      (let [comentario (:comentario
                        (jdbc/execute-one! (:ds c)
                          ["SELECT col_description('transparencia.presenca_parlamentar'::regclass,
                                                   (SELECT attnum FROM pg_attribute
                                                     WHERE attrelid = 'transparencia.presenca_parlamentar'::regclass
                                                       AND attname = 'tipo')) AS comentario"]))]
        (is (some? comentario)
            "a coluna `tipo` precisa de COMMENT no catalogo — o .sql da 0064 e' imutavel e mente")
        (is (re-find #"entrada" comentario)
            "o comentario declara o vocabulario REAL de sessoes.logic/tipos-evento-presenca")
        (is (not (re-find #"presente\|ausente" comentario))
            "e NUNCA o par ficticio 'presente|ausente', que produtor nenhum emite"))
      (finally (component/stop c)))))
