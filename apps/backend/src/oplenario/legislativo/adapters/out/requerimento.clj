(ns oplenario.legislativo.adapters.out.requerimento
  "Gate de SAIDA `dominio -> wire/out` do requerimento do vereador (§22.10 adapters/out, ADR-0001, fatia 2a).
  Projeta CAMPO A CAMPO e valida contra o contrato fechado — campo novo no dominio vira erro de servidor, nao
  vazamento silencioso."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.requerimento :as wire]))

(set! *warn-on-reflection* true)

(defn- validado [schema out msg]
  (when-not (m/validate schema out)
    (throw (ex-info msg {:erros (me/humanize (m/explain schema out))})))
  out)

(defn modelos->wire
  "[{:id :nome :campos}] -> ModelosRequerimentoOut."
  [modelos]
  (validado wire/ModelosRequerimentoOut
            {:itens (mapv (fn [{:keys [id nome campos]}] {:id (str id) :nome nome :campos (vec campos)}) modelos)}
            "modelos de requerimento violam o contrato ModelosRequerimentoOut (bug de servidor)"))

(defn previa->wire
  "{:texto} -> PreviaRequerimentoOut."
  [{:keys [texto]}]
  (validado wire/PreviaRequerimentoOut {:texto texto}
            "previa de requerimento viola o contrato PreviaRequerimentoOut (bug de servidor)"))

(defn protocolado->wire
  "Recibo do Repo/protocolar! ({:id :ano :sequencial :urn-lex :estado :assinatura}) -> RequerimentoProtocoladoOut."
  [{:keys [id ano sequencial urn-lex estado assinatura]}]
  (validado wire/RequerimentoProtocoladoOut
            {:proposicao-id (str id) :ano ano :sequencial sequencial :urn-lex urn-lex :estado estado
             :assinatura-algoritmo (:algoritmo assinatura)}
            "recibo do requerimento viola o contrato RequerimentoProtocoladoOut (bug de servidor)"))

;; ---------- fatia 2c: o requerimento COLETIVO ----------

(defn- ->str [x] (some-> x str))

(defn colegas->wire
  "[{:id :nome :partido}] -> ColegasOut."
  [colegas]
  (validado wire/ColegasOut
            {:itens (mapv (fn [{:keys [id nome partido]}] {:id (str id) :nome nome :partido partido}) colegas)}
            "colegas violam o contrato ColegasOut (bug de servidor)"))

(defn proposta->wire
  "A proposta (db/subscricao/buscar-proposta + :sou-autor/:minha-subscricao do controller) -> PropostaRequerimentoOut."
  [p]
  (validado wire/PropostaRequerimentoOut
            {:id (str (:id p)) :ementa (:ementa p) :tipo-requerimento (:tipo-requerimento p) :texto (:texto p)
             :autor-nome (:autor-nome p) :estado (:estado p) :proposicao-id (->str (:proposicao-id p))
             :criada-em (->str (:criada-em p)) :sou-autor (boolean (:sou-autor p))
             :minha-subscricao (:minha-subscricao p)
             :subscricoes (mapv (fn [s] {:vereador-nome (:vereador-nome s) :estado (:estado s)
                                         :respondida-em (->str (:respondida-em s))})
                                (:subscricoes p))}
            "proposta viola o contrato PropostaRequerimentoOut (bug de servidor)"))

(defn propostas->wire
  [linhas]
  (validado wire/PropostasOut
            {:itens (mapv (fn [l] {:id (str (:id l)) :ementa (:ementa l) :tipo-requerimento (:tipo-requerimento l)
                                   :criada-em (->str (:criada-em l)) :confirmadas (int (:confirmadas l))
                                   :pendentes (int (:pendentes l)) :recusadas (int (:recusadas l))})
                          linhas)}
            "propostas violam o contrato PropostasOut (bug de servidor)"))

(defn convites->wire
  [linhas]
  (validado wire/ConvitesSubscricaoOut
            {:itens (mapv (fn [l] {:proposta-id (str (:proposta-id l)) :ementa (:ementa l)
                                   :tipo-requerimento (:tipo-requerimento l) :autor-nome (:autor-nome l)
                                   :convidada-em (->str (:convidada-em l))})
                          linhas)}
            "convites violam o contrato ConvitesSubscricaoOut (bug de servidor)"))

(defn resposta->wire
  [r]
  (validado wire/RespostaSubscricaoOut
            {:proposta-id (str (:proposta-id r)) :estado (:estado r) :assinatura-algoritmo (:assinatura-algoritmo r)}
            "resposta de subscricao viola o contrato RespostaSubscricaoOut (bug de servidor)"))

(defn coletivo-protocolado->wire
  [{:keys [id ano sequencial urn-lex estado assinatura coautores]}]
  (validado wire/RequerimentoColetivoProtocoladoOut
            {:proposicao-id (str id) :ano ano :sequencial sequencial :urn-lex urn-lex :estado estado
             :assinatura-algoritmo (:algoritmo assinatura) :coautores (mapv :vereador-nome coautores)}
            "recibo do requerimento coletivo viola o contrato (bug de servidor)"))
