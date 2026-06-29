(ns oplenario.legislativo.protocolo-geral-db-test
  "INTEGRACAO (PG real): F3.9a — Expediente / PROTOCOLO GERAL (§16.3, feature 3.23). O livro institucional:
  numerador UNICO gapless por ente/ano (reinicio anual) que protocola QUALQUER coisa — proposicao E documento
  administrativo (objeto POLIMORFICO, disc.2). APPEND-ONLY puro (o livro nao se rasura)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.protocolo-geral :as protocolo]
            [oplenario.legislativo.models.protocolo-geral :as mod]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- prot! [tx ente extra]
  (protocolo/protocolar! tx (merge {:id (random-uuid) :ente-id ente :ano 2026 :objeto-tipo "outro"
                                    :sentido "recebido" :assunto "Assunto generico"} extra)))

;; ---------- numeracao gapless + reinicio anual ----------

(deftest numera-gapless-com-reinicio-anual
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [a (prot! tx ente {}) b (prot! tx ente {}) c (prot! tx ente {})
              d (prot! tx ente {:ano 2027})]
          (is (= [1 2 3] [(:numero a) (:numero b) (:numero c)]) "gapless 1,2,3 em 2026")
          (is (= 1 (:numero d)) "2027 reinicia em 1 (escopo por ano)")
          (let [r (protocolo/buscar tx ente (:id a))]
            (is (= "Assunto generico" (:assunto r)))
            (is (m/validate mod/ProtocoloGeral r) "protocolo bate o model")))))))

;; ---------- objeto polimorfico (proposicao E papel externo) ----------

(deftest protocola-proposicao-e-documento-externo
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        ;; uma proposicao tem seu numero legislativo E um protocolo geral
        (let [pid (:id (prop/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                             :uf "CE" :municipio-nome "Fortaleza" :ementa "X"}))
              pg (prot! tx ente {:objeto-tipo "proposicao" :objeto-id pid :sentido "interno"
                                 :assunto "Protocolo do PL"})
              ;; um oficio recebido de papel: sem objeto interno (objeto_id NULL)
              of (prot! tx ente {:objeto-tipo "oficio_recebido" :objeto-id nil :sentido "recebido"
                                 :assunto "Oficio 10/2026 da Prefeitura" :interessado-texto "Prefeitura"})]
          (is (= "proposicao" (:objeto-tipo (protocolo/buscar tx ente (:id pg)))))
          (is (nil? (:objeto-id (protocolo/buscar tx ente (:id of)))) "papel externo sem objeto interno")
          (let [achados (protocolo/buscar-por-objeto tx ente "proposicao" pid)]
            (is (= 1 (count achados)) "1 protocolo da proposicao")
            (is (= (:id pg) (:id (first achados)))))
          ;; busca por objeto_id NULL (papel externo) usa IS NULL, nao `= NULL` (retornaria vazio)
          (let [externos (protocolo/buscar-por-objeto tx ente "oficio_recebido" nil)]
            (is (= 1 (count externos)) "papel externo (objeto_id NULL) retornado via IS NULL")
            (is (= (:id of) (:id (first externos))))))))))

;; ---------- append-only puro ----------

(deftest protocolo-append-only
  (let [ente (random-uuid) pid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (reset! pid (:id (prot! tx ente {})))))
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["UPDATE legislativo.protocolo_geral SET assunto = 'x' WHERE id = ?" @pid]))))
        "protocolo e' append-only (sem UPDATE — o livro nao se rasura)")
    (is (thrown? Exception
                 (tenancy/com-tenant* *ds* ente
                   (fn [tx] (jdbc/execute-one! tx ["DELETE FROM legislativo.protocolo_geral WHERE id = ?" @pid]))))
        "protocolo e' append-only (sem DELETE)")))

;; ---------- vocabularios ----------

(deftest vocabularios-invalidos-barram
  (let [ente (random-uuid)]
    (is (thrown? Exception (tenancy/com-tenant* *ds* ente (fn [tx] (prot! tx ente {:objeto-tipo "alienigena"}))))
        "objeto_tipo invalido barra (CHECK)")
    (is (thrown? Exception (tenancy/com-tenant* *ds* ente (fn [tx] (prot! tx ente {:sentido "lateral"}))))
        "sentido invalido barra (CHECK)")))

;; ---------- listagem do ano (o livro) ----------

(deftest lista-o-livro-do-ano
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (prot! tx ente {}) (prot! tx ente {}) (prot! tx ente {:ano 2027})
        (let [livro-2026 (protocolo/listar-por-ano tx ente 2026)]
          (is (= 2 (count livro-2026)) "2 protocolos em 2026")
          (is (= [1 2] (mapv :numero livro-2026)) "ordenado por numero"))))))
