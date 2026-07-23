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

(deftest comentarios-de-sessao-com-chamada-declaram-a-semantica-real-da-coluna-data
  ;; Mesma armadilha da 0066, um commit depois e na mesma familia de tabelas (revisao da fatia 5 do carry
  ;; I-5). `transparencia.sessao_com_chamada.data` NAO e' "a data da sessao": e' (a) a data civil do PRIMEIRO
  ;; evento de presenca, para a linha projetada AO VIVO, e (b) a do PRIMEIRO dos ULTIMOS eventos POR VEREADOR
  ;; para a linha vinda do backfill/reconciliador — `presenca_parlamentar` guarda ESTADO por (sessao,
  ;; vereador), nao log, e o log so' existe em `sessoes.presenca_evento` (JOIN cross-schema proibido). Essa
  ;; semantica vivia so' em prosa no `.sql` e na docstring: `\d+` mostrava `data | date | not null` e mais
  ;; nada. E' a coluna que a fatia 6 usa como PREDICADO da janela de mandato num numero publico e nominal —
  ;; o dominio dela tem de viver no CATALOGO, que e' o que o proximo dev abre.
  (let [c (component/start (ds/datasource (config/carregar)))]
    (try
      (migracao/migrar! (:ds c))
      (let [tabela (:comentario
                    (jdbc/execute-one! (:ds c)
                      ["SELECT obj_description('transparencia.sessao_com_chamada'::regclass, 'pg_class') AS comentario"]))
            coluna (:comentario
                    (jdbc/execute-one! (:ds c)
                      ["SELECT col_description('transparencia.sessao_com_chamada'::regclass,
                                               (SELECT attnum FROM pg_attribute
                                                 WHERE attrelid = 'transparencia.sessao_com_chamada'::regclass
                                                   AND attname = 'data')) AS comentario"]))]
        (is (some? tabela) "a TABELA precisa de COMMENT: 'sessao com chamada' nao e' 'sessao realizada'")
        (is (re-find #"NAO e' \"sessao realizada\"" tabela)
            "a confusao cara e' essa — sessao sem nenhum check-in some dos DOIS lados da fracao")
        (is (some? coluna) "a coluna `data` precisa de COMMENT — o .sql da 0067 e' imutavel e afirma so' o caso vivo")
        (is (re-find #"PRIMEIRO evento" coluna) "declara a semantica do caminho VIVO")
        (is (re-find #"ULTIMOS eventos" coluna)
            "e declara, em voz alta, que a linha BACKFILLADA/RECONCILIADA tem semantica DIFERENTE e pode ser posterior"))
      (finally (component/stop c)))))
