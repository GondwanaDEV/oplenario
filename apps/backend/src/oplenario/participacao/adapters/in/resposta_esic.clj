(ns oplenario.participacao.adapters.in.resposta-esic
  "Gate de ENTRADA `wire/in -> models` da RESPOSTA e-SIC (§22.10 adapters/in, ADR-0001) — chamado SO pelo
  diplomat/. Coage o corpo {corpo} das rotas de SERVIDOR (responder pedido / decidir recurso), fail-closed
  (-> 400). ALLOWLIST estrita (so `corpo`): respondido-por/respondida-em sao INJETADOS do ator/relogio na
  borda (anti-forge). O ALVO (pedido vs recurso) e' decidido pela ROTA, nunca pelo corpo — a mesma coercao
  serve as duas rotas."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.resposta-esic :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(def ^:private campos ["corpo"])

(defn coagir-resposta
  "Corpo JSON {corpo} (chaves STRING) -> mapa de dominio {:corpo}. ALLOWLIST descarta campo forjado
  (pedido_id/recurso_id/respondido_por); schema CLOSED + nao-vazio espelham a CHECK da mig 0040. Serve
  responder-pedido E decidir-recurso (forma identica)."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {corpo}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos)]
    (when-let [erros (m/explain wire/RespostaEsicIn mp)]
      (invalido! "corpo de resposta e-SIC invalido" {:campos (keys (me/humanize erros))}))
    (when (str/blank? (:corpo mp)) (invalido! "corpo obrigatorio nao pode ser vazio" {:campo :corpo}))
    {:corpo (:corpo mp)}))
