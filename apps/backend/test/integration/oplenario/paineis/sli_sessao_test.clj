(ns oplenario.paineis.sli-sessao-test
  "INTEGRACAO (PG real) — F7 E3: a PROJECAO do SLI de janela de sessao (Inv.9). `sessoes` (F4) EMITE
  `sessao.transicionou` (agora com :ocorrido-em, o instante real da transicao); o relay DRENA e despacha ao
  consumer de paineis, que faz UPSERT em `paineis.sli_sessao` (mig 0051) — sem import/JOIN cross-modulo
  (§22.10). Este teste emite os eventos DIRETO no outbox (o payload casa o contrato real de sessoes), drena, e
  le' pela leitura interna (Repo). Prova a janela (aberta_em->encerrada_em), preservacao de aberta_em atraves
  de suspensao, RLS (isolamento cross-tenant), o gate de monotonicidade (transicao fora de ordem = no-op) e a
  idempotencia (redrive do mesmo evento = no-op)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.components.repositorio :as repo]
            [oplenario.paineis.diplomat.consumers :as consumers])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo/->RepoPaineisPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- drena-eventos! []
  (outbox/drenar! *ds* (consumers/registrar {})))

(defn- emitir-transicao! [ente sid de para ocorrido-em]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (eventos/emitir! (outbox/bus) tx
               (eventos/evento "sessao.transicionou" ente
                 {:sessao-id (str sid) :de de :para para :ocorrido-em ocorrido-em})))))

(defn- emitir-agendamento! [ente sid agendada-para ocorrido-em]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (eventos/emitir! (outbox/bus) tx
               (eventos/evento "sessao.agendada" ente
                 {:sessao-id (str sid) :agendada-para agendada-para :ocorrido-em ocorrido-em})))))

(defn- sli-de [ente sid]
  (first (filter #(= sid (:sessao-id %)) (repo/sli-sessoes *repo* ente))))

;; ---------- F7 E3 carry: o agendamento materializa a linha JA' no nascimento (no-show visivel) ----------

(deftest agendamento-materializa-linha-agendada
  (let [ente (random-uuid) sid (random-uuid)]
    (emitir-agendamento! ente sid "2026-07-08T13:00:00Z" "2026-07-01T10:00:00Z")
    (drena-eventos!)
    (let [s (sli-de ente sid)]
      (is (some? s) "a sessao aparece no SLI JA' no agendamento (fecha a cegueira ao no-show)")
      (is (= "agendada" (:estado-atual s)))
      (is (= (Instant/parse "2026-07-08T13:00:00Z") (:agendada-para s)) "carimba agendada_para (p/ detectar no-show)")
      (is (nil? (:aberta-em s)) "nunca abriu ainda")
      (is (nil? (:encerrada-em s)) "nem encerrou"))))

(deftest agendamento-depois-abertura-preserva-agendada-para
  (let [ente (random-uuid) sid (random-uuid)]
    (emitir-agendamento! ente sid "2026-07-08T13:00:00Z" "2026-07-01T10:00:00Z")
    (drena-eventos!)
    (emitir-transicao! ente sid "agendada" "aberta" "2026-07-08T13:05:00Z")
    (drena-eventos!)
    (let [s (sli-de ente sid)]
      (is (= "aberta" (:estado-atual s)) "a abertura move o estado")
      (is (= (Instant/parse "2026-07-08T13:00:00Z") (:agendada-para s))
          "agendada_para PRESERVADO pela transicao (nao esta' no SET de projetar-transicao!)")
      (is (= (Instant/parse "2026-07-08T13:05:00Z") (:aberta-em s)) "aberta_em carimbado na abertura"))))

(deftest transicao-antes-do-agendamento-nao-retrocede
  ;; reordenacao rara (redrive fora de ordem): a transicao chega ANTES do agendamento. A linha ja' esta' aberta
  ;; (transicionou_em maior) -> o agendamento (instante do ato, anterior) e' no-op pelo gate; estado nao retrocede.
  (let [ente (random-uuid) sid (random-uuid)]
    (emitir-transicao! ente sid "agendada" "aberta" "2026-07-08T13:05:00Z")
    (drena-eventos!)
    (emitir-agendamento! ente sid "2026-07-08T13:00:00Z" "2026-07-01T10:00:00Z")
    (drena-eventos!)
    (let [s (sli-de ente sid)]
      (is (= "aberta" (:estado-atual s)) "o agendamento fora de ordem NAO retrocedeu o estado p/ agendada"))))

;; ---------- 1a transicao (agendada->aberta) INSERE a linha, carimba aberta_em ----------

(deftest abertura-projeta-janela-com-aberta-em
  (let [ente (random-uuid) sid (random-uuid)]
    (emitir-transicao! ente sid "agendada" "aberta" "2026-07-01T13:00:00Z")
    (drena-eventos!)
    (let [s (sli-de ente sid)]
      (is (some? s) "a sessao foi projetada na 1a transicao")
      (is (= "aberta" (:estado-atual s)))
      (is (= (Instant/parse "2026-07-01T13:00:00Z") (:aberta-em s)) "aberta_em = instante real da abertura")
      (is (nil? (:encerrada-em s)) "ainda em curso: encerrada_em nulo"))
    (is (nil? (sli-de (random-uuid) sid)) "RLS: outro ente nao ve a sessao projetada")))

;; ---------- encerramento fecha a janela, PRESERVA aberta_em ----------

(deftest encerramento-fecha-a-janela-preservando-aberta-em
  (let [ente (random-uuid) sid (random-uuid)]
    (emitir-transicao! ente sid "agendada" "aberta" "2026-07-01T13:00:00Z")
    (drena-eventos!)
    (emitir-transicao! ente sid "aberta" "encerrada" "2026-07-01T15:30:00Z")
    (drena-eventos!)
    (let [s (sli-de ente sid)]
      (is (= "encerrada" (:estado-atual s)))
      (is (= (Instant/parse "2026-07-01T13:00:00Z") (:aberta-em s)) "aberta_em PRESERVADO (nao reescrito)")
      (is (= (Instant/parse "2026-07-01T15:30:00Z") (:encerrada-em s)) "encerrada_em carimbado no fechamento"))))

;; ---------- suspensao/reabertura NAO reescreve o inicio da janela ----------

(deftest suspensao-e-reabertura-preservam-aberta-em
  (let [ente (random-uuid) sid (random-uuid)]
    (emitir-transicao! ente sid "agendada" "aberta"   "2026-07-01T13:00:00Z")
    (drena-eventos!)
    (emitir-transicao! ente sid "aberta"   "suspensa" "2026-07-01T13:40:00Z")
    (drena-eventos!)
    (emitir-transicao! ente sid "suspensa" "aberta"   "2026-07-01T14:00:00Z")
    (drena-eventos!)
    (let [s (sli-de ente sid)]
      (is (= "aberta" (:estado-atual s)) "estado atual = ultima transicao")
      (is (= (Instant/parse "2026-07-01T13:00:00Z") (:aberta-em s))
          "aberta_em ancora na 1a abertura, imune a reabertura (COALESCE existente-primeiro)"))))

;; ---------- nao_realizada tambem fecha a janela ----------

(deftest nao-realizada-carimba-encerrada-em
  (let [ente (random-uuid) sid (random-uuid)]
    (emitir-transicao! ente sid "agendada" "nao_realizada" "2026-07-01T13:00:00Z")
    (drena-eventos!)
    (let [s (sli-de ente sid)]
      (is (= "nao_realizada" (:estado-atual s)))
      (is (nil? (:aberta-em s)) "nunca abriu: aberta_em nulo")
      (is (= (Instant/parse "2026-07-01T13:00:00Z") (:encerrada-em s)) "desfecho terminal fecha a janela"))))

;; ---------- gate de monotonicidade: transicao mais antiga fora de ordem = no-op ----------

(deftest transicao-fora-de-ordem-e-no-op
  (let [ente (random-uuid) sid (random-uuid)]
    (emitir-transicao! ente sid "agendada" "aberta" "2026-07-01T13:00:00Z")
    (drena-eventos!)
    (emitir-transicao! ente sid "aberta" "encerrada" "2026-07-01T15:00:00Z")
    (drena-eventos!)
    ;; um redrive fora de ordem de uma transicao MAIS ANTIGA (13:30) chega DEPOIS da mais nova (15:00) ja' projetada
    (emitir-transicao! ente sid "aberta" "suspensa" "2026-07-01T13:30:00Z")
    (drena-eventos!)
    (let [s (sli-de ente sid)]
      (is (= "encerrada" (:estado-atual s)) "a transicao mais antiga NAO retrocedeu o estado")
      (is (= (Instant/parse "2026-07-01T15:00:00Z") (:transicionou-em s)) "carimbo temporal nao retrocedeu"))))

;; ---------- idempotencia: redrive do MESMO evento = no-op ----------

(deftest redrive-do-mesmo-evento-e-idempotente
  (let [ente (random-uuid) sid (random-uuid)
        payload {:tipo "sessao.transicionou" :ente-id ente
                 :payload {:sessao-id (str sid) :de "agendada" :para "aberta" :ocorrido-em "2026-07-01T13:00:00Z"}}]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (repo/projetar-evento! tx payload)
        (repo/projetar-evento! tx payload)))
    (is (= 1 (count (filter #(= sid (:sessao-id %)) (repo/sli-sessoes *repo* ente))))
        "duas projecoes do mesmo evento -> uma unica linha (UPSERT idempotente, nunca lanca)")))

;; ---------- leitura: abertas (em curso) primeiro, concluidas por recencia ----------

(deftest leitura-poe-abertas-primeiro
  (let [ente (random-uuid) s-aberta (random-uuid) s-encerrada (random-uuid)]
    (emitir-transicao! ente s-encerrada "agendada" "aberta"    "2026-07-01T09:00:00Z")
    (drena-eventos!)
    (emitir-transicao! ente s-encerrada "aberta"   "encerrada" "2026-07-01T11:00:00Z")
    (drena-eventos!)
    (emitir-transicao! ente s-aberta    "agendada" "aberta"    "2026-07-02T09:00:00Z")
    (drena-eventos!)
    (let [ordem (map :sessao-id (repo/sli-sessoes *repo* ente))]
      (is (= [s-aberta s-encerrada] ordem)
          "a sessao ainda ABERTA (encerrada_em nulo) vem antes da ja' concluida"))))

(deftest entre-abertas-a-mais-antiga-vem-primeiro
  ;; review database MEDIUM: no grupo aberto, a MAIS ANTIGA (aberta ha' mais tempo = mais provavelmente TRAVADA)
  ;; tem de estar no topo — senao, sob o teto, cairia fora justamente o sinal mais critico.
  (let [ente (random-uuid) s-antiga (random-uuid) s-recente (random-uuid)]
    (emitir-transicao! ente s-recente "agendada" "aberta" "2026-07-01T14:00:00Z")
    (drena-eventos!)
    (emitir-transicao! ente s-antiga  "agendada" "aberta" "2026-07-01T09:00:00Z")
    (drena-eventos!)
    (is (= [s-antiga s-recente] (map :sessao-id (repo/sli-sessoes *repo* ente)))
        "a sessao aberta ha' mais tempo (09:00) vem antes da recem-aberta (14:00)")))
