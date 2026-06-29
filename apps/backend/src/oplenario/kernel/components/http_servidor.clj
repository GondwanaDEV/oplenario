(ns oplenario.kernel.components.http-servidor
  "Component do servidor HTTP do host: sobe o Jetty (Pedestal) no start, derruba no stop. Recebe o `config` +
  as `rotas` ja compostas. W2/W3 enriquecem as rotas (interceptors de auth/tenancy + rotas-dado de modulo, via
  `using` dos Repo). Fica FORA de novo-sistema (so em sistema-serve) — os testes de boot do dominio nao bindam
  porta."
  (:require [com.stuartsierra.component :as component]
            [io.pedestal.http :as http]
            [oplenario.http :as oplenario-http]))

(defrecord ServidorHttp [config rotas servidor]
  component/Lifecycle
  (start [this]
    (if servidor
      this
      (assoc this :servidor (-> (oplenario-http/servico config rotas)
                                http/create-server
                                http/start))))
  (stop [this]
    (when servidor (http/stop servidor))
    (assoc this :servidor nil)))

(defn servidor-http
  "Cria o Component do servidor (sem subir ainda). `rotas` = conjunto de rotas Pedestal (table syntax)."
  [config rotas]
  (map->ServidorHttp {:config config :rotas rotas}))
