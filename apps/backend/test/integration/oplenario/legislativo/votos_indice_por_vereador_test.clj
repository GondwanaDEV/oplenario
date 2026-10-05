(ns oplenario.legislativo.votos-indice-por-vereador-test
  "INTEGRACAO (PG real) — `GET /meu/votos` lia `legislativo.votos` por (ente_id, vereador_id) numa tabela que so'
  tinha a UNIQUE (ente_id, votacao_id, vereador_id) e um parcial de staging: cada abertura da tela varria os votos da
  Casa. A mig 20261005000230 cria `idx_votos_vereador_registrado`.

  Prova em duas partes, sem FORCAR o planner (nada de `enable_seqscan = off`, que escolheria o indice por
  decreto):
  (1) o indice existe no catalogo com a definicao esperada;
  (2) com volume real (2 Casas x 21 vereadores x 300 votacoes = 12.600 votos), depois de `ANALYZE`, o plano das DUAS
      queries REAIS de `meus-votos` (o HoneySQL vem das proprias `consulta-votos` / `consulta-contagem`, nao de uma
      copia) le `legislativo.votos` pelo indice — e nao por Seq Scan. O EXPLAIN roda no regime de tenant
      (`oplenario_app` + RLS), o mesmo da producao."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [honey.sql :as sql]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.meus-votos :as meus-votos]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)]
        (try (t) (finally (component/stop c)))))))

(def ^:private vereadores-por-casa 21)
(def ^:private votacoes-por-casa 300)

(def ^:private indice "idx_votos_vereador_registrado")

(defn- semear-casa!
  "Uma Casa com `votacoes-por-casa` votacoes nominais encerradas e o voto de cada um dos `vereadores-por-casa`
  vereadores em todas (UNIQUE (ente_id, votacao_id, vereador_id) respeitada). `registrado_em` distinto por votacao
  para a ordem da lista ser determinada. Devolve {:ente :vereadores [uuid ...]}."
  []
  (let [ente (random-uuid)
        vereadores (vec (repeatedly vereadores-por-casa random-uuid))]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (jdbc/execute! tx
          ["INSERT INTO legislativo.votacoes
              (ente_id, id, objeto_tipo, objeto_id, modalidade, quorum_tipo, estado, resultado, efetivado_em)
            SELECT ?, gen_random_uuid(), 'proposicao', gen_random_uuid(), 'nominal', 'maioria_simples',
                   'encerrada', 'aprovada', now()
              FROM generate_series(1, ?)" ente votacoes-por-casa])
        (jdbc/execute! tx
          ["INSERT INTO legislativo.votos (ente_id, votacao_id, vereador_id, voto, efetivado_em, registrado_em)
            SELECT vt.ente_id, vt.id, v.id,
                   (ARRAY['sim','nao','abstencao'])[1 + (abs(hashtext(vt.id::text || v.id::text)) % 3)],
                   now(), now() - (vt.n || ' minutes')::interval
              FROM (SELECT ente_id, id, row_number() OVER (ORDER BY id) AS n
                      FROM legislativo.votacoes WHERE ente_id = ?) vt
             CROSS JOIN unnest(string_to_array(?, ',')::uuid[]) AS v(id)"
           ente (str/join "," vereadores)])))
    {:ente ente :vereadores vereadores}))

(defn- plano
  "O EXPLAIN (FORMAT JSON) da query HoneySQL, no regime de tenant da Casa. Devolve o no raiz do plano (mapa)."
  [ente consulta]
  (let [[texto & params] (sql/format consulta)
        linha (tenancy/com-tenant* *ds* ente
                (fn [tx]
                  (jdbc/execute-one! tx (into [(str "EXPLAIN (FORMAT JSON) " texto)] params)
                                     {:builder-fn rs/as-unqualified-maps})))
        json-plano (str (get linha (keyword "QUERY PLAN")))]
    (-> (json/read-value json-plano json/keyword-keys-object-mapper) first :Plan)))

(defn- nos [plano]
  (cons plano (mapcat nos (:Plans plano))))

(defn- votos-no-plano
  "Como `legislativo.votos` e' lida: um mapa por no que a le (Seq Scan, Index Scan, Bitmap Heap Scan...), com os
  indices usados por ele e por seus filhos — o Bitmap Heap Scan nao carrega o nome do indice, quem o carrega e' o
  Bitmap Index Scan abaixo dele (que nao tem `Relation Name`)."
  [plano]
  (->> (nos plano)
       (filter #(= "votos" (get % (keyword "Relation Name"))))
       (mapv (fn [n] {:no (get n (keyword "Node Type"))
                      :indices (->> (nos n) (keep #(get % (keyword "Index Name"))) distinct vec)}))))

(defn- le-pelo-indice? [acessos]
  (and (seq acessos)
       (every? #(= [indice] (:indices %)) acessos)
       (not-any? #(= "Seq Scan" (:no %)) acessos)))

(deftest o-indice-existe-no-catalogo-com-a-definicao-esperada
  (let [defs (map :indexdef
                  (jdbc/execute! *ds*
                    ["SELECT indexdef FROM pg_indexes
                       WHERE schemaname = 'legislativo' AND tablename = 'votos' AND indexname = ?" indice]
                    {:builder-fn rs/as-unqualified-kebab-maps}))]
    (is (= 1 (count defs)) "o indice da mig 20261005000230 existe, uma vez")
    (is (str/includes? (first defs)
                       "(ente_id, vereador_id, registrado_em DESC, votacao_id DESC)")
        "predicado (ente_id, vereador_id) + a ordem da lista, na mesma direcao da query")
    (is (not (str/includes? (first defs) "WHERE"))
        "nao e' parcial: a query nao tem predicado de efetivado_em (a policy de RLS e' que filtra voto de lote)")))

(deftest o-plano-das-queries-reais-de-meus-votos-le-votos-pelo-indice
  (let [{:keys [ente vereadores]} (semear-casa!)
        _outra (semear-casa!)
        alvo (first vereadores)]
    ;; estatisticas reais (o runbook da mig manda `ANALYZE legislativo.votos` depois do deploy); sem elas o
    ;; planner nao tem `reltuples` e o resultado depende de quando o autovacuum rodou
    (jdbc/execute! *ds* ["ANALYZE legislativo.votos"])
    (jdbc/execute! *ds* ["ANALYZE legislativo.votacoes"])
    (testing "a tabela tem o volume do cenario (as 2 Casas semeadas aqui, no minimo)"
      (is (<= (* 2 vereadores-por-casa votacoes-por-casa)
              (:n (jdbc/execute-one! *ds* ["SELECT count(*) AS n FROM legislativo.votos"]
                                     {:builder-fn rs/as-unqualified-kebab-maps})))))
    (testing "a LISTA le legislativo.votos pelo indice por vereador (sem Seq Scan)"
      (let [acessos (votos-no-plano (plano ente (meus-votos/consulta-votos ente alvo)))]
        (println "PLANO lista — acessos a votos:" (pr-str acessos))
        (is (le-pelo-indice? acessos)
            (str "todo acesso a votos usa " indice " e nenhum e' Seq Scan; achou " (pr-str acessos)))))
    (testing "a CONTAGEM le legislativo.votos pelo indice por vereador (sem Seq Scan)"
      (let [acessos (votos-no-plano (plano ente (meus-votos/consulta-contagem ente alvo)))]
        (println "PLANO contagem — acessos a votos:" (pr-str acessos))
        (is (le-pelo-indice? acessos)
            (str "todo acesso a votos usa " indice " e nenhum e' Seq Scan; achou " (pr-str acessos)))))
    (testing "o resultado funcional nao muda: a lista sai do mais recente ao mais antigo e a contagem fecha"
      (tenancy/com-tenant* *ds* ente
        (fn [tx]
          (let [lista (meus-votos/votos-do-vereador tx ente alvo)
                {:keys [total sim nao abstencao]} (meus-votos/contar-por-opcao tx ente alvo)]
            (is (= meus-votos/teto-votos (count lista)))
            (is (= (sort-by :registrado-em #(compare %2 %1) lista) lista))
            (is (= votacoes-por-casa total))
            (is (= total (+ sim nao abstencao)))))))))
