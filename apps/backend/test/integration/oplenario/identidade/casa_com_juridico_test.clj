(ns oplenario.identidade.casa-com-juridico-test
  "INTEGRACAO (PG real): `casa-tem-papel-ativo?` (ADR-0019 Eixo 5) — a Casa tem juridico ativo quando existe pelo menos um
  vinculo ATIVO de pessoa da Casa com o papel `juridico`. Papel de vinculo suspenso ou encerrado nao conta, o vinculo de
  cidadao nao conta, e a resposta e' por Casa (RLS)."
  (:require [oplenario.suporte-cpf :refer [cpf-valido]]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.identidade.components.repositorio :as repo]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-identidade [] (assoc (repo/repositorio) :datasource {:ds *ds*}))

(defn- pessoa! [r] (repo/criar-identidade! r {:id (random-uuid) :cpf (cpf-valido) :nome "Pessoa"}))

(defn- conceder! [r ente iid tipo papeis]
  (repo/conceder-acesso! r ente {:id (random-uuid) :ente-id ente :identidade-id iid :tipo tipo} papeis))

(deftest sem-ninguem-com-o-papel-a-casa-nao-tem-juridico
  (let [r (repo-identidade) ente (random-uuid)]
    (is (false? (repo/casa-tem-papel-ativo? r ente "juridico")) "Casa vazia")
    (conceder! r ente (pessoa! r) "servidor" ["secretario"])
    (is (false? (repo/casa-tem-papel-ativo? r ente "juridico")) "so' secretaria: nao ha' juridico")))

(deftest vinculo-ativo-com-o-papel-conta
  (let [r (repo-identidade) ente (random-uuid) adv (pessoa! r)]
    (conceder! r ente adv "servidor" ["juridico"])
    (is (true? (repo/casa-tem-papel-ativo? r ente "juridico")))
    (is (false? (repo/casa-tem-papel-ativo? r ente "auditor")) "outro papel, outra resposta")))

(deftest vinculo-suspenso-ou-encerrado-nao-conta
  (let [r (repo-identidade) ente (random-uuid) adv (pessoa! r)]
    (conceder! r ente adv "servidor" ["juridico"])
    (let [vid (:id (first (repo/vinculos-de r ente adv)))]
      (testing "suspenso"
        (repo/mudar-estado-vinculo! r ente vid "suspenso")
        (is (false? (repo/casa-tem-papel-ativo? r ente "juridico"))))
      (testing "reativado volta a contar"
        (repo/mudar-estado-vinculo! r ente vid "ativo")
        (is (true? (repo/casa-tem-papel-ativo? r ente "juridico"))))
      (testing "encerrado"
        (repo/mudar-estado-vinculo! r ente vid "encerrado")
        (is (false? (repo/casa-tem-papel-ativo? r ente "juridico")))))))

(deftest outro-advogado-ativo-mantem-a-casa-com-juridico
  (let [r (repo-identidade) ente (random-uuid) a (pessoa! r) b (pessoa! r)]
    (conceder! r ente a "servidor" ["juridico"])
    (conceder! r ente b "servidor" ["juridico"])
    (repo/mudar-estado-vinculo! r ente (:id (first (repo/vinculos-de r ente a))) "encerrado")
    (is (true? (repo/casa-tem-papel-ativo? r ente "juridico")) "sobrou o outro")))

(deftest o-vinculo-de-cidadao-nao-conta
  (let [r (repo-identidade) ente (random-uuid) iid (pessoa! r)]
    (conceder! r ente iid "cidadao" ["juridico"])
    (is (false? (repo/casa-tem-papel-ativo? r ente "juridico")) "papel num vinculo de cidadao nao e' juridico da Casa")))

(deftest a-resposta-e-por-casa
  (let [r (repo-identidade) casa-a (random-uuid) casa-b (random-uuid)]
    (conceder! r casa-a (pessoa! r) "servidor" ["juridico"])
    (is (true? (repo/casa-tem-papel-ativo? r casa-a "juridico")))
    (is (false? (repo/casa-tem-papel-ativo? r casa-b "juridico")) "o juridico da Casa A nao vale para a Casa B")))
