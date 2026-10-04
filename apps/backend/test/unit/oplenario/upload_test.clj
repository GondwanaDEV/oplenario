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
  ;; troca de ring-core nao a tira do classpath sem aviso. A versao e' a que saiu da faixa do CVE-2025-48976 (< 2.0.0-M4)
  ;; e anda EM PAR com o ring-core (a API mudou entre milestones) — ver o comentario em deps.edn e a trava de
  ;; comportamento em `oplenario.multipart-limite-test`.
  (let [deps (edn/read-string (slurp "deps.edn"))
        v (get-in deps [:deps 'org.apache.commons/commons-fileupload2-core :mvn/version])]
    (is (string? v) "commons-fileupload2-core declarada em deps.edn")
    (is (= "2.0.0-M5" v) "trocar esta versao e' decisao: rodar a trava e os testes de upload antes")
    (is (= "1.15.5" (get-in deps [:deps 'ring/ring-core :mvn/version])) "o par do parser: ring-core declarado junto")))

(deftest teto-de-envios-simultaneos
  (is (= 4 it/max-envios-simultaneos))
  (is (pos? it/max-partes-do-envio)))

;; ---------------------------------------------------------------- o :enter do interceptor, sem HTTP

(def ^:private fronteira "XfronteiraX")

(defn- corpo-multipart ^bytes [nome conteudo]
  (.getBytes (str "--" fronteira "\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"" nome "\"\r\n"
                  "Content-Type: text/plain\r\n\r\n" conteudo "\r\n--" fronteira "--\r\n")
             "UTF-8"))

(defn- ctx-de-upload [quem ^java.io.InputStream corpo]
  {:request {:headers {"content-type" (str "multipart/form-data; boundary=" fronteira)}
             :ator {:identidade-id quem}
             :body corpo}})

(defn- vagas-livres [] (.availablePermits ^java.util.concurrent.Semaphore @#'it/vagas-de-envio))

(deftest um-envio-por-pessoa-por-vez
  ;; sem isto, uma pessoa so' abre `max-envios-simultaneos` conexoes lentas e ninguem mais anexa, em Casa nenhuma
  (let [{:keys [enter leave]} (it/anexo-multipart {:max-bytes 1024})
        ana (random-uuid) bia (random-uuid)
        em-voo (enter (ctx-de-upload ana (java.io.ByteArrayInputStream. (corpo-multipart "a.txt" "oi"))))]
    (is (= "a.txt" (get-in em-voo [:request :anexo :nome])) "o 1o envio da Ana passou e segue em voo (sem :leave)")
    (testing "o 2o envio da MESMA pessoa: 429 com Retry-After, sem ler o corpo e sem gastar vaga"
      (let [bytes (corpo-multipart "b.txt" "oi")
            corpo (java.io.ByteArrayInputStream. bytes)
            livres (vagas-livres)
            r (:response (enter (ctx-de-upload ana corpo)))]
        (is (= 429 (:status r)))
        (is (= "5" (get-in r [:headers "Retry-After"])))
        (is (= (alength bytes) (.available corpo)) "nenhum byte lido")
        (is (= livres (vagas-livres)))))
    (testing "outra pessoa envia normalmente enquanto isso"
      (let [c (enter (ctx-de-upload bia (java.io.ByteArrayInputStream. (corpo-multipart "c.txt" "oi"))))]
        (is (= "c.txt" (get-in c [:request :anexo :nome])))
        (leave c)))
    (testing "terminado o 1o envio, a mesma pessoa envia de novo"
      (leave em-voo)
      (let [c (enter (ctx-de-upload ana (java.io.ByteArrayInputStream. (corpo-multipart "d.txt" "oi"))))]
        (is (= "d.txt" (get-in c [:request :anexo :nome])))
        (leave c)))
    (is (= it/max-envios-simultaneos (vagas-livres)) "todas as vagas voltaram")))

(deftest erro-grave-na-leitura-devolve-a-vaga-e-a-pessoa
  ;; um Error (nao Exception) no meio da leitura: a vaga e a pessoa tem de voltar, senao depois de
  ;; `max-envios-simultaneos` erros todo upload responde 503 ate' reiniciar
  (let [{:keys [enter]} (it/anexo-multipart {:max-bytes 1024})
        quem (random-uuid)
        estoura (fn [] (proxy [java.io.InputStream] []
                         (read ([] (throw (OutOfMemoryError. "simulado")))
                               ([_b] (throw (OutOfMemoryError. "simulado")))
                               ([_b _off _len] (throw (OutOfMemoryError. "simulado"))))))]
    (dotimes [_ (inc it/max-envios-simultaneos)]
      (is (thrown? OutOfMemoryError (enter (ctx-de-upload quem (estoura))))
          "o erro sobe (nao vira 429 nem 503: nem a pessoa nem a vaga ficaram presas)"))
    (is (= it/max-envios-simultaneos (vagas-livres)))))
