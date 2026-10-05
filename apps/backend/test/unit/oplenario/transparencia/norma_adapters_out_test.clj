(ns oplenario.transparencia.norma-adapters-out-test
  "UNIT (puro, sem DB) — mesmo achado IMPORTANTE de materia-adapters-out-test, sitio (c) da frente
  'truncamento-familia': `normas->wire` tinha `(or normas-total 0)`, desarmando a trava do schema."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.transparencia.adapters.out.norma :as adapters]))

(def ^:private norma
  {:norma-id (random-uuid) :proposicao-id (random-uuid) :tipo-norma "lei" :numero 12 :ano 2026
   :urn "urn:lex:br;ce;x:lei:2026;12" :ementa "Institui X" :publicado-em (java.time.Instant/parse "2026-03-10T15:00:00Z")
   :veiculo-publicacao "Diario Oficial"})

(deftest a-pagina-sai-com-total-pagina-e-tamanho
  (is (= {:normas-total 45 :pagina 3 :por-pagina 20}
         (select-keys (adapters/normas->wire {:normas [norma] :normas-total 45 :pagina 3 :por-pagina 20})
                      [:normas-total :pagina :por-pagina]))))

(deftest pagina-ou-tamanho-ausente-lanca-nao-vira-default
  (is (thrown? clojure.lang.ExceptionInfo (adapters/normas->wire {:normas [] :normas-total 0 :por-pagina 20})))
  (is (thrown? clojure.lang.ExceptionInfo (adapters/normas->wire {:normas [] :normas-total 0 :pagina 1}))))

(deftest normas-total-ausente-lanca-nao-vira-zero-silencioso
  (is (thrown? clojure.lang.ExceptionInfo (adapters/normas->wire {:normas [] :pagina 1 :por-pagina 20}))
      "sem :normas-total no mapa de dominio, o adapter tem de lancar (bug de servidor) — nao coagir a 0"))
