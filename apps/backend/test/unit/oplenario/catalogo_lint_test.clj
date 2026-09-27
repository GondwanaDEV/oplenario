(ns oplenario.catalogo-lint-test
  "ADR-0009 — a regra 2 do Eixo 2 (§22.11) enforçada por maquina: o catalogo de acoes e' a FONTE; toda rota HTTP e'
  (a) a mesma acao de uma entrada do catalogo, (b) uma rota da LISTA-BASE congelada de quando o catalogo nasceu, ou
  (c) declarada fora do catalogo, com motivo. Rota nova sem nenhum dos tres quebra o CI (mesmo molde do
  estrutura_lint_test). A lista-base so' diminui."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [oplenario.catalogo :as catalogo]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]))

(defn- ler [recurso] (edn/read-string (slurp (io/resource recurso))))

(defn- nome-da-rota [r]
  (let [v (vec r) i (.indexOf v :route-name)] (when (>= i 0) (nth v (inc i)))))

(def ^:private rotas-montadas
  ;; `:repo-integracao-ia` qualquer: so' liga o fragmento da fronteira com a IA (as rotas existem em producao)
  (delay (set (keep nome-da-rota (rotas/montar {:idp (idp-dev/idp-dev) :repo-integracao-ia :lint})))))

(deftest toda-rota-e-do-catalogo-da-lista-base-ou-tem-motivo
  (let [cobertas (set (mapcat :rotas catalogo/entradas))
        base (set (ler "catalogo/rotas-base.edn"))
        fora (ler "catalogo/fora-do-catalogo.edn")
        orfas (remove #(or (cobertas %) (base %) (contains? fora %)) @rotas-montadas)]
    (is (empty? orfas)
        (str "rota nova sem entrada no catalogo de acoes (ADR-0009): " (vec (sort orfas))
             " — declare a acao no `diplomat/catalogo.clj` do modulo (com `:rotas`), ou registre a rota em"
             " resources/catalogo/fora-do-catalogo.edn com o motivo"))))

(deftest a-lista-base-so-diminui
  (let [base (ler "catalogo/rotas-base.edn")
        cobertas (set (mapcat :rotas catalogo/entradas))]
    (testing "nao ha' rota fantasma: tudo na lista-base ainda existe"
      (is (empty? (remove @rotas-montadas base)) "rota removida: tire-a da lista-base"))
    (testing "rota que ganhou entrada no catalogo sai da lista-base"
      (is (empty? (filter cobertas base))))
    (is (= (count base) (count (set base))) "sem repetidos")))

(deftest entradas-apontam-para-rotas-que-existem
  (doseq [e catalogo/entradas r (:rotas e)]
    (is (contains? @rotas-montadas r) (str (:nome e) " aponta para a rota inexistente " r))))

(deftest fora-do-catalogo-tem-motivo
  (is (map? (ler "catalogo/fora-do-catalogo.edn")))
  (doseq [[rota {:keys [categoria motivo]}] (ler "catalogo/fora-do-catalogo.edn")]
    (is (contains? #{:so-tela :servico :pessoal} categoria) (str rota))
    (is (and (string? motivo) (>= (count motivo) 20)) (str rota " sem motivo"))))

(def ^:private atos-pessoais
  "docs/25 Eixo 4.3 (ADR-0012): voto, presenca e conducao da sessao ao vivo. Esta lista so' cresce."
  #{:legislativo/registrar-voto :legislativo/abrir-votacao :legislativo/encerrar-votacao
    :sessoes/confirmar-minha-presenca :sessoes/registrar-presenca :sessoes/registrar-presenca-lote
    :sessoes/conduzir-chamada :sessoes/transicionar :sessoes/iniciar-fala :sessoes/encerrar-fala
    :sessoes/registrar-evento-cronometro :sessoes/anunciar-item-pauta :sessoes/registrar-decisao-mesa})

(deftest atos-que-o-agente-nem-propoe
  (let [fora (ler "catalogo/fora-do-catalogo.edn")
        pessoais (set (keep (fn [[r {:keys [categoria]}]] (when (= :pessoal categoria) r)) fora))
        cobertas (set (mapcat :rotas catalogo/entradas))]
    (is (empty? (remove pessoais atos-pessoais)) "ato pessoal saiu da lista :pessoal de fora-do-catalogo.edn")
    (is (every? @rotas-montadas pessoais) "rota :pessoal que nao existe")
    (is (empty? (filter pessoais cobertas)) "entrada do catalogo apontando para ato pessoal (Eixo 4.3)")))

(deftest confirmar-proposta-nunca-e-ferramenta
  (let [cobertas (set (mapcat :rotas catalogo/entradas))
        propostas #{:propostas/listar :propostas/ver :propostas/confirmar :propostas/recusar}]
    (is (every? @rotas-montadas propostas))
    (is (empty? (filter propostas cobertas)) "o agente nunca confirma o que propos (Eixo 4.2 B)")))

(deftest todo-ato-do-catalogo-e-proponivel
  (doseq [e catalogo/entradas :when (= :ato (:classe e))]
    (is (and (:ritual e) (:apresentar e)) (str (:nome e) " e' ato sem ritual/apresentar"))))
