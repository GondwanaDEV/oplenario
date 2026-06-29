(ns oplenario.sessoes.sessoes-eventos-repo-test
  "INTEGRACAO (PG real): a COMPOSICAO do Repo-Component de SESSOES (ADR-0001 §3-bis) — os caminhos de escrita
  emitem os EVENTOS DE DOMINIO DE TEMPO REAL no shared.outbox na MESMA tx do ato (§22.6 eixo G / carry F4;
  atomicidade outbox-com-o-ato §22.9 E2: a linha do evento so existe se a tx commitou). Estes eventos sao a
  FONTE do projetor SSE (G2). Prova o caminho de producao (via o Component, nao o db/ direto). Instantes nos
  payloads viajam como ISO-8601 string (jsonista nao serializa java.time.Instant)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo/->RepoSessoesPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- eventos-por-tipo [ente tipo]
  (jdbc/execute! *ds*
    ["SELECT tipo, ente_id, payload::text AS payload FROM shared.outbox
      WHERE ente_id = ? AND tipo = ? ORDER BY id" ente tipo]))

(defn- agendar! [ente]
  (:id (repo/agendar-sessao! *repo* ente {:id (random-uuid) :sessao-legislativa-id (random-uuid)
                                          :tipo-sessao "ordinaria" :modalidade "presencial"})))

(def ^:private t0 (java.time.Instant/parse "2026-06-29T14:00:00Z"))
(defn- mais [^java.time.Instant t s] (.plusSeconds t s))

;; ---------- sessao.transicionou ----------

(deftest transicao-de-sessao-emite-evento
  (let [ente (random-uuid)
        sid  (agendar! ente)]
    (is (empty? (eventos-por-tipo ente "sessao.transicionou")) "nada antes de transicionar")
    (repo/transicionar-sessao! *repo* ente {:id sid :para "aberta" :updated-by (random-uuid) :lock-version 0})
    (let [evs (eventos-por-tipo ente "sessao.transicionou")]
      (is (= 1 (count evs)) "exatamente 1 evento de transicao")
      (let [pl (:payload (first evs))]
        (is (re-find #"agendada" pl) "payload carrega o estado de origem")
        (is (re-find #"aberta" pl) "payload carrega o estado de destino")
        (is (re-find (re-pattern (str sid)) pl) "payload carrega a sessao-id")))))

;; ---------- presenca.registrada ----------

(deftest presenca-emite-evento
  (let [ente (random-uuid)
        sid  (agendar! ente)
        ver  (random-uuid)]
    (repo/registrar-presenca! *repo* ente {:id (random-uuid) :sessao-id sid :vereador-id ver
                                           :tipo "entrada" :modalidade "plenario" :fonte "manual_secretaria"
                                           :ocorrido-em t0 :created-by (random-uuid)})
    (let [evs (eventos-por-tipo ente "presenca.registrada")]
      (is (= 1 (count evs)) "1 evento de presenca")
      (let [pl (:payload (first evs))]
        (is (re-find #"entrada" pl) "payload carrega o tipo")
        (is (re-find (re-pattern (str ver)) pl) "payload carrega o vereador")
        (is (re-find #"2026-06-29T14:00:00Z" pl) "ocorrido-em como ISO-8601 string")))))

;; ---------- fala.iniciada / fala.cronometro / fala.encerrada ----------

(deftest tribuna-emite-eventos
  (let [ente (random-uuid)
        sid  (agendar! ente)
        orad (random-uuid)
        fid  (random-uuid)]
    (repo/iniciar-fala! *repo* ente {:id fid :sessao-id sid :orador-id orad :tipo-fala "principal"
                                     :fase "ordem_do_dia" :iniciou-em t0 :created-by (random-uuid)})
    (is (= 1 (count (eventos-por-tipo ente "fala.iniciada"))) "fala.iniciada emitido")
    (let [pl (:payload (first (eventos-por-tipo ente "fala.iniciada")))]
      (is (re-find (re-pattern (str orad)) pl) "carrega o orador")
      (is (re-find #"principal" pl) "carrega o tipo de fala"))
    (repo/registrar-evento-cronometro! *repo* ente {:fala-id fid :tipo "pausada"
                                                    :ocorrido-em (mais t0 100) :created-by (random-uuid)})
    (repo/registrar-evento-cronometro! *repo* ente {:fala-id fid :tipo "retomada"
                                                    :ocorrido-em (mais t0 160) :created-by (random-uuid)})
    (is (= 2 (count (eventos-por-tipo ente "fala.cronometro"))) "fala.cronometro emitido por marco")
    (is (re-find #"pausada" (:payload (first (eventos-por-tipo ente "fala.cronometro")))) "carrega o tipo de marco")
    (repo/encerrar-fala! *repo* ente {:id fid :encerrou-em (mais t0 300) :lock-version 0 :updated-by (random-uuid)})
    (let [evs (eventos-por-tipo ente "fala.encerrada")]
      (is (= 1 (count evs)) "fala.encerrada emitido")
      (is (re-find #"\"tempo-segundos\":\s*240" (:payload (first evs)))
          "carrega o tempo efetivamente usado (300 - 60 de pausa = 240)"))))
