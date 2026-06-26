(ns oplenario.main
  "Entrypoint do processo (host, §22.10): le a config e dispatcha por subcomando. `migrate` aplica
  as migrations e sai (passo de init separado do app, §22.9 — evita corrida de migration entre
  replicas); sem arg = `serve` (sobe o sistema Component e bloqueia ate o shutdown). Glue fino
  sobre pecas ja testadas (config/sistema/migracao/datasource)."
  (:gen-class)
  (:require [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao]
            [oplenario.sistema :as sistema]))

(defn- migrar!
  "Aplica as migrations num datasource efemero e o fecha (passo de init)."
  [cfg]
  (let [ds (component/start (datasource/datasource cfg))]
    (try (migracao/migrar! (:ds ds))
         (finally (component/stop ds)))))

(defn -main [& args]
  (let [cfg (config/carregar)]
    (if (= "migrate" (first args))
      (do (migrar! cfg)
          (println "[oplenario] migrations aplicadas"))
      (let [sys (component/start (sistema/novo-sistema cfg))]
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. ^Runnable (fn [] (component/stop sys))))
        (println "[oplenario] sistema no ar")
        @(promise)))))
