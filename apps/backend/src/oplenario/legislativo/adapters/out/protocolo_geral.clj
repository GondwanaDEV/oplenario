(ns oplenario.legislativo.adapters.out.protocolo-geral
  "Gate de SAIDA `models -> wire/out` do PROTOCOLO GERAL (§22.10 adapters/out, ADR-0001, Onda B Slice 6)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.protocolo-geral :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- item->wire [{:keys [id numero ano objeto-tipo objeto-id sentido assunto protocolado-em]}]
  {:id (->str id) :numero numero :ano ano :objeto-tipo objeto-tipo :objeto-id (->str objeto-id)
   :sentido sentido :assunto assunto :protocolado-em (->str protocolado-em)})

(defn protocolo->wire
  "Uma linha de protocolo (dominio, kebab) -> ProtocoloGeralOut (validado)."
  [protocolo]
  (let [out (item->wire protocolo)]
    (when-not (m/validate wire/ProtocoloGeralOut out)
      (throw (ex-info "projecao de protocolo geral viola o contrato wire/out (bug de servidor)"
                      {:campos (keys (me/humanize (m/explain wire/ProtocoloGeralOut out)))})))
    out))

(defn livro->wire
  "Linhas de protocolo (dominio, kebab, ja' ORDENADAS pelo db/protocolo-geral.clj) -> LivroProtocoloOut
  (validado; envelope {:itens [...]}, ordem preservada)."
  [linhas]
  (let [out {:itens (mapv item->wire linhas)}]
    (when-not (m/validate wire/LivroProtocoloOut out)
      (throw (ex-info "livro do protocolo geral viola o contrato LivroProtocoloOut (bug de servidor)"
                      {:campos (keys (me/humanize (m/explain wire/LivroProtocoloOut out)))})))
    out))
