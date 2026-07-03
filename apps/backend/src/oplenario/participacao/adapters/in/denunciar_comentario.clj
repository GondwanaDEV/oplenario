(ns oplenario.participacao.adapters.in.denunciar-comentario
  "Gate de ENTRADA `wire/in -> models` da DENUNCIA de um comentario (§22.10 adapters/in, ADR-0001) —
  chamado SO pelo diplomat/. Coage o corpo {motivo?} da rota de CIDADAO (POST /portal/comentarios/:id/
  denunciar), fail-closed (-> 400 se o schema nao casar). `motivo` e' OPCIONAL (diferente do motivo de
  rejeicao da moderacao — texto livre, sem vocabulario fixo). `motivo` em branco e' tratado como AUSENTE
  (nil) — espelha `solicitacao-titular` (mesmo CHECK IS NULL OR length(trim(x)) > 0 no banco)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.denunciar-comentario :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(def ^:private campos ["motivo"])

(defn coagir-denunciar
  "Corpo JSON {motivo?} (chave STRING) -> mapa de dominio {:motivo}. ALLOWLIST descarta campo forjado.
  `motivo` ausente/em branco -> removido (evita 500 espurio no CHECK
  `length(trim(motivo)) > 0` da mig 0043; o guard `string?` ANTES do blank? evita ClassCastException em
  valor nao-string, que cai no m/explain -> 400, nunca no blank? -> 500)."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {motivo?}" {:campo :corpo}))
  (let [mp0 (so-esperados json-params campos)
        v   (:motivo mp0)
        mp  (cond-> mp0 (or (nil? v) (and (string? v) (str/blank? v))) (dissoc :motivo))]
    (when-let [erros (m/explain wire/DenunciarComentarioIn mp)]
      (invalido! "corpo de denuncia de comentario invalido" {:campos (keys (me/humanize erros))}))
    {:motivo (:motivo mp)}))
