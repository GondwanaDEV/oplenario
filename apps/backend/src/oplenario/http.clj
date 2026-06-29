(ns oplenario.http
  "Service Pedestal do host (§22.10): monta o service-map a partir das rotas-dado dos modulos + interceptors
  base. W1 = esqueleto + /saude (sem auth). A cadeia de auth/tenancy/authz (W2) e as rotas de modulo (W3)
  crescem a partir daqui — o servidor (kernel/components/http-servidor) recebe as rotas ja compostas."
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [jsonista.core :as json]))

(defn json-resposta
  "Resposta Ring com corpo JSON (jsonista). Helper base ate a W3 plugar negociacao de conteudo por interceptor."
  [status data]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (json/write-value-as-string data)})

(defn- saude
  "Liveness/readiness do host (sem auth). 200 + {:status \"ok\"}."
  [_req]
  (json-resposta 200 {:status "ok"}))

(def rotas-saude
  "Rota de health do host (table syntax Pedestal). Sem auth — fora da cadeia de W2."
  #{["/saude" :get saude :route-name :saude]})

(defn servico
  "Service-map Pedestal a partir do `config` + `rotas` (ja expandidas ou table-syntax). `::http/join? false` —
  o Component ServidorHttp controla o ciclo (start/stop). `::http/host` 0.0.0.0 p/ container."
  [config rotas]
  {::http/routes (route/expand-routes rotas)
   ::http/type   :jetty
   ::http/host   "0.0.0.0"
   ::http/port   (get-in config [:http :port])
   ::http/join?  false})
