(ns oplenario.kernel.components.scheduler
  "Eleicao de lider por advisory lock do Postgres (§22.9 Eixo 3): so UM replica varre o outbox.
  pg_try_advisory_lock(chave) e' session-level — preso a' conexao; soltar a conexao solta o lock.
  Helpers sobre uma connection; o loop periodico vive no relay Component (outbox_relay)."
  (:require [next.jdbc :as jdbc]))

(set! *warn-on-reflection* true)

(defn tentar-lider?
  "Tenta adquirir a lideranca (advisory lock `chave`, bigint) na conexao `conn`. true se adquiriu."
  [conn chave]
  (boolean (:ok (jdbc/execute-one! conn ["SELECT pg_try_advisory_lock(?) AS ok" chave]))))

(defn liberar-lider!
  "Libera a lideranca (advisory unlock `chave`) na conexao `conn`."
  [conn chave]
  (jdbc/execute-one! conn ["SELECT pg_advisory_unlock(?) AS ok" chave]))
