(ns oplenario.participacao.adapters.out.recurso-esic
  "Gate de SAIDA `models -> wire/out` do RECURSO e-SIC (§22.10 adapters/out, ADR-0001) — chamado SO pelo
  diplomat/. Projeta o recibo/decisao do recurso p/ a representacao externa (strings, JSON) e FILTRA o que
  nao deve vazar (tenant, pedido_id, ids internos). A projecao e' VALIDADA contra wire/out (drift de campo =
  bug de servidor -> 500, nunca resposta malformada que envenena o codegen do front)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.recurso-esic :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn recibo->wire
  "Recibo de interposicao {:protocolo :recibo-em} -> RecursoReciboOut (resposta 201). Sem PII, sem id interno."
  [r]
  (validar! wire/RecursoReciboOut
            {:protocolo (:protocolo r)
             :recibo-em (->str (:recibo-em r))}
            "RecursoReciboOut"))

(defn decisao->wire
  "Recibo de decisao {:decidido-em} -> DecisaoReciboOut (resposta 200). So o carimbo do desfecho."
  [r]
  (validar! wire/DecisaoReciboOut
            {:decidido-em (->str (:decidido-em r))}
            "DecisaoReciboOut"))
