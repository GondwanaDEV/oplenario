(ns oplenario.participacao.adapters.out.comentario
  "Gate de SAIDA `models -> wire/out` do comentario (§22.10 adapters/out, ADR-0001) — chamado SO pelo
  diplomat/. `recibo->wire` projeta a resposta 201 (sem PII). `publico->wire` projeta CADA item da lista
  PUBLICA (comentarios-da-materia), FILTRANDO tenant/autor/proposicao/estado — a lista JA veio pre-filtrada
  por estado='aprovado' do Repo; aqui e' so' a projecao de campo. Validada contra wire/out (drift de campo =
  bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.comentario :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn recibo->wire
  "Recibo de criacao {:id :estado} -> ReciboOut (resposta 201). Sem PII, sem proposicao/autor."
  [r]
  (validar! wire/ReciboOut {:id (->str (:id r)) :estado (:estado r)} "ReciboOut"))

(defn publico->wire
  "Item da lista PUBLICA {:id :corpo :criado-em} -> PublicoOut. FILTRA autor/proposicao/tenant/estado."
  [c]
  (validar! wire/PublicoOut
            {:id (->str (:id c)) :corpo (:corpo c) :criado-em (->str (:criado-em c))}
            "PublicoOut"))

(defn publicos->wire
  "A lista publica inteira, item a item."
  [cs]
  (mapv publico->wire cs))
