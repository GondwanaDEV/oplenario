(ns oplenario.cadastros.adapters.in.vereador
  "Gate de ENTRADA wire/in -> dominio das 4 escritas (§22.10 adapters/in, ADR-0001, Onda D Slice 4). Chamado
  SO' pelo diplomat/. Valida (fail-closed -> :validacao/invalido -> 400) e COAGE (datas ISO -> LocalDate,
  legislatura-id -> UUID); INJETA o que nao vem do corpo (`id` gerado, `ente-id`/`vereador-id` do ator/path,
  §22.5). `cadastros` nao importa outro modulo (§22.10) — usa `parse-uuid` do core, nao o helper de legislativo."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.cadastros.wire.in.vereador :as wire])
  (:import (java.time LocalDate)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- keywordizar
  "Converte TODAS as chaves string do corpo p/ keyword (sem filtrar) — precisa preservar chave forjada
  (ex.: \"identidade-id\") ate' a validacao, senao o :closed do schema nunca a enxerga p/ recusar (a
  checagem anti-forja mora no schema, nao numa allowlist manual antes dele)."
  [m]
  (reduce-kv (fn [acc k v] (assoc acc (keyword k) v)) {} m))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    ;; guarda so' os nomes-de-campo (NUNCA o payload cru — m/explain embute :value = vazaria dado).
    (invalido! msg {:campos (keys (me/humanize erros))})))

(defn- ->data!
  "String ISO (AAAA-MM-DD) -> LocalDate. nil-safe (nil -> nil, p/ campos opcionais). Parse invalido -> 400."
  [s campo]
  (when (some? s)
    (try (LocalDate/parse s)
      (catch DateTimeParseException _ (invalido! "data invalida (esperado AAAA-MM-DD)" {:campos [campo]})))))

(defn- ->uuid! [s campo]
  (or (parse-uuid s) (invalido! "identificador invalido" {:campos [campo]})))

(defn criar-vereador->dominio [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/CriarVereador mm "corpo de criar vereador invalido")
    (when (str/blank? (:nome mm)) (invalido! "nome obrigatorio (nao-branco)" {:campos [:nome]}))
    {:id (random-uuid) :ente-id (:ente-id ator) :nome (:nome mm) :nome-parlamentar (:nome-parlamentar mm)}))

(defn editar-vereador->dominio [wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/EditarVereador mm "corpo de editar vereador invalido")
    (when-not (or (contains? mm :nome) (contains? mm :nome-parlamentar))
      (invalido! "informe ao menos um campo (nome ou nome-parlamentar)" {:campos [:nome :nome-parlamentar]}))
    (when (and (contains? mm :nome) (str/blank? (:nome mm)))
      (invalido! "nome nao pode ser vazio" {:campos [:nome]}))
    (select-keys mm [:nome :nome-parlamentar])))

(defn registrar-mandato->dominio [ator vereador-id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/RegistrarMandato mm "corpo de registrar mandato invalido")
    {:id (random-uuid) :ente-id (:ente-id ator) :vereador-id vereador-id
     :legislatura-id (->uuid! (:legislatura-id mm) :legislatura-id)
     :partido (:partido mm) :estado "vigente" :natureza (:natureza mm)
     :vigencia-inicio (->data! (:vigencia-inicio mm) :vigencia-inicio)
     :vigencia-fim (->data! (:vigencia-fim mm) :vigencia-fim)}))

(defn registrar-licenca->dominio [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/RegistrarLicenca mm "corpo de registrar licenca invalido")
    {:id (random-uuid) :ente-id (:ente-id ator)
     :inicio (->data! (:inicio mm) :inicio) :fim (->data! (:fim mm) :fim) :motivo (:motivo mm)}))
