(ns oplenario.participacao.adapters.in.atendimento
  "Gate de ENTRADA `wire/in -> models` do BALCAO interno de atendimento (§22.10 adapters/in, ADR-0001) — chamado SO
  pelo diplomat/. Coage a `situacao` da query das filas (ausente -> `abertos`; fora do vocabulario -> 400) e o corpo
  da prorrogacao do e-SIC (so' `justificativa`; as datas sao SEMPRE calculadas no controller), fail-closed."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.atendimento :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn situacao
  "query-params (keyword->string do Pedestal) -> a situacao da fila. Ausente/branco = `abertos`."
  [query-params]
  (let [s (:situacao query-params)]
    (cond
      (or (nil? s) (and (string? s) (str/blank? s))) "abertos"
      (m/validate wire/SituacaoIn s) s
      :else (invalido! "situacao invalida (abertos|respondidos|todos)" {:campo :situacao}))))

(defn coagir-prorrogar-pedido
  "Corpo JSON {justificativa} (chaves STRING) -> {:justificativa}. ALLOWLIST: de/para nunca vem do cliente."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {justificativa}" {:campo :corpo}))
  (let [mp (cond-> {} (contains? json-params "justificativa") (assoc :justificativa (get json-params "justificativa")))]
    (when-let [erros (m/explain wire/ProrrogarPedidoIn mp)]
      (invalido! "corpo de prorrogacao do pedido invalido" {:campos (keys (me/humanize erros))}))
    (when (str/blank? (:justificativa mp)) (invalido! "justificativa obrigatoria nao pode ser vazia" {:campo :justificativa}))
    {:justificativa (:justificativa mp)}))
