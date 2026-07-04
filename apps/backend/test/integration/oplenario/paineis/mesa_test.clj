(ns oplenario.paineis.mesa-test
  "INTEGRACAO (PG real) — F7 dashboard da Mesa (§16.11 item 11.4): os ROLLUPS que o Repo agrega dos tres
  read-models que o proprio paineis projeta (tramitacao/pendencias/sessoes). Semeia os eventos reais no
  outbox, drena pelo consumer, e le' `repo/dashboard-mesa` — provando a agregacao GROUP BY e o isolamento RLS
  (a COMPOSICAO com o card de compliance e' provada na borda, em mesa_http_in_test — aqui e' so' o que o
  paineis possui, sem cruzar modulo §22.10)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.components.repositorio :as repo]
            [oplenario.paineis.diplomat.consumers :as consumers]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo/->RepoPaineisPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- drenar! []
  (outbox/drenar! *ds* (consumers/registrar {})))

(defn- emitir! [ente tipo payload]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (eventos/emitir! (outbox/bus) tx (eventos/evento tipo ente payload)))))

(defn- protocolada [pid over]
  (merge {:proposicao-id (str pid) :tipo "pl" :ano 2026 :sequencial 1
          :urn-lex "urn:lex:br:camara:pl:2026;1" :ementa "ementa" :autor-tipo "vereador"
          :autor-texto "Fulano" :estado "protocolada"} over))

(defn- transicionou [pid para ocorrido-em]
  {:proposicao-id (str pid) :template-id (str (random-uuid)) :de "protocolada" :para para
   :gatilho "manual" :transicao-id (str (random-uuid)) :ocorrido-em ocorrido-em})

(defn- por [rows chave] (into {} (map (juxt chave :n) rows)))

(deftest dashboard-mesa-agrega-os-tres-read-models
  (let [ente (random-uuid)
        p1 (random-uuid) p2 (random-uuid) p3 (random-uuid)
        s-aberta (random-uuid) s-encerrada (random-uuid) s-noshow (random-uuid)]
    ;; --- tramitacao: 3 proposicoes; p1 transiciona p/ em_comissao, p2/p3 ficam protocolada ---
    (emitir! ente "proposicao.protocolada" (protocolada p1 {:sequencial 1}))
    (emitir! ente "proposicao.protocolada" (protocolada p2 {:sequencial 2}))
    (emitir! ente "proposicao.protocolada" (protocolada p3 {:sequencial 3}))
    (drenar!)
    (emitir! ente "proposicao.transicionou" (transicionou p1 "em_comissao" "2026-07-05T09:00:00Z"))
    (drenar!)
    ;; --- pendencias: 2 e-SIC (pendentes) + 1 LGPD ---
    (emitir! ente "participacao.pedido_esic.protocolado"
             {:pedido-id (str (random-uuid)) :protocolo "ESIC-1" :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-07-24"})
    (emitir! ente "participacao.pedido_esic.protocolado"
             {:pedido-id (str (random-uuid)) :protocolo "ESIC-2" :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-07-25"})
    (emitir! ente "participacao.solicitacao_titular.protocolada"
             {:solicitacao-id (str (random-uuid)) :protocolo "LGPD-1" :tipo "acesso" :recibo-em "2026-07-04T12:00:00Z" :vence-em "2026-08-03"})
    (drenar!)
    ;; --- sessoes: 1 aberta (em curso) + 1 encerrada (realizada) + 1 nao_realizada (no-show) ---
    (emitir! ente "sessao.transicionou" {:sessao-id (str s-aberta) :de "agendada" :para "aberta" :ocorrido-em "2026-07-05T14:00:00Z"})
    (emitir! ente "sessao.transicionou" {:sessao-id (str s-encerrada) :de "aberta" :para "encerrada" :ocorrido-em "2026-07-04T18:00:00Z"})
    (emitir! ente "sessao.transicionou" {:sessao-id (str s-noshow) :de "agendada" :para "nao_realizada" :ocorrido-em "2026-07-03T20:00:00Z"})
    (drenar!)

    (let [{:keys [tramitacao pendencias sessoes]} (repo/dashboard-mesa *repo* ente)
          tram (por tramitacao :estado)
          pend (por pendencias :estado)
          sess (por sessoes :estado-atual)]
      (is (= 2 (get tram "protocolada")) "p2 e p3 seguem protocolada")
      (is (= 1 (get tram "em_comissao")) "p1 transicionou")
      (is (= 3 (get pend "pendente")) "as 3 pendencias (2 e-SIC + 1 LGPD) nascem pendente")
      (is (= 1 (get sess "aberta")))
      (is (= 1 (get sess "encerrada")))
      (is (= 1 (get sess "nao_realizada"))))

    (let [{:keys [tramitacao pendencias sessoes]} (repo/dashboard-mesa *repo* (random-uuid))]
      (is (= [] tramitacao) "RLS: outro ente nao ve tramitacao")
      (is (= [] pendencias) "RLS: outro ente nao ve pendencias")
      (is (= [] sessoes) "RLS: outro ente nao ve sessoes"))))
