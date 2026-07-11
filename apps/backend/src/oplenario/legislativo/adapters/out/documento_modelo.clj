(ns oplenario.legislativo.adapters.out.documento-modelo
  "Gate de SAIDA `models -> wire/out` do MODELO de documento (§22.10 adapters/out, ADR-0001, Onda B Slice 6)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.out.documento-modelo :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- item->wire [{:keys [id chave nome tipo-documento]}]
  {:id (->str id) :chave chave :nome nome :tipo-documento tipo-documento})

(defn modelo->wire
  "Uma linha de modelo (dominio, kebab) -> DocumentoModeloOut (validado)."
  [modelo]
  (let [out (item->wire modelo)]
    (when-not (m/validate wire/DocumentoModeloOut out)
      (throw (ex-info "projecao de modelo de documento viola o contrato wire/out (bug de servidor)"
                      {:campos (keys (me/humanize (m/explain wire/DocumentoModeloOut out)))})))
    out))

(defn modelos->wire
  "Linhas de modelo (dominio, kebab) -> ListaModelosOut (validado; envelope {:itens [...]})."
  [linhas]
  (let [out {:itens (mapv item->wire linhas)}]
    (when-not (m/validate wire/ListaModelosOut out)
      (throw (ex-info "lista de modelos de documento viola o contrato ListaModelosOut (bug de servidor)"
                      {:campos (keys (me/humanize (m/explain wire/ListaModelosOut out)))})))
    out))
