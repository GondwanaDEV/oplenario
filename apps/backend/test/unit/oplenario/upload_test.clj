(ns oplenario.upload-test
  "UNIT (puro) — o upload multipart da borda (`oplenario.interceptors`): a leitura do `filename` do cabecalho da parte (linear,
  sem regex recursiva: um nome de 10 mil caracteres nao pode derrubar a pilha) e a dependencia declarada do parser."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [oplenario.interceptors :as it]))

(deftest filename-entre-aspas-com-escapes
  (is (= "folha 2025.pdf" (it/valor-do-filename "form-data; name=\"arquivo\"; filename=\"folha 2025.pdf\"")))
  (is (= "a\"b.pdf" (it/valor-do-filename "form-data; name=\"arquivo\"; filename=\"a\\\"b.pdf\"")) "\\\" vira aspas")
  (is (= "a\\b.pdf" (it/valor-do-filename "form-data; name=\"arquivo\"; filename=\"a\\\\b.pdf\"")) "\\\\ vira uma barra")
  (is (= "x;y.pdf" (it/valor-do-filename "form-data; name=\"arquivo\"; filename=\"x;y.pdf\"")) "ponto e virgula dentro das aspas e' do nome"))

(deftest filename-sem-aspas-e-ordem-dos-parametros
  (is (= "a.pdf" (it/valor-do-filename "form-data; name=arquivo; filename=a.pdf")))
  (is (= "a.pdf" (it/valor-do-filename "form-data; filename=\"a.pdf\"; name=\"arquivo\"")))
  (is (= "a.pdf" (it/valor-do-filename "form-data; name=\"arquivo\"; FILENAME = \"a.pdf\"")) "caixa e espacos")
  (is (nil? (it/valor-do-filename "form-data; name=\"campo\"")) "parte sem arquivo")
  (is (nil? (it/valor-do-filename "form-data; name=\"x\"; filename*=UTF-8''a.pdf")) "filename* (RFC 5987) nao e' filename")
  (is (= "ascii.pdf" (it/valor-do-filename "form-data; filename=\"ascii.pdf\"; filename*=UTF-8''utf8.pdf"))))

(deftest filename-longo-nao-estoura-a-pilha
  ;; a regex antiga `(?:[^"\\]|\\.)*` recursava por caractere; a leitura agora e' um laco
  (let [longo (apply str (repeat 8000 "a"))]
    (is (= (str longo ".pdf") (it/valor-do-filename (str "form-data; name=\"arquivo\"; filename=\"" longo ".pdf\""))))
    (is (= (apply str (repeat 2500 "a\"")) (it/valor-do-filename (str "form-data; filename=\"" (apply str (repeat 2500 "a\\\"")) "\""))))))

(deftest cabecalho-grande-demais-e-recusado
  (testing "acima do teto do cabecalho: ex-info (a borda responde 400), nunca StackOverflowError"
    (let [cd (str "form-data; name=\"arquivo\"; filename=\"" (apply str (repeat 10000 "a")) ".pdf\"")]
      (is (= :cabecalho/grande (:tipo (try (it/valor-do-filename cd) (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))

(deftest o-parser-do-upload-e-dependencia-declarada
  ;; o interceptor usa commons-fileupload2-core DIRETO; ela chegava so' como transitiva do ring-core. Declarada, uma
  ;; troca de ring-core nao a tira do classpath sem aviso.
  (let [deps (edn/read-string (slurp "deps.edn"))
        v (get-in deps [:deps 'org.apache.commons/commons-fileupload2-core :mvn/version])]
    (is (string? v) "commons-fileupload2-core declarada em deps.edn")
    (is (= "2.0.0-M1" v) "na versao que ja' resolvia (a subida de versao e' outra tarefa)")))

(deftest teto-de-envios-simultaneos
  (is (= 4 it/max-envios-simultaneos))
  (is (pos? it/max-partes-do-envio)))
