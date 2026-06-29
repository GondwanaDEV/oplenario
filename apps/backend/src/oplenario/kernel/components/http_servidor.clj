(ns oplenario.kernel.components.http-servidor
  "Component do servidor HTTP do host: sobe o Jetty (Pedestal) no start, derruba no stop. Recebe o `config` +
  uma `rotas-fn` (fn do PROPRIO component -> rotas) — avaliada no START, quando os deps injetados por `using`
  (idp, repo-identidade, e os Repo de modulo em W3) ja estao prontos; assim os interceptors fecham sobre as
  instancias iniciadas. Fica FORA de novo-sistema (so em sistema-serve) — os testes de boot do dominio nao
  bindam porta."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as http]
            [oplenario.http :as oplenario-http]
            [oplenario.interceptors :as interceptors]))

(defrecord ServidorHttp [config rotas-fn idp repo-identidade repo-sessoes servidor]
  component/Lifecycle
  (start [this]
    (if servidor
      this
      (assoc this :servidor (-> (oplenario-http/servico config (rotas-fn this) interceptors/globais)
                                http/create-server
                                http/start))))
  (stop [this]
    ;; cleanup robusto (review W2 M1): se http/stop lancar, ainda zera :servidor — senao o guard de start
    ;; veria :servidor nao-nil e nunca re-subiria o Jetty (Component preso em estado morto).
    (when servidor
      (try (http/stop servidor)
           (catch Exception e (log/warn e "http/stop lancou; seguindo com o cleanup"))))
    (assoc this :servidor nil)))

(defn servidor-http
  "Cria o Component do servidor (sem subir ainda). `rotas-fn` = (fn [componente] -> rotas Pedestal); o
  componente carrega os deps (via `using` no system-map) que a rotas-fn usa p/ montar os interceptors."
  [config rotas-fn]
  (map->ServidorHttp {:config config :rotas-fn rotas-fn}))
