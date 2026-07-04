(ns oplenario.legislativo.adapters.out.relator-pendente
  "Gate de SAIDA `models -> wire/out` da fila de relatores pendentes (§22.10 adapters/out, ADR-0001,
  FE Onda A1)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.relator-pendente :as wire]))

(set! *warn-on-reflection* true)

(defn- item->wire [{:keys [id proposicao-id tipo ano sequencial urn-lex ementa criado-em]}]
  {:id (str id) :proposicao-id (str proposicao-id) :tipo tipo :ano ano :sequencial sequencial
   :urn-lex urn-lex :ementa ementa :criado-em (str criado-em)})

(defn relatores-pendentes->wire
  "Linhas cruas (kebab, do db) -> RelatoresPendentesOut (validado)."
  [linhas]
  (let [out {:itens (mapv item->wire linhas)}]
    (when-not (m/validate wire/RelatoresPendentesOut out)
      (throw (ex-info "fila de relatores pendentes viola o contrato RelatoresPendentesOut (bug de servidor)"
                      {:erros (me/humanize (m/explain wire/RelatoresPendentesOut out))})))
    out))
