(ns oplenario.legislativo.proposicao-adapters-out-test
  "UNIT (puro, sem DB) — o gate adapters/out da lista de proposicoes (Onda B Slice 1). Prova a forma
  (so' os campos de ProposicaoResumoOut, uuid/instant como string) e a validacao de contrato."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.adapters.out.proposicao :as adapters]
            [oplenario.legislativo.wire.out.proposicao :as wire]))

(defn- linha-canonica [ente]
  {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :sequencial 42
   :urn-lex "urn:lex:br:camara.municipal.fortaleza:projeto.lei:2026;42"
   :ementa "Cria o Programa Municipal de Hortas Comunitarias"
   :autor-tipo "vereador" :autor-texto "Helena Matos" :estado "em_comissoes"
   :atualizado-em (java.time.Instant/parse "2026-05-21T10:00:00Z")
   :atributos-especificos nil :texto-vigente-versao-id nil})

(deftest projeta-lista-com-total-pagina-tamanho
  (let [ente (random-uuid)
        out (adapters/listar->wire {:itens [(linha-canonica ente)] :total 1284 :pagina 1 :tamanho-pagina 20})]
    (is (m/validate wire/ListaProposicoesOut out) "a projecao satisfaz ListaProposicoesOut")
    (is (= 1284 (:total out))) (is (= 1 (:pagina out))) (is (= 20 (:tamanho-pagina out)))
    (let [item (first (:itens out))]
      (is (string? (:id item)) "id vira string")
      (is (= "projeto_lei" (:tipo item)))
      (is (= "em_comissoes" (:estado item)))
      (is (string? (:atualizado-em item)) "instant vira string")
      (is (not (contains? item :ente-id)) "ente-id (tenant) nao vaza")
      (is (not (contains? item :atributos-especificos)) "atributos_especificos nao vaza")
      (is (not (contains? item :texto-vigente-versao-id)) "texto-vigente-versao-id nao vaza"))))

(deftest lista-vazia-projeta-itens-vazio
  (let [out (adapters/listar->wire {:itens [] :total 0 :pagina 1 :tamanho-pagina 20})]
    (is (m/validate wire/ListaProposicoesOut out))
    (is (= [] (:itens out)))))

(deftest autor-nulo-fica-nil-no-wire
  (let [ente (random-uuid)
        linha (assoc (linha-canonica ente) :autor-tipo nil :autor-texto nil)
        out (adapters/listar->wire {:itens [linha] :total 1 :pagina 1 :tamanho-pagina 20})]
    (is (m/validate wire/ListaProposicoesOut out))
    (is (nil? (:autor-tipo (first (:itens out)))))))

(deftest detalhe->wire-projeta-e-inclui-texto
  (let [linha {:id (random-uuid) :tipo "projeto_lei" :ano 2026 :sequencial 1 :urn-lex "urn:x"
               :ementa "X" :estado "protocolada" :lock-version 0
               :atualizado-em (java.time.Instant/parse "2026-01-01T00:00:00Z")}
        out (adapters/detalhe->wire linha "## Art. 1o")]
    (is (= "## Art. 1o" (:texto out)))
    (is (string? (:id out)))))

(deftest detalhe->wire-texto-nil-quando-sem-versao-vigente
  (let [linha {:id (random-uuid) :tipo "projeto_lei" :ano 2026 :sequencial 1 :urn-lex "urn:x"
               :ementa "X" :estado "protocolada" :lock-version 0
               :atualizado-em (java.time.Instant/parse "2026-01-01T00:00:00Z")}]
    (is (nil? (:texto (adapters/detalhe->wire linha nil))))))
