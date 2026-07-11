(ns oplenario.legislativo.adapters.out.documento
  "Gate de SAIDA `models -> wire/out` do documento gerado (§22.10 adapters/out, ADR-0001, Onda B Slice 6).
  `documento->wire` projeta+valida (mesmo padrao `validado` de adapters.out.parecer/adapters.out.proposicao).
  Aceita OPCIONALMENTE `protocolo` (a linha ja' buscada do protocolo geral vinculado, kebab) — o controller
  junta as duas leituras (documento + protocolo, se `protocolo-geral-id` presente) NUMA tx e repassa aqui;
  este adapter nunca busca nada, so' projeta o que ja' recebeu."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.documento :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out tipo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao de " tipo " viola o contrato wire/out (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn documento->wire
  "documento (dominio, kebab) + `protocolo` OPCIONAL (a linha do protocolo geral vinculado, kebab, ou nil se
  ainda nao protocolado) -> DocumentoOut."
  ([documento] (documento->wire documento nil))
  ([{:keys [id modelo-id tipo-documento assunto corpo estado protocolo-geral-id lock-version criado-em]}
    protocolo]
   (validado wire/DocumentoOut
             {:id (->str id) :modelo-id (->str modelo-id) :tipo-documento tipo-documento :assunto assunto
              :corpo corpo :estado estado :protocolo-geral-id (->str protocolo-geral-id)
              :protocolo-numero (:numero protocolo) :protocolo-ano (:ano protocolo)
              :lock-version lock-version :criado-em (->str criado-em)}
             "documento")))
