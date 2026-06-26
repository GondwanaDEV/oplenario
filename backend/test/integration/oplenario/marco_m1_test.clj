(ns oplenario.marco-m1-test
  "MARCO M1 — 'a Casa existe' (plano docs/11): roteiro executavel que prova, ponta-a-ponta e CROSS-MODULO
  (cadastros + identidade + relacoes + autenticacao), que duas Casas existem ISOLADAS, com login resolvivel
  e fatos de cadastro respondendo por relacao. E' o criterio-de-feito da F1 materializado como teste.
  (Sem Keycloak vivo: as claims sao injetadas como ja-verificadas — os fluxos vivos sao carry F1.4.)"
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.db.comissao :as comissao]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.cadastros.relacoes.cadastro :as crel]
            [oplenario.config :as config]
            [oplenario.identidade.autenticacao :as auth]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))
(defn- d [s] (LocalDate/parse s))
(def ini (d "2025-01-01"))

(defn- monta-casa!
  "Provisiona uma Casa completa: perfil do ente + legislatura + vereador (com identidade/vinculo/papel)
  + Mesa com o vereador na presidencia. Devolve {:ente :ident :vereador}."
  [municipio-ibge nome-oficial papel-extra]
  (let [ente (random-uuid) ident (random-uuid) ver (random-uuid) leg (random-uuid) mesa (random-uuid)]
    (id/inserir! *ds* {:id ident :cpf (cpf-valido) :nome "Vereadora"})
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge municipio-ibge :nome-oficial nome-oficial})
        (estrutura/inserir-legislatura! tx {:id leg :ente-id ente :numero 19 :ano-inicio 2025 :ano-fim 2028 :vigente true})
        (vereador/inserir! tx {:id ver :ente-id ente :identidade-id ident :nome "Ana Lima" :nome-parlamentar "Ana Lima"})
        (vereador/inserir-mandato! tx {:id (random-uuid) :ente-id ente :vereador-id ver :legislatura-id leg
                                       :partido "PT" :estado "vigente" :vigencia-inicio ini})
        (comissao/inserir! tx {:id mesa :ente-id ente :nome "Mesa Diretora" :tipo "mesa" :legislatura-id leg :vigencia-inicio ini})
        (comissao/inserir-cargo! tx {:id (random-uuid) :ente-id ente :comissao-id mesa :vereador-id ver :cargo "presidente" :vigencia-inicio ini})
        (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id ident :tipo "vereador"})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id ident :papel "vereador"})
        (when papel-extra (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id ident :papel papel-extra}))))
    {:ente ente :ident ident :vereador ver}))

(deftest m1-duas-casas-existem-isoladas-com-login-e-fatos
  ;; reference data (dono): Fortaleza/CE -> TCE-CE; Sao Paulo/SP -> TCE-SP
  (referencia/inserir-municipio! *ds* {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})
  (referencia/inserir-municipio! *ds* {:codigo-ibge "3550308" :nome "Sao Paulo" :uf "SP" :capital true :populacao 12300000})
  (referencia/inserir-tribunal! *ds* {:codigo "TCE-CE" :nome "TCE-CE" :uf "CE" :tipo "estadual"})
  (referencia/inserir-tribunal! *ds* {:codigo "TCE-SP" :nome "TCE-SP" :uf "SP" :tipo "estadual"})
  (jdbc/execute! *ds* ["DELETE FROM cadastros.jurisdicao_camara WHERE uf IN ('CE','SP')"])
  (referencia/inserir-jurisdicao! *ds* {:id (random-uuid) :uf "CE" :tribunal-codigo "TCE-CE"})
  (referencia/inserir-jurisdicao! *ds* {:id (random-uuid) :uf "SP" :tribunal-codigo "TCE-SP"})

  (let [a (monta-casa! "2304400" "Camara Municipal de Fortaleza" "presidente_mesa")
        b (monta-casa! "3550308" "Camara Municipal de Sao Paulo" nil)]

    ;; (1) AS DUAS CASAS EXISTEM — perfil cadastral distinto, isolado por tenant
    (is (= "Camara Municipal de Fortaleza"
           (tenancy/com-tenant* *ds* (:ente a) (fn [tx] (:nome-oficial (estrutura/buscar-ente tx))))) "Casa A existe")
    (is (= "Camara Municipal de Sao Paulo"
           (tenancy/com-tenant* *ds* (:ente b) (fn [tx] (:nome-oficial (estrutura/buscar-ente tx))))) "Casa B existe")

    ;; (2) LOGIN RESOLVIVEL — resolver-sessao monta o ator do vereador em cada Casa
    (let [ator-a (auth/resolver-sessao *ds* {:identidade-id (:ident a) :ente-id (:ente a)})]
      (is (= "vereador" (:tipo-vinculo ator-a)) "login do vereador na Casa A")
      (is (= #{"vereador" "presidente_mesa"} (:papeis ator-a)) "snapshot de papeis da Casa A"))

    ;; (3) FATOS DE CADASTRO POR RELACAO — temporais + tribunal competente, isolados
    (tenancy/com-tenant* *ds* (:ente a)
      (fn [tx]
        (is (true? (crel/tem-mandato-vigente? tx (:ident a) ini)) "A: mandato vigente")
        (is (true? (crel/presidente-da-mesa? tx (:ident a) ini)) "A: presidente da Mesa")
        (is (= 1 (crel/membros-da-casa tx ini)) "A: 1 membro")
        (is (= 2703391 (crel/populacao tx)) "A: populacao de Fortaleza")
        (is (= "TCE-CE" (crel/tribunal-competente tx)) "A: tribunal TCE-CE")))
    (tenancy/com-tenant* *ds* (:ente b)
      (fn [tx]
        (is (= "TCE-SP" (crel/tribunal-competente tx)) "B: tribunal TCE-SP (jurisdicao distinta)")
        (is (= 12300000 (crel/populacao tx)) "B: populacao de Sao Paulo")))

    ;; (4) ISOLAMENTO CROSS-TENANT — a identidade/vereador de A NAO aparece na Casa B
    (is (nil? (tenancy/com-tenant* *ds* (:ente b) (fn [tx] (vereador/buscar tx (:vereador a)))))
        "vereador de A invisivel na Casa B (RLS)")
    (is (false? (tenancy/com-tenant* *ds* (:ente b) (fn [tx] (crel/tem-mandato-vigente? tx (:ident a) ini))))
        "mandato de A nao conta na Casa B")
    (is (nil? (auth/resolver-sessao *ds* {:identidade-id (:ident a) :ente-id (:ente b)}))
        "a identidade de A nao tem sessao na Casa B (sem vinculo)")

    ;; (5) MULTI-VINCULO (disc.1) — a MESMA identidade de A vira cidada na Casa B, sem vazar poderes
    (tenancy/com-tenant* *ds* (:ente b)
      (fn [tx] (vinc/criar! tx {:id (random-uuid) :ente-id (:ente b) :identidade-id (:ident a) :tipo "cidadao"})))
    (let [ator-cidada (auth/resolver-sessao *ds* {:identidade-id (:ident a) :ente-id (:ente b)})]
      (is (= "cidadao" (:tipo-vinculo ator-cidada)) "mesma identidade = cidada na Casa B")
      (is (= #{} (:papeis ator-cidada)) "NAO traz os papeis de vereadora de A (permissoes nao vazam entre perfis)"))))
