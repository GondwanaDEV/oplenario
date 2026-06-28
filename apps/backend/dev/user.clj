(ns user
  "Workflow de REPL (Stuart Sierra Component): (go) (reset) (halt)."
  (:require [com.stuartsierra.component.repl :as cr]
            [oplenario.sistema :as sistema]))

;; TODO: (cr/set-init (fn [_] (sistema/novo-sistema (config))))
(comment (cr/go) (cr/reset) (cr/halt))
