(ns oplenario.identidade.adapters.in.acesso
  "Gate de ENTRADA wire/in -> dominio da superficie ADMINISTRATIVA (§22.10 adapters/in, ADR-0001).
  Chamado SO' pelo diplomat/. Valida (fail-closed -> :validacao/invalido -> 400), coage e INJETA o que nao
  vem do corpo (`id` gerado, `ente-id` do ator). Espelha cadastros/adapters/in/vereador.clj.

  O CPF e' validado AQUI, de verdade: db/identidade.clj:17 valida por {:pre}, e assertion SOME com -da —
  em prod a unica barreira poderia evaporar sem sinal (nao ha CHECK no banco). A assertion fica como rede
  interna; esta e' a barreira real."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.identidade.wire.in.acesso :as wire]))

(set! *warn-on-reflection* true)

(def papeis-concediveis
  "Os papeis que a tela concede — e, pelo adendo da ADR-0005, os que ela revoga e lista."
  wire/papeis-concediveis)

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

(def ^:private oab-re
  "UF (2 letras) + numero (1..7 digitos) + sufixo opcional (letra: suplementar); separadores livres entre eles."
  #"^([A-Z]{2})\s?-?\s?(\d{1,7})([A-Z]?)$")

(defn- normalizar-oab!
  "trim + espacos colapsados + maiusculas, e a forma canonica `UF NUMERO[SUFIXO]` (ex.: \"ce-12345\" -> \"CE 12345\")."
  [oab]
  (let [bruta (-> (str oab) str/trim (str/replace #"\s+" " ") str/upper-case)
        [_ uf numero sufixo] (re-matches oab-re bruta)]
    (when-not uf (invalido! "oab invalida" {:campos [:oab]}))
    (str uf " " numero sufixo)))

(defn- perfil-juridico! [mm]
  (let [juridico? (some #{"juridico"} (:papeis mm))]
    (cond
      juridico?
      (do
        ;; um vinculo servidor nao mistura `auditor` e `juridico` na mesma concessao (ADR-0019 Eixo 1)
        (when-not (= 1 (count (:papeis mm)))
          (invalido! "o papel juridico e' concedido sozinho" {:campo :papeis}))
        (when-not (and (:qualificacao mm) (:oab mm))
          (invalido! "o papel juridico exige qualificacao e oab"
                     {:campos (vec (cond-> [] (nil? (:qualificacao mm)) (conj :qualificacao)
                                              (nil? (:oab mm)) (conj :oab)))}))
        {:qualificacao (:qualificacao mm)
         :oab (normalizar-oab! (:oab mm))})

      (or (contains? mm :qualificacao) (contains? mm :oab))
      (invalido! "qualificacao e oab so' valem para o papel juridico" {:campos [:qualificacao :oab]})

      :else nil)))

(defn conceder-acesso->dominio
  "Corpo de conceder acesso -> dominio. Com o papel `juridico` (ADR-0019) devolve tambem `:perfil-juridico
  {:qualificacao <string> :oab <normalizada>}`; sem ele, nao ha' a chave."
  [_ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/ConcederAcesso mm "corpo de conceder acesso invalido")
    (when-not (every? (get wire/papeis-por-tipo (:tipo mm) #{}) (:papeis mm))
      (invalido! "papel incompativel com o tipo do vinculo" {:campo :papeis}))
    (let [perfil (perfil-juridico! mm)]
      (cond-> {:identidade-id (->uuid! (:identidade-id mm) :identidade-id)
               :tipo (:tipo mm)
               :papeis (:papeis mm)
               :email (:email mm)}
        perfil (assoc :perfil-juridico perfil)))))

(defn revogar-acesso->dominio
  "Corpo de revogar acesso + o `identidade-id` do caminho -> dominio {:identidade-id :papel :motivo :por}. `por` e' quem
  chama (o `ator`), nunca o corpo. Motivo em branco (so' espacos) e' recusado, e o que fica gravado e' o texto aparado.
  Devolve nil quando o identificador do caminho nao e' UUID (o handler responde 404, como o reenvio de convite)."
  [ator identidade-id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/RevogarAcesso mm "corpo de revogar acesso invalido")
    (let [motivo (str/trim (:motivo mm))]
      (when (< (count motivo) 3) (invalido! "motivo obrigatorio" {:campos [:motivo]}))
      (when-let [iid (parse-uuid (str identidade-id))]
        {:identidade-id iid :papel (:papel mm) :motivo motivo :por (:identidade-id ator)}))))
