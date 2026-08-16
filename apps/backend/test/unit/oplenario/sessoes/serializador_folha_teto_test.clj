(ns oplenario.sessoes.serializador-folha-teto-test
  "UNIT (puro) — o TETO DE TAMANHO do `SerializadorFolha` (Etapa 5 fatia 5, obrigacao herdada da revisao de
  seguranca da fatia 3: sem teto, uma sessao com dado inflado vira DoS por payload contra o renderizador de
  PDF a jusante). `SerializadorFolhaHtmlComTeto` decora o adapter real e recusa ANTES do PDF."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.sessoes.components.serializador-folha :as ser]))

(def ^:private sid #uuid "00000000-0000-0000-0000-0000000000c1")
(def ^:private instante (java.time.Instant/parse "2026-06-20T18:00:00Z"))

(def ^:private cabecalho-ok
  {:nome-oficial "Camara Municipal de Fortaleza" :nome-curto "CMF"
   :legislatura-numero 19 :legislatura-ano-inicio 2025 :legislatura-ano-fim 2028})

(def ^:private quorum-ok
  {:presentes-plenario 0 :presentes-remoto 0 :presentes-total 0 :membros-da-casa 0
   :presencas-fora-do-roster 0})

(def ^:private documento-minimo
  {:spec-versao "folha-sessao-v1"
   :sessao {:id sid :estado "encerrada" :motivo-nao-realizada nil}
   :instante instante
   :cabecalho-da-casa cabecalho-ok
   :linhas []
   :quorum quorum-ok
   :serie {}
   :justificativas []
   :atos-de-chamada-conduzida []})

;; ---------- o caso comum: nunca reprova documento legitimo ----------

(deftest documento-legitimo-sob-o-teto-passa
  (let [decorado (ser/serializador-folha-html-com-teto)
        saida (ser/serializar decorado documento-minimo 1)]
    (is (pos? (alength ^bytes (:bytes saida))))
    (is (= "text/html; charset=utf-8" (:content-type saida)))))

(deftest documento-legitimo-sob-o-teto-passa-aridade-2
  ;; a aridade-2 (pre-visualizacao, sem versao — `dev/folha_preview.clj`) tambem precisa continuar
  ;; delegando corretamente atraves do decorator.
  (let [decorado (ser/serializador-folha-html-com-teto)
        saida (ser/serializar decorado documento-minimo)]
    (is (pos? (alength ^bytes (:bytes saida))))))

;; ---------- o teto reprovando ----------

(deftest teto-pequeno-reprova-o-mesmo-documento
  ;; O documento MINIMO ja produz varios KiB de HTML (o CSS inline sozinho pesa ~22 KiB) — um teto de 100
  ;; bytes reprova QUALQUER folha real, provando que o guard dispara sem depender de gerar megabytes de
  ;; dado na massa de teste.
  (let [decorado (ser/serializador-folha-html-com-teto (ser/serializador-folha-html) 100)
        erro (try (ser/serializar decorado documento-minimo 1) nil
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (some? erro) "o teto tem de reprovar — se nao lancou, o guard esta furado")
    (is (= :validacao/documento-grande (:tipo (ex-data erro))))
    (is (> (:tamanho-bytes (ex-data erro)) 100))
    (is (= 100 (:teto-bytes (ex-data erro))))))

(deftest teto-generoso-nao-reprova
  (let [decorado (ser/serializador-folha-html-com-teto (ser/serializador-folha-html) (* 5 1024 1024))]
    (is (some? (ser/serializar decorado documento-minimo 1))
        "o teto default (5 MiB) e' generoso o bastante para nunca reprovar um documento minimo")))

(deftest teto-nao-consome-o-delegate-quando-reprova
  ;; documenta o CONTRATO: o delegate roda ate' o fim (a checagem e' PODO-render, sobre o HTML ja pronto) —
  ;; o teto barra a ENTREGA ao chamador (e por extensao ao PDF), nao a serializacao em si.
  (let [chamadas (atom 0)
        contador (reify ser/SerializadorFolha
                   (serializar [_ documento] (ser/serializar (ser/serializador-folha-html) documento))
                   (serializar [_ documento versao]
                     (swap! chamadas inc)
                     (ser/serializar (ser/serializador-folha-html) documento versao)))
        decorado (ser/serializador-folha-html-com-teto contador 100)]
    (is (thrown? clojure.lang.ExceptionInfo (ser/serializar decorado documento-minimo 1)))
    (is (= 1 @chamadas))))
