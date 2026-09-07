(ns oplenario.cadastros.repositorio-nomes-comissoes-test
  "INTEGRACAO (PG real) — RepoCadastros/nomes-de-comissoes: `#{comissao-id}` -> `{id nome}`. E' a metade
  de `cadastros` do `resolver-comissoes` que o HOST injeta no `legislativo` (§22.5.3, exceção nomeada,
  mesma forma de `resolver-vereador`). Existe porque `legislativo.pareceres.comissao_id` e' guard ref
  `uuid NOT NULL` SEM FK cross-schema (mig 20260620000019) — o legislativo NUNCA soube o nome, e a tela
  do parecer mostrava o UUID (defeito #11 do ledger de prontidao).

  PLURAL de proposito: a ficha da materia lista N pareceres, e resolver um a um seria N transacoes por
  request."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao])
  (:import (java.time LocalDate)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- semear-comissao! [ente id nome]
  (repo/criar-comissao! *repo* ente {:id id :ente-id ente :nome nome :tipo "permanente"
                                     :legislatura-id nil :vigencia-inicio (LocalDate/of 2025 1 1)
                                     :vigencia-fim nil}))

(deftest resolve-varios-nomes-numa-consulta-so
  (let [ente (random-uuid) ccj (random-uuid) fin (random-uuid)]
    (semear-comissao! ente ccj "Comissão de Constituição e Justiça")
    (semear-comissao! ente fin "Comissão de Finanças e Orçamento")
    (is (= {ccj "Comissão de Constituição e Justiça"
            fin "Comissão de Finanças e Orçamento"}
           (repo/nomes-de-comissoes *repo* ente [ccj fin])))))

(deftest id-desconhecido-simplesmente-nao-aparece-no-mapa
  (let [ente (random-uuid) ccj (random-uuid) fantasma (random-uuid)]
    (semear-comissao! ente ccj "Comissão de Educação")
    (let [nomes (repo/nomes-de-comissoes *repo* ente [ccj fantasma])]
      (is (= {ccj "Comissão de Educação"} nomes))
      (is (nil? (get nomes fantasma))
          "guard ref orfao degrada pra nil — o chamador mostra rotulo honesto, nunca lanca"))))

(deftest coll-vazia-nao-vai-ao-banco
  (let [ente (random-uuid)]
    (is (= {} (repo/nomes-de-comissoes *repo* ente [])) "`IN ()` nao e' SQL valido — curto-circuita")
    (is (= {} (repo/nomes-de-comissoes *repo* ente nil)))
    (is (= {} (repo/nomes-de-comissoes *repo* ente [nil nil])) "so' nils = nada a resolver")))

(deftest ids-repetidos-nao-duplicam-a-linha
  (let [ente (random-uuid) ccj (random-uuid)]
    (semear-comissao! ente ccj "Comissão de Saúde")
    (is (= {ccj "Comissão de Saúde"} (repo/nomes-de-comissoes *repo* ente [ccj ccj ccj])))))

(deftest rls-isola-o-tenant
  (let [ente-a (random-uuid) ente-b (random-uuid) ccj (random-uuid)]
    (semear-comissao! ente-a ccj "Comissão de Obras")
    (is (= {} (repo/nomes-de-comissoes *repo* ente-b [ccj]))
        "o id existe, mas nao NESTA Casa — a comissao de outro ente nunca vaza pro nome")))
