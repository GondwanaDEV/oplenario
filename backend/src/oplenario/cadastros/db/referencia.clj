(ns oplenario.cadastros.db.referencia
  "Persistencia das tabelas de REFERENCIA/DOMINIO do cadastros (sem ente_id): municipios (IBGE),
  tribunal_de_contas (E2) e jurisdicao_camara (E1). Lidas dentro da tx do tenant (mesmo schema)."
  (:require [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(defn inserir-municipio! [tx {:keys [codigo-ibge nome uf capital populacao]}]
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.municipios (codigo_ibge, nome, uf, capital, populacao)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (codigo_ibge) DO UPDATE SET nome = EXCLUDED.nome, uf = EXCLUDED.uf,
        capital = EXCLUDED.capital, populacao = EXCLUDED.populacao"
     codigo-ibge nome uf (boolean capital) populacao]))

(defn buscar-municipio [tx codigo-ibge]
  (comum/linha->kebab
    (jdbc/execute-one! tx ["SELECT codigo_ibge, nome, uf, capital, populacao
                            FROM cadastros.municipios WHERE codigo_ibge = ?" codigo-ibge])))

(defn inserir-tribunal! [tx {:keys [codigo nome uf tipo]}]
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.tribunal_de_contas (codigo, nome, uf, tipo) VALUES (?, ?, ?, ?)
      ON CONFLICT (codigo) DO UPDATE SET nome = EXCLUDED.nome, uf = EXCLUDED.uf, tipo = EXCLUDED.tipo"
     codigo nome uf (or tipo "estadual")]))

(defn inserir-jurisdicao!
  "Uma linha de jurisdicao. municipio-ibge NULL = regra default da UF; preenchido = override do municipio.
  id e' fornecido pelo caller (kernel/ids), como nas demais inserir! do modulo."
  [tx {:keys [id uf municipio-ibge tribunal-codigo]}]
  {:pre [(some? id)]}
  (jdbc/execute-one! tx
    ["INSERT INTO cadastros.jurisdicao_camara (id, uf, municipio_ibge, tribunal_codigo) VALUES (?, ?, ?, ?)"
     id uf municipio-ibge tribunal-codigo]))
