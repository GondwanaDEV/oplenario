(ns oplenario.sessoes.adapters.out.gravacao
  "Gate de SAIDA `models -> wire/out` da gravacao (§22.10 adapters/out, ADR-0001 §3). Projeta o recibo de
  ingestao e o read-model de segmentos p/ a borda, FILTRANDO o que nao deve vazar: a chave interna do store
  (`container-bruto-uri`), `audio-hash`, `ente-id`, `lock-version`. Validado contra o contrato wire/out
  (drift de campo = bug de servidor -> 500, nunca resposta malformada que envenena o codegen do front)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out msg]
  (when-not (m/validate schema out)
    (throw (ex-info msg {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn recibo-ingestao->wire
  "Recibo de dominio {:id uuid :audio-hash string} -> GravacaoReciboOut (resposta 201). NAO inclui a chave do store."
  [{:keys [id audio-hash]}]
  (validado wire/GravacaoReciboOut {:id (->str id) :audio-hash audio-hash}
            "recibo de ingestao viola o contrato GravacaoReciboOut (bug de servidor)"))

(defn- segmento->wire [s]
  {:id              (->str (:id s))
   :sessao-id       (->str (:sessao-id s))
   :iniciou-em      (->str (:iniciou-em s))
   :encerrou-em     (->str (:encerrou-em s))
   :motivo-inicio   (:motivo-inicio s)
   :motivo-fim      (:motivo-fim s)
   :fonte-ingestao  (:fonte-ingestao s)
   :acesso-restrito (boolean (:acesso-restrito s))
   :audio-disponivel (some? (:audio-uri s))})

(defn segmentos->wire
  "{:sessao-id uuid :segmentos [seg...]} -> SegmentosOut (validado). Filtra os internos por segmento."
  [sessao-id segmentos]
  (validado wire/SegmentosOut
            {:sessao-id (->str sessao-id) :segmentos (mapv segmento->wire segmentos)}
            "read-model de gravacao viola o contrato SegmentosOut (bug de servidor)"))
