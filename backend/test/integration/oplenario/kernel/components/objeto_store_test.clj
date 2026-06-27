(ns oplenario.kernel.components.objeto-store-test
  "Integracao: o objeto_store guarda/le/remove binarios num S3 real (MinIO Dockerizado).
  Roda com MINIO_ENDPOINT apontando p/ o MinIO de teste (local: host:9100)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.objeto-store :as objeto-store]))

(def ^:dynamic *store* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (objeto-store/objeto-store (config/carregar)))]
      (binding [*store* c] (try (t) (finally (component/stop c)))))))

(deftest guardar-obter-remover-round-trip
  (let [chave    (str "teste/" (random-uuid) ".txt")
        conteudo (.getBytes "ata da sessao de quarta" "UTF-8")]
    (is (= chave (objeto-store/guardar! *store* chave conteudo "text/plain")) "guardar devolve a chave")
    (is (= "ata da sessao de quarta" (String. ^bytes (objeto-store/obter *store* chave) "UTF-8"))
        "obter devolve o conteudo guardado")
    (objeto-store/remover! *store* chave)
    (is (nil? (objeto-store/obter *store* chave)) "apos remover, obter devolve nil")))

(deftest obter-chave-inexistente-devolve-nil
  (is (nil? (objeto-store/obter *store* (str "nao-existe/" (random-uuid)))) "chave ausente -> nil (nao excecao)"))
