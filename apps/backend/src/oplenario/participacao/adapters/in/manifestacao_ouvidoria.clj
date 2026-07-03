(ns oplenario.participacao.adapters.in.manifestacao-ouvidoria
  "Gate de ENTRADA `wire/in -> models` da manifestacao de ouvidoria (§22.10 adapters/in, ADR-0001) —
  chamado SO pelo diplomat/. Valida e coage a representacao externa (JSON: strings) p/ o dominio,
  defendendo a borda (fail-closed -> 400 via ex-info :validacao/invalido). ALLOWLIST estrita (tipo+assunto+
  descricao+anonima): o manifestante e o recibo sao INJETADOS do ator/relogio na borda, NUNCA vem do corpo
  (anti-forge). Reusa `id-param->uuid`/`ente-param->uuid` de adapters.in.pedido-esic (mesma coercao de path)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.participacao.wire.in.manifestacao-ouvidoria :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(def ^:private campos ["tipo" "assunto" "descricao" "anonima"])

(defn coagir-manifestacao
  "Corpo JSON {tipo, assunto, descricao, anonima?} (chaves STRING) -> mapa de dominio. ALLOWLIST descarta
  campo forjado (estado, manifestante); o schema CLOSED + os nao-vazios espelham os CHECK da mig 0042.
  `anonima` ausente = false (default explicito — nao confiar em nil implicito rio abaixo)."
  [json-params]
  (when-not (map? json-params)
    (invalido! "corpo deve ser objeto JSON {tipo, assunto, descricao, anonima?}" {:campo :corpo}))
  (let [mp (so-esperados json-params campos)]
    (when-let [erros (m/explain wire/ManifestacaoOuvidoriaIn mp)]
      (invalido! "corpo de manifestacao de ouvidoria invalido" {:campos (keys (me/humanize erros))}))
    (when (str/blank? (:assunto mp)) (invalido! "assunto obrigatorio nao pode ser vazio" {:campo :assunto}))
    (when (str/blank? (:descricao mp)) (invalido! "descricao obrigatoria nao pode ser vazia" {:campo :descricao}))
    {:tipo (:tipo mp) :assunto (:assunto mp) :descricao (:descricao mp) :anonima (boolean (:anonima mp))}))
