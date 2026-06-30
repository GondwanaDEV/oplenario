(ns oplenario.kernel.components.objeto-store
  "Object store S3-compativel (§22.9: audio de sessao, anexos, artefatos de remessa, PII pre-filtro).
  PROTOCOLO + RECORD co-localizados (sem pasta port/): o protocolo ObjetoStore e' o contrato trocavel;
  ObjetoStoreS3 (MinIO SDK) e' a impl, fala com QUALQUER endpoint S3-compativel. Stuart Sierra Component:
  start cria o client + garante o bucket; stop fecha. NAO seto *warn-on-reflection* de proposito: o SDK
  e' fluent-builder (hint em cada passo deixaria ilegivel) e tudo aqui e' I/O dominado por rede."
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log])
  (:import (io.minio MinioClient PutObjectArgs GetObjectArgs RemoveObjectArgs
                     MakeBucketArgs BucketExistsArgs)
           (io.minio.errors ErrorResponseException)
           (java.io ByteArrayInputStream InputStream)))

(def ^:private ^:const part-size-bytes
  "Tamanho de parte do upload multipart de tamanho DESCONHECIDO (objectSize=-1): o minimo S3 (5 MiB). Com
  isso o stream e' enviado em partes sem materializar o arquivo inteiro em heap (§22.6 gravacao: media pode
  ter centenas de MB)."
  (* 5 1024 1024))

(defprotocol ObjetoStore
  (guardar! [this chave bytes content-type] "Guarda o blob (byte-array) sob `chave`; devolve a chave.")
  (guardar-stream! [this chave in content-type]
    "Transmite o InputStream `in` (tamanho desconhecido) ao store em partes, sem bufferizar tudo em heap;
     devolve a chave. O chamador e' dono do ciclo de vida de `in` (fecha apos).")
  (obter    [this chave] "Devolve os bytes do blob (byte-array), ou nil se ausente.")
  (remover! [this chave] "Remove o blob da `chave`."))

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

  ObjetoStore
  (guardar! [_ chave bytes content-type]
    ;; ByteArrayInputStream nao precisa close (close() e' no-op); o SDK le sincrono antes de retornar.
    (.putObject client
                (-> (PutObjectArgs/builder)
                    (.bucket bucket) (.object chave)
                    (.stream (ByteArrayInputStream. bytes) (long (alength bytes)) -1)
                    (.contentType content-type)
                    (.build)))
    chave)
  (guardar-stream! [_ chave in content-type]
    (.putObject client
                (-> (PutObjectArgs/builder)
                    (.bucket bucket) (.object chave)
                    ;; objectSize=-1 + partSize -> upload multipart em streaming (sem heap p/ o arquivo todo)
                    (.stream ^InputStream in -1 (long part-size-bytes))
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
