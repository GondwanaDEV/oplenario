(ns oplenario.normas.diplomat.http.in
  "Borda HTTP das normas de referencia (ADR-0011) — a CURADORIA pela secretaria: importar o texto, conferir cada
  dispositivo e publicar (ou descartar). O agente le norma pelas ferramentas da B.5, nunca por aqui."
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.normas.adapters.in.versao :as adapters-in]
            [oplenario.normas.adapters.out.versao :as adapters-out]
            [oplenario.normas.controllers :as controllers]))

(set! *warn-on-reflection* true)

(def ^:private teto-corpo
  "O texto de uma LOM ou de um Regimento inteiro (o teto padrao da borda, 256 KiB, nao cabe um Regimento grande)."
  (* 4 1024 1024))

(defn- uuid-do-path [req]
  (or (parse-uuid (str (get-in req [:path-params :id])))
      (throw (ex-info "id invalido" {:tipo :validacao/invalido :campo :id}))))

(defn- conflito [e]
  (case (:tipo (ex-data e))
    :conflito/versao-em-conferencia
    (http/json-resposta 409 {:erro "Esta norma já tem uma versão esperando conferência. Confira ou descarte aquela antes."})
    :conflito/versao-ja-decidida
    (http/json-resposta 409 {:erro "Esta versão já foi conferida por outra pessoa. Recarregue."})
    nil))

(defn- listar-handler [repo]
  (fn [req] (http/json-resposta 200 (adapters-out/lista->wire (controllers/listar repo (:ator req))))))

(defn- importar-handler [repo municipio-do-ente]
  (fn [req]
    (let [pedido (adapters-in/importacao->dominio (:json-params req))]
      (try
        (http/json-resposta 201 (adapters-out/versao->wire
                                 (controllers/importar! repo municipio-do-ente (:ator req) pedido)))
        (catch clojure.lang.ExceptionInfo e (or (conflito e) (throw e)))))))

(defn- versao-handler [repo]
  (fn [req]
    (if-let [v (controllers/versao repo (:ator req) (uuid-do-path req))]
      (http/json-resposta 200 (adapters-out/versao->wire v))
      (http/json-resposta 404 {:erro "versão não encontrada"}))))

(defn- conferir-handler [repo]
  (fn [req]
    (let [id (uuid-do-path req)
          decisao (adapters-in/decisao->dominio (:json-params req))]
      (try
        (if-let [v (controllers/conferir! repo (:ator req) id decisao)]
          (http/json-resposta 200 (adapters-out/versao->wire v))
          (http/json-resposta 404 {:erro "versão não encontrada"}))
        (catch clojure.lang.ExceptionInfo e (or (conflito e) (throw e)))))))

(defn rotas
  [{:keys [auth repo-normas municipio-do-ente]}]
  (let [sec (it/exige-papel "secretario")]
    #{["/normas" :get [auth sec (listar-handler repo-normas)] :route-name :normas/listar]
      ["/normas/versoes" :post [auth sec (it/corpo-json-ate teto-corpo) (importar-handler repo-normas municipio-do-ente)]
       :route-name :normas/importar-versao]
      ["/normas/versoes/:id" :get [auth sec (versao-handler repo-normas)] :route-name :normas/versao]
      ["/normas/versoes/:id/conferencia" :post [auth sec it/corpo-json (conferir-handler repo-normas)]
       :route-name :normas/conferir]}))
