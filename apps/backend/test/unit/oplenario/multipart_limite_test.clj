(ns oplenario.multipart-limite-test
  "Trava de dependencia (gate de CI): CVE-2025-48976 / GHSA-vv7r-c36w-3prj — o commons-fileupload2-core da 2.0.0-M1 a
  2.0.0-M3 aceita ate' 10 KB de cabecalho POR PARTE do multipart, sem teto configuravel (alocacao sem limite
  suficiente = DoS); a 2.0.0-M4 fechou em 512 bytes por parte. O parser nao e' escolha nossa a cada upload: ele vem
  no classpath (ring-core, por baixo do Pedestal) e TODO upload passa por ele — os anexos dos comunicados, os
  documentos das contas. Aqui a versao vira regra de build: uma troca de dependencia que rebaixe o parser reprova
  NESTE teste, e nao numa revisao de seguranca feita de memoria.

  O caso positivo nao e' enfeite: ring-core e commons-fileupload2-core so' funcionam em PAR (ring-core 1.12.0 com a M4
  morre em `AbstractMethodError` no primeiro upload). Sem ele, um parser quebrado para tudo passaria por \"recusou\"."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ring.middleware.multipart-params :as multipart])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream InputStream)))

(def ^:private fronteira "----oplenario-trava")

(defn- requisicao
  "Uma requisicao Ring `multipart/form-data` com UMA parte de arquivo e os `cabecalhos-extras` nessa parte."
  [cabecalhos-extras]
  (let [out (ByteArrayOutputStream.)
        cabecalhos (into ["Content-Disposition: form-data; name=\"arquivo\"; filename=\"oficio.pdf\""
                          "Content-Type: application/pdf"]
                         cabecalhos-extras)]
    (.write out (.getBytes (str "--" fronteira "\r\n" (str/join "\r\n" cabecalhos) "\r\n\r\n%PDF-1.7\r\n"
                                "--" fronteira "--\r\n")
                           "UTF-8"))
    (let [corpo (.toByteArray out)
          tipo (str "multipart/form-data; boundary=" fronteira)]
      {:request-method :post
       :headers {"content-type" tipo "content-length" (str (alength corpo))}
       :content-type tipo
       :content-length (alength corpo)
       :body (ByteArrayInputStream. corpo)})))

(defn- ler
  "O que o parser entrega para a parte `arquivo`, pelo `multipart-params-request` do Ring: a biblioteca com o teto PADRAO
  (512 bytes). Nenhuma rota nossa le upload por aqui (todas usam `it/anexo-multipart`, que fixa 2048 — `upload-test`); o
  Ring e' so' o jeito mais curto de medir qual versao do parser esta' no classpath."
  [req]
  (get-in (multipart/multipart-params-request
           req {:store (fn [{:keys [filename content-type stream]}]
                         {:nome filename :tipo-midia content-type
                          :bytes (alength (.readAllBytes ^InputStream stream))})})
          [:multipart-params "arquivo"]))

(deftest upload-comum-passa-pelo-parser
  (is (= {:nome "oficio.pdf" :tipo-midia "application/pdf" :bytes 8}
         (ler (requisicao [])))))

(deftest cabecalho-de-parte-acima-do-teto-e-recusado
  ;; 4 KB num cabecalho da parte: abaixo dos 10 KB que a faixa vulneravel (M1..M3) aceita, muito acima dos 512 bytes
  ;; da correcao. Se este `is` reprovar com "expected: thrown", o parser no classpath voltou para a faixa do CVE.
  (is (thrown-with-msg? Exception #"Header section has more than 512 bytes"
                        (ler (requisicao [(str "X-Preenchimento: " (apply str (repeat 4096 "a")))])))))
