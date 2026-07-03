(ns oplenario.participacao.adapters.out.pedido-esic
  "Gate de SAIDA `models -> wire/out` do pedido e-SIC (§22.10 adapters/out, ADR-0001) — chamado SO pelo
  diplomat/. Projeta o pedido de dominio (kebab) p/ a representacao externa (strings, JSON) e FILTRA o que
  nao deve vazar: o tenant (ente-id) e o solicitante (PII). A projecao e' VALIDADA contra wire/out (drift de
  campo = bug de servidor -> 500, nunca resposta malformada que envenena o codegen do front)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.pedido-esic :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    ;; o erro global LOGA (nao vai ao corpo) — precedente compliance/adapters-out.
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn recibo->wire
  "Recibo de protocolo {:protocolo :recibo-em} -> ReciboOut (resposta 201). Sem PII, sem id interno."
  [r]
  (validar! wire/ReciboOut
            {:protocolo (:protocolo r)
             :recibo-em (->str (:recibo-em r))}
            "ReciboOut"))

(defn pedido->wire
  "Detalhe do pedido do proprio solicitante {:id :protocolo :assunto :descricao :estado :recibo-em :vence-em
  :dias-restantes} -> PedidoOut. FILTRA tenant (ente-id) + solicitante (id de PII). vence-em/dias-restantes
  nulaveis (pedido sem prazo ativo)."
  [p]
  (validar! wire/PedidoOut
            {:id             (->str (:id p))
             :protocolo      (:protocolo p)
             :assunto        (:assunto p)
             :descricao      (:descricao p)
             :estado         (:estado p)
             :recibo-em      (->str (:recibo-em p))
             :vence-em       (->str (:vence-em p))
             :dias-restantes (:dias-restantes p)}
            "PedidoOut"))
