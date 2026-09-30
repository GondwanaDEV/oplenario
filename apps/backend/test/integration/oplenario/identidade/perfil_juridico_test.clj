(ns oplenario.identidade.perfil-juridico-test
  "INTEGRACAO (PG real): o perfil do papel `juridico` (ADR-0019, mig `identidade-perfil-juridico`). Prova o UPSERT
  (reconceder atualiza, sem duplicar), a RLS entre Casas, o nome pela leitura estreita (sem CPF), os CHECKs do
  banco e a gravacao do perfil NA MESMA tx dos papeis em `conceder-acesso!`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.identidade.components.repositorio :as repo]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-identidade []
  (assoc (repo/repositorio) :datasource {:ds *ds*}))

(defn- dv [ds]
  (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)]
    (if (< r 2) 0 (- 11 r))))

(defn- cpf-valido []
  (let [base (vec (repeatedly 9 #(rand-int 10)))
        d1 (dv base)
        d2 (dv (conj base d1))]
    (apply str (concat base [d1 d2]))))

(defn- nova-identidade! [r nome]
  (repo/criar-identidade! r {:id (random-uuid) :cpf (cpf-valido) :nome nome}))

(deftest salvar-e-buscar-o-perfil-com-o-nome
  (let [r (repo-identidade)
        ente (random-uuid)
        iid (nova-identidade! r "Dra. Helena Matos")]
    (is (nil? (repo/perfil-juridico r ente iid)) "sem perfil: nil")
    (repo/salvar-perfil-juridico! r ente iid {:qualificacao "efetivo" :oab "CE 12345"})
    (let [p (repo/perfil-juridico r ente iid)]
      (is (= {:nome "Dra. Helena Matos" :qualificacao "efetivo" :oab "CE 12345"} p)
          "nome, qualificacao e OAB; e NADA mais (nunca CPF)")
      (is (not (contains? p :cpf))))))

(deftest reconceder-atualiza-em-vez-de-duplicar
  (let [r (repo-identidade)
        ente (random-uuid)
        iid (nova-identidade! r "Dr. Paulo Rocha")]
    (repo/salvar-perfil-juridico! r ente iid {:qualificacao "contratado" :oab "CE 111"})
    (repo/salvar-perfil-juridico! r ente iid {:qualificacao "comissionado" :oab "CE 222"})
    (is (= {:nome "Dr. Paulo Rocha" :qualificacao "comissionado" :oab "CE 222"}
           (repo/perfil-juridico r ente iid))
        "o upsert atualizou")
    (is (= 1 (:n (tenancy/com-tenant* *ds* ente
                                      (fn [tx]
                                        (jdbc/execute-one! tx ["SELECT count(*)::int AS n FROM identidade.perfil_juridico"])))))
        "uma linha so' (PK ente+identidade)")
    (is (= true (tenancy/com-tenant* *ds* ente
                                     (fn [tx]
                                       (:ok (jdbc/execute-one!
                                             tx ["SELECT (atualizado_em >= criado_em) AS ok FROM identidade.perfil_juridico"])))))
        "atualizado_em nao anda para tras")))

(deftest o-perfil-e-por-casa-e-a-rls-isola
  (let [r (repo-identidade)
        casa-a (random-uuid)
        casa-b (random-uuid)
        iid (nova-identidade! r "Dra. Marina Freire")]
    (repo/salvar-perfil-juridico! r casa-a iid {:qualificacao "efetivo" :oab "CE 12345"})
    (is (nil? (repo/perfil-juridico r casa-b iid))
        "a Casa B nao ve o perfil da mesma pessoa na Casa A")
    (repo/salvar-perfil-juridico! r casa-b iid {:qualificacao "contratado" :oab "SP 999"})
    (is (= "efetivo" (:qualificacao (repo/perfil-juridico r casa-a iid))) "cada Casa guarda o seu")
    (is (= "contratado" (:qualificacao (repo/perfil-juridico r casa-b iid))))
    (is (= 1 (:n (tenancy/com-tenant* *ds* casa-a
                                      (fn [tx]
                                        (jdbc/execute-one! tx ["SELECT count(*)::int AS n FROM identidade.perfil_juridico"])))))
        "a leitura crua, sem WHERE, da Casa A so' enxerga a linha dela (RLS)")
    (is (thrown? java.sql.SQLException
                 (tenancy/com-tenant* *ds* casa-a
                                      (fn [tx]
                                        (jdbc/execute-one!
                                         tx ["INSERT INTO identidade.perfil_juridico (ente_id, identidade_id, qualificacao, oab)
                                              VALUES (?, ?, 'efetivo', 'CE 1')" casa-b iid]))))
        "o WITH CHECK barra escrever em nome de outra Casa")))

(deftest o-banco-recusa-qualificacao-e-oab-fora-do-vocabulario
  (let [r (repo-identidade)
        ente (random-uuid)
        iid (nova-identidade! r "Dr. Caio Lima")]
    (is (thrown? java.sql.SQLException
                 (repo/salvar-perfil-juridico! r ente iid {:qualificacao "estagiario" :oab "CE 1"}))
        "CHECK da qualificacao")
    (is (thrown? java.sql.SQLException
                 (repo/salvar-perfil-juridico! r ente iid {:qualificacao "efetivo" :oab "   "}))
        "CHECK: OAB em branco")
    (is (thrown? java.sql.SQLException
                 (repo/salvar-perfil-juridico! r ente iid {:qualificacao "efetivo" :oab (apply str (repeat 21 "A"))}))
        "CHECK: OAB acima de 20 caracteres")
    (is (nil? (repo/perfil-juridico r ente iid)) "nada foi gravado")))

(deftest conceder-acesso-grava-papel-e-perfil-na-mesma-tx
  (let [r (repo-identidade)
        ente (random-uuid)
        iid (nova-identidade! r "Dra. Ana Braga")
        v {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "servidor" :estado "ativo"}
        perfil {:qualificacao "comissionado" :oab "CE 4321"}
        {:keys [vinculo-id]} (repo/conceder-acesso! r ente v ["juridico"] perfil)]
    (is (some? vinculo-id))
    (is (= #{"juridico"} (repo/papeis-de r ente iid)) "o papel `juridico` cabe no vocabulario aberto de usuario_papel")
    (is (= {:nome "Dra. Ana Braga" :qualificacao "comissionado" :oab "CE 4321"}
           (repo/perfil-juridico r ente iid)))
    ;; reconceder atualiza o perfil
    (repo/conceder-acesso! r ente (assoc v :id (random-uuid)) ["juridico"] {:qualificacao "efetivo" :oab "CE 4321"})
    (is (= "efetivo" (:qualificacao (repo/perfil-juridico r ente iid))))
    (is (= 1 (count (repo/vinculos-de r ente iid))) "o vinculo segue um so'")))

(deftest conceder-acesso-sem-perfil-continua-como-antes
  (let [r (repo-identidade)
        ente (random-uuid)
        iid (nova-identidade! r "Dr. Rui Dantas")
        v {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "servidor" :estado "ativo"}]
    (repo/conceder-acesso! r ente v ["auditor"])
    (is (= #{"auditor"} (repo/papeis-de r ente iid)))
    (is (nil? (repo/perfil-juridico r ente iid)) "auditor nao ganha perfil juridico")
    (repo/conceder-acesso! r ente (assoc v :id (random-uuid)) ["auditor"] nil)
    (is (nil? (repo/perfil-juridico r ente iid)) "perfil nil na aridade longa = nenhum perfil")))

(deftest perfil-invalido-desfaz-a-concessao-inteira
  (let [r (repo-identidade)
        ente (random-uuid)
        iid (nova-identidade! r "Dr. Leo Alves")
        v {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "servidor" :estado "ativo"}]
    (is (thrown? java.sql.SQLException
                 (repo/conceder-acesso! r ente v ["juridico"] {:qualificacao "estagiario" :oab "CE 1"})))
    (is (= #{} (repo/papeis-de r ente iid)) "a tx deu rollback: nenhum papel sobrou sem perfil")
    (is (= [] (repo/vinculos-de r ente iid)) "nem o vinculo")))
