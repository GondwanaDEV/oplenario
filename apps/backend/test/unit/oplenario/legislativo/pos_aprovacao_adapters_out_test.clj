(ns oplenario.legislativo.pos-aprovacao-adapters-out-test
  "UNIT (puro, sem DB) — o gate adapters/out do POS-APROVACAO (Onda B Slice 7, F3.8a): autografo,
  tramitacao executiva e a composicao (mesmo estilo de documento-adapters-out-test)."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.adapters.out.autografo :as autografo-out]
            [oplenario.legislativo.adapters.out.pos-aprovacao :as pos-aprovacao-out]
            [oplenario.legislativo.adapters.out.tramitacao-executiva :as tramitacao-out]
            [oplenario.legislativo.wire.out.autografo :as wire-autografo]
            [oplenario.legislativo.wire.out.pos-aprovacao :as wire-pos-aprovacao]
            [oplenario.legislativo.wire.out.tramitacao-executiva :as wire-tramitacao]))

;; ---------- autografo->wire ----------

(defn- autografo-canonico []
  {:id (random-uuid) :ente-id (random-uuid) :proposicao-id (random-uuid) :numero 1 :ano 2026
   :texto-versao-id (random-uuid) :destinatario-texto "Prefeito Municipal de Fortaleza"
   :destinatario-id nil :enviado-em (java.time.Instant/parse "2026-06-18T00:00:00Z")
   :prazo-resposta-em nil})

(deftest autografo->wire-basico
  (let [out (autografo-out/autografo->wire (autografo-canonico))]
    (is (m/validate wire-autografo/AutografoOut out))
    (is (= 1 (:numero out)))
    (is (= "Prefeito Municipal de Fortaleza" (:destinatario-texto out)))
    (is (nil? (:prazo-resposta-em out)))))

(deftest autografo->wire-com-prazo-resposta-em
  (let [out (autografo-out/autografo->wire
              (assoc (autografo-canonico) :prazo-resposta-em (java.time.Instant/parse "2026-08-01T00:00:00Z")))]
    (is (m/validate wire-autografo/AutografoOut out))
    (is (= "2026-08-01T00:00:00Z" (:prazo-resposta-em out)))))

;; ---------- tramitacao-executiva->wire ----------

(defn- tramitacao-canonica [& {:keys [estado veto-tipo veto-razoes veto-votacao-id]
                                :or {estado "aguardando"}}]
  {:id (random-uuid) :ente-id (random-uuid) :autografo-id (random-uuid) :estado estado
   :veto-tipo veto-tipo :veto-razoes veto-razoes :veto-votacao-id veto-votacao-id
   :respondido-em nil :apreciado-em nil :lock-version 0})

(deftest tramitacao-executiva->wire-aguardando
  (let [out (tramitacao-out/tramitacao-executiva->wire (tramitacao-canonica))]
    (is (m/validate wire-tramitacao/TramitacaoExecutivaOut out))
    (is (= "aguardando" (:estado out)))
    (is (nil? (:veto-tipo out)))))

(deftest tramitacao-executiva->wire-vetado-com-tipo-e-razoes
  (let [out (tramitacao-out/tramitacao-executiva->wire
              (tramitacao-canonica :estado "vetado" :veto-tipo "total" :veto-razoes "Inconstitucional"))]
    (is (m/validate wire-tramitacao/TramitacaoExecutivaOut out))
    (is (= "vetado" (:estado out)))
    (is (= "total" (:veto-tipo out)))
    (is (= "Inconstitucional" (:veto-razoes out)))
    (is (nil? (:veto-votacao-id out)))))

(deftest tramitacao-executiva->wire-veto-derrubado-carrega-votacao
  (let [vid (random-uuid)
        out (tramitacao-out/tramitacao-executiva->wire
              (tramitacao-canonica :estado "veto_derrubado" :veto-tipo "total" :veto-votacao-id vid))]
    (is (m/validate wire-tramitacao/TramitacaoExecutivaOut out))
    (is (= (str vid) (:veto-votacao-id out)))))

;; ---------- pos-aprovacao->wire (composicao) ----------

(deftest pos-aprovacao->wire-sem-autografo-ainda
  (let [out (pos-aprovacao-out/pos-aprovacao->wire nil nil)]
    (is (m/validate wire-pos-aprovacao/PosAprovacaoOut out))
    (is (nil? (:autografo out)))
    (is (nil? (:tramitacao-executiva out)))))

(deftest pos-aprovacao->wire-com-autografo-e-tramitacao
  (let [aut-out (autografo-out/autografo->wire (autografo-canonico))
        tram-out (tramitacao-out/tramitacao-executiva->wire (tramitacao-canonica))
        out (pos-aprovacao-out/pos-aprovacao->wire aut-out tram-out)]
    (is (m/validate wire-pos-aprovacao/PosAprovacaoOut out))
    (is (= aut-out (:autografo out)))
    (is (= tram-out (:tramitacao-executiva out)))))
