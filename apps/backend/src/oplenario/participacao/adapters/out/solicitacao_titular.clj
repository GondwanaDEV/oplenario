(ns oplenario.participacao.adapters.out.solicitacao-titular
  "Gate de SAIDA `models -> wire/out` da SOLICITACAO do titular LGPD (§22.10 adapters/out, ADR-0001) — chamado SO
  pelo diplomat/. Projeta a solicitacao de dominio (kebab) p/ a representacao externa (strings, JSON) e FILTRA o
  que nao deve vazar: o tenant (ente-id) e o titular (PII). A projecao e' VALIDADA contra wire/out (drift de campo
  = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.solicitacao-titular :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn recibo->wire
  "Recibo de protocolo {:protocolo :recibo-em} -> SolicitacaoTitularReciboOut (resposta 201). Sem PII, sem id."
  [r]
  (validar! wire/SolicitacaoTitularReciboOut
            {:protocolo (:protocolo r)
             :recibo-em (->str (:recibo-em r))}
            "SolicitacaoTitularReciboOut"))

(defn resposta-recibo->wire
  "Recibo da resposta {:respondida-em} -> RespostaTitularReciboOut (resposta 200). So o carimbo do ato."
  [r]
  (validar! wire/RespostaTitularReciboOut
            {:respondida-em (->str (:respondida-em r))}
            "RespostaTitularReciboOut"))

(defn solicitacao->wire
  "Detalhe da solicitacao do proprio titular {:id :protocolo :tipo :detalhe :estado :recibo-em :vence-em
  :dias-restantes} -> SolicitacaoTitularOut. FILTRA tenant (ente-id) + titular (id de PII). detalhe/vence-em/
  dias-restantes nulaveis (presente por chave)."
  [s]
  (validar! wire/SolicitacaoTitularOut
            {:id             (->str (:id s))
             :protocolo      (:protocolo s)
             :tipo           (:tipo s)
             :detalhe        (:detalhe s)
             :estado         (:estado s)
             :recibo-em      (->str (:recibo-em s))
             :vence-em       (->str (:vence-em s))
             :dias-restantes (:dias-restantes s)}
            "SolicitacaoTitularOut"))
