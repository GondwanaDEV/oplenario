(ns oplenario.identidade.adapters.in.acesso
  "Gate de ENTRADA wire/in -> dominio da superficie ADMINISTRATIVA (§22.10 adapters/in, ADR-0001).
  Chamado SO' pelo diplomat/. Valida (fail-closed -> :validacao/invalido -> 400), coage e INJETA o que nao
  vem do corpo (`id` gerado, `ente-id` do ator). Espelha cadastros/adapters/in/vereador.clj.

  O CPF e' validado AQUI, de verdade: db/identidade.clj:17 valida por {:pre}, e assertion SOME com -da —
  em prod a unica barreira poderia evaporar sem sinal (nao ha CHECK no banco). A assertion fica como rede
  interna; esta e' a barreira real."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.identidade.wire.in.acesso :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- keywordizar
  "Converte TODAS as chaves string p/ keyword (sem filtrar) — preservar chave forjada ate' a validacao e'
  o que permite o :closed do schema recusa-la. A checagem anti-forja mora no schema, nao numa allowlist."
  [m]
  (reduce-kv (fn [acc k v] (assoc acc (keyword k) v)) {} m))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    ;; SO' os nomes-de-campo — m/explain embute :value, e aqui :value e' CPF. Nunca logar o payload cru.
    (invalido! msg {:campos (keys (me/humanize erros))})))

(defn- ->uuid! [s campo]
  (or (parse-uuid s) (invalido! "identificador invalido" {:campos [campo]})))

(defn criar-identidade->dominio [_ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/CriarIdentidade mm "corpo de criar identidade invalido")
    ;; digito verificador — o regex do schema so' garante 11 digitos.
    (when-not (mod/valido-cpf? (:cpf mm)) (invalido! "cpf invalido" {:campos [:cpf]}))
    {:id (random-uuid) :cpf (:cpf mm) :nome (:nome mm)}))

(defn conceder-acesso->dominio [_ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/ConcederAcesso mm "corpo de conceder acesso invalido")
    {:identidade-id (->uuid! (:identidade-id mm) :identidade-id)
     :tipo (:tipo mm)
     :papeis (:papeis mm)
     :email (:email mm)}))
