(ns oplenario.participacao.adapters.out.meus-protocolos
  "Gate de SAIDA `models -> wire/out` de GET /portal/meus-protocolos (§22.10 adapters/out, ADR-0001). Projeta por
  ALLOWLIST (so' as chaves do contrato: nada de tenant, dono ou corpo) e VALIDA contra wire/out — drift e' bug de
  servidor (500), nunca resposta malformada."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.meus-protocolos :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- base [i]
  {:id (->str (:id i)) :protocolo (:protocolo i) :estado (:estado i) :recibo-em (->str (:recibo-em i))
   :vence-em (->str (:vence-em i)) :dias-restantes (:dias-restantes i)
   :resposta (when-let [r (:resposta i)] {:corpo (:corpo r) :respondida-em (->str (:respondida-em r))})})

(defn- anexos
  "Os anexos por ALLOWLIST (as 6 chaves do contrato): nada de chave no object storage, sha256 nem quem enviou."
  [xs]
  (mapv (fn [a] {:id (->str (:id a)) :nome (:nome a) :tipo-midia (:tipo-midia a) :bytes (:bytes a)
                 :origem (:origem a) :enviado-em (->str (:enviado-em a))})
        xs))

(defn- prorrogacao
  "A prorrogacao por ALLOWLIST (as 4 chaves do contrato): nada de quem prorrogou, id ou tenant, mesmo que o dominio os traga."
  [p]
  (when p
    {:de-data (->str (:de-data p)) :para-data (->str (:para-data p))
     :justificativa (:justificativa p) :prorrogado-em (->str (:prorrogado-em p))}))

(defn meus-protocolos->wire
  [{:keys [pedidos-esic solicitacoes-lgpd manifestacoes]}]
  (let [recurso (fn [r] (when r {:protocolo (:protocolo r) :estado (:estado r) :recibo-em (->str (:recibo-em r))
                                  :resposta (when-let [x (:resposta r)]
                                              {:corpo (:corpo x) :respondida-em (->str (:respondida-em x))})}))
        out {:pedidos-esic      (mapv #(assoc (base %) :assunto (:assunto %) :recurso (recurso (:recurso %))
                                              :prorrogacao (prorrogacao (:prorrogacao %)) :anexos (anexos (:anexos %)))
                                      pedidos-esic)
             :solicitacoes-lgpd (mapv #(assoc (base %) :tipo (:tipo %) :anexos (anexos (:anexos %))) solicitacoes-lgpd)
             :manifestacoes     (mapv #(assoc (base %) :tipo (:tipo %) :assunto (:assunto %)
                                              :prorrogacao (prorrogacao (:prorrogacao %)) :anexos (anexos (:anexos %)))
                                      manifestacoes)}]
    (when-not (m/validate wire/MeusProtocolosOut out)
      (throw (ex-info "projecao viola o contrato MeusProtocolosOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/MeusProtocolosOut out))})))
    out))
