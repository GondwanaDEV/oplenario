(ns oplenario.kernel.components.objeto-store
  "Component do object store: client S3 (MinIO SDK — fala com QUALQUER endpoint S3-compativel, §22.9).
  start cria o client + garante o bucket; implementa kernel.port.objeto-store. NAO seto
  *warn-on-reflection* aqui de proposito: o SDK e' fluent-builder (hint em cada passo deixaria
  ilegivel) e tudo aqui e' I/O dominado por rede — a reflexao e' negligivel."
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [oplenario.kernel.port.objeto-store :as port])
  (:import (io.minio MinioClient PutObjectArgs GetObjectArgs RemoveObjectArgs
                     MakeBucketArgs BucketExistsArgs)
           (io.minio.errors ErrorResponseException)
           (java.io ByteArrayInputStream)))

(defrecord ObjetoStoreS3 [config client bucket]
  component/Lifecycle
  (start [this]
    (if client
      this
      (let [{:keys [endpoint access-key secret-key bucket]} (:objeto-store config)
            c (-> (MinioClient/builder)
                  (.endpoint ^String endpoint)
                  (.credentials access-key secret-key)
                  (.build))]
        (when-not (.bucketExists c (-> (BucketExistsArgs/builder) (.bucket bucket) (.build)))
          (.makeBucket c (-> (MakeBucketArgs/builder) (.bucket bucket) (.build))))
        (assoc this :client c :bucket bucket))))
  (stop [this]
    (when client                                  ; MinioClient e' AutoCloseable (libera o pool OkHttp)
      (try (.close client) (catch Exception _ nil)))
    (assoc this :client nil :bucket nil))

  port/ObjetoStore
  (guardar! [_ chave bytes content-type]
    ;; ByteArrayInputStream nao precisa close (close() e' no-op); o SDK le sincrono antes de retornar.
    (.putObject client
                (-> (PutObjectArgs/builder)
                    (.bucket bucket) (.object chave)
                    (.stream (ByteArrayInputStream. bytes) (long (alength bytes)) -1)
                    (.contentType content-type)
                    (.build)))
    chave)
  (obter [_ chave]
    (try
      (with-open [is (.getObject client (-> (GetObjectArgs/builder) (.bucket bucket) (.object chave) (.build)))]
        (.readAllBytes is))
      (catch ErrorResponseException e
        (if (= "NoSuchKey" (some-> e .errorResponse .code))
          nil
          (do (log/error e "objeto-store/obter: erro S3" {:chave chave :code (some-> e .errorResponse .code)})
              (throw e))))))
  (remover! [_ chave]
    (.removeObject client (-> (RemoveObjectArgs/builder) (.bucket bucket) (.object chave) (.build)))
    chave))

(defn objeto-store
  "Cria o Component do object store a partir do config (:objeto-store{:endpoint :access-key :secret-key :bucket})."
  [config]
  (map->ObjetoStoreS3 {:config config}))
