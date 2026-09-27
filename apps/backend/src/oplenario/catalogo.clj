(ns oplenario.catalogo
  "Host (§22.10): o CATALOGO DE ACOES inteiro (ADR-0009) — as entradas que cada modulo declara no seu
  `diplomat/catalogo.clj`, agregadas e validadas na carga, e os CONJUNTOS POR PUBLICO. O servidor MCP (B.3) e o agente
  interno so' chegam ao core por aqui; a tela continua pela rota HTTP de mesma acao (`:rotas` de cada entrada).

  Conjunto por publico = o que um agente daquele publico PODE oferecer; expoe MENOS do que a permissao da pessoa
  (Eixo 2), e a ferramenta ainda exige o papel dela a cada chamada (interseccao, Eixo 3.2)."
  (:require [oplenario.kernel.catalogo :as catalogo]
            [oplenario.legislativo.diplomat.catalogo :as legislativo]
            [oplenario.sessoes.diplomat.catalogo :as sessoes]))

(def entradas
  "Todas as entradas, na ordem dos modulos."
  (into [] cat [legislativo/entradas sessoes/entradas]))

(def por-nome (catalogo/validar-catalogo! entradas))

(def conjuntos
  "Publico -> nomes das ferramentas que um agente daquele publico oferece."
  {:secretaria #{"situacao_da_materia" "tramitacao_da_materia" "pauta_da_sessao"}
   :vereador   #{"situacao_da_materia" "tramitacao_da_materia" "pauta_da_sessao"}})

(defn ferramentas
  "As ferramentas que o agente do `publico` pode oferecer a este `ator`: o conjunto do publico ∩ o que o papel do ator
  alcanca. Descritas como o agente as ve (JSON Schema)."
  [publico ator]
  (->> (get conjuntos publico #{})
       sort
       (map por-nome)
       (filter #(some (set (:papeis ator)) (:papeis %)))
       (mapv catalogo/descrever)))

(defn executar!
  "Executa a ferramenta `nome` para o `ator` com os `dados` (JSON decodificado, chaves keyword). Ferramenta fora do
  conjunto do `publico` nao existe para ele (`:validacao/ferramenta-desconhecida`). nil = nao encontrado."
  [deps publico ator nome dados]
  (let [e (get por-nome nome)]
    (when-not (and e (contains? (get conjuntos publico #{}) nome))
      (throw (ex-info (str "ferramenta desconhecida: " nome) {:tipo :validacao/ferramenta-desconhecida :nome nome})))
    (catalogo/executar e deps ator dados)))
