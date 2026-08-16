(ns oplenario.sessoes.renderizador-pdf-timeout-test
  "UNIT — o TIMEOUT DE RENDERIZACAO do `RenderizadorPdf` (Etapa 5 fatia 5, obrigacao herdada da revisao de
  seguranca da fatia 3: sem prazo, um documento patologico pendura a conexao HTTP e o worker que a atende
  indefinidamente). `RenderizadorPdfGuardado` decora o adapter real com TRES guardas — DEADLINE, TETO DE
  SAIDA e POOL DEDICADO — testado com um delegate FAKE (nunca o openhtmltopdf real, que tornaria o teste
  lento e nao-deterministico de proposito)."
  (:require [clojure.string]
            [clojure.test :refer [deftest is]]
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
  (let [decorado (pdf/renderizador-pdf-guardado (delegate-rapido) 200)]
    (is (= "application/pdf" (:content-type (pdf/renderizar decorado html-bytes instante))))))

;; ---------- o timeout disparando ----------

(deftest render-lento-estoura-o-timeout
  (let [decorado (pdf/renderizador-pdf-guardado (delegate-lento 500) 50)
        erro (try (pdf/renderizar decorado html-bytes instante) nil
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (some? erro) "o timeout tem de reprovar — se nao lancou, o guard esta furado")
    (is (= :servidor/timeout-renderizacao (:tipo (ex-data erro))))
    (is (= 50 (:timeout-ms (ex-data erro))))))

;; ---------- erro real do delegate: `deref` desembrulha, o comportamento de baixo nao muda ----------

(deftest excecao-do-delegate-propaga-desembrulhada
  (let [causa (ex-info "falha real do renderizador" {:tipo :servidor/erro})
        decorado (pdf/renderizador-pdf-guardado (delegate-que-lanca causa) 200)
        erro (try (pdf/renderizar decorado html-bytes instante) nil
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (identical? causa erro)
        "deref desembrulha ExecutionException — a excecao ORIGINAL do delegate atravessa, nunca envelopada")))

;; ---------- o TETO DE SAIDA (achado da revisao adversarial da fatia 5: o teto era ASSIMETRICO) ----------
;; O teto de 5 MiB de `serializador-folha` barra o HTML de ENTRADA. Nada garantia que o PDF de SAIDA
;; ficasse sob teto nenhum — o tamanho de saida do openhtmltopdf nao e' linear no tamanho da entrada. E o
;; PDF gravado (de tamanho nao verificado) e' depois SERVIDO pela rota de leitura, que le' o blob inteiro em
;; heap. O teto de saida fecha o ciclo: nada entra no objeto_store sem ter passado por uma medida.

(defn- delegate-que-devolve-n-bytes [n]
  (reify pdf/RenderizadorPdf
    (renderizar [_ _html-bytes _instante] {:bytes (byte-array ^long n) :content-type "application/pdf"})))

(deftest pdf-acima-do-teto-de-saida-e-recusado
  (let [decorado (pdf/renderizador-pdf-guardado (delegate-que-devolve-n-bytes 101) 5000 100)
        erro (try (pdf/renderizar decorado html-bytes instante) nil
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (some? erro) "PDF acima do teto tem de reprovar ANTES do INSERT/objeto_store")
    (is (= :validacao/documento-grande (:tipo (ex-data erro)))
        "MESMO :tipo do teto de HTML — a borda ja' o traduz para 413, sem ramo novo")
    (is (= 101 (:tamanho-bytes (ex-data erro))))
    (is (= 100 (:teto-bytes (ex-data erro))))))

(deftest pdf-exatamente-no-teto-passa
  (let [decorado (pdf/renderizador-pdf-guardado (delegate-que-devolve-n-bytes 100) 5000 100)]
    (is (= 100 (alength ^bytes (:bytes (pdf/renderizar decorado html-bytes instante))))
        "o teto e' '>', nunca '>=' — um documento exatamente no limite e' legitimo")))

(deftest teto-de-saida-default-e-o-do-componente
  (let [decorado (pdf/renderizador-pdf-guardado (delegate-que-devolve-n-bytes 1024) 5000)]
    (is (= 1024 (alength ^bytes (:bytes (pdf/renderizar decorado html-bytes instante))))
        "a aridade-2 herda `teto-bytes-pdf` — nenhum call-site fica SEM teto por omissao")
    (is (pos? pdf/teto-bytes-pdf))))

;; ---------- o POOL DEDICADO (achado da revisao adversarial: o timeout nao limitava CPU) ----------
;; O timeout limita a LATENCIA da resposta, nao o consumo. No estouro, `.get` lanca e o handler responde —
;; mas a renderizacao continua rodando ate' terminar sozinha (`future-cancel` e' melhor-esforco, o
;; openhtmltopdf pode nao responder a interrupcao no meio do layout). Rodando no `Agent/soloExecutor` — o
;; pool ILIMITADO e COMPARTILHADO por todo `future`/`send-off` da JVM — renders zumbis se acumulam sem teto
;; e competem com qualquer outro trabalho assincrono do processo. Pool proprio e LIMITADO poe teto nisso.

(defn- thread-do-render []
  (reify pdf/RenderizadorPdf
    (renderizar [_ _html-bytes _instante]
      {:bytes (.getBytes (.getName (Thread/currentThread)) "UTF-8") :content-type "application/pdf"})))

(deftest render-nao-roda-no-pool-compartilhado-da-jvm
  ;; REPROVA o codigo anterior: com `future`, o nome da thread era `clojure-agent-send-off-pool-N`.
  (let [decorado (pdf/renderizador-pdf-guardado (thread-do-render) 5000)
        nome (String. ^bytes (:bytes (pdf/renderizar decorado html-bytes instante)) "UTF-8")]
    (is (not (clojure.string/includes? nome "clojure-agent-send-off-pool"))
        "renderizacao NUNCA no pool compartilhado de agents da JVM")
    (is (clojure.string/starts-with? nome "folha-pdf-")
        "roda no pool PROPRIO e limitado do renderizador")))

(deftest pool-saturado-recusa-em-vez-de-crescer-sem-teto
  ;; Executor de 1 thread e fila ZERO (SynchronousQueue): o primeiro render prende a unica thread num latch,
  ;; o segundo NAO TEM ONDE RODAR. Com o pool ilimitado anterior isso nunca acontecia — crescia.
  (let [porta (java.util.concurrent.CountDownLatch. 1)
        ex (java.util.concurrent.ThreadPoolExecutor.
            1 1 0 java.util.concurrent.TimeUnit/MILLISECONDS
            (java.util.concurrent.SynchronousQueue.))
        preso (reify pdf/RenderizadorPdf
                (renderizar [_ _h _i]
                  (.await porta)
                  {:bytes (byte-array 3) :content-type "application/pdf"}))
        decorado (pdf/renderizador-pdf-guardado preso 5000 1024 ex)
        ocupando (future (try (pdf/renderizar decorado html-bytes instante) (catch Exception e e)))]
    (try
      ;; espera a unica thread do pool ser de fato ocupada
      (loop [n 0] (when (and (< n 200) (zero? (.getActiveCount ex))) (Thread/sleep 10) (recur (inc n))))
      (let [erro (try (pdf/renderizar decorado html-bytes instante) nil
                      (catch clojure.lang.ExceptionInfo e e))]
        (is (some? erro) "pool saturado tem de RECUSAR — se nao lancou, nao ha teto de concorrencia")
        (is (= :servidor/renderizador-saturado (:tipo (ex-data erro)))))
      (finally
        (.countDown porta)
        @ocupando
        (.shutdownNow ex)))))
