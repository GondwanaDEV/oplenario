(ns oplenario.http
  "Service Pedestal do host (§22.10): monta o service-map a partir das rotas-dado dos modulos + interceptors
  base. W1 = esqueleto + /saude (sem auth). A cadeia de auth/tenancy/authz (W2) e as rotas de modulo (W3)
  crescem a partir daqui — o servidor (kernel/components/http-servidor) recebe as rotas ja compostas."
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as interceptor]
            [jsonista.core :as json]))

(defn json-resposta
  "Resposta Ring com corpo JSON (jsonista). Helper base ate a W3 plugar negociacao de conteudo por interceptor."
  [status data]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (json/write-value-as-string data)})

(defn saude
  "Liveness/readiness do host (sem auth). 200 + {:status \"ok\"}."
  [_req]
  (json-resposta 200 {:status "ok"}))

(defn eu
  "Echo do ator autenticado — rota protegida de prova da cadeia de auth (W2). Le (:request :ator)."
  [req]
  (json-resposta 200 {:ator (:ator req)}))

(defn painel-secretaria
  "Rota de prova de AUTORIZACAO GROSSA (exige papel 'secretario') — W2. Substituida por rotas reais em W3."
  [_req]
  (json-resposta 200 {:ok true :recurso "painel-secretaria"}))

(def rotas-saude
  "Rota de health do host (table syntax Pedestal). Sem auth — fora da cadeia de W2."
  #{["/saude" :get saude :route-name :saude]})

(defn servico
  "Service-map Pedestal a partir do `config` + `rotas` + `globais` (interceptors OUTERMOST — erro/headers, review
  W2). Roda `default-interceptors` (mantem o router) e PREpende os globais a ::http/interceptors (enter primeiro
  => erro envolve toda rota; cabecalhos :leave por ultimo). `::http/join? false` — o Component controla o ciclo."
  ([config rotas] (servico config rotas []))
  ([config rotas globais]
   (-> {::http/routes (route/expand-routes rotas)
        ::http/type   :jetty
        ::http/host   "0.0.0.0"
        ::http/port   (get-in config [:http :port])
        ::http/join?  false}
       http/default-interceptors
       ;; coage os globais a interceptor-records (a chain ja construida nao passa por route-expansion, que e'
       ;; quem coage mapas/fns) e PREpende (outermost).
       (update ::http/interceptors #(into (mapv interceptor/interceptor globais) %)))))
