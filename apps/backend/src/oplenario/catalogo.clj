(ns oplenario.catalogo
  "Host (§22.10): o CATALOGO DE ACOES inteiro (ADR-0009) — as entradas que cada modulo declara no seu
  `diplomat/catalogo.clj`, agregadas e validadas na carga, e os CONJUNTOS POR PUBLICO. O servidor MCP (B.3) e o agente
  interno so' chegam ao core por aqui; a tela continua pela rota HTTP de mesma acao (`:rotas` de cada entrada).

  Conjunto por publico = o que um agente daquele publico PODE oferecer (o publico vem da credencial delegada,
  ADR-0010); expoe MENOS do que a permissao da pessoa (Eixo 2), e a ferramenta ainda exige o papel dela a cada
  chamada (interseccao, Eixo 3.2)."
  (:require [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.catalogo :as catalogo]
            [oplenario.legislativo.diplomat.catalogo :as legislativo]
            [oplenario.normas.diplomat.catalogo :as normas]
            [oplenario.sessoes.diplomat.catalogo :as sessoes]))

(def entradas
  "Todas as entradas, na ordem dos modulos."
  (into [] cat [legislativo/entradas sessoes/entradas normas/entradas]))

(def por-nome (catalogo/validar-catalogo! entradas))

(def conjuntos
  "Publico -> nomes das ferramentas que um agente daquele publico oferece."
  {:secretaria #{"situacao_da_materia" "tramitacao_da_materia" "pauta_da_sessao" "buscar_dispositivos" "ler_dispositivo"}
   :vereador   #{"situacao_da_materia" "tramitacao_da_materia" "pauta_da_sessao" "buscar_dispositivos" "ler_dispositivo"
                ;; B.6: o requerimento do proprio vereador — o agente so' PROPOE; ele assina na tela (ADR-0012)
                "modelos_de_requerimento" "protocolar_requerimento"}
   ;; B.8 (ADR-0013): o agente institucional da Casa (sem pessoa) — le a materia e as normas, e so' deixa RASCUNHO
   :institucional #{"situacao_da_materia" "buscar_dispositivos" "ler_dispositivo" "registrar_nota_tecnica"}})

(defn- publico-do [ator]
  (or (get-in ator [:via :publico])
      (authz/negar! :sem-agente {:motivo "o catalogo so' atende chamada de agente (credencial delegada)"})))

(defn ferramentas
  "As ferramentas que o agente pode oferecer a este `ator` (com `:via`, ADR-0010): o conjunto do publico da
  credencial ∩ o que os papeis da pessoa AGORA alcancam ∩ as classes concedidas a execucao. Descritas como o agente
  as ve (JSON Schema)."
  [ator]
  (let [classes (get-in ator [:via :classes] #{})]
    (->> (get conjuntos (publico-do ator) #{})
         sort
         (map por-nome)
         (filter #(some (set (:papeis ator)) (:papeis %)))
         (filter #(contains? classes (:classe %)))
         (mapv catalogo/descrever))))

(defn executar!
  "Executa a ferramenta `nome` para o `ator` de agente com os `dados` (JSON decodificado, chaves keyword). Ferramenta
  fora do conjunto do publico da credencial nao existe para ele (`:validacao/ferramenta-desconhecida`). nil = nao
  encontrado."
  [deps ator nome dados]
  (let [publico (publico-do ator)
        e (get por-nome nome)]
    (when-not (and e (contains? (get conjuntos publico #{}) nome))
      (throw (ex-info (str "ferramenta desconhecida: " nome) {:tipo :validacao/ferramenta-desconhecida :nome nome})))
    (catalogo/executar e deps ator dados)))

(defn registrador
  "O seam de audit das chamadas de agente que escrevem (ADR-0010, Eixo 3.5), sobre o repositorio da fronteira com a
  IA: pessoa + agente + execucao + ferramenta + classe + desfecho."
  [repo-integracao-ia]
  (fn [ator e desfecho]
    (let [via (:via ator)]
      (repo-ia/registrar-chamada-agente! repo-integracao-ia
                                         {:ente-id (:ente-id ator) :execucao-id (:execucao-id via)
                                          :identidade-id (:identidade-id ator) :agente (:agente via)
                                          :ferramenta (:nome e) :classe (name (:classe e)) :desfecho desfecho}))))
