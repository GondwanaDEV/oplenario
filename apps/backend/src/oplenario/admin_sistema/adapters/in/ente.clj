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

;; ---- ADR-0018 (fatia 1) ----

(defn- corpo! [schema json-params]
  (when-not (map? json-params) (invalido! "corpo deve ser objeto JSON" :corpo))
  (let [c (into {} (map (fn [[k v]] [k (aparar v)])) json-params)]
    (when-not (m/validate schema c) (invalido! "pedido invalido" :corpo))
    c))

(defn pedir-suspensao->dominio [json-params]
  (let [c (corpo! wire/PedirSuspensaoIn json-params)]
    {:motivo (get c "motivo") :justificativa (get c "justificativa")}))

(defn iniciar-encerramento->dominio [json-params]
  (let [c (corpo! wire/IniciarEncerramentoIn json-params)]
    {:origem (get c "origem") :justificativa (get c "justificativa")}))

(defn decisao->justificativa
  "Corpo opcional (sem corpo = sem justificativa)."
  [json-params]
  (if (nil? json-params)
    nil
    (not-empty (get (corpo! wire/DecisaoIn json-params) "justificativa"))))

(defn reativar->justificativa [json-params]
  (get (corpo! wire/ReativarIn json-params) "justificativa"))

;; ---- ADR-0018 (fatia 2) ----

(defn confirmar-recebimento->sha256 [json-params]
  (str/lower-case (get (corpo! wire/ConfirmarRecebimentoIn
                               (cond-> json-params
                                 (string? (get json-params "sha256")) (update "sha256" str/lower-case)))
                       "sha256")))

(defn oficio->texto [json-params]
  (get (corpo! wire/OficioIn json-params) "texto"))

(defn destino-acervo->url [json-params]
  (not-empty (get (corpo! wire/DestinoAcervoIn (cond-> json-params
                                                (and (map? json-params) (string? (get json-params "url")) (str/blank? (get json-params "url")))
                                                (assoc "url" nil)))
                  "url")))

(defn pedir-apagamento->justificativa [json-params]
  (get (corpo! wire/PedirApagamentoIn json-params) "justificativa"))
