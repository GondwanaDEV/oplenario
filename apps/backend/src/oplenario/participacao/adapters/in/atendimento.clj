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

(defn coagir-complemento
  "Corpo JSON {corpo} (chaves STRING) -> {:corpo}. Texto obrigatorio (ausente, nulo, vazio ou so' espacos -> 400), com o
  teto do texto da resposta. ALLOWLIST: autor e instante nunca vem do cliente (campo a mais e' descartado/recusado)."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {corpo}" {:campo :corpo}))
  (let [mp (cond-> {} (contains? json-params "corpo") (assoc :corpo (get json-params "corpo")))]
    (when-let [erros (m/explain wire/ComplementoIn mp)]
      (invalido! "corpo do complemento invalido" {:campos (keys (me/humanize erros))}))
    (when (str/blank? (:corpo mp)) (invalido! "o texto do complemento e obrigatorio" {:campo :corpo}))
    {:corpo (:corpo mp)}))

(defn coagir-retirar-anexo
  "Corpo JSON {motivo} (chaves STRING) -> {:motivo}, aparado. Motivo obrigatorio (ausente, nulo, vazio ou so' espacos -> 400)."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {motivo}" {:campo :corpo}))
  (let [mp (cond-> {} (contains? json-params "motivo") (assoc :motivo (get json-params "motivo")))]
    (when-let [erros (m/explain wire/RetirarAnexoIn mp)]
      (invalido! "corpo da retirada do anexo invalido" {:campos (keys (me/humanize erros))}))
    (when (str/blank? (:motivo mp)) (invalido! "o motivo da retirada e obrigatorio" {:campo :motivo}))
    {:motivo (str/trim (:motivo mp))}))

(defn coagir-substituir-anexo
  "Campos de texto do multipart da substituicao (`(:request :campos-do-envio)`, chaves keyword, so' as pedidas pela rota) ->
  {:motivo}, aparado. Motivo obrigatorio (ausente, vazio ou so' espacos -> 400), com o teto da retirada. E' conferido ANTES de o
  blob subir: um motivo faltando nao deixa rastro no object storage."
  [campos]
  (let [mp (cond-> {} (some? (get campos :motivo)) (assoc :motivo (get campos :motivo)))]
    (when-let [erros (m/explain wire/SubstituirAnexoIn mp)]
      (invalido! "motivo da substituicao do anexo invalido" {:campos (keys (me/humanize erros))}))
    (when (str/blank? (:motivo mp)) (invalido! "o motivo da substituicao e obrigatorio" {:campo :motivo}))
    {:motivo (str/trim (:motivo mp))}))
