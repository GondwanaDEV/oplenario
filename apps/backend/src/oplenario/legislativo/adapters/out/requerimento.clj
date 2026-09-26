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
