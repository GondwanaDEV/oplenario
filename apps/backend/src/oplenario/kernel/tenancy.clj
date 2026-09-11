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

(defn ente-da-sessao
  "O ente_id da tx CORRENTE, lido de volta do GUC `app.ente_id` que `set-tenant!` escreveu.

  Existe para a camada `relacoes/` (§22.5.3 disc.5): a assinatura de uma funcao de relacao e'
  `(fn tx arg-de-dominio…)` e NAO carrega `ente` — a decisao §4-bis/C2 do catalogo e' que 'a Casa e'
  1:1 com o tenant, implicita na tx'. Quando a relacao precisa delegar a uma fn de `db/` que recebe
  `ente-id` explicito (defesa em profundidade sobre a RLS), este e' o unico jeito honesto de obte-lo
  sem reintroduzir `ente` como argumento do DSL. Mesma fonte que a propria RLS usa nas policies
  (`NULLIF(current_setting('app.ente_id', true), '')::uuid`) e que `kernel/sequencial` ja' le.

  FAIL-LOUD (mesma postura de `populacao` em cadastros/relacoes): tx sem tenant setado LANCA. Devolver
  nil aqui faria a consulta a jusante casar zero linhas e o fato responder `falso` em silencio — um
  guard de tramitacao negaria para sempre sem que ninguem visse a causa."
  [tx]
  (or (-> (jdbc/execute-one! tx ["SELECT NULLIF(current_setting('app.ente_id', true), '')::uuid AS ente_id"])
          vals first)
      (throw (ex-info "ente-da-sessao: app.ente_id nao setado na tx (chamada fora de com-tenant*)"
                      {:erro :tenancy/sem-tenant}))))

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
