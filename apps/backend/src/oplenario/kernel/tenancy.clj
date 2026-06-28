(ns oplenario.kernel.tenancy
  "Contexto de tenant na conexao (§22.2): toda operacao de dominio roda numa tx que (1) vira o role
  oplenario_app (NOBYPASSRLS) e (2) seta o GUC app.ente_id; a RLS (policy nas tabelas tenant) isola
  por ele. com-reconciliacao* abre tambem app.ver_lote -> visao de UM lote nao-efetivado (staging,
  fundacao #2). set_config(...,true) = SET LOCAL (so na tx; parametrizado -> sem injecao). Kernel."
  (:require [next.jdbc :as jdbc]))

(set! *warn-on-reflection* true)

(defn set-tenant!
  "Seta app.ente_id na tx corrente. ente-id obrigatorio (nil -> erro alto, nao um 'nil'::uuid silencioso)."
  [tx ente-id]
  (when-not ente-id (throw (ex-info "set-tenant!: ente-id nao pode ser nil" {})))
  (jdbc/execute-one! tx ["SELECT set_config('app.ente_id', ?, true)" (str ente-id)]))

(defn set-ver-lote!
  "Abre a visao do lote nao-efetivado `lote-id` na tx. NAO e' API publica — use com-reconciliacao*."
  [tx lote-id]
  (when-not lote-id (throw (ex-info "set-ver-lote!: lote-id nao pode ser nil" {})))
  (jdbc/execute-one! tx ["SELECT set_config('app.ver_lote', ?, true)" (str lote-id)]))

(defn- entrar-app!
  ;; troca p/ oplenario_app (NOBYPASSRLS) na tx -> a RLS SEMPRE aplica, mesmo que o pool tenha
  ;; conectado como o dono. Garantia incondicional, nao dependente da config do pool.
  [tx]
  (jdbc/execute-one! tx ["SET LOCAL ROLE oplenario_app"]))

(defn com-tenant*
  "Roda (f tx) numa tx ISOLADA pelo tenant `ente-id`: vira oplenario_app + seta app.ente_id."
  [ds ente-id f]
  (jdbc/with-transaction [tx ds]
    (entrar-app! tx)
    (set-tenant! tx ente-id)
    (f tx)))

(defn com-reconciliacao*
  "Como com-tenant*, mas ABRE a visao do `lote-id` nao-efetivado (reconciliacao/staging, fundacao #2).
  E' o unico caminho que enxerga dado nao-efetivado — escopo restrito e grep-avel p/ auditoria."
  [ds ente-id lote-id f]
  (jdbc/with-transaction [tx ds]
    (entrar-app! tx)
    (set-tenant! tx ente-id)
    (set-ver-lote! tx lote-id)
    (f tx)))
