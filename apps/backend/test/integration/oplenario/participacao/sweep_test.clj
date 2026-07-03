(ns oplenario.participacao.sweep-test
  "INTEGRACAO (PG real) — F6 Slice 3: o SWEEP de vencimento do prazo do e-SIC (F6.3, forma disc.6/§22.7.7).
  O vencimento (pendente->vencida) e' a UNICA transicao do ciclo do prazo que NENHUM evento dispara — e'
  dirigida por TEMPO. Espelha o sweep do compliance (compliance/db/obrigacao + repositorio): puro por DATA
  sobre os prazos ABERTOS, CAS race-safe (so vence se AINDA pendente), emite `participacao.prazo.vencido` na
  MESMA tx do CAS (o `acao_no_vencimento` V1 = emitir evento; escalada/notificacao = consumers futuros).

  Prova, contra o banco real sob FORCE RLS (mig 0039): (a) pendente overdue -> vencida + evento; (b) idempotente
  (2o sweep no-op, sem re-emitir); (c) prazo cumprido NAO e' varrido; (d) prazo com vencimento FUTURO NAO vence;
  (e) POLIMORFICO — um prazo de RECURSO vencido tambem e' varrido; (f) ISOLAMENTO de tenant (RLS). Constroi o
  Repo direto (datasource + outbox/bus) p/ inspecionar o outbox — mesmo estilo do recurso-resposta-test."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.participacao.controllers :as controllers])
  (:import (java.time Instant LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds*   (:ds c)
                *repo* (repo-part/->RepoParticipacaoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

;; relogio fixo: 12:00Z de 2026-07-03 -> zona civil America/Fortaleza = 2026-07-03.
;;   pedido: vence = recibo + 20 = 2026-07-23 ; recurso: vence = recibo + 10 (default [GAP]) = 2026-07-13.
(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))
(def ^:private relogio (tempo/relogio-fixo t0))
;; datas de sweep (LocalDate, passadas direto ao job — o sweep NAO usa relogio):
(def ^:private hoje-pos-vencimento (LocalDate/of 2026 8 1))   ; > todos os vencimentos -> vence
(def ^:private hoje-antes-vencimento (LocalDate/of 2026 7 10)) ; < 2026-07-23 -> NAO vence o pedido
(def ^:private hoje-no-vencimento (LocalDate/of 2026 7 23))   ; == vence_em do pedido -> NAO vence (estrito <)

(defn- ator [ente ident] {:ente-id ente :identidade-id ident :papeis #{}})

(defn- protocolar! [ente ident]
  (controllers/protocolar-pedido *repo* relogio (ator ente ident)
                                 {:assunto "Contratos 2026" :descricao "Solicito a lista de contratos."}))

(defn- eventos-por-tipo [ente tipo]
  (jdbc/execute! *ds*
    ["SELECT tipo FROM shared.outbox WHERE ente_id = ? AND tipo = ?" ente tipo]))

(defn- payloads-por-tipo [ente tipo]
  (jdbc/execute! *ds*
    ["SELECT payload::text AS payload FROM shared.outbox WHERE ente_id = ? AND tipo = ?" ente tipo]))

;; ---------- (a) pendente overdue -> vencida + evento participacao.prazo.vencido ----------

(deftest sweep-vence-pendente-overdue-e-emite
  (let [ente (random-uuid) cidadao (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)
        transicionadas (repo-part/varrer-vencimentos! *repo* ente hoje-pos-vencimento)]
    (is (= [pedido-id] (mapv :objeto-id transicionadas)) "o sweep transicionou o prazo do pedido overdue")
    (is (= "pedido_esic" (:objeto-tipo (first transicionadas))) "objeto_tipo do prazo transicionado")
    (is (= "pendente" (:de (first transicionadas))) "de = pendente")
    (is (= "vencida" (:para (first transicionadas))) "para = vencida (a unica transicao do sweep)")
    (let [prazo (repo-part/prazo-do-objeto *repo* ente "pedido_esic" pedido-id)]
      (is (= "vencida" (:estado prazo)) "prazo persistido como vencida")
      (is (= (LocalDate/of 2026 7 23) (:vence-em prazo)) "vence_em intocado (o sweep so muda estado)"))
    (is (= 1 (count (eventos-por-tipo ente "participacao.prazo.vencido")))
        "emite participacao.prazo.vencido (mesma tx do CAS)")
    ;; SEM PII: o payload carrega so as chaves de rastreamento (objeto_tipo/objeto_id/vence_em), nunca assunto/descricao.
    (let [p (:payload (first (payloads-por-tipo ente "participacao.prazo.vencido")))]
      (is (re-find #"pedido_esic" p) "payload cita o objeto_tipo")
      ;; vence-em serializado como ISO string (VencidoPayload exige :string; jsonista nao serializa LocalDate) —
      ;; pina o formato que consumers parseiam: se a conversao LocalDate->str drift, o teste trava.
      (is (re-find #"2026-07-23" p) "payload carrega vence-em como ISO string (2026-07-23)")
      (is (not (re-find #"(?i)contratos|solicito" p)) "payload NAO carrega PII (assunto/descricao ficam no banco)"))))

;; ---------- (g) FRONTEIRA (estrito <): no PROPRIO dia do vencimento o prazo NAO vence ----------

(deftest sweep-nao-vence-no-proprio-dia-do-vencimento
  ;; a fronteira e' JURIDICAMENTE load-bearing (ultimo dia LAI art. 11 ainda valido): vence_em < hoje (ESTRITO).
  ;; Um refactor p/ <= venceria o pedido no dia 23 e emitiria participacao.prazo.vencido espurio — este teste trava.
  (let [ente (random-uuid) cidadao (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)   ; vence 2026-07-23
        transicionadas (repo-part/varrer-vencimentos! *repo* ente hoje-no-vencimento)]  ; hoje == 2026-07-23
    (is (empty? transicionadas) "no proprio dia do vencimento NAO vence (estrito vence_em < hoje)")
    (is (= "pendente" (:estado (repo-part/prazo-do-objeto *repo* ente "pedido_esic" pedido-id))) "segue pendente")
    (is (= 0 (count (eventos-por-tipo ente "participacao.prazo.vencido"))) "sem evento no dia do vencimento")))

;; ---------- (b) idempotente: 2a passada nao re-vence nem re-emite ----------

(deftest sweep-e-idempotente
  (let [ente (random-uuid) cidadao (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)
        p1 (repo-part/varrer-vencimentos! *repo* ente hoje-pos-vencimento)
        p2 (repo-part/varrer-vencimentos! *repo* ente hoje-pos-vencimento)]
    (is (= [pedido-id] (mapv :objeto-id p1)) "1a passada vence o prazo overdue")
    (is (empty? p2) "2a passada e' no-op (a ja-vencida nao re-transiciona — CAS WHERE estado=pendente nao casa)")
    (is (= "vencida" (:estado (repo-part/prazo-do-objeto *repo* ente "pedido_esic" pedido-id))) "segue vencida")
    (is (= 1 (count (eventos-por-tipo ente "participacao.prazo.vencido")))
        "exatamente 1 evento (a 2a passada NAO re-emite)")))

;; ---------- (c) prazo CUMPRIDO antes do sweep NAO e' varrido ----------

(deftest sweep-nao-toca-prazo-cumprido
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)
        _ (controllers/responder-pedido! *repo* relogio (ator ente servidor) pedido-id {:corpo "Segue a resposta."})
        transicionadas (repo-part/varrer-vencimentos! *repo* ente hoje-pos-vencimento)]
    (is (empty? transicionadas) "prazo cumprida (respondido antes do sweep) NAO e' candidato")
    (is (= "cumprida" (:estado (repo-part/prazo-do-objeto *repo* ente "pedido_esic" pedido-id))) "segue cumprida (intocado)")
    (is (= 0 (count (eventos-por-tipo ente "participacao.prazo.vencido"))) "nenhum evento de vencimento espurio")))

;; ---------- (d) prazo com vencimento FUTURO NAO vence ----------

(deftest sweep-nao-toca-prazo-futuro
  (let [ente (random-uuid) cidadao (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)   ; vence 2026-07-23
        transicionadas (repo-part/varrer-vencimentos! *repo* ente hoje-antes-vencimento)]  ; 2026-07-10 < 2026-07-23
    (is (empty? transicionadas) "prazo com vencimento no futuro NAO e' tocado")
    (is (= "pendente" (:estado (repo-part/prazo-do-objeto *repo* ente "pedido_esic" pedido-id))) "segue pendente")
    (is (= 0 (count (eventos-por-tipo ente "participacao.prazo.vencido"))) "sem evento de vencimento")))

;; ---------- (e) POLIMORFICO: um prazo de RECURSO vencido tambem e' varrido ----------

(deftest sweep-vence-prazo-de-recurso-polimorfico
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)
        _ (controllers/responder-pedido! *repo* relogio (ator ente servidor) pedido-id {:corpo "r"})
        {recurso-id :id} (controllers/interpor-recurso! *repo* relogio (ator ente cidadao) pedido-id {:motivo "recorro"})
        ;; agora: prazo do PEDIDO = cumprida (respondido); prazo do RECURSO = pendente, vence 2026-07-13.
        transicionadas (repo-part/varrer-vencimentos! *repo* ente hoje-pos-vencimento)]
    (is (= [recurso-id] (mapv :objeto-id transicionadas)) "o sweep vence o prazo do RECURSO (polimorfico)")
    (is (= "recurso_esic" (:objeto-tipo (first transicionadas))) "objeto_tipo = recurso_esic")
    (is (= "vencida" (:estado (repo-part/prazo-do-objeto *repo* ente "recurso_esic" recurso-id))) "prazo do recurso -> vencida")
    (is (= "cumprida" (:estado (repo-part/prazo-do-objeto *repo* ente "pedido_esic" pedido-id)))
        "o prazo do pedido (cumprida) NAO foi tocado")
    (is (= 1 (count (eventos-por-tipo ente "participacao.prazo.vencido"))) "1 evento (so o recurso venceu)")))

;; ---------- (f) ISOLAMENTO de tenant (RLS): varrer ente-A nao toca ente-B ----------

(deftest sweep-isola-por-tenant
  (let [ente-a (random-uuid) ente-b (random-uuid) cidadao (random-uuid)
        {pedido-a :id} (protocolar! ente-a cidadao)   ; ente-A: vence 2026-07-23
        {pedido-b :id} (protocolar! ente-b cidadao)   ; ente-B: vence 2026-07-23
        transicionadas (repo-part/varrer-vencimentos! *repo* ente-a hoje-pos-vencimento)]
    (is (= [pedido-a] (mapv :objeto-id transicionadas)) "o sweep de ente-A so ve os prazos de ente-A")
    (is (= "vencida" (:estado (repo-part/prazo-do-objeto *repo* ente-a "pedido_esic" pedido-a))) "prazo de A -> vencida")
    (is (= "pendente" (:estado (repo-part/prazo-do-objeto *repo* ente-b "pedido_esic" pedido-b)))
        "prazo de B intocado (RLS isola o sweep por tenant)")
    (is (= 1 (count (eventos-por-tipo ente-a "participacao.prazo.vencido"))) "evento so no tenant A")
    (is (= 0 (count (eventos-por-tipo ente-b "participacao.prazo.vencido"))) "nenhum evento no tenant B")))
