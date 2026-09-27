(ns oplenario.normas.db.norma
  "Persistencia do conhecimento normativo (mig 0094, ADR-0011) — funcoes sobre a `tx` do tenant. A RLS deixa ver as
  normas de referencia (federal/estadual, sem ente_id) e as da propria Casa, e so' deixa escrever as da Casa."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private colunas-norma [:id :ente_id :camada :uf :municipio_ibge :especie :numero :data :titulo])

(def ^:private colunas-versao
  [:id :norma_id :estado :consolidada_ate :fonte :texto_sha256 :n_dispositivos :alertas :enviada_por :enviada_em
   :decidida_por :decidida_em])

(defn- versao<- [linha]
  (some-> linha comum/linha->kebab (update :alertas comum/jsonb->kw)))

(defn norma-por-identidade
  "A norma da Casa com esta especie (e numero, se houver) — a LOM e o Regimento sao unicos na Casa."
  [tx ente-id especie numero]
  (comum/linha->kebab
    (jdbc/execute-one! tx (sql/format {:select colunas-norma :from [:normas.norma]
                                       :where [:and [:= :ente_id ente-id] [:= :especie especie]
                                               (if numero [:= :numero numero] [:= :numero nil])]}))))

(defn inserir-norma!
  [tx {:keys [ente-id camada municipio-ibge especie numero data titulo criada-por]}]
  (let [id (random-uuid)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :normas.norma
                   :values [{:id id :ente_id ente-id :camada camada :municipio_ibge municipio-ibge :especie especie
                             :numero numero :data data :titulo titulo :criada_por criada-por}]}))
    id))

(defn tem-versao-em-conferencia? [tx norma-id]
  (some? (jdbc/execute-one! tx (sql/format {:select [1] :from [:normas.versao]
                                            :where [:and [:= :norma_id norma-id] [:= :estado "em_conferencia"]]}))))

(defn inserir-versao!
  "A versao importada (em conferencia) e os dispositivos dela, na mesma tx. Devolve o id."
  [tx {:keys [ente-id norma-id consolidada-ate fonte texto texto-sha256 alertas enviada-por]} dispositivos]
  (let [id (random-uuid)]
    (jdbc/execute-one! tx
      (sql/format {:insert-into :normas.versao
                   :values [{:id id :norma_id norma-id :ente_id ente-id :estado "em_conferencia"
                             :consolidada_ate consolidada-ate :fonte fonte :texto texto :texto_sha256 texto-sha256
                             :n_dispositivos (count dispositivos) :alertas (comum/->jsonb (vec alertas))
                             :enviada_por enviada-por}]}))
    (doseq [lote (partition-all 500 dispositivos)]
      (jdbc/execute-one! tx
        (sql/format {:insert-into :normas.dispositivo
                     :values (vec (for [d lote]
                                    {:versao_id id :ente_id ente-id :endereco (:endereco d) :rotulo (:rotulo d)
                                     :tipo (name (:tipo d)) :pai (:pai d) :ordem (:ordem d) :texto (:texto d)
                                     :agrupador (:agrupador d)}))})))
    id))

(defn versao
  "A versao (sem o texto bruto) com a norma dela, ou nil."
  [tx versao-id]
  (when-let [v (versao<- (jdbc/execute-one! tx (sql/format {:select colunas-versao :from [:normas.versao]
                                                             :where [:= :id versao-id]})))]
    (assoc v :norma (comum/linha->kebab
                     (jdbc/execute-one! tx (sql/format {:select colunas-norma :from [:normas.norma]
                                                        :where [:= :id (:norma-id v)]}))))))

(defn dispositivos [tx versao-id]
  (comum/linhas->kebab
    (jdbc/execute! tx (sql/format {:select [:endereco :rotulo :tipo :pai :ordem :texto :agrupador]
                                   :from [:normas.dispositivo] :where [:= :versao_id versao-id]
                                   :order-by [[:ordem :asc]]}))))

(defn listar
  "As normas visiveis (referencia + da Casa), cada uma com a versao vigente e a em conferencia, se houver."
  [tx]
  (let [normas (comum/linhas->kebab
                (jdbc/execute! tx (sql/format {:select colunas-norma :from [:normas.norma]
                                               :order-by [[:camada :asc] [:especie :asc] [:titulo :asc]]})))
        versoes (when (seq normas)
                  (map versao<-
                       (jdbc/execute! tx (sql/format {:select colunas-versao :from [:normas.versao]
                                                      :where [:and [:in :norma_id (mapv :id normas)]
                                                              [:in :estado ["vigente" "em_conferencia"]]]}))))
        por-norma (group-by :norma-id versoes)]
    (vec (for [n normas :let [vs (get por-norma (:id n))]]
           (assoc n
                  :vigente (first (filter #(= "vigente" (:estado %)) vs))
                  :em-conferencia (first (filter #(= "em_conferencia" (:estado %)) vs)))))))

(defn decidir!
  "A conferencia: 'publicar' torna a versao vigente (a anterior passa a substituida); 'descartar' a descarta. So' uma
  versao EM CONFERENCIA e' decidida — devolve nil se ela ja' foi decidida (ou nao existe)."
  [tx versao-id decisao pessoa]
  (let [v (jdbc/execute-one! tx (sql/format {:select [:id :norma_id] :from [:normas.versao]
                                             :where [:and [:= :id versao-id] [:= :estado "em_conferencia"]]
                                             :for :update}))]
    (when v
      (when (= "publicar" decisao)
        (jdbc/execute-one! tx (sql/format {:update :normas.versao
                                           :set {:estado "substituida"}
                                           :where [:and [:= :norma_id (:versao/norma_id v)] [:= :estado "vigente"]]})))
      (jdbc/execute-one! tx (sql/format {:update :normas.versao
                                         :set {:estado (if (= "publicar" decisao) "vigente" "descartada")
                                               :decidida_por pessoa :decidida_em [:now]}
                                         :where [:= :id versao-id]}))
      (versao tx versao-id))))

;; ---------- B.5: a leitura pelo agente (so' a versao VIGENTE) ----------

(def ^:private colunas-leitura
  [[:d.endereco :endereco] [:d.rotulo :rotulo] [:d.tipo :tipo] [:d.pai :pai] [:d.ordem :ordem] [:d.texto :texto]
   [:d.agrupador :agrupador] [:v.id :versao_id] [:v.consolidada_ate :consolidada_ate] [:v.decidida_em :conferida_em]
   [:n.id :norma_id] [:n.especie :especie] [:n.titulo :titulo] [:n.camada :camada]])

(defn- base-vigente []
  {:select colunas-leitura
   :from [[:normas.dispositivo :d]]
   :join [[:normas.versao :v] [:= :v.id :d.versao_id]
          [:normas.norma :n] [:= :n.id :v.norma_id]]
   :where [:= :v.estado "vigente"]})

(defn norma-da-casa-por-especie
  "O id da norma da Casa com esta especie (LOM e Regimento sao unicos na Casa); nil se nao houver."
  [tx ente-id especie]
  (:norma/id (jdbc/execute-one! tx (sql/format {:select [:id] :from [:normas.norma]
                                                :where [:and [:= :ente_id ente-id] [:= :especie especie]]
                                                :order-by [[:criada_em :asc]] :limit 1}))))

(defn- escapar-like
  "O texto literal num padrao LIKE: `%`, `_` e a barra deixam de ser curinga (o endereco tem `_`; a consulta vem da
  pessoa)."
  [s]
  (str/replace s #"[\\\\%_]" #(str "\\" %)))

(defn ler-vigente
  "O dispositivo `endereco` da versao VIGENTE da norma, com os descendentes (o artigo inteiro: paragrafos, incisos,
  alineas), em ordem. Vazio se a norma nao tem vigente ou o endereco nao existe nela."
  [tx norma-id endereco]
  (comum/linhas->kebab
    (jdbc/execute! tx (sql/format (-> (base-vigente)
                                      (update :where (fn [w] [:and w [:= :n.id norma-id]
                                                              [:or [:= :d.endereco endereco]
                                                               [:like :d.endereco (str (escapar-like endereco) "\\_%")]]]))
                                      (assoc :order-by [[:d.ordem :asc]]))))))

(defn vigentes-por-endereco
  "Hidratacao da busca da IA: dos pares [versao-id endereco], so' os que ainda sao da versao VIGENTE (e visiveis pela
  RLS). A IA pode estar um passo atras; o core decide o que existe."
  [tx pares]
  (if (empty? pares)
    []
    (comum/linhas->kebab
      (jdbc/execute! tx (sql/format (update (base-vigente) :where
                                            (fn [w] [:and w (into [:or] (for [[v e] pares]
                                                                         [:and [:= :d.versao_id v] [:= :d.endereco e]]))])))))))

(defn buscar-literal
  "R-IA-1: com a IA fora, a busca cai nas palavras exatas do texto vigente (todas as palavras da consulta)."
  [tx consulta limite]
  (let [palavras (->> (str/split (str/lower-case consulta) #"\s+") (remove #(< (count %) 3)) (take 6))]
    (if (empty? palavras)
      []
      (comum/linhas->kebab
        (jdbc/execute! tx (sql/format (-> (base-vigente)
                                          (update :where (fn [w] (into [:and w] (for [p palavras]
                                                                                 [:ilike :d.texto (str "%" (escapar-like p) "%")]))))
                                          (assoc :order-by [[:n.especie :asc] [:d.ordem :asc]] :limit limite))))))))
