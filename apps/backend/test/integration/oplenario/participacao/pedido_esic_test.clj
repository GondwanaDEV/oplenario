(ns oplenario.participacao.pedido-esic-test
  "INTEGRACAO (PG real) — F6 Slice 1: o ato de PROTOCOLAR um pedido e-SIC via o Repo-Component + o
  acompanhamento. Prova a decisao Arch B: pedido + RELOGIO (prazo_ativo) materializados na MESMA tx do recibo
  + o evento no shared.outbox na mesma tx (§22.9 E2). Relogio FIXO (determinismo do prazo). Sob FORCE RLS
  (mig 0039): um 2o ente NAO enxerga o pedido do 1o. Constroi o Repo direto (datasource + outbox/bus), sem
  relay — p/ inspecionar o outbox (precedente sessoes_eventos_repo_test)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.participacao.controllers :as controllers]
            [oplenario.participacao.db.pedido-esic :as db-pedido])
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

;; relogio fixo: 12:00Z de 2026-07-03. Na zona civil America/Fortaleza (UTC-3) = 09:00 do mesmo dia ->
;; hoje = 2026-07-03; vence = +20 dias corridos = 2026-07-23 (LAI 20).
(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))
(def ^:private relogio (tempo/relogio-fixo t0))
(def ^:private vence-esperado (LocalDate/of 2026 7 23))

(defn- ator [ente ident] {:ente-id ente :identidade-id ident :papeis #{}})

(defn- eventos-por-tipo [ente tipo]
  (jdbc/execute! *ds*
    ["SELECT tipo, payload::text AS payload FROM shared.outbox WHERE ente_id = ? AND tipo = ? ORDER BY id" ente tipo]))

;; ---------- protocolar: pedido + prazo + evento na mesma tx ----------

(deftest protocolar-insere-pedido-prazo-e-emite-evento
  (let [ente  (random-uuid)
        ident (random-uuid)
        {:keys [id protocolo recibo-em]}
        (controllers/protocolar-pedido *repo* relogio (ator ente ident)
                                       {:assunto "Contratos 2026" :descricao "Solicito a lista de contratos."})]
    (is (= "ESIC-2026-000001" protocolo) "protocolo gapless comeca em 1 no ano (ente fresco)")
    (is (= t0 recibo-em) "recibo-em = instante do relogio (marco de inicio do relogio LAI)")
    (let [pedido (repo-part/buscar-pedido *repo* ente id)
          prazo  (repo-part/prazo-do-objeto *repo* ente "pedido_esic" id)]
      (is (= "protocolado" (:estado pedido)) "pedido nasce protocolado")
      (is (= t0 (:recibo-em pedido)) "recibo_em carimbado")
      (is (= "pendente" (:estado prazo)) "prazo materializado pendente")
      (is (= "pedido_esic" (:objeto-tipo prazo)) "objeto_tipo do prazo")
      (is (= id (:objeto-id prazo)) "prazo aponta pro pedido")
      (is (= vence-esperado (:vence-em prazo)) "vence_em = recibo + 20 dias corridos (LAI)")
      (is (= 20 (:base-dias prazo)) "base_dias registra a proveniencia do calculo (LAI 20)"))
    (let [evs (eventos-por-tipo ente "participacao.pedido_esic.protocolado")]
      (is (= 1 (count evs)) "exatamente 1 evento protocolado (mesma tx do ato)")
      (let [pl (:payload (first evs))]
        (is (re-find #"ESIC-2026-000001" pl) "payload carrega o protocolo")
        (is (re-find #"2026-07-23" pl) "payload carrega o vence-em (ISO date)")
        (is (re-find #"2026-07-03T12:00:00Z" pl) "payload carrega o recibo-em (ISO-8601)")))))

(deftest protocolo-e-gapless-sequencial-por-ente
  (let [ente (random-uuid) ident (random-uuid)
        p1 (:protocolo (controllers/protocolar-pedido *repo* relogio (ator ente ident) {:assunto "a" :descricao "a1"}))
        p2 (:protocolo (controllers/protocolar-pedido *repo* relogio (ator ente ident) {:assunto "b" :descricao "b1"}))]
    (is (= "ESIC-2026-000001" p1))
    (is (= "ESIC-2026-000002" p2) "sequencial anda de 1 em 1 por (ente, ano)")))

;; ---------- acompanhar: estado + dias-restantes sob relogio fixo ----------

(deftest acompanhar-devolve-estado-e-dias-restantes
  (let [ente (random-uuid) ident (random-uuid)
        {:keys [protocolo]} (controllers/protocolar-pedido *repo* relogio (ator ente ident)
                                                           {:assunto "x" :descricao "y"})
        acomp (controllers/acompanhar-por-protocolo *repo* ente relogio protocolo)]
    (is (= protocolo (:protocolo acomp)))
    (is (= "protocolado" (:estado acomp)))
    (is (= 20 (:dias-restantes acomp)) "no dia do recibo faltam 20 (LAI 20 corridos)")))

(deftest acompanhar-protocolo-inexistente-e-nil
  (let [ente (random-uuid)]
    (is (nil? (controllers/acompanhar-por-protocolo *repo* ente relogio "ESIC-2026-999999"))
        "protocolo que nao existe no tenant -> nil (o diplomat mapeia p/ 404)")))

;; ---------- imutabilidade: a linha CONGELA em estado terminal (trg_pedido_esic_trava_terminal) ----------

(deftest pedido_esic-terminal-congela-defesa-em-profundidade
  ;; defesa-em-profundidade ALEM do CAS do servico: o trigger (mig 0039 -> shared.imut_trava_estado_terminal)
  ;; bloqueia QUALQUER UPDATE numa linha ja terminal, exceto sob correcao auditada (GUC app.correcao_auditada).
  (let [ente  (random-uuid) ident (random-uuid)
        {:keys [id]} (controllers/protocolar-pedido *repo* relogio (ator ente ident)
                                                    {:assunto "t" :descricao "u"})]
    ;; nao-terminal -> terminal PASSA (a transicao P/ terminal e' legal; so mexer numa linha JA terminal trava).
    (let [ok (repo-part/transacao *repo* ente
               (fn [tx] (db-pedido/transicionar-estado! tx {:id id :ente-id ente :de "protocolado" :para "respondido"})))]
      (is (= "respondido" (:estado ok)) "protocolado -> respondido (transicao p/ terminal passa o trigger)"))
    ;; UPDATE numa linha JA terminal (respondido) SEM correcao auditada -> check_violation (trigger).
    (is (thrown-with-msg? Exception #"estado terminal"
          (repo-part/transacao *repo* ente
            (fn [tx] (db-pedido/transicionar-estado! tx {:id id :ente-id ente :de "respondido" :para "indeferido"}))))
        "linha terminal NAO muda sem correcao auditada (defesa-em-profundidade)")
    ;; escape-hatch: sob app.correcao_auditada o MESMO UPDATE passa (correcao auditada, mig 0012).
    (let [corr (repo-part/transacao *repo* ente
                 (fn [tx]
                   (jdbc/execute-one! tx ["SELECT set_config('app.correcao_auditada', ?, true)" "correcao-teste-esic"])
                   (db-pedido/transicionar-estado! tx {:id id :ente-id ente :de "respondido" :para "indeferido"})))]
      (is (= "indeferido" (:estado corr)) "sob app.correcao_auditada a linha terminal muda (excecao auditada)"))))

;; ---------- ISOLAMENTO de tenant (RLS) ----------

(deftest tenant-nao-le-pedido-de-outro
  (let [ente-a (random-uuid) ente-b (random-uuid) ident (random-uuid)
        {:keys [protocolo]} (controllers/protocolar-pedido *repo* relogio (ator ente-a ident)
                                                           {:assunto "A" :descricao "segredo de A"})]
    (is (nil? (controllers/acompanhar-por-protocolo *repo* ente-b relogio protocolo))
        "acompanhar sob o tenant errado NAO encontra o pedido de outro ente (RLS isola)")
    (tenancy/com-tenant* *ds* ente-b
      (fn [tx]
        (is (empty? (jdbc/execute! tx ["SELECT id FROM participacao.pedido_esic WHERE protocolo = ?" protocolo]))
            "RLS: o pedido de ente-a nao aparece na visao de ente-b nem por SQL direto")))))
