(ns oplenario.cadastros.db.estrutura
  "Persistencia da estrutura institucional do tenant: ente (perfil 1:1), legislatura, sessao_legislativa.
  Funcoes sobre a `tx` do tenant (RLS isola). next.jdbc parametrizado, schema-qualified (§22.10)."
  (:require [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

;; ---- ente (perfil cadastral 1:1) ----
(defn inserir-ente!
  "Cria/atualiza o perfil cadastral do ente. Idempotente (ON CONFLICT) p/ re-provisionamento seguro (retry)."
  [tx {:keys [ente-id municipio-ibge nome-oficial nome-curto brasao-ref]}]
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.ente (ente_id, municipio_ibge, nome_oficial, nome_curto, brasao_ref)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (ente_id) DO UPDATE SET municipio_ibge = EXCLUDED.municipio_ibge,
        nome_oficial = EXCLUDED.nome_oficial, nome_curto = EXCLUDED.nome_curto,
        brasao_ref = EXCLUDED.brasao_ref, atualizado_em = now()"
     ente-id municipio-ibge nome-oficial nome-curto brasao-ref]))

(defn buscar-ente [tx]
  ;; RLS ja restringe ao tenant corrente -> a unica linha visivel e' a da Casa.
  (comum/linha->kebab
    (jdbc/execute-one! tx ["SELECT ente_id, municipio_ibge, nome_oficial, nome_curto, brasao_ref
                            FROM cadastros.ente"])))

;; ---- legislatura ----
(defn inserir-legislatura!
  ;; criacao NATIVA -> nasce efetivada (efetivado_em = now()); o caminho de import (admin_sistema) e' que
  ;; estaga com efetivado_em NULL + lote_id. Sem isto a RLS de staging esconde a linha (fundacao #2).
  [tx {:keys [id ente-id numero ano-inicio ano-fim vigente]}]
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.legislatura (id, ente_id, numero, ano_inicio, ano_fim, vigente, efetivado_em)
      VALUES (?, ?, ?, ?, ?, ?, now())" id ente-id numero ano-inicio ano-fim (boolean vigente)]))

(defn buscar-legislatura [tx id]
  (comum/linha->kebab
    (jdbc/execute-one! tx ["SELECT id, ente_id, numero, ano_inicio, ano_fim, vigente
                            FROM cadastros.legislatura WHERE id = ?" id])))

(defn legislatura-vigente [tx]
  (comum/linha->kebab
    (jdbc/execute-one! tx ["SELECT id, ente_id, numero, ano_inicio, ano_fim, vigente
                            FROM cadastros.legislatura WHERE vigente = true LIMIT 1"])))

;; ---- sessao legislativa (1..4 dentro da legislatura) ----
(defn inserir-sessao-legislativa! [tx {:keys [id ente-id legislatura-id numero ano data-inicio data-fim]}]
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.sessao_legislativa (id, ente_id, legislatura_id, numero, ano, data_inicio, data_fim, efetivado_em)
      VALUES (?, ?, ?, ?, ?, ?, ?, now())" id ente-id legislatura-id numero ano data-inicio data-fim]))
