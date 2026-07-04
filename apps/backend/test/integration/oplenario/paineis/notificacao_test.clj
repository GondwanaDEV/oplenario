(ns oplenario.paineis.notificacao-test
  "INTEGRACAO (PG real) — F7 E2: notificacao DURAVEL (fan-out do acompanhamento, §16.11). Prova o vertical
  completo cross-modulo: uma `proposicao.transicionou` de materia ACOMPANHADA -> transparencia faz o FAN-OUT
  (um `notificacao.requisitada` por seguidor ativo) -> paineis materializa o INTENT de entrega ('pendente')
  no ledger (mig 0004+0050). Depois o WORKER (entregar-pendentes!) envia pela porta (stub) e marca enviada/
  falha. Usa o relay REAL (drena o shared.outbox com os DOIS registros fundidos — como sistema.clj), provando
  a fronteira mig 0045 (transparencia produz, paineis entrega, sem JOIN/import cross-modulo §22.10), a dupla
  idempotencia (dedup do ledger em replay) e o consent-gating (so' seguidor 'ativo' e' notificado)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.eventos :as eventos]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.paineis.components.notificacao :as porta]
            [oplenario.paineis.components.repositorio :as paineis-repo]
            [oplenario.paineis.diplomat.consumers :as paineis-consumers]
            [oplenario.paineis.diplomat.notificador :as notificador]
            [oplenario.transparencia.components.repositorio :as transp-repo]
            [oplenario.transparencia.diplomat.consumers :as transp-consumers]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *transp* nil)
(def ^:dynamic *paineis* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*      (:ds c)
                *transp*  (transp-repo/->RepoTransparenciaPg c)
                *paineis* (paineis-repo/->RepoPaineisPg c)]
        (try (t) (finally (component/stop c)))))))

;; relay com os DOIS registros fundidos (como sistema.clj): transparencia (portal + fan-out) + paineis.
(defn- drenar! []
  (outbox/drenar! *ds* (-> {} transp-consumers/registrar paineis-consumers/registrar)))

(defn- emitir! [ente tipo payload]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (eventos/emitir! (outbox/bus) tx (eventos/evento tipo ente payload)))))

(defn- protocolar-materia! [ente pid]
  (emitir! ente "proposicao.protocolada"
           {:proposicao-id (str pid) :tipo "pl" :ano 2026 :sequencial 12 :urn-lex "urn:lex:br;..."
            :ementa "Dispoe sobre a arborizacao urbana." :autor-tipo "vereador" :autor-texto "Ver. Fulano"
            :estado "protocolada"})
  (drenar!))

(defn- transicionar! [ente pid para]
  (emitir! ente "proposicao.transicionou"
           {:proposicao-id (str pid) :template-id (str (random-uuid)) :de "protocolada" :para para
            :gatilho "manual" :transicao-id (str (random-uuid)) :ocorrido-em "2026-07-04T12:00:00Z"})
  (drenar!))

(defn- seguir! [ente pid seguidor]
  (transp-repo/seguir! *transp* ente {:id (random-uuid) :proposicao-id pid
                                      :seguidor-identidade-id seguidor :created-by seguidor}))

(defn- ledger [ente]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute! tx ["SELECT destinatario, canal, estado, consent_base, assunto, corpo,
                                        objeto_tipo, objeto_id, enviada_em, falha_motivo
                                 FROM paineis.notificacao_entrega ORDER BY destinatario"]))))

;; ---------- fan-out: uma transicao materializa um intent 'pendente' por seguidor ativo ----------

(deftest fan-out-materializa-intent-por-seguidor-ativo
  (let [ente (random-uuid) pid (random-uuid) seg1 (random-uuid) seg2 (random-uuid)]
    (protocolar-materia! ente pid)
    (seguir! ente pid seg1)
    (seguir! ente pid seg2)
    (transicionar! ente pid "em_pauta")
    (let [linhas (ledger ente)]
      (is (= 2 (count linhas)) "um intent por seguidor ativo")
      (is (= #{(str seg1) (str seg2)} (set (map :notificacao_entrega/destinatario linhas)))
          "destinatarios = os dois seguidores (UUID de identidade, nao PII)")
      (is (every? #(= "pendente" (:notificacao_entrega/estado %)) linhas) "intent nasce 'pendente'")
      (is (every? #(= "email" (:notificacao_entrega/canal %)) linhas))
      (is (every? #(= "acompanhamento" (:notificacao_entrega/consent_base %)) linhas) "consent = o ato de seguir")
      (is (every? #(str/includes? (:notificacao_entrega/corpo %) "em_pauta") linhas) "corpo tem a nova fase")
      (is (every? #(str/includes? (:notificacao_entrega/assunto %) "PL 12/2026") linhas) "assunto identifica a materia")
      (is (every? #(= "proposicao" (:notificacao_entrega/objeto_tipo %)) linhas) "rastreabilidade opaca"))))

(deftest seguidor-cancelado-nao-e-notificado
  (let [ente (random-uuid) pid (random-uuid) seg1 (random-uuid) seg2 (random-uuid)]
    (protocolar-materia! ente pid)
    (seguir! ente pid seg1)
    (seguir! ente pid seg2)
    (transp-repo/deixar-de-seguir! *transp* ente {:proposicao-id pid :seguidor-identidade-id seg2})
    (transicionar! ente pid "em_pauta")
    (let [linhas (ledger ente)]
      (is (= 1 (count linhas)) "consent-gating: so' o seguidor ATIVO recebe intent")
      (is (= (str seg1) (:notificacao_entrega/destinatario (first linhas)))))))

(deftest transicao-sem-seguidores-nao-emite-nada
  (let [ente (random-uuid) pid (random-uuid)]
    (protocolar-materia! ente pid)
    (transicionar! ente pid "em_pauta")
    (is (empty? (ledger ente)) "materia sem seguidores -> fan-out no-op (nenhum intent)")))

(deftest fan-out-idempotente-no-redrive
  ;; a chave DETERMINISTICA do ledger (f(transicao,destinatario)) torna re-executar o MESMO fan-out um no-op.
  (let [ente (random-uuid) pid (random-uuid) seg1 (random-uuid)
        tid  (str (random-uuid))]
    (protocolar-materia! ente pid)
    (seguir! ente pid seg1)
    ;; emite a MESMA transicao (mesmo transicao-id) DUAS vezes -> a 2a entrega e' no-op no ledger
    (dotimes [_ 2]
      (emitir! ente "proposicao.transicionou"
               {:proposicao-id (str pid) :template-id (str (random-uuid)) :de "protocolada" :para "em_pauta"
                :gatilho "manual" :transicao-id tid :ocorrido-em "2026-07-04T12:00:00Z"})
      (drenar!))
    (is (= 1 (count (ledger ente))) "mesma (transicao, seguidor) = um unico intent (dedup deterministico)")))

;; ---------- worker de entrega: envia pela porta e marca enviada/falha ----------

(deftest worker-entrega-marca-enviada-com-stub
  (let [ente (random-uuid) pid (random-uuid) seg1 (random-uuid)]
    (protocolar-materia! ente pid)
    (seguir! ente pid seg1)
    (transicionar! ente pid "em_pauta")
    (let [r (paineis-repo/entregar-pendentes! *paineis* ente (notificador/notificador-log))]
      (is (= 1 (:processados r)))
      (let [linha (first (ledger ente))]
        (is (= "enviada" (:notificacao_entrega/estado linha)) "stub confirma -> enviada")
        (is (some? (:notificacao_entrega/enviada_em linha)) "carimba enviada_em")))
    (is (= {:processados 0} (paineis-repo/entregar-pendentes! *paineis* ente (notificador/notificador-log)))
        "2a rodada: nada pendente (worker reentrante e' no-op)")))

(deftest worker-entrega-marca-falha-quando-porta-falha
  (let [ente (random-uuid) pid (random-uuid) seg1 (random-uuid)
        porta-falha (reify porta/CanalNotificacao
                      (enviar! [_ _] {:ok? false :motivo "smtp indisponivel"}))]
    (protocolar-materia! ente pid)
    (seguir! ente pid seg1)
    (transicionar! ente pid "em_pauta")
    (let [r (paineis-repo/entregar-pendentes! *paineis* ente porta-falha)]
      (is (= 1 (:processados r)))
      (let [linha (first (ledger ente))]
        (is (= "falha" (:notificacao_entrega/estado linha)) "porta falhou -> estado 'falha'")
        (is (= "smtp indisponivel" (:notificacao_entrega/falha_motivo linha)) "registra o motivo")
        (is (nil? (:notificacao_entrega/enviada_em linha)) "sem enviada_em em falha")))))

