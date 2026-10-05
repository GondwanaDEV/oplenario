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

(defn- votacao->wire
  "ALLOWLIST de campos: so' o que a ata cita. O voto por vereador nao esta' entre eles (e o wire e' fechado)."
  [v]
  (-> (select-keys v [:objeto :modalidade :quorum-tipo :votos-necessarios :base-membros :resultado
                      :total-sim :total-nao :total-abstencao])
      (assoc :id (->str (:id v)) :encerrada-em (->str (:encerrada-em v)))))

(defn contexto->wire
  "{:sessao :segmentos :falas :votacoes} (segmentos ja' sem os restritos; `votacoes` ausente = sem votacoes) + `nomes`
  {orador-id nome} -> ContextoSessaoOut."
  [ente-id {:keys [sessao segmentos falas votacoes]} nomes]
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
                              falas)
             :votacoes  (mapv votacao->wire votacoes)}
            "contexto viola o contrato ContextoSessaoOut (bug de servidor)"))

(defn recibo->wire [chave {:keys [aplicado]}]
  (validado wire/ReciboEventoOut {:chave chave :aplicado (boolean aplicado)}
            "recibo viola o contrato ReciboEventoOut (bug de servidor)"))

(defn ata->wire [a]
  (validado wire/AtaPublicadaOut {:versao (:versao a) :texto (:texto a) :conteudo-sha256 (:conteudo-sha256 a)
                                  :origem-redacao (:origem-redacao a)}
            "ata viola o contrato AtaPublicadaOut (bug de servidor)"))

(defn texto-proposicao->wire [t]
  (validado wire/TextoProposicaoOut
            {:proposicao-id (str (:proposicao-id t)) :tipo (:tipo t) :ano (:ano t) :sequencial (:sequencial t)
             :ementa (:ementa t) :autor-texto (:autor-texto t) :texto (:texto t) :texto-sha256 (:texto-sha256 t)}
            "texto viola o contrato TextoProposicaoOut (bug de servidor)"))

(defn dispositivos-da-norma->wire [v]
  (validado wire/DispositivosDaNormaOut
            {:norma-id (str (:norma-id v)) :versao-id (str (:id v)) :especie (get-in v [:norma :especie])
             :titulo (get-in v [:norma :titulo]) :consolidada-ate (some-> (:consolidada-ate v) str)
             :dispositivos (mapv #(select-keys % [:endereco :rotulo :tipo :texto :agrupador]) (:dispositivos v))}
            "dispositivos violam o contrato DispositivosDaNormaOut (bug de servidor)"))
