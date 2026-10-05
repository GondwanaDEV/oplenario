(ns oplenario.identidade.casas-com-acesso-test
  "INTEGRACAO (PG real), ADR-0024: a entrada pelo CPF pergunta em quais Casas a identidade tem acesso INSTITUCIONAL
  ativo. O vinculo e' TENANT (FORCE RLS); a resposta atravessa as Casas so' pela funcao estreita
  `identidade.casas_com_acesso_institucional`. Tudo e' conferido com o role de RUNTIME (`oplenario_pool`, NOBYPASSRLS,
  nao-dono) — o superuser so' semeia. Prova tambem que a abertura NAO vazou: o pool continua sem ver vinculo fora da
  Casa, e o dominio (`oplenario_app`) nem executa a funcao."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.identidade.components.repositorio :as repo]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.cpf :as kcpf]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *dono* nil)   ; superuser: migra e semeia
(def ^:dynamic *pool* nil)   ; o role de runtime da aplicacao

(use-fixtures :once
  (fn [t]
    (let [cfg (config/carregar)
          dono (component/start (datasource/datasource cfg))]
      (migracao/migrar! (:ds dono))
      (let [pool (component/start (datasource/datasource (update cfg :db assoc :user "oplenario_pool"
                                                                 :password "oplenario_dev_pool")))]
        (binding [*dono* (:ds dono) *pool* (:ds pool)]
          (try (t) (finally (component/stop pool) (component/stop dono))))))))

(defn- cpf-valido []
  (first (filter kcpf/valido? (repeatedly #(apply str (repeatedly 11 (fn [] (rand-int 10))))))))

(defn- repo-sobre [ds] (assoc (repo/repositorio) :datasource {:ds ds}))

(defn- vinculo! [ente identidade tipo estado]
  (let [r (repo-sobre *dono*)
        vid (repo/criar-vinculo! r ente {:id (random-uuid) :ente-id ente :identidade-id identidade :tipo tipo})]
    (when (not= "ativo" estado) (repo/mudar-estado-vinculo! r ente vid estado))
    vid))

(deftest so-as-casas-com-vinculo-institucional-ativo
  (let [pessoa (id/inserir! *dono* {:id (random-uuid) :cpf (cpf-valido) :nome "Maria Servidora"})
        outra (id/inserir! *dono* {:id (random-uuid) :cpf (cpf-valido) :nome "Outra Pessoa"})
        [servidor vereadora-encerrada cidada administradora suspensa da-outra]
        (repeatedly 6 random-uuid)]
    (vinculo! servidor pessoa "servidor" "ativo")
    (vinculo! vereadora-encerrada pessoa "vereador" "encerrado")
    (vinculo! cidada pessoa "cidadao" "ativo")
    (vinculo! administradora pessoa "admin_ente" "ativo")
    (vinculo! suspensa pessoa "servidor" "suspenso")
    (vinculo! da-outra outra "servidor" "ativo")
    (let [casas (set (repo/casas-com-acesso-institucional (repo-sobre *pool*) pessoa))]
      (is (= #{servidor administradora} casas)
          "servidor e admin_ente ativos entram; vinculo encerrado, suspenso e de cidadao ficam fora; a Casa de outra
          identidade nunca aparece"))))

(deftest identidade-sem-vinculo-devolve-vazio
  (is (= [] (repo/casas-com-acesso-institucional (repo-sobre *pool*) (random-uuid)))))

(deftest a-abertura-nao-vaza-o-vinculo
  (let [pessoa (id/inserir! *dono* {:id (random-uuid) :cpf (cpf-valido) :nome "Joao"})
        casa (random-uuid)]
    (vinculo! casa pessoa "vereador" "ativo")
    (testing "o pool de runtime, fora da Casa, continua sem ver linha alguma do vinculo"
      (is (= 0 (:n (jdbc/execute-one! *pool* ["SELECT count(*) AS n FROM identidade.vinculo WHERE identidade_id = ?"
                                              pessoa])))))
    (testing "o dominio (oplenario_app) nao executa a funcao"
      (is (thrown-with-msg? Exception #"permission denied"
                            (jdbc/with-transaction [tx *pool*]
                              (jdbc/execute! tx ["SET LOCAL ROLE oplenario_app"])
                              (jdbc/execute! tx ["SELECT * FROM identidade.casas_com_acesso_institucional(?)" pessoa])))))))

(deftest id-por-cpf-le-so-o-id
  (let [cpf (cpf-valido)
        pessoa (id/inserir! *dono* {:id (random-uuid) :cpf cpf :nome "Ana"})]
    (is (= pessoa (repo/id-por-cpf (repo-sobre *pool*) cpf)))
    (is (nil? (repo/id-por-cpf (repo-sobre *pool*) (cpf-valido))) "CPF sem identidade -> nil")))
