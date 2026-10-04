(ns oplenario.legislativo.adapters.out.nota-tecnica
  "Gate de SAIDA `dominio -> wire/out` da nota tecnica de conferencia (Faixa B / B.8). Projeta campo a campo e valida
  contra o contrato fechado — o id de quem decidiu e a execucao do agente nao saem."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.logic :as logic]
            [oplenario.legislativo.wire.out.nota-tecnica :as wire]))

(set! *warn-on-reflection* true)

(defn- validado [schema out]
  (when-not (m/validate schema out)
    (throw (ex-info "nota tecnica viola o contrato (bug de servidor)" {:erros (me/humanize (m/explain schema out))})))
  out)

(defn- ->str [x] (some-> x (#(if (instance? java.util.Date %) (.toInstant ^java.util.Date %) %)) str))

(defn- resumo [n]
  {:id (->str (:id n)) :proposicao-id (->str (:proposicao-id n)) :tipo (:tipo n) :sequencial (:sequencial n)
   :ano (:ano n) :ementa (:ementa n) :estado (:estado n) :incerteza (:incerteza n)
   :criada-em (->str (:criada-em n)) :decidida-em (->str (:decidida-em n))})

(defn notas->wire
  "A fila. `casa-com-juridico` (padrao false) vem do seam do host: a Casa tem juridico ativo (ADR-0019 Eixo 5)."
  ([notas] (notas->wire notas false))
  ([notas casa-com-juridico]
   (validado wire/NotasTecnicasOut {:itens (mapv resumo notas) :casa-com-juridico (boolean casa-com-juridico)})))

(defn nota->wire [n]
  (validado wire/NotaTecnicaOut
            (merge (resumo n)
                   {:agente (:agente n) :texto (:texto n) :texto-limpo (logic/texto-limpo (:texto n))
                    :citacoes (mapv (fn [c] {:fonte-id (str (:fonte-id c)) :trecho (:trecho c) :status (:status c)
                                             :rotulo (:rotulo c)})
                                    (:citacoes n))
                    :paragrafos-sem-fonte (vec (:paragrafos-sem-fonte n))
                    :motivos-incerteza (vec (:motivos-incerteza n))
                    :modelo-llm-id (:modelo-llm-id n) :texto-final (:texto-final n)}
                   ;; so' quando ha' (nota anterior a 8.4 fica sem o campo); a execucao da CREDENCIAL nao sai
                   (when-let [ia (:execucao-ia n)] {:execucao-ia (str ia)}))))
