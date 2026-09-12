(ns oplenario.kernel.outbox
  "Bus transacional (outbox->relay->consumidor, §22.9 E2/E3). O producer grava o evento no
  shared.outbox DENTRO da tx do ato (atomicidade outbox-com-o-ato: a linha so existe se a tx
  commitar). O relay drena pendentes (SELECT FOR UPDATE SKIP LOCKED), despacha a cada consumidor
  registrado p/ o 'tipo', com dedup CAS no ledger de inbox (§22.9 E2), e marca processado — tudo
  na MESMA tx por evento => effectively-once (rollback retenta). Kernel — nao importa modulo."
  (:require [oplenario.kernel.eventos :as eventos]
            [next.jdbc :as jdbc]
            [jsonista.core :as json]
            [clojure.tools.logging :as log])
  (:import (org.postgresql.util PGobject)))

(set! *warn-on-reflection* true)

(defn- ->jsonb ^PGobject [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-value-as-string x))))

(defn- jsonb-> [v]
  (if (instance? PGobject v)
    (json/read-value (.getValue ^PGobject v) json/keyword-keys-object-mapper)
    v))

;; ---------------------------------------------------------------------------
;; Producer
;; ---------------------------------------------------------------------------
(defrecord OutboxBus []
  eventos/EventBus
  (emitir! [_ tx evento]
    (jdbc/execute-one! tx
                       ["INSERT INTO shared.outbox (ente_id, tipo, payload, idempotency_key)
                         VALUES (?, ?, ?, ?)"
                        (:ente-id evento) (:tipo evento)
                        (->jsonb (:payload evento)) (:idempotency-key evento)])))

(defn bus
  "O EventBus que grava no shared.outbox. Stateless — `emitir!` recebe a tx do ato."
  []
  (->OutboxBus))

;; ---------------------------------------------------------------------------
;; Registro de consumidores (broadcast por tipo): {tipo [{:nome :handler}]}.
;; handler = (fn [tx evento]) — roda DENTRO da tx do relay (efeitos atomicos com o dedup+processado).
;; ---------------------------------------------------------------------------
(defn registrar
  "Adiciona um consumidor (nome + handler [tx evento]) para um tipo de evento."
  [registro nome tipo handler]
  (update registro tipo (fnil conj []) {:nome nome :handler handler}))

;; ---------------------------------------------------------------------------
;; Relay
;; ---------------------------------------------------------------------------
(defn- row->evento [row]
  ;; `:id` (a PK bigint da linha em shared.outbox) entrou p/ frente 'relay-tolerante': um handler
  ;; que TOLERA payload malformado (loga e descarta em vez de lancar) precisa nomear, no log, a linha
  ;; EXATA que descartou — a `idempotency-key` sozinha exige um SELECT extra p/ achar a linha; o `:id`
  ;; e' a chave primaria, direto. Chave NOVA no mapa; nenhum handler existente quebra (todos destruturam
  ;; via `{:keys [...]}`, que ignora chaves extras).
  {:id              (:outbox/id row)
   :tipo            (:outbox/tipo row)
   :ente-id         (:outbox/ente_id row)
   :payload         (jsonb-> (:outbox/payload row))
   :idempotency-key (:outbox/idempotency_key row)})

(defn- drenar-um!
  "Processa UM evento pendente em sua propria tx. Retorna true se processou algo, false se nada pendente."
  [ds registro]
  (jdbc/with-transaction [tx ds]
    (if-let [row (jdbc/execute-one! tx
                                    ["SELECT id, ente_id, tipo, payload, idempotency_key
                                      FROM shared.outbox
                                      WHERE processed_at IS NULL
                                      ORDER BY id
                                      FOR UPDATE SKIP LOCKED
                                      LIMIT 1"])]
      (let [ik   (:outbox/idempotency_key row)
            ente (:outbox/ente_id row)
            ev   (row->evento row)
            consumidores (get registro (:outbox/tipo row))]
        (when (empty? consumidores)
          (log/debug "relay: nenhum consumidor para tipo" (:outbox/tipo row) "id" (:outbox/id row)))
        (doseq [{:keys [nome handler]} consumidores]
          ;; CAS: insere no ledger; se conflitar (ja consumido), nao retorna linha -> nao chama o handler.
          (when (jdbc/execute-one! tx
                                   ["INSERT INTO shared.evento_consumido (consumidor, idempotency_key, ente_id)
                                     VALUES (?, ?, ?) ON CONFLICT DO NOTHING RETURNING consumidor"
                                    nome ik ente])
            (handler tx ev)))
        (jdbc/execute-one! tx ["UPDATE shared.outbox SET processed_at = now() WHERE id = ?"
                               (:outbox/id row)])
        true)
      false)))

(defn drenar!
  "Drena TODOS os eventos pendentes (cada um em sua tx). Retorna o nº de eventos processados."
  [ds registro]
  (loop [n 0]
    (if (drenar-um! ds registro)
      (recur (inc n))
      n)))

(defn limpar-consumidos!
  "Poda o ledger de inbox: remove entradas com mais de `dias` dias (retencao >> janela de retry do
  relay; uma entrada ainda pendente apos isso e' anomalia, nao retry legitimo). Retorna o nº removido."
  [ds dias]
  (:next.jdbc/update-count
   (jdbc/execute-one! ds ["DELETE FROM shared.evento_consumido
                           WHERE processado_em < now() - make_interval(days => ?::int)" dias])))
