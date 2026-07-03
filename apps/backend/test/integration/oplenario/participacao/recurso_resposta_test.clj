(ns oplenario.participacao.recurso-resposta-test
  "INTEGRACAO (PG real) — F6 Slice 2: RESPONDER pedido + RECURSO (relogio proprio) + DECIDIR + RESPOSTA
  append-only. Prova, contra o banco real sob FORCE RLS (mig 0040): (1) responder cumpre o prazo do PEDIDO +
  grava a resposta + emite; (2) interpor recurso abre um 2o prazo INDEPENDENTE (linha distinta, vence_em
  proprio) sem tocar o pedido terminal; (3) decidir cumpre o prazo do RECURSO; (4) resposta_esic e' APPEND-ONLY
  (UPDATE -> check_violation); (5) nao-dono nao recorre (403); (6) recorrer de pedido em curso -> conflito;
  (7) isolamento de tenant. Constroi o Repo direto (datasource + outbox/bus) p/ inspecionar o outbox."
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
;;   pedido: vence = +20 dias = 2026-07-23 ; recurso: vence = +10 dias (default [GAP]) = 2026-07-13.
(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))
(def ^:private relogio (tempo/relogio-fixo t0))
(def ^:private vence-pedido  (LocalDate/of 2026 7 23))
(def ^:private vence-recurso (LocalDate/of 2026 7 13))

(defn- ator [ente ident] {:ente-id ente :identidade-id ident :papeis #{}})

(defn- eventos-por-tipo [ente tipo]
  (jdbc/execute! *ds*
    ["SELECT tipo FROM shared.outbox WHERE ente_id = ? AND tipo = ?" ente tipo]))

(defn- respostas-do-pedido [ente pedido-id]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (jdbc/execute! tx ["SELECT id, corpo, respondido_por FROM participacao.resposta_esic
                                 WHERE ente_id = ? AND pedido_id = ?" ente pedido-id]))))

(defn- protocolar! [ente ident]
  (controllers/protocolar-pedido *repo* relogio (ator ente ident)
                                 {:assunto "Contratos 2026" :descricao "Solicito a lista de contratos."}))

;; ---------- responder: cumpre o prazo do PEDIDO + resposta append-only + evento ----------

(deftest responder-cumpre-prazo-do-pedido-e-grava-resposta
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)
        r (controllers/responder-pedido! *repo* relogio (ator ente servidor) pedido-id
                                         {:corpo "Segue a lista de contratos solicitada."})]
    (is (= t0 (:respondida-em r)) "responder devolve o instante da resposta")
    (let [pedido (repo-part/buscar-pedido *repo* ente pedido-id)
          prazo  (repo-part/prazo-do-objeto *repo* ente "pedido_esic" pedido-id)
          resps  (respostas-do-pedido ente pedido-id)]
      (is (= "respondido" (:estado pedido)) "pedido -> respondido (CAS)")
      (is (= "cumprida" (:estado prazo)) "prazo do pedido -> cumprida")
      (is (some? (:cumprida-em prazo)) "cumprida_em carimbado")
      (is (= 1 (count resps)) "exatamente 1 resposta gravada")
      (is (= servidor (:resposta_esic/respondido_por (first resps))) "respondido_por = o SERVIDOR (do ator)"))
    (is (= 1 (count (eventos-por-tipo ente "participacao.pedido_esic.respondido")))
        "emite pedido_esic.respondido (mesma tx)")))

(deftest responder-pedido-ja-terminal-e-nil-ou-conflito
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)]
    (controllers/responder-pedido! *repo* relogio (ator ente servidor) pedido-id {:corpo "primeira resposta"})
    ;; 2a resposta ao MESMO pedido (ja respondido): o CAS falha; existe -> :conflito/participacao (409 na borda).
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ja respondido"
          (controllers/responder-pedido! *repo* relogio (ator ente servidor) pedido-id {:corpo "segunda"}))
        "responder um pedido ja terminal -> conflito de ciclo")))

(deftest responder-pedido-inexistente-e-nil
  (let [ente (random-uuid) servidor (random-uuid)]
    (is (nil? (controllers/responder-pedido! *repo* relogio (ator ente servidor) (random-uuid) {:corpo "x"}))
        "responder um pedido que nao existe no tenant -> nil (a borda mapeia 404)")))

;; ---------- recurso: RELOGIO INDEPENDENTE (2a linha de prazo, vence_em proprio) ----------

(deftest interpor-recurso-abre-2o-prazo-independente
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)
        _ (controllers/responder-pedido! *repo* relogio (ator ente servidor) pedido-id {:corpo "resposta"})
        {recurso-id :id protocolo :protocolo recibo :recibo-em}
        (controllers/interpor-recurso! *repo* relogio (ator ente cidadao) pedido-id
                                       {:motivo "Resposta incompleta; recorro."})]
    (is (re-matches #"REC-2026-\d{6}" protocolo) "protocolo do recurso tem namespace proprio (REC-)")
    (is (= t0 recibo) "recibo do recurso = instante do ato (marco do relogio proprio)")
    (let [recurso       (repo-part/buscar-recurso *repo* ente recurso-id)
          prazo-recurso (repo-part/prazo-do-objeto *repo* ente "recurso_esic" recurso-id)
          prazo-pedido  (repo-part/prazo-do-objeto *repo* ente "pedido_esic" pedido-id)
          pedido        (repo-part/buscar-pedido *repo* ente pedido-id)]
      (is (= "protocolado" (:estado recurso)) "recurso nasce protocolado")
      (is (= 1 (:instancia recurso)) "1a instancia recursal")
      (is (= pedido-id (:pedido-id recurso)) "recurso aponta ao pedido recorrido")
      ;; RELOGIOS INDEPENDENTES: duas linhas de prazo distintas, cada uma com seu vence_em.
      (is (= "pendente" (:estado prazo-recurso)) "prazo do recurso nasce pendente (relogio proprio)")
      (is (= vence-recurso (:vence-em prazo-recurso)) "vence_em do recurso = recibo + 10 (default [GAP])")
      (is (= vence-pedido (:vence-em prazo-pedido)) "vence_em do pedido segue = recibo + 20 (LAI)")
      (is (not= (:id prazo-recurso) (:id prazo-pedido)) "sao DUAS linhas de prazo distintas")
      (is (not= (:vence-em prazo-recurso) (:vence-em prazo-pedido)) "relogios INDEPENDENTES: vencimentos distintos")
      ;; o pedido terminal NAO foi tocado pela interposicao do recurso.
      (is (= "respondido" (:estado pedido)) "o pedido terminal permanece congelado")
      (is (= "cumprida" (:estado prazo-pedido)) "o prazo do pedido segue cumprida (intocado)"))
    (is (= 1 (count (eventos-por-tipo ente "participacao.recurso_esic.protocolado")))
        "emite recurso_esic.protocolado")))

(deftest nao-dono-nao-interpoe-recurso
  (let [ente (random-uuid) dono (random-uuid) intruso (random-uuid) servidor (random-uuid)
        {pedido-id :id} (protocolar! ente dono)
        _ (controllers/responder-pedido! *repo* relogio (ator ente servidor) pedido-id {:corpo "r"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"autorizacao negada"
          (controllers/interpor-recurso! *repo* relogio (ator ente intruso) pedido-id {:motivo "quero recorrer"}))
        "ator != solicitante do pedido -> authz/negar! (403 na borda)")))

(deftest recorrer-de-pedido-em-curso-e-conflito
  (let [ente (random-uuid) cidadao (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)]  ; pedido AINDA protocolado (nao respondido)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nao admite recurso"
          (controllers/interpor-recurso! *repo* relogio (ator ente cidadao) pedido-id {:motivo "cedo demais"}))
        "recorrer de um pedido ainda em curso -> conflito (409 na borda)")))

(deftest interpor-recurso-duplicado-e-conflito
  ;; double-click / retry do cidadao: o pedido terminal NAO muta ao receber recurso, entao a policy do
  ;; controller (dono + recorrivel) segue verdadeira na 2a tentativa. A UNIQUE(ente, pedido, instancia) da
  ;; mig 0040 barra a duplicata: a 2a interposicao vira 23505 -> :conflito/participacao (409 na borda), e NAO
  ;; deixa 2 recursos/2 prazos/2 obrigacoes LAI para 1 unico ato de recorrer.
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)
        _ (controllers/responder-pedido! *repo* relogio (ator ente servidor) pedido-id {:corpo "r"})
        {recurso-id :id} (controllers/interpor-recurso! *repo* relogio (ator ente cidadao) pedido-id {:motivo "recorro"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ja interposto"
          (controllers/interpor-recurso! *repo* relogio (ator ente cidadao) pedido-id {:motivo "recorro de novo"}))
        "2a interposicao no mesmo pedido/instancia -> conflito (UNIQUE 23505 mapeado a :conflito/participacao)")
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (= 1 (count (jdbc/execute! tx ["SELECT id FROM participacao.recurso_esic
                                            WHERE ente_id = ? AND pedido_id = ?" ente pedido-id])))
            "exatamente 1 recurso persistido (a duplicata deu rollback)")
        (is (= 1 (count (jdbc/execute! tx ["SELECT id FROM participacao.prazo_ativo
                                            WHERE ente_id = ? AND objeto_tipo = 'recurso_esic' AND objeto_id = ?"
                                           ente recurso-id])))
            "exatamente 1 prazo do recurso (nenhuma obrigacao LAI espuria)")))))

;; ---------- decidir: cumpre o prazo do RECURSO ----------

(deftest decidir-cumpre-prazo-do-recurso
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)
        _ (controllers/responder-pedido! *repo* relogio (ator ente servidor) pedido-id {:corpo "r"})
        {recurso-id :id} (controllers/interpor-recurso! *repo* relogio (ator ente cidadao) pedido-id {:motivo "recorro"})
        d (controllers/decidir-recurso! *repo* relogio (ator ente servidor) recurso-id
                                        {:corpo "Recurso provido; segue a informacao."})]
    (is (= t0 (:decidido-em d)) "decidir devolve o instante da decisao")
    (let [recurso (repo-part/buscar-recurso *repo* ente recurso-id)
          prazo   (repo-part/prazo-do-objeto *repo* ente "recurso_esic" recurso-id)]
      (is (= "decidido" (:estado recurso)) "recurso -> decidido (CAS)")
      (is (= t0 (:decidido-em recurso)) "decidido_em carimbado (instante injetado)")
      (is (= "cumprida" (:estado prazo)) "prazo do recurso -> cumprida"))
    (is (= 1 (count (eventos-por-tipo ente "participacao.recurso_esic.decidido")))
        "emite recurso_esic.decidido")))

(deftest decidir-recurso-ja-decidido-e-conflito
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)
        _ (controllers/responder-pedido! *repo* relogio (ator ente servidor) pedido-id {:corpo "r"})
        {recurso-id :id} (controllers/interpor-recurso! *repo* relogio (ator ente cidadao) pedido-id {:motivo "recorro"})]
    (controllers/decidir-recurso! *repo* relogio (ator ente servidor) recurso-id {:corpo "primeira decisao"})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ja decidido"
          (controllers/decidir-recurso! *repo* relogio (ator ente servidor) recurso-id {:corpo "segunda"}))
        "decidir um recurso ja decidido -> conflito de ciclo")))

;; ---------- resposta_esic e' APPEND-ONLY (Inv.10) ----------

(deftest resposta-esic-e-append-only
  ;; DUAS camadas de defesa append-only (Inv.10): (1) o role de runtime `oplenario_app` NAO tem grant de UPDATE
  ;; (mig 0040: GRANT SELECT,INSERT) -> a permissao e' negada ANTES mesmo do trigger (a mais forte); (2) o
  ;; trg_resposta_esic_append_only barra qualquer UPDATE/DELETE (defesa-em-profundidade p/ roles privilegiados).
  ;; Sob com-tenant* (runtime role), a camada (1) e' quem dispara: "permission denied".
  (let [ente (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {pedido-id :id} (protocolar! ente cidadao)
        _ (controllers/responder-pedido! *repo* relogio (ator ente servidor) pedido-id {:corpo "original"})
        resp-id (:resposta_esic/id (first (respostas-do-pedido ente pedido-id)))]
    (is (some? resp-id) "ha uma resposta gravada")
    (is (thrown-with-msg? Exception #"(?i)permission denied|append-only"
          (tenancy/com-tenant* *ds* ente
            (fn [tx] (jdbc/execute-one! tx ["UPDATE participacao.resposta_esic SET corpo = ? WHERE ente_id = ? AND id = ?"
                                            "adulterado" ente resp-id]))))
        "UPDATE numa resposta e' rejeitado (sem grant de UPDATE / trigger append-only)")))

;; ---------- ISOLAMENTO de tenant (RLS) ----------

(deftest tenant-nao-le-recurso-de-outro
  (let [ente-a (random-uuid) ente-b (random-uuid) cidadao (random-uuid) servidor (random-uuid)
        {pedido-id :id} (protocolar! ente-a cidadao)
        _ (controllers/responder-pedido! *repo* relogio (ator ente-a servidor) pedido-id {:corpo "r"})
        {recurso-id :id} (controllers/interpor-recurso! *repo* relogio (ator ente-a cidadao) pedido-id {:motivo "recorro"})]
    (is (nil? (repo-part/buscar-recurso *repo* ente-b recurso-id))
        "o recurso de ente-a nao e' visivel sob o tenant de ente-b (RLS isola)")
    (tenancy/com-tenant* *ds* ente-b
      (fn [tx]
        (is (empty? (jdbc/execute! tx ["SELECT id FROM participacao.recurso_esic WHERE id = ?" recurso-id]))
            "RLS: o recurso de ente-a nao aparece na visao de ente-b nem por SQL direto")))))
