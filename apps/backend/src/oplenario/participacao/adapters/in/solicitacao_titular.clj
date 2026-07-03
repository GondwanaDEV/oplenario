(ns oplenario.participacao.adapters.in.solicitacao-titular
  "Gate de ENTRADA `wire/in -> models` da SOLICITACAO do titular LGPD (§22.10 adapters/in, ADR-0001) — chamado SO
  pelo diplomat/. Valida e coage o corpo externo (JSON strings) p/ o dominio, fail-closed (-> 400 via ex-info
  :validacao/invalido). ALLOWLIST estrita (tipo + detalhe?): o titular e o recibo sao INJETADOS do ator/relogio
  na borda, NUNCA vem do corpo (anti-forge). `detalhe` em branco e' tratado como AUSENTE (nil)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.solicitacao-titular :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(def ^:private campos ["tipo" "detalhe"])

(defn coagir-solicitar
  "Corpo JSON {tipo, detalhe?} (chaves STRING) -> mapa de dominio {:tipo :detalhe?}. ALLOWLIST (so-esperados)
  descarta campo forjado (titular, estado). `detalhe` em branco -> removido (tratado como ausente). O schema
  CLOSED valida `tipo` contra o enum dos 5 direitos + o teto de `detalhe` (espelha os CHECK da mig 0041)."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {tipo, detalhe?}" {:campo :corpo}))
  (let [mp0 (so-esperados json-params campos)
        d   (:detalhe mp0)
        ;; detalhe ausente/em branco == ausente (nil OU string vazia -> removido; nao gravar string vazia). Um
        ;; detalhe de tipo ERRADO (nao-string: numero/bool/objeto/array) NAO e' coagido aqui — cai no m/explain
        ;; abaixo (400), NUNCA no str/blank? (que exige CharSequence -> ClassCastException -> 500 espurio). Por
        ;; isso o guard `string?` ANTES do blank?: mantem o contrato fail-closed 400 (espelha os demais adapters).
        mp  (cond-> mp0 (or (nil? d) (and (string? d) (str/blank? d))) (dissoc :detalhe))]
    (when-let [erros (m/explain wire/SolicitarTitular mp)]
      (invalido! "corpo de solicitacao do titular invalido" {:campos (keys (me/humanize erros))}))
    (cond-> {:tipo (:tipo mp)}
      (contains? mp :detalhe) (assoc :detalhe (:detalhe mp)))))
