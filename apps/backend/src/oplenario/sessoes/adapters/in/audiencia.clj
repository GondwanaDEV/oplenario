(ns oplenario.sessoes.adapters.in.audiencia
  "Gate de ENTRADA `wire/in -> models` da AUDIENCIA PUBLICA (ADR-0021 Parte A). Chamado SO pelo diplomat/. Valida o
  corpo contra o `wire/in`, promove a keyword SO' as chaves esperadas (o corpo-json chega com chaves STRING) e coage
  uuid/texto (trim) — fail-closed (`:validacao/invalido` -> 400)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.logic.audiencia :as logic-aud]
            [oplenario.sessoes.wire.in :as wire])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- so-esperados [m campos]
  (reduce (fn [acc k] (cond-> acc (contains? m k) (assoc (keyword k) (get m k)))) {} campos))

(defn- validado [schema m msg]
  (when-let [erros (m/explain schema m)]
    (invalido! msg {:campos (keys (me/humanize erros))}))
  m)

(defn- corpo [json-params campos]
  (when-not (map? json-params) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (so-esperados json-params campos))

(defn id-param->uuid
  "Path-param (string) -> UUID; malformado = 400."
  [s campo]
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "id invalido" {:campo campo}))))

(defn- texto [s] (some-> s str/trim not-empty))

(defn atualizar->dominio
  "PATCH /sessoes/:id/audiencia -> {campo valor} so' com as chaves presentes (`local` presente e vazio = limpar)."
  [json-params]
  (let [m (validado wire/AtualizarAudiencia
                    (corpo json-params ["tempo-fala-segundos" "inscricoes-abertas" "local"])
                    "corpo de atualizar audiencia invalido")]
    (when (empty? m) (invalido! "informe o que mudar (tempo-fala-segundos, inscricoes-abertas ou local)" {:campo :corpo}))
    (when (and (contains? m :tempo-fala-segundos) (not (logic-aud/tempo-de-fala-valido? (:tempo-fala-segundos m))))
      (invalido! "tempo de fala entre 60 e 1800 segundos" {:campo :tempo-fala-segundos}))
    (cond-> m (contains? m :local) (update :local texto))))

(defn- fala->dominio [m]
  (logic-aud/validar-fala! {:fala-como (:fala-como m) :entidade (texto (:entidade m)) :tema (texto (:tema m))}))

(defn inscricao-presencial->dominio
  "POST /sessoes/:id/audiencia/inscricoes (a Mesa) -> {:nome :fala-como :entidade :tema}."
  [json-params]
  (let [m (validado wire/InscreverPresencial (corpo json-params ["nome" "fala-como" "entidade" "tema"])
                    "corpo de inscricao invalido")
        nome (texto (:nome m))]
    (when-not nome (invalido! "nome obrigatorio" {:campo :nome}))
    (assoc (fala->dominio m) :nome nome)))

(defn inscricao-portal->dominio
  "POST /portal/audiencias/:sessao-id/inscricoes (a cidada) -> {:fala-como :entidade :tema}. O NOME nao e' lido do
  corpo (nem existe no contrato): vem da identidade, no controller. `ciente-publicidade` != true -> 400."
  [json-params]
  (fala->dominio (validado wire/InscreverPeloPortal
                           (corpo json-params ["fala-como" "entidade" "tema" "ciente-publicidade"])
                           "corpo de inscricao invalido (confirme que esta' ciente de que a fala e' publica)")))

(defn encerramento->dominio
  "POST .../encerramento -> {:tempo-usado-segundos n}."
  [json-params]
  (validado wire/EncerrarFalaCidada (corpo json-params ["tempo-usado-segundos"]) "informe o tempo usado (segundos)"))
