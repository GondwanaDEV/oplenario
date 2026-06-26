(ns oplenario.cadastros.db.vereador
  "Persistencia de vereador + mandato (entidade com estado, §22.5 eixo C) + licenca + suplencia.
  Funcoes sobre a `tx` do tenant (RLS isola)."
  (:require [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

;; ---- vereador (registro institucional; identidade-id = guard ref ao modulo identidade) ----
(defn inserir!
  ;; criacao NATIVA nasce efetivada (efetivado_em = now()); import (admin_sistema) e' que estaga. Fundacao #2.
  [tx {:keys [id ente-id identidade-id nome nome-parlamentar]}]
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.vereador (id, ente_id, identidade_id, nome, nome_parlamentar, efetivado_em)
      VALUES (?, ?, ?, ?, ?, now())" id ente-id identidade-id nome nome-parlamentar]))

(defn buscar [tx id]
  (comum/linha->kebab
    (jdbc/execute-one! tx ["SELECT id, ente_id, identidade_id, nome, nome_parlamentar
                            FROM cadastros.vereador WHERE id = ?" id])))

(defn por-identidade
  "O vereador vinculado a uma identidade (CPF) neste ente — base da autorizacao por relacao (F2)."
  [tx identidade-id]
  (comum/linha->kebab
    (jdbc/execute-one! tx ["SELECT id, ente_id, identidade_id, nome, nome_parlamentar
                            FROM cadastros.vereador WHERE identidade_id = ?" identidade-id])))

;; ---- mandato ----
(defn inserir-mandato!
  [tx {:keys [id ente-id vereador-id legislatura-id partido estado natureza
              vigencia-inicio vigencia-fim fim-efetivo]}]
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.mandato (id, ente_id, vereador_id, legislatura_id, partido, estado, natureza,
        vigencia_inicio, vigencia_fim, fim_efetivo, efetivado_em)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())"
     id ente-id vereador-id legislatura-id partido (or estado "vigente") (or natureza "titular")
     vigencia-inicio vigencia-fim fim-efetivo]))

(defn mudar-estado!
  "Transicao de estado do mandato (cassacao/renuncia/licenca/...). fim-efetivo opcional."
  [tx {:keys [id estado fim-efetivo]}]
  (jdbc/execute-one! tx
    ["UPDATE cadastros.mandato SET estado = ?, fim_efetivo = COALESCE(?, fim_efetivo) WHERE id = ?"
     estado fim-efetivo id]))

(defn mandatos-do-vereador [tx vereador-id]
  (comum/linhas->kebab
    (jdbc/execute! tx ["SELECT id, ente_id, vereador_id, legislatura_id, partido, estado, natureza,
                          vigencia_inicio, vigencia_fim, fim_efetivo
                        FROM cadastros.mandato WHERE vereador_id = ? ORDER BY vigencia_inicio" vereador-id])))

;; ---- licenca + suplencia ----
(defn inserir-licenca! [tx {:keys [id ente-id mandato-id mandato-suplente-id inicio fim motivo]}]
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.mandato_licenca (id, ente_id, mandato_id, mandato_suplente_id, inicio, fim, motivo, efetivado_em)
      VALUES (?, ?, ?, ?, ?, ?, ?, now())" id ente-id mandato-id mandato-suplente-id inicio fim motivo]))

(defn inserir-suplencia! [tx {:keys [id ente-id legislatura-id partido vereador-id ordem]}]
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.suplencia (id, ente_id, legislatura_id, partido, vereador_id, ordem, efetivado_em)
      VALUES (?, ?, ?, ?, ?, ?, now())" id ente-id legislatura-id partido vereador-id ordem]))
