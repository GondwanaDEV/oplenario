(ns oplenario.participacao.adapters.out.moderacao-comentario
  "Gate de SAIDA `models -> wire/out` da MODERACAO de comentarios (§22.10 adapters/out, ADR-0001) — chamado
  SO pelo diplomat/. `recibo->wire` projeta a resposta 200 de moderar!. `item->wire` projeta CADA linha da
  fila de moderacao (rota INTERNA de servidor — autor/proposicao PODEM aparecer aqui, diferente da lista
  publica de comentario.clj). Validada contra wire/out (drift = bug de servidor -> 500)."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.out.moderacao-comentario :as wire]))

(set! *warn-on-reflection* true)

(defn- ->str [x] (some-> x str))

(defn- validar! [schema out rotulo]
  (when-not (m/validate schema out)
    (throw (ex-info (str "projecao viola o contrato " rotulo " (bug de servidor)")
                    {:erros (me/humanize (m/explain schema out))})))
  out)

(defn recibo->wire
  "Recibo da moderacao {:id :estado} -> ReciboOut (resposta 200)."
  [r]
  (validar! wire/ReciboOut {:id (->str (:id r)) :estado (:estado r)} "ReciboOut"))

(defn item->wire
  "Item da fila de moderacao {:id :proposicao-id :autor-identidade-id :corpo :denunciado :criado-em} ->
  ItemFilaOut. Rota INTERNA (servidor, exige-papel) — nao filtra PII (o moderador precisa ver o autor)."
  [c]
  (validar! wire/ItemFilaOut
            {:id                  (->str (:id c))
             :proposicao-id       (->str (:proposicao-id c))
             :autor-identidade-id (->str (:autor-identidade-id c))
             :corpo               (:corpo c)
             :denunciado          (boolean (:denunciado c))
             :criado-em           (->str (:criado-em c))}
            "ItemFilaOut"))

(defn fila->wire
  "A fila de moderacao inteira, item a item."
  [cs]
  (mapv item->wire cs))
