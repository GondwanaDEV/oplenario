(ns oplenario.integracao-ia.diplomat.http.in
  "Borda HTTP de SERVICO da fronteira core <-> IA (ADR-0008). Nao usa o login de pessoas: toda rota exige o
  segredo compartilhado core<->satelite (`Authorization: Bearer <segredo>`), comparado em tempo constante; sem
  segredo configurado as rotas respondem 503 (desligadas, fail-closed). O tenant de cada leitura vem EXPLICITO no
  caminho (`/entes/:ente-id/...`) e o core abre a tx dele (RLS)."
  (:require [clojure.string :as str]
            [io.pedestal.interceptor.chain :as chain]
            [oplenario.http :as http]
            [oplenario.integracao-ia.adapters.in.evento :as adapters-in]
            [oplenario.integracao-ia.adapters.out.feed :as adapters-out]
            [oplenario.integracao-ia.controllers :as controllers]
            [oplenario.integracao-ia.logic :as logic]
            [oplenario.interceptors :as it]))

(set! *warn-on-reflection* true)

(defn- recusa [ctx status erro]
  (chain/terminate (assoc ctx :response (http/json-resposta status {:erro erro}))))

(defn exige-servico-ia
  "Interceptor de autenticacao de SERVICO. Segredo em branco = integracao desligada (503)."
  [segredo]
  {:name  ::exige-servico-ia
   :enter (fn [ctx]
            (let [h   (get-in ctx [:request :headers "authorization"])
                  tok (when (and h (str/starts-with? h "Bearer ")) (subs h 7))]
              (cond
                (str/blank? segredo)                 (recusa ctx 503 "integracao com a IA desligada")
                (logic/segredo-confere? segredo tok) ctx
                :else                                (recusa ctx 401 "credencial de servico invalida"))))})

(defn- feed-handler [repo-ia]
  (fn [req]
    (let [c (adapters-in/cursor (:query-params req))]
      (http/json-resposta 200 (adapters-out/eventos->wire (:depois c) (controllers/feed repo-ia c))))))

(defn- contexto-handler [contexto-da-sessao]
  (fn [req]
    (let [ente (adapters-in/id-de-caminho (get-in req [:path-params :ente-id]) "ente-id")
          sid  (adapters-in/id-de-caminho (get-in req [:path-params :sessao-id]) "sessao-id")
          r    (controllers/contexto contexto-da-sessao ente sid)]
      (cond
        (nil? r)         (http/json-resposta 404 {:erro "sessao nao encontrada"})
        (= :restrita r)  (http/json-resposta 403 {:erro "sessao sigilosa nao vai para a IA"})
        :else            (http/json-resposta 200 (adapters-out/contexto->wire ente (first r) (second r)))))))

(defn- conteudo-handler [abrir-gravacao]
  (fn [req]
    (let [ente (adapters-in/id-de-caminho (get-in req [:path-params :ente-id]) "ente-id")
          seg  (adapters-in/id-de-caminho (get-in req [:path-params :segmento-id]) "segmento-id")
          r    (controllers/conteudo abrir-gravacao ente seg)]
      (cond
        (nil? r)        (http/json-resposta 404 {:erro "gravacao nao encontrada"})
        (= :restrita r) (http/json-resposta 403 {:erro "gravacao restrita nao vai para a IA"})
        :else           {:status  200
                         :headers (cond-> {"Content-Type" "application/octet-stream"}
                                    (:audio-hash r) (assoc "X-Conteudo-Sha256" (:audio-hash r)))
                         :body    (:stream r)}))))

(defn- receber-handler [repo-ia registrar-transcricao]
  (fn [req]
    (try
      (let [ev (adapters-in/evento->dominio (:json-params req))
            r  (controllers/receber! repo-ia registrar-transcricao ev)]
        (http/json-resposta (if (:aplicado r) 201 200) (adapters-out/recibo->wire (:chave ev) r)))
      (catch clojure.lang.ExceptionInfo e
        (case (:tipo (ex-data e))
          :validacao/evento-desconhecido
          (http/json-resposta 422 {:erro (ex-message e) :evento (:evento (ex-data e)) :versao (:versao (ex-data e))})
          :validacao/transcricao-fora-da-sessao
          (http/json-resposta 422 {:erro (ex-message e)})
          (throw e))))))

(defn rotas
  "Fragmento de rotas da fronteira. `segredo` = OPLENARIO_IA_SEGREDO; os seams vem do host (rotas/montar)."
  [{:keys [repo-integracao-ia segredo contexto-da-sessao abrir-gravacao registrar-transcricao]}]
  (let [servico (exige-servico-ia segredo)]
    #{[(str logic/prefixo "/eventos") :get [servico (feed-handler repo-integracao-ia)]
       :route-name :integracao-ia/feed]
      [(str logic/prefixo "/eventos") :post
       [servico it/corpo-json (receber-handler repo-integracao-ia registrar-transcricao)]
       :route-name :integracao-ia/receber]
      [(str logic/prefixo "/entes/:ente-id/sessoes/:sessao-id/contexto") :get
       [servico (contexto-handler contexto-da-sessao)]
       :route-name :integracao-ia/contexto]
      [(str logic/prefixo "/entes/:ente-id/gravacoes/:segmento-id/conteudo") :get
       [servico (conteudo-handler abrir-gravacao)]
       :route-name :integracao-ia/conteudo]}))
