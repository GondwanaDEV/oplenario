(ns oplenario.kernel.eventos
  "EventBus do kernel (§22.10): o envelope do evento de dominio + o protocolo `emitir!`.
  Parte PURA aqui (envelope + idempotency-key, §22.9 E2); a impl que grava no shared.outbox
  na tx corrente e' o producer (F0.2). O kernel nao importa modulo."
  (:require [malli.core :as m]
            [oplenario.kernel.ids :as ids]))

(defprotocol EventBus
  (emitir! [bus tx evento]
    "Publica o evento no outbox DENTRO da transacao `tx` (next.jdbc representa a tx pela
    connection/connectable — passar explicito, nao via var dinamica, p/ compor com seguranca).
    Atomicidade outbox-com-o-ato: a linha so existe se a tx commitar. Impl: o producer (F0.2)."))

(defn evento
  "Constroi o envelope de um evento de dominio. `tipo` (string, ex.: \"proposicao.protocolada\"),
  `ente-id` (UUID do tenant, ou nil p/ evento supratenant — admin_sistema), `payload` (mapa).
  Carimba uma idempotency-key unica — dedup at-least-once no consumidor por (consumidor, key) (§22.9 E2)."
  [tipo ente-id payload]
  {:pre [(string? tipo)
         (or (nil? ente-id) (uuid? ente-id))
         (map? payload)]}
  {:tipo            tipo
   :ente-id         ente-id
   :payload         payload
   :idempotency-key (str (ids/novo-id))})

(defn evento-validado
  "Constroi o envelope VALIDANDO `payload` contra `schema` (Malli) na fonte: lanca :payload-invalido se nao
  casa — o shared.outbox (cross-modulo e duravel, §22.9 E2) so recebe evento bem-formado. Reusavel por
  qualquer modulo que construa eventos com contrato (events/)."
  [schema tipo ente-id payload]
  (when-not (m/validate schema payload)
    (throw (ex-info (str "payload de " tipo " invalido (contrato do evento)")
                    {:erro :payload-invalido :explain (m/explain schema payload)})))
  (evento tipo ente-id payload))
