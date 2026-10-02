(ns oplenario.cadastros.db.setor
  "Persistencia de SETOR e da LOTACAO (ADR-0020 Eixo 1, mig 20261003000180) — funcoes sobre a `tx` do tenant (FORCE
  RLS isola). HoneySQL schema-qualified; `ente_id` em toda query (defesa em profundidade sobre a RLS). Setor nao da
  permissao: e' o endereco de um comunicado. IMPL atras do RepoCadastros (ADR-0001 §3-bis)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def teto-de-setores
  "Uma Casa tem dezenas de setores, nao milhares. Teto explicito na listagem (convencao da casa: toda leitura de
  cardinalidade aberta tem teto no SQL)."
  500)

(defn inserir!
  "Cria o setor (ativo). Nome repetido na Casa (sem diferenca de caixa) -> 23505 do indice unico; o Repo traduz."
  [tx {:keys [id ente-id nome]}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :cadastros.setor
                  :values [{:id id :ente_id ente-id :nome nome}]
                  :returning [:id :nome :ativo :criado_em]}))))

(defn atualizar!
  "Renomeia e/ou ativa/desativa. Devolve a linha nova, ou nil se o setor nao existe nesta Casa."
  [tx ente-id id {:keys [nome ativo]}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:update :cadastros.setor
                  :set (cond-> {}
                         (some? nome) (assoc :nome nome)
                         (some? ativo) (assoc :ativo ativo))
                  :where [:and [:= :ente_id ente-id] [:= :id id]]
                  :returning [:id :nome :ativo :criado_em]}))))

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:id :nome :ativo :criado_em] :from [:cadastros.setor]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar
  "Os setores da Casa por nome (ativos e inativos — a tela de administracao mostra os dois)."
  [tx ente-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :nome :ativo :criado_em] :from [:cadastros.setor]
                  :where [:= :ente_id ente-id]
                  :order-by [[[:lower :nome] :asc] [:id :asc]]
                  :limit teto-de-setores}))))

(defn membros-por-setor
  "{setor-id [identidade-id ...]} dos setores `ids` (lote; ids vazios nao vao ao banco)."
  [tx ente-id ids]
  (let [ids (vec (distinct (remove nil? ids)))]
    (if (empty? ids)
      {}
      (reduce (fn [m {:keys [setor-id identidade-id]}] (update m setor-id (fnil conj []) identidade-id))
              {}
              (comum/linhas->kebab
               (jdbc/execute! tx
                 (sql/format {:select [:setor_id :identidade_id] :from [:cadastros.setor_membro]
                              :where [:and [:= :ente_id ente-id] [:in :setor_id ids]]
                              :order-by [[:desde :asc] [:identidade_id :asc]]})))))))

(defn trocar-membros!
  "Troca a LOTACAO inteira do setor por `identidades` (apaga e insere, na tx do chamador). Quem ja' estava e continua
  mantem o `desde` original — so' sai quem saiu e so' entra quem entrou."
  [tx ente-id setor-id identidades]
  (let [novos (set identidades)]
    (if (empty? novos)
      (jdbc/execute-one! tx (sql/format {:delete-from :cadastros.setor_membro
                                         :where [:and [:= :ente_id ente-id] [:= :setor_id setor-id]]}))
      (do (jdbc/execute-one! tx (sql/format {:delete-from :cadastros.setor_membro
                                             :where [:and [:= :ente_id ente-id] [:= :setor_id setor-id]
                                                     [:not-in :identidade_id (vec novos)]]}))
          (jdbc/execute-one! tx (sql/format {:insert-into :cadastros.setor_membro
                                             :values (mapv (fn [i] {:ente_id ente-id :setor_id setor-id
                                                                    :identidade_id i})
                                                           (sort-by str novos))
                                             :on-conflict [:ente_id :setor_id :identidade_id]
                                             :do-nothing true}))))
    nil))
