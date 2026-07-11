(ns oplenario.legislativo.adapters.out.autografo
  "Gate de SAIDA `models -> wire/out` do AUTOGRAFO (§22.10 adapters/out, ADR-0001, Onda B Slice 7, F3.8a).
  `autografo->wire` projeta+valida (mesmo padrao `validado` de adapters.out.documento/adapters.out.parecer)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.autografo :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out tipo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao de " tipo " viola o contrato wire/out (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn autografo->wire
  "autografo (dominio, kebab) -> AutografoOut."
  [{:keys [id proposicao-id numero ano texto-versao-id destinatario-texto destinatario-id
           enviado-em prazo-resposta-em]}]
  (validado wire/AutografoOut
            {:id (->str id) :proposicao-id (->str proposicao-id) :numero numero :ano ano
             :texto-versao-id (->str texto-versao-id) :destinatario-texto destinatario-texto
             :destinatario-id (->str destinatario-id) :enviado-em (->str enviado-em)
             :prazo-resposta-em (->str prazo-resposta-em)}
            "autografo"))
