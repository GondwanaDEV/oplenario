(ns oplenario.identidade.db.vinculo
  "Persistencia TENANT (FORCE RLS) do identidade: vinculo (identidade<->ente), usuario_papel (RBAC
  estatico) e consentimento (LGPD). Funcoes sobre a `tx` do tenant (com-tenant* -> RLS isola)."
  (:require [next.jdbc :as jdbc]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

;; ---- vinculo ----
(defn criar! [tx {:keys [id ente-id identidade-id tipo estado]}]
  (jdbc/execute-one! tx
    ["INSERT INTO identidade.vinculo (id, ente_id, identidade_id, tipo, estado)
      VALUES (?, ?, ?, ?, ?)" id ente-id identidade-id tipo (or estado "ativo")]))

(defn vinculos-de
  "Os vinculos da identidade NESTE ente (RLS ja restringe ao tenant). Base do escopo ativo (§22.5 eixo D)."
  [tx identidade-id]
  (comum/linhas->kebab
    (jdbc/execute! tx ["SELECT id, ente_id, identidade_id, tipo, estado FROM identidade.vinculo
                        WHERE identidade_id = ?" identidade-id])))

(defn mudar-estado! [tx id estado]
  {:pre [(contains? mod/estados-vinculo estado)]}   ; erro de dominio antes do CHECK do banco virar PSQLException
  (jdbc/execute-one! tx ["UPDATE identidade.vinculo SET estado = ? WHERE id = ?" estado id]))

;; ---- usuario_papel (RBAC estatico) ----
(defn adicionar-papel! [tx {:keys [id ente-id identidade-id papel]}]
  (jdbc/execute-one! tx
    ["INSERT INTO identidade.usuario_papel (id, ente_id, identidade_id, papel) VALUES (?, ?, ?, ?)
      ON CONFLICT (ente_id, identidade_id, papel) DO NOTHING" id ente-id identidade-id papel]))

(defn papeis-de
  "Conjunto de papeis estaticos da identidade neste ente (o snapshot do token, §22.5.2 eixo D)."
  [tx identidade-id]
  (set (map :usuario_papel/papel
            (jdbc/execute! tx ["SELECT papel FROM identidade.usuario_papel WHERE identidade_id = ?" identidade-id]))))

;; ---- consentimento (LGPD, §22.5.2 eixo G) ----
(defn registrar-consentimento! [tx {:keys [id ente-id identidade-id finalidade base-legal versao-termo]}]
  (jdbc/execute-one! tx
    ["INSERT INTO identidade.consentimento (id, ente_id, identidade_id, finalidade, base_legal, versao_termo)
      VALUES (?, ?, ?, ?, ?, ?)" id ente-id identidade-id finalidade base-legal versao-termo]))

(defn revogar-consentimento!
  "Revogacao e' ato auditado (set revogado_em); tratamentos com base 'consentimento' cessam, 'obrigacao
  legal' nao. Retorna true se REVOGOU de fato (LGPD: o titular tem direito a confirmacao); false se o
  consentimento nao existe ou ja estava revogado (caller decide se isso e' erro)."
  [tx id]
  (pos? (:next.jdbc/update-count
         (jdbc/execute-one! tx ["UPDATE identidade.consentimento SET revogado_em = now()
                                 WHERE id = ? AND revogado_em IS NULL" id]))))

(defn consentimentos-ativos [tx identidade-id]
  (comum/linhas->kebab
    (jdbc/execute! tx ["SELECT id, ente_id, identidade_id, finalidade, base_legal, versao_termo
                        FROM identidade.consentimento WHERE identidade_id = ? AND revogado_em IS NULL" identidade-id])))
