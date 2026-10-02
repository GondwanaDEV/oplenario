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

(deftest listar-recursivo-e-so-o-nivel
  ;; ADR-0018: o encerramento descobre as pastas `<pasta>/<ente>/` da Casa listando a raiz (nao-recursivo) e depois
  ;; cada pasta da Casa (recursivo)
  (let [raiz (str "teste-listar-" (random-uuid) "/")
        chaves [(str raiz "a/1.bin") (str raiz "a/sub/2.bin") (str raiz "b/3.bin") (str raiz "4.bin")]]
    (doseq [k chaves] (objeto-store/guardar! *store* k (.getBytes "x") "application/octet-stream"))
    (try
      (is (= (sort chaves) (objeto-store/listar *store* raiz true)) "recursivo: todos os blobs, em ordem")
      (is (= [(str raiz "4.bin") (str raiz "a/") (str raiz "b/")] (objeto-store/listar *store* raiz false))
          "so' o nivel: os blobs dele e as subpastas terminadas em /")
      (is (= [(str raiz "a/1.bin") (str raiz "a/sub/2.bin")] (objeto-store/listar *store* (str raiz "a/") true)))
      (is (some #{raiz} (objeto-store/listar *store* "" false)) "a raiz do bucket (sem prefixo) mostra a pasta")
      (is (= [] (objeto-store/listar *store* (str raiz "nada/") true)))
      (finally (doseq [k chaves] (objeto-store/remover! *store* k))))))
