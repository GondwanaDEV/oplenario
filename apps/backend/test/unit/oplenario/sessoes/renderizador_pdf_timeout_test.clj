(ns oplenario.sessoes.renderizador-pdf-timeout-test
  "UNIT — o TIMEOUT DE RENDERIZACAO do `RenderizadorPdf` (Etapa 5 fatia 5, obrigacao herdada da revisao de
  seguranca da fatia 3: sem prazo, um documento patologico pendura a conexao HTTP e o worker que a atende
  indefinidamente). `RenderizadorPdfComTimeout` decora o adapter real com um DEADLINE — testado com um
  delegate FAKE (nunca o openhtmltopdf real, que tornaria o teste lento e nao-deterministico de proposito)."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.sessoes.components.renderizador-pdf :as pdf]))

(def ^:private instante (java.time.Instant/parse "2026-06-20T18:00:00Z"))
(def ^:private html-bytes (.getBytes "<html></html>" "UTF-8"))

(defn- delegate-rapido []
  (reify pdf/RenderizadorPdf
    (renderizar [_ _html-bytes _instante] {:bytes (byte-array 3) :content-type "application/pdf"})))

(defn- delegate-lento [sleep-ms]
  (reify pdf/RenderizadorPdf
    (renderizar [_ _html-bytes _instante]
      (Thread/sleep ^long sleep-ms)
      {:bytes (byte-array 3) :content-type "application/pdf"})))

(defn- delegate-que-lanca [ex]
  (reify pdf/RenderizadorPdf
    (renderizar [_ _html-bytes _instante] (throw ex))))

;; ---------- o caso comum: nunca penaliza um render rapido ----------

(deftest render-rapido-passa-sob-o-timeout
  (let [decorado (pdf/renderizador-pdf-com-timeout (delegate-rapido) 200)]
    (is (= "application/pdf" (:content-type (pdf/renderizar decorado html-bytes instante))))))

;; ---------- o timeout disparando ----------

(deftest render-lento-estoura-o-timeout
  (let [decorado (pdf/renderizador-pdf-com-timeout (delegate-lento 500) 50)
        erro (try (pdf/renderizar decorado html-bytes instante) nil
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (some? erro) "o timeout tem de reprovar — se nao lancou, o guard esta furado")
    (is (= :servidor/timeout-renderizacao (:tipo (ex-data erro))))
    (is (= 50 (:timeout-ms (ex-data erro))))))

;; ---------- erro real do delegate: `deref` desembrulha, o comportamento de baixo nao muda ----------

(deftest excecao-do-delegate-propaga-desembrulhada
  (let [causa (ex-info "falha real do renderizador" {:tipo :servidor/erro})
        decorado (pdf/renderizador-pdf-com-timeout (delegate-que-lanca causa) 200)
        erro (try (pdf/renderizar decorado html-bytes instante) nil
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (identical? causa erro)
        "deref desembrulha ExecutionException — a excecao ORIGINAL do delegate atravessa, nunca envelopada")))
