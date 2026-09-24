(ns oplenario.legislativo.proposicao-listagem-db-test
  "INTEGRACAO (PG real): Onda B Slice 1 — a nova query de leitura filtravel/paginada/ordenavel sobre
  `legislativo.proposicoes` (fonte da verdade, NAO read-model). Prova filtros combinaveis, paginacao
  offset/limit com desempate estavel, ordenacao pelo allowlist e isolamento por ente_id (RLS)."
  (:require [clojure.set]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(def ^:private filtro-base
  {:busca nil :tipo nil :estado nil :autor-id nil :ano nil
   :pagina 1 :tamanho 20 :ordenar-por "atualizado_em" :ordenar-dir "desc"})

(defn- protocolar!
  [tx ente & {:keys [tipo ano ementa autor-id autor-texto tipo-requerimento]
              :or {tipo "projeto_lei" ano 2026 ementa "Dispoe sobre X"}}]
  (:id (prop/protocolar! tx (cond-> {:id (random-uuid) :ente-id ente :tipo tipo :ano ano
                                     :uf "CE" :municipio-nome "Fortaleza" :ementa ementa}
                              autor-id     (assoc :autor-id autor-id :autor-tipo "vereador")
                              autor-texto  (assoc :autor-texto autor-texto)
                              (= tipo "requerimento") (assoc :tipo-requerimento (or tipo-requerimento "informacao"))))))

(deftest lista-so-do-proprio-ente
  (let [e1 (random-uuid) e2 (random-uuid)]
    (tenancy/com-tenant* *ds* e1 (fn [tx] (protocolar! tx e1)))
    (tenancy/com-tenant* *ds* e2 (fn [tx] (protocolar! tx e2)))
    (tenancy/com-tenant* *ds* e1
      (fn [tx]
        (let [resultado (prop/listar tx e1 filtro-base)]
          (is (= 1 (count resultado)) "so' enxerga a proposicao do proprio ente (RLS)")
          (is (some? (:atualizado-em (first resultado))) "atualizado-em vem preenchido (coluna precisa estar em `colunas`)")
          (is (= 1 (prop/contar tx e1 filtro-base))))))))

(deftest filtro-por-tipo-estado-ano-autor-combinaveis
  (let [ente (random-uuid) autor (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (protocolar! tx ente :tipo "projeto_lei" :ano 2026 :ementa "Hortas comunitarias" :autor-id autor :autor-texto "Helena Matos")
        (protocolar! tx ente :tipo "requerimento" :ano 2026 :ementa "Informacoes sobre iluminacao")
        (protocolar! tx ente :tipo "projeto_lei" :ano 2025 :ementa "Outra materia de 2025")
        (is (= 2 (prop/contar tx ente (assoc filtro-base :tipo "projeto_lei"))) "filtro por tipo")
        (is (= 1 (prop/contar tx ente (assoc filtro-base :tipo "projeto_lei" :ano 2026))) "tipo+ano combinados")
        (is (= 1 (prop/contar tx ente (assoc filtro-base :autor-id autor))) "filtro por autor-id")
        (is (= 1 (count (prop/listar tx ente (assoc filtro-base :tipo "requerimento")))))))))

(deftest busca-textual-ilike-case-insensitive-em-ementa-e-urn
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (protocolar! tx ente :ementa "Cria o Programa Municipal de Hortas Comunitarias")
        (protocolar! tx ente :ementa "Dispoe sobre acessibilidade")
        (is (= 1 (prop/contar tx ente (assoc filtro-base :busca "hortas"))) "case-insensitive, substring")
        (is (= 1 (prop/contar tx ente (assoc filtro-base :busca "HORTAS"))))
        (is (= 0 (prop/contar tx ente (assoc filtro-base :busca "inexistente"))))))))

(deftest paginacao-offset-limit-com-desempate-estavel
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (dotimes [n 5] (protocolar! tx ente :ementa (str "Materia " n)))
        (let [total (prop/contar tx ente filtro-base)
              pagina1 (prop/listar tx ente (assoc filtro-base :pagina 1 :tamanho 2))
              pagina2 (prop/listar tx ente (assoc filtro-base :pagina 2 :tamanho 2))
              pagina3 (prop/listar tx ente (assoc filtro-base :pagina 3 :tamanho 2))]
          (is (= 5 total))
          (is (= 2 (count pagina1))) (is (= 2 (count pagina2))) (is (= 1 (count pagina3)))
          (is (empty? (clojure.set/intersection (set (map :id pagina1)) (set (map :id pagina2))))
              "paginas nao se sobrepoem")
          (is (= 5 (count (distinct (map :id (concat pagina1 pagina2 pagina3)))))
              "as 3 paginas cobrem as 5 linhas sem duplicar/pular"))))))

(deftest pagina-alem-do-total-devolve-vazio
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (protocolar! tx ente)
        (is (= [] (prop/listar tx ente (assoc filtro-base :pagina 99 :tamanho 20))))))))

(deftest ordenacao-por-ano-allowlist
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (protocolar! tx ente :ano 2024 :ementa "Mais antiga")
        (protocolar! tx ente :ano 2026 :ementa "Mais nova")
        (let [asc (prop/listar tx ente (assoc filtro-base :ordenar-por "ano" :ordenar-dir "asc"))]
          (is (= [2024 2026] (mapv :ano asc))))
        (let [desc (prop/listar tx ente (assoc filtro-base :ordenar-por "ano" :ordenar-dir "desc"))]
          (is (= [2026 2024] (mapv :ano desc))))))))

(deftest resumos-por-ids-lote-so-do-proprio-ente
  ;; Modo TV (docs/22): o resumo da pauta em UMA query. Id de outro ente nao volta (a RLS + o ente_id no WHERE
  ;; isolam) e id inexistente simplesmente some — quem chama nao inventa. Lote vazio nao toca o banco.
  (let [e1 (random-uuid) e2 (random-uuid)
        alheia (tenancy/com-tenant* *ds* e2 (fn [tx] (protocolar! tx e2 :ementa "Materia de outra Casa")))]
    (tenancy/com-tenant* *ds* e1
      (fn [tx]
        (let [a (protocolar! tx e1 :tipo "projeto_lei" :ementa "Energia solar em predios publicos")
              b (protocolar! tx e1 :tipo "requerimento" :ementa "Informacoes sobre escolas")
              rs (prop/resumos-por-ids tx e1 [a b alheia (random-uuid)])
              por-id (into {} (map (juxt :id identity)) rs)]
          (is (= #{a b} (set (keys por-id))) "so' as do proprio ente, e so' as que existem")
          (is (= "Energia solar em predios publicos" (:ementa (get por-id a))))
          (is (= "projeto_lei" (:tipo (get por-id a))))
          (is (= 2026 (:ano (get por-id a))))
          (is (pos-int? (:sequencial (get por-id a))))
          (is (= [] (prop/resumos-por-ids tx e1 [])) "lote vazio -> []"))))))
