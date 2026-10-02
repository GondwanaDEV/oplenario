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

(defn nomes-por-id
  "ids -> {id nome}, numa consulta so'. Base da metade de `cadastros` do `resolver-comissoes` que o HOST
  injeta no `legislativo` (§22.5.3, exceção nomeada): o legislativo guarda `comissao_id` como guard ref
  `uuid NOT NULL` SEM FK cross-schema (mig 20260620000019) e por isso nunca soube o nome — a tela do
  parecer mostrava o UUID (defeito #11 do ledger de prontidao).

  PLURAL de proposito: a ficha da materia lista N pareceres, e resolver um a um seria N transacoes por
  request. Cardinalidade nao e' aberta (nao leva teto proprio como a pauta/o fan-out): os ids chegam de
  uma lista que o Repo do legislativo ja' limita em 50.

  Coll vazia (ou so' de nils) NAO vai ao banco — `IN ()` nao e' SQL valido — e devolve {}. Id sem
  comissao correspondente simplesmente nao aparece no mapa: o chamador degrada pra nil, nunca lanca.
  RLS isola o tenant, mesma forma de `buscar` (que tambem nao repete `ente_id` no WHERE)."
  [tx ids]
  (let [ids (vec (distinct (remove nil? ids)))]
    (if (empty? ids)
      {}
      (into {}
            (map (juxt :id :nome))
            (comum/linhas->kebab
              (jdbc/execute! tx
                (sql/format {:select [:id :nome] :from [:cadastros.comissao] :where [:in :id ids]})))))))

(defn listar-vigentes
  "As comissoes (menos a Mesa Diretora) vigentes em `data`, por nome — o que a secretaria escolhe ao encaminhar uma
  materia para parecer (ADR-0019). Devolve [{:id :nome :tipo}]."
  [tx data]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :nome :tipo] :from [:cadastros.comissao]
                   :where [:and [:<> :tipo "mesa"]
                           [:or [:is :vigencia_inicio nil] [:<= :vigencia_inicio data]]
                           [:or [:is :vigencia_fim nil] [:>= :vigencia_fim data]]]
                   :order-by [[:nome :asc]]}))))

(defn- vigente-em [prefixo data]
  (let [ini (keyword (str prefixo ".vigencia_inicio")) fim (keyword (str prefixo ".vigencia_fim"))]
    [:and [:or [:is ini nil] [:<= ini data]] [:or [:is fim nil] [:>= fim data]]]))

(defn com-membros-vigentes
  "ADR-0020: as comissoes vigentes em `data` (INCLUSIVE a Mesa Diretora — a Mesa e' um destino legitimo de comunicado)
  com os membros VIGENTES em `data`: [{:id :nome :tipo :membros [{:vereador-id :identidade-id :nome}]}], por nome.
  `comissao-id` (opcional) restringe a uma. Membro = `comissao_membro` vigente; nome de exibicao = o parlamentar, ou o
  civil. `:identidade-id` nil = o vereador nao tem acesso ao sistema. Duas consultas (comissoes + membros em lote)."
  ([tx ente-id data] (com-membros-vigentes tx ente-id data nil))
  ([tx ente-id data comissao-id]
   (let [cs (comum/linhas->kebab
             (jdbc/execute! tx
               (sql/format {:select [:c.id :c.nome :c.tipo] :from [[:cadastros.comissao :c]]
                            :where (cond-> [:and [:= :c.ente_id ente-id] (vigente-em "c" data)]
                                     comissao-id (conj [:= :c.id comissao-id]))
                            ;; a Mesa primeiro, depois por nome
                            :order-by [[[:case [:= :c.tipo "mesa"] 0 :else 1] :asc] [:c.nome :asc] [:c.id :asc]]})))
         ms (when (seq cs)
              (comum/linhas->kebab
               (jdbc/execute! tx
                 (sql/format {:select [:cm.comissao_id [:v.id :vereador_id] :v.identidade_id :v.nome :v.nome_parlamentar]
                              :from [[:cadastros.comissao_membro :cm]]
                              :join [[:cadastros.vereador :v] [:and [:= :v.ente_id :cm.ente_id] [:= :v.id :cm.vereador_id]]]
                              :where [:and [:= :cm.ente_id ente-id] [:in :cm.comissao_id (mapv :id cs)]
                                      (vigente-em "cm" data)]
                              :order-by [[:v.nome :asc] [:v.id :asc]]}))))
         por-comissao (group-by :comissao-id ms)]
     (mapv (fn [c]
             (assoc c :membros (->> (get por-comissao (:id c))
                                    (map (fn [m] {:vereador-id (:vereador-id m) :identidade-id (:identidade-id m)
                                                  :nome (or (not-empty (:nome-parlamentar m)) (:nome m))}))
                                    (reduce (fn [acc m] (if (some #(= (:vereador-id m) (:vereador-id %)) acc) acc (conj acc m)))
                                            []))))
           cs))))

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
