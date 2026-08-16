(ns oplenario.sessoes.folha-db-test
  "INTEGRACAO (PG real) — `db/presenca/serie-de-eventos-da-sessao` (§22.6 eixo C, Etapa 5 fatia 1): a SERIE
  cronologica de eventos por vereador NA JANELA [piso, teto] — insumo cru da FOLHA. Prova: (1) devolve TODOS
  os eventos do vereador na janela, ordenados por (vereador_id, ocorrido_em, fonte_precedencia desc, id); (2)
  evento fora da janela (antes do piso ou depois do teto) e' EXCLUIDO; (3) ambos os limites sao INCLUSIVOS."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.db.presenca :as presenca]
            [oplenario.sessoes.db.sessao :as sessao])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- nova-sessao! [tx ente]
  (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente
                            :sessao-legislativa-id (random-uuid) :tipo-sessao "ordinaria"})))

(defn- ev! [tx ente sessao-id vereador-id extra]
  (presenca/registrar-evento! tx (merge {:id (random-uuid) :ente-id ente :sessao-id sessao-id
                                         :vereador-id vereador-id :tipo "entrada" :modalidade "plenario"
                                         :fonte "manual_secretaria" :ocorrido-em (Instant/parse "2026-06-20T10:00:00Z")}
                                        extra)))

(def ^:private piso (Instant/parse "2026-06-20T00:00:00Z"))
(def ^:private teto (Instant/parse "2026-06-20T23:59:59Z"))
(def ^:private t-antes-do-piso (Instant/parse "2026-06-19T23:00:00Z"))
(def ^:private t-1403 (Instant/parse "2026-06-20T14:03:00Z"))
(def ^:private t-1510 (Instant/parse "2026-06-20T15:10:00Z"))
(def ^:private t-1540 (Instant/parse "2026-06-20T15:40:00Z"))
(def ^:private t-depois-do-teto (Instant/parse "2026-06-21T00:30:00Z"))

;; ---------- a SERIE inteira, ordenada, por vereador ----------

(deftest devolve-a-serie-cronologica-de-cada-vereador
  (let [ente (random-uuid)
        v1 (random-uuid) v2 (random-uuid)
        sid (tenancy/com-tenant* *ds* ente
              (fn [tx]
                (let [sid (nova-sessao! tx ente)]
                  (ev! tx ente sid v1 {:tipo "entrada" :ocorrido-em t-1403})
                  (ev! tx ente sid v1 {:tipo "saida" :ocorrido-em t-1510})
                  (ev! tx ente sid v1 {:tipo "retorno" :ocorrido-em t-1540})
                  (ev! tx ente sid v2 {:tipo "entrada" :ocorrido-em t-1403})
                  sid)))
        serie (tenancy/com-tenant* *ds* ente
                #(presenca/serie-de-eventos-da-sessao % ente sid piso teto))
        por-v (group-by :vereador-id serie)]
    (is (= 4 (count serie)) "os 4 eventos gravados aparecem, nenhum a mais nem a menos")
    (is (= ["entrada" "saida" "retorno"] (mapv :tipo (por-v v1)))
        "a serie de v1 vem em ordem CRONOLOGICA: entrou, saiu, retornou")
    (is (= [t-1403 t-1510 t-1540] (mapv :ocorrido-em (por-v v1))))
    (is (= ["entrada"] (mapv :tipo (por-v v2))))))

;; ---------- evento FORA da janela e' EXCLUIDO ----------

(deftest evento-fora-da-janela-e-excluido
  (let [ente (random-uuid)
        v1 (random-uuid)
        sid (tenancy/com-tenant* *ds* ente
              (fn [tx]
                (let [sid (nova-sessao! tx ente)]
                  (ev! tx ente sid v1 {:tipo "entrada" :ocorrido-em t-antes-do-piso})
                  (ev! tx ente sid v1 {:tipo "entrada" :ocorrido-em t-1403})
                  (ev! tx ente sid v1 {:tipo "saida" :ocorrido-em t-depois-do-teto})
                  sid)))
        serie (tenancy/com-tenant* *ds* ente
                #(presenca/serie-de-eventos-da-sessao % ente sid piso teto))]
    (is (= 1 (count serie))
        "so' o evento DENTRO da janela sobrevive — o de antes do piso e o de depois do teto vazam da sessao")
    (is (= t-1403 (:ocorrido-em (first serie))))))

;; ---------- os limites sao INCLUSIVOS ----------

(deftest limites-da-janela-sao-inclusivos
  (let [ente (random-uuid)
        v1 (random-uuid)
        sid (tenancy/com-tenant* *ds* ente
              (fn [tx]
                (let [sid (nova-sessao! tx ente)]
                  (ev! tx ente sid v1 {:tipo "entrada" :ocorrido-em piso})
                  (ev! tx ente sid v1 {:tipo "saida" :ocorrido-em teto})
                  sid)))
        serie (tenancy/com-tenant* *ds* ente
                #(presenca/serie-de-eventos-da-sessao % ente sid piso teto))]
    (is (= 2 (count serie)) "um evento EXATAMENTE no piso e outro EXATAMENTE no teto contam os dois")))
