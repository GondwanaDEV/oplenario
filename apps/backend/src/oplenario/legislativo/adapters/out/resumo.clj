(ns oplenario.legislativo.adapters.out.resumo
  "Gate de SAIDA `dominio -> wire/out` do resumo cidadao (Faixa A / A.8, §22.10 adapters/out). Projeta campo a campo e
  valida contra o contrato fechado. O `detalhe` da falha da IA nao sai (e' texto interno do satelite): so' a categoria."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.resumo :as wire]))

(set! *warn-on-reflection* true)

(defn- validado [schema out msg]
  (when-not (m/validate schema out)
    (throw (ex-info msg {:erros (me/humanize (m/explain schema out))})))
  out)

(defn- ->str [x] (some-> x str))

(defn- ponteiro->wire [base r]
  {:situacao (:situacao r) :rascunho-id (->str (:rascunho-id r))
   :desatualizado (boolean (and (:texto-base-sha256 r) (not= base (:texto-base-sha256 r))))
   :modelo-llm-id (:modelo-llm-id r) :prompt-versao (:prompt-versao r) :incerteza (:incerteza r)
   :n-citacoes (:n-citacoes r) :n-citacoes-conferidas (:n-citacoes-conferidas r)
   :n-paragrafos-sem-fonte (:n-paragrafos-sem-fonte r) :categoria-erro (:categoria-erro r)
   :retentavel (:retentavel r) :ocorrido-em (->str (:ocorrido-em r))})

(defn- versao->wire [base v]
  {:versao (:versao v) :conteudo-sha256 (:conteudo-sha256 v)
   :desatualizado (not= base (:texto-base-sha256 v))
   :origem-redacao (:origem-redacao v) :rascunho-id (->str (:rascunho-id v)) :modelo-llm-id (:modelo-llm-id v)
   :prompt-versao (:prompt-versao v) :publicado-por (->str (:publicado-por v)) :publicado-em (->str (:publicado-em v))})

(defn resumo->wire [{:keys [proposicao texto-base-sha256 rascunho atual versoes]}]
  (validado wire/ResumoProposicaoOut
            {:proposicao-id (->str (:id proposicao))
             :rascunho (some->> rascunho (ponteiro->wire texto-base-sha256))
             :atual (when atual {:versao (versao->wire texto-base-sha256 atual) :texto (:texto atual)})
             :versoes (mapv (partial versao->wire texto-base-sha256) versoes)}
            "resumo viola o contrato ResumoProposicaoOut (bug de servidor)"))

(defn conteudo-rascunho->wire
  "O mapa que a IA devolveu + o ponteiro do core + a versao atual do texto -> RascunhoResumoOut. So' as chaves do
  contrato atravessam."
  [{:keys [ponteiro texto-base-sha256] :as r}]
  (validado wire/RascunhoResumoOut
            {:rascunho-id (->str (:rascunho-id ponteiro)) :texto (:texto r) :texto-limpo (:texto-limpo r)
             :incerteza {:nivel (get-in r [:incerteza :nivel]) :motivos (vec (get-in r [:incerteza :motivos]))}
             :citacoes (mapv (fn [c] {:fonte-id (:fonte-id c) :trecho (:trecho c) :status (:status c)
                                      :rotulo (:rotulo c)})
                             (:citacoes r))
             :paragrafos-sem-fonte (vec (:paragrafos-sem-fonte r))
             :modelo-llm-id (:modelo-llm-id ponteiro) :prompt-versao (:prompt-versao ponteiro)
             :desatualizado (not= texto-base-sha256 (:texto-base-sha256 ponteiro))
             :execucao-ia (not-empty (some-> (:execucao-id r) str))}
            "rascunho da IA viola o contrato RascunhoResumoOut"))

(defn recibo->wire [v]
  (validado wire/ResumoReciboOut {:versao (:versao v) :conteudo-sha256 (:conteudo-sha256 v)}
            "recibo viola o contrato ResumoReciboOut (bug de servidor)"))
