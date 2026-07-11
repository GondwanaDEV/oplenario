(ns oplenario.legislativo.adapters.in.pos-aprovacao
  "Gate de ENTRADA `wire/in -> models` do POS-APROVACAO (§22.10 adapters/in, ADR-0001, Onda B Slice 7,
  F3.8a). Chamado SO pelo diplomat/. Valida (fail-closed -> 400) e COAGE o corpo JSON p/ o dominio; INJETA
  o que nao vem do corpo (`id`/`created-by`/`updated-by` do ator, `proposicao-id`/`veto-votacao-id` p/ UUID,
  nunca do cliente cru, §22.5). `ano`/`destinatario-texto`/`texto-versao-id` do autografo NUNCA saem daqui:
  resolvidos pelo CONTROLLER (fase 2) — este adapter so' ve o wire-in cru."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.legislativo.wire.in.pos-aprovacao :as wire])
  (:import (java.time Instant)
           (java.time.format DateTimeParseException)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados
  "mapa STRING-keyed -> mapa keyword-keyed contendo SO os `campos` presentes (keyword ja internada)."
  [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    ;; guarda so os nomes-de-campo humanizados (NUNCA o payload cru — m/explain embute :value = vazaria dado).
    (invalido! msg {:campos (keys (me/humanize erros))})))

(defn- ->uuid [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn- ->instante [s campo]
  (when s
    (try (Instant/parse s) (catch DateTimeParseException _ (invalido! "instante invalido (ISO-8601)" {:campo campo})))))

(def ^:private campos-gerar ["prazo-resposta-em"])
(def ^:private campos-resposta ["lock-version" "resultado" "veto-tipo" "veto-razoes"])
(def ^:private campos-apreciar ["lock-version" "resultado" "veto-votacao-id"])

(defn gerar-autografo->dominio
  "`proposicao-id` (path, ja' UUID) + corpo (wire/in.GerarAutografo) + `ator` -> mapa PARCIAL de dominio p/
  Repo/gerar-autografo-e-abrir-tramitacao!. Gera `:id` (novo, do autografo) + `:created-by` (do ator). NAO
  inclui `:ano`/`:destinatario-texto`/`:texto-versao-id` — o controller resolve (proposicao + municipio) e
  junta esses campos antes de chamar o Repo."
  [ator proposicao-id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-gerar)]
    (validar! wire/GerarAutografo m "corpo de gerar autografo invalido")
    {:id (random-uuid) :proposicao-id proposicao-id
     :prazo-resposta-em (->instante (:prazo-resposta-em m) :prazo-resposta-em)
     :created-by (:identidade-id ator)}))

(defn registrar-resposta->dominio
  "Corpo (wire/in.RegistrarRespostaExecutivo) + `ator` -> mapa PARCIAL de dominio p/ Repo/
  registrar-resposta-executivo!. NAO inclui `:id` — o controller resolve a tramitacao executiva DESTE
  autografo (o path e' o autografo-id, o Repo escreve por tramitacao-executiva-id). COERENCIA
  resultado<->veto-tipo (espelha na BORDA o guard de db/tramitacao-executiva.clj/registrar-resposta!, que
  lanca SEM `:tipo` — sem este guard aqui, 'vetado' sem veto-tipo alcancaria o db/ e cairia no fallback 500
  em vez de 400 fail-closed, mesma disciplina de adapters.in.incidente/coerencia objeto-tipo<->objeto-id)."
  [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-resposta)]
    (validar! wire/RegistrarRespostaExecutivo m "corpo de registrar resposta invalido")
    (when (and (= "vetado" (:resultado m)) (nil? (:veto-tipo m)))
      (invalido! "resultado 'vetado' exige veto-tipo (total|parcial)" {:campo :veto-tipo}))
    {:lock-version (:lock-version m) :resultado (:resultado m) :veto-tipo (:veto-tipo m)
     :veto-razoes (:veto-razoes m) :updated-by (:identidade-id ator)}))

(defn apreciar-veto->dominio
  "Corpo (wire/in.ApreciarVeto) + `ator` -> mapa PARCIAL de dominio p/ Repo/apreciar-veto!. NAO inclui
  `:id` — o path E' o tramitacao-executiva-id, o controller injeta."
  [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [m (so-esperados wire-in campos-apreciar)]
    (validar! wire/ApreciarVeto m "corpo de apreciar veto invalido")
    {:lock-version (:lock-version m) :resultado (:resultado m)
     :veto-votacao-id (->uuid (:veto-votacao-id m) :veto-votacao-id) :updated-by (:identidade-id ator)}))
