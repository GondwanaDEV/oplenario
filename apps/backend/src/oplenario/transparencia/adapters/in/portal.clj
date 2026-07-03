(ns oplenario.transparencia.adapters.in.portal
  "Gate de ENTRADA `wire/in -> models` do portal (§22.10 adapters/in, ADR-0001) — chamado SO pelo diplomat/.
  TODAS as rotas desta fatia sao PUBLICAS (sem auth, sem corpo) — so' coagem path-params a UUID fail-closed
  (:validacao/invalido -> 400). Mesmo seam `ente-param->uuid` de participacao (V1: :ente do path = UUID cru;
  slug humano e' refino futuro)."
  (:require [clojure.string :as str])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn ->uuid
  "String -> UUID; malformado/ausente = requisicao invalida (:validacao/invalido -> 400), nunca 500."
  [s campo]
  (when (str/blank? s) (invalido! "uuid ausente" {:campo campo}))
  (try (UUID/fromString s) (catch IllegalArgumentException _ (invalido! "uuid invalido" {:campo campo}))))

(defn ente-param->uuid
  "Path-param :ente (rota PUBLICA) -> UUID do ente. Malformado/ausente -> 400 fail-closed (NUNCA vaza
  cross-tenant). A RLS (com-tenant* com este ente-id) e' o que isola a fronteira de tenant da rota sem-ator."
  [s]
  (when (str/blank? s) (invalido! "ente ausente na rota publica" {:campo :ente}))
  (->uuid s :ente))

(defn proposicao-param->uuid [s] (->uuid s :proposicao-id))
(defn norma-param->uuid [s] (->uuid s :norma-id))
