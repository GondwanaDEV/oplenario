(ns oplenario.cadastros.db.referencia
  "Persistencia das tabelas de REFERENCIA/DOMINIO do cadastros (sem ente_id): municipios (IBGE),
  tribunal_de_contas (E2) e jurisdicao_camara (E1). Lidas dentro da tx do tenant (mesmo schema). HoneySQL."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn inserir-municipio! [tx {:keys [codigo-ibge nome uf capital populacao]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.municipios
                 :values [{:codigo_ibge codigo-ibge :nome nome :uf uf :capital (boolean capital) :populacao populacao}]
                 :on-conflict [:codigo_ibge]
                 :do-update-set {:nome :excluded.nome :uf :excluded.uf
                                 :capital :excluded.capital :populacao :excluded.populacao}})))

(defn buscar-municipio [tx codigo-ibge]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:codigo_ibge :nome :uf :capital :populacao]
                   :from [:cadastros.municipios] :where [:= :codigo_ibge codigo-ibge]}))))

(defn inserir-tribunal! [tx {:keys [codigo nome uf tipo]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.tribunal_de_contas
                 :values [{:codigo codigo :nome nome :uf uf :tipo (or tipo "estadual")}]
                 :on-conflict [:codigo]
                 :do-update-set {:nome :excluded.nome :uf :excluded.uf :tipo :excluded.tipo}})))

(defn inserir-jurisdicao!
  "Uma linha de jurisdicao. municipio-ibge NULL = regra default da UF; preenchido = override do municipio.
  id e' fornecido pelo caller (kernel/ids), como nas demais inserir! do modulo."
  [tx {:keys [id uf municipio-ibge tribunal-codigo]}]
  {:pre [(some? id)]}
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.jurisdicao_camara
                 :values [{:id id :uf uf :municipio_ibge municipio-ibge :tribunal_codigo tribunal-codigo}]})))
