(ns oplenario.participacao.adapters.in.recurso-esic
  "Gate de ENTRADA `wire/in -> models` do RECURSO e-SIC (§22.10 adapters/in, ADR-0001) — chamado SO pelo
  diplomat/. Valida e coage o corpo externo (JSON strings) p/ o dominio, fail-closed (-> 400 via ex-info
  :validacao/invalido). ALLOWLIST estrita (so `motivo`): o pedido recorrido vem do :id do path, o solicitante/
  recibo/instancia sao INJETADOS na borda (anti-forge). A coercao do :id do pedido reusa adapters.in.pedido-esic
  (uuid fail-closed)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.recurso-esic :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(def ^:private campos ["motivo"])

(defn coagir-recurso
  "Corpo JSON {motivo} (chaves STRING) -> mapa de dominio {:motivo}. ALLOWLIST (so-esperados) descarta campo
  forjado (pedido_id, instancia, solicitante); o schema CLOSED + o nao-vazio espelham a CHECK da mig 0040."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {motivo}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos)]
    (when-let [erros (m/explain wire/InterporRecurso mp)]
      (invalido! "corpo de recurso e-SIC invalido" {:campos (keys (me/humanize erros))}))
    (when (str/blank? (:motivo mp)) (invalido! "motivo obrigatorio nao pode ser vazio" {:campo :motivo}))
    {:motivo (:motivo mp)}))
