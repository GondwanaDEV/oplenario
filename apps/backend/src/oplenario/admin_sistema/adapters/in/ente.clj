(ns oplenario.admin-sistema.adapters.in.ente
  "Gate de ENTRADA do provisionar Casa (ADR-0016), chamado so' pelo diplomat/. Fail-closed: 400 via
  `:validacao/invalido`. O ente_id NUNCA vem do corpo — este modulo o EMITE."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [oplenario.admin-sistema.wire.in.ente :as wire]
            [oplenario.kernel.cpf :as cpf])
  (:import (java.util UUID)))

(defn- invalido! [msg campo] (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(defn- aparar [x] (if (string? x) (str/trim x) x))

(defn provisionar->dominio [json-params]
  (when-not (map? json-params) (invalido! "corpo deve ser objeto JSON" :corpo))
  (let [aparar-mapa #(into {} (map (fn [[k v]] [k (aparar v)])) %)
        admin (get json-params "admin")
        c (cond-> (aparar-mapa json-params)
            (map? admin) (assoc "admin" (-> (aparar-mapa admin)
                                            ;; o CPF chega com ou sem pontuacao; so' os digitos seguem
                                            (update "cpf" #(if (string? %) (str/replace % #"\D" "") %)))))]
    (when-not (m/validate wire/ProvisionarCasaIn c) (invalido! "dados da Casa invalidos" :corpo))
    (when-not (cpf/valido? (get-in c ["admin" "cpf"])) (invalido! "CPF do administrador invalido" :cpf))
    {:nome (get c "nome-oficial")
     :nome-curto (not-empty (get c "nome-curto"))
     :uf (get c "uf")
     :municipio-ibge (get c "municipio-ibge")
     :municipio-nome (get c "municipio-nome")
     :admin {:nome (get-in c ["admin" "nome"]) :cpf (get-in c ["admin" "cpf"])
             :email (str/lower-case (get-in c ["admin" "email"]))}}))

(defn ente-param->uuid
  "Path -> UUID, ou nil (o handler responde 404)."
  [s]
  (try (UUID/fromString (str s)) (catch IllegalArgumentException _ nil)))
