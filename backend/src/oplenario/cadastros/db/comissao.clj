(ns oplenario.cadastros.db.comissao
  "Persistencia de comissao (a Mesa Diretora e' tipo='mesa') + cargos nomeados + membership.
  Funcoes sobre a `tx` do tenant (RLS isola)."
  (:require [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn inserir!
  ;; criacao NATIVA nasce efetivada (efetivado_em = now()); import (admin_sistema) e' que estaga. Fundacao #2.
  [tx {:keys [id ente-id nome tipo legislatura-id vigencia-inicio vigencia-fim]}]
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.comissao (id, ente_id, nome, tipo, legislatura_id, vigencia_inicio, vigencia_fim, efetivado_em)
      VALUES (?, ?, ?, ?, ?, ?, ?, now())" id ente-id nome (or tipo "permanente") legislatura-id vigencia-inicio vigencia-fim]))

(defn buscar [tx id]
  (comum/linha->kebab
    (jdbc/execute-one! tx ["SELECT id, ente_id, nome, tipo, legislatura_id, vigencia_inicio, vigencia_fim
                            FROM cadastros.comissao WHERE id = ?" id])))

(defn mesa-vigente
  "A Mesa Diretora vigente em `data` (tipo='mesa', dentro da vigencia). Base de quem_exerce_presidencia (F2)."
  [tx data]
  (comum/linha->kebab
    (jdbc/execute-one! tx ["SELECT id, ente_id, nome, tipo, legislatura_id, vigencia_inicio, vigencia_fim
                            FROM cadastros.comissao
                            WHERE tipo = 'mesa' AND vigencia_inicio <= ?
                              AND (vigencia_fim IS NULL OR vigencia_fim >= ?)
                            ORDER BY vigencia_inicio DESC LIMIT 1" data data])))

(defn inserir-cargo! [tx {:keys [id ente-id comissao-id vereador-id cargo vigencia-inicio vigencia-fim]}]
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.comissao_cargo (id, ente_id, comissao_id, vereador_id, cargo, vigencia_inicio, vigencia_fim, efetivado_em)
      VALUES (?, ?, ?, ?, ?, ?, ?, now())" id ente-id comissao-id vereador-id cargo vigencia-inicio vigencia-fim]))

(defn inserir-membro! [tx {:keys [id ente-id comissao-id vereador-id vigencia-inicio vigencia-fim]}]
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.comissao_membro (id, ente_id, comissao_id, vereador_id, vigencia_inicio, vigencia_fim, efetivado_em)
      VALUES (?, ?, ?, ?, ?, ?, now())" id ente-id comissao-id vereador-id vigencia-inicio vigencia-fim]))

(defn membros [tx comissao-id]
  (comum/linhas->kebab
    (jdbc/execute! tx ["SELECT id, ente_id, comissao_id, vereador_id, vigencia_inicio, vigencia_fim
                        FROM cadastros.comissao_membro WHERE comissao_id = ?" comissao-id])))
