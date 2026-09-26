(ns oplenario.integracao-ia.adapters.out.feed
  "Gate de SAIDA `dominio -> wire` da fronteira core -> IA (ADR-0008). Valida contra `wire/out` (drift = bug de
  servidor -> 500). O contexto da sessao carrega so' o que a IA precisa para transcrever e atribuir falas; a
  chave interna do store nunca sai (a IA le o conteudo pela `conteudo-uri`, via core)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.integracao-ia.logic :as logic]
            [oplenario.integracao-ia.wire.out :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validado [schema out msg]
  (when-not (m/validate schema out)
    (throw (ex-info msg {:campos (me/humanize (m/explain schema out))})))
  out)

(defn eventos->wire [depois eventos]
  (validado wire/EventosOut
            {:eventos (mapv (fn [e] {:seq (:seq e) :ente-id (->str (:ente-id e)) :tipo (:tipo e) :versao (:versao e)
                                     :chave (:chave e) :payload (:payload e) :criado-em (->str (:criado-em e))})
                            eventos)
             :proximo (if (seq eventos) (:seq (peek eventos)) depois)}
            "feed viola o contrato EventosOut (bug de servidor)"))

(defn contexto->wire
  "{:sessao :segmentos :falas} (segmentos ja' sem os restritos) + `nomes` {orador-id nome} -> ContextoSessaoOut."
  [ente-id {:keys [sessao segmentos falas]} nomes]
  (validado wire/ContextoSessaoOut
            {:sessao    {:id (->str (:id sessao)) :tipo-sessao (:tipo-sessao sessao)
                         :numero-sequencial (:numero-sequencial sessao) :estado (:estado sessao)
                         :aberta-em (->str (:aberta-em sessao)) :encerrada-em (->str (:encerrada-em sessao))}
             :segmentos (mapv (fn [s] {:id (->str (:id s)) :iniciou-em (->str (:iniciou-em s))
                                       :encerrou-em (->str (:encerrou-em s))
                                       :conteudo-uri (logic/uri-conteudo-gravacao ente-id (:id s))})
                              segmentos)
             :falas     (mapv (fn [f] {:id (->str (:id f)) :orador-id (->str (:orador-id f))
                                       :orador-nome (get nomes (:orador-id f))
                                       :tipo-fala (:tipo-fala f) :fase (:fase f)
                                       :fala-pai-id (->str (:fala-pai-id f))
                                       :iniciou-em (->str (:iniciou-em f)) :encerrou-em (->str (:encerrou-em f))})
                              falas)}
            "contexto viola o contrato ContextoSessaoOut (bug de servidor)"))

(defn recibo->wire [chave {:keys [aplicado]}]
  (validado wire/ReciboEventoOut {:chave chave :aplicado (boolean aplicado)}
            "recibo viola o contrato ReciboEventoOut (bug de servidor)"))
