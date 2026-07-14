(ns oplenario.cadastros.db.comissao
  "Persistencia de comissao (a Mesa Diretora e' tipo='mesa') + cargos nomeados + membership.
  Funcoes sobre a `tx` do tenant (RLS isola). HoneySQL."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn inserir!
  ;; criacao NATIVA nasce efetivada (efetivado_em = now()); import (admin_sistema) e' que estaga. Fundacao #2.
  [tx {:keys [id ente-id nome tipo legislatura-id vigencia-inicio vigencia-fim]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.comissao
                 :values [{:id id :ente_id ente-id :nome nome :tipo (or tipo "permanente")
                           :legislatura_id legislatura-id :vigencia_inicio vigencia-inicio
                           :vigencia_fim vigencia-fim :efetivado_em [:now]}]})))

(defn buscar [tx id]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :ente_id :nome :tipo :legislatura_id :vigencia_inicio :vigencia_fim]
                   :from [:cadastros.comissao] :where [:= :id id]}))))

(defn mesa-vigente
  "A Mesa Diretora vigente em `data` (tipo='mesa', dentro da vigencia). Base de quem_exerce_presidencia (F2)."
  [tx data]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :ente_id :nome :tipo :legislatura_id :vigencia_inicio :vigencia_fim]
                   :from [:cadastros.comissao]
                   :where [:and [:= :tipo "mesa"] [:<= :vigencia_inicio data]
                           [:or [:is :vigencia_fim nil] [:>= :vigencia_fim data]]]
                   :order-by [[:vigencia_inicio :desc]] :limit 1}))))

(defn inserir-cargo! [tx {:keys [id ente-id comissao-id vereador-id cargo vigencia-inicio vigencia-fim]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.comissao_cargo
                 :values [{:id id :ente_id ente-id :comissao_id comissao-id :vereador_id vereador-id
                           :cargo cargo :vigencia_inicio vigencia-inicio :vigencia_fim vigencia-fim :efetivado_em [:now]}]})))

(defn inserir-membro! [tx {:keys [id ente-id comissao-id vereador-id vigencia-inicio vigencia-fim]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.comissao_membro
                 :values [{:id id :ente_id ente-id :comissao_id comissao-id :vereador_id vereador-id
                           :vigencia_inicio vigencia-inicio :vigencia_fim vigencia-fim :efetivado_em [:now]}]})))

(defn membros [tx comissao-id]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :ente_id :comissao_id :vereador_id :vigencia_inicio :vigencia_fim]
                   :from [:cadastros.comissao_membro] :where [:= :comissao_id comissao-id]}))))

(defn comissoes-do-vereador
  "Comissoes vigentes em `data` de que o vereador e' membro, com o cargo nomeado (se houver)."
  [tx ente-id vereador-id data]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format
        {:select [:c.nome :c.tipo [:cc.cargo :cargo]]
         :from [[:cadastros.comissao_membro :cm]]
         :join [[:cadastros.comissao :c] [:and [:= :c.id :cm.comissao_id] [:= :c.ente_id :cm.ente_id]]]
         :left-join [[:cadastros.comissao_cargo :cc]
                     [:and [:= :cc.comissao_id :cm.comissao_id] [:= :cc.vereador_id :cm.vereador_id]
                      [:= :cc.ente_id :cm.ente_id]
                      [:<= :cc.vigencia_inicio data]
                      [:or [:is :cc.vigencia_fim nil] [:>= :cc.vigencia_fim data]]]]
         :where [:and [:= :cm.ente_id ente-id] [:= :cm.vereador_id vereador-id]
                 [:<= :cm.vigencia_inicio data]
                 [:or [:is :cm.vigencia_fim nil] [:>= :cm.vigencia_fim data]]]
         :order-by [[:c.nome :asc]]}))))
