(ns oplenario.identidade.autenticacao-test
  "INTEGRACAO (PG real): o seam de auth-base (F1.4). De claims VERIFICADAS -> ator (resolver-sessao) ->
  camada de autorizacao do kernel (two-layer: grossa esfera/papel + fina policy.check). Prova o caminho
  ponta-a-ponta sem Keycloak vivo (os fluxos vivos passkey/gov.br/realm sao carry, infra-gated)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.identidade.autenticacao :as auth]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.identidade.relacoes.identidade :as rel]
            [oplenario.kernel.autorizacao :as az]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

;; RepoIdentidade construido sobre o *ds* do teste (resolver-sessao agora recebe o Repo, nao o ds — §3-bis).
(defn- repo [] (assoc (repo-id/repositorio) :datasource {:ds *ds*}))

(defn- seed-vereador! [ente iid papeis]
  (id/inserir! *ds* {:id iid :cpf (cpf-valido) :nome "Vereadora"})
  (tenancy/com-tenant* *ds* ente
    (fn [tx]
      (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "vereador"})
      (doseq [p papeis] (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id iid :papel p})))))

(deftest resolver-sessao-monta-o-ator
  (let [ente (random-uuid) iid (random-uuid)]
    (seed-vereador! ente iid ["vereador" "presidente_mesa"])
    (let [ator (auth/resolver-sessao (repo) {:identidade-id iid :ente-id ente})]
      (is (= iid (:identidade-id ator)) "ator carrega a identidade")
      (is (= ente (:ente-id ator)) "ator carrega o ente do escopo ativo")
      (is (= "vereador" (:tipo-vinculo ator)) "ator carrega o tipo do vinculo ativo")
      (is (= #{"vereador" "presidente_mesa"} (:papeis ator)) "ator carrega o snapshot de papeis"))))

(deftest vinculo-suspenso-nao-da-sessao
  (let [ente (random-uuid) iid (random-uuid)]
    (id/inserir! *ds* {:id iid :cpf (cpf-valido) :nome "Suspenso"})
    (let [vid (random-uuid)]
      (tenancy/com-tenant* *ds* ente
        (fn [tx]
          (vinc/criar! tx {:id vid :ente-id ente :identidade-id iid :tipo "servidor"})
          (vinc/mudar-estado! tx vid "suspenso")))
      (is (nil? (auth/resolver-sessao (repo) {:identidade-id iid :ente-id ente}))
          "vinculo suspenso -> sem sessao (fail-closed, §22.5 eixo G)"))))

(deftest claims-de-outro-ente-nao-da-sessao
  (let [a (random-uuid) b (random-uuid) iid (random-uuid)]
    (seed-vereador! a iid ["vereador"])
    (is (some? (auth/resolver-sessao (repo) {:identidade-id iid :ente-id a})) "sessao no ente do vinculo")
    (is (nil? (auth/resolver-sessao (repo) {:identidade-id iid :ente-id b}))
        "claims apontando p/ ente B (sem vinculo) -> sem sessao (RLS)")))

(deftest ator-alimenta-a-camada-de-autorizacao-two-layer
  (let [ente (random-uuid) iid (random-uuid) outra (random-uuid)]
    (seed-vereador! ente iid ["vereador"])
    (let [ator (auth/resolver-sessao (repo) {:identidade-id iid :ente-id ente})]
      ;; camada GROSSA: esfera tenant ok; papel presente ok; papel ausente nega
      (is (= ator (az/checar-esfera! ator :tenant)) "esfera tenant ok (ator tem ente-id)")
      (is (= ator (az/exige-papel! ator "vereador")) "papel estatico do snapshot autoriza a categoria")
      (is (thrown? Exception (az/exige-papel! ator "admin_ente")) "papel ausente do snapshot -> negado")
      ;; camada FINA: policy.check usando a relacao é_o_próprio como politica de self-action
      (is (true? (az/check! ator :editar-proprio {:id iid}
                            (fn [a r] (rel/e-o-proprio? (:identidade-id a) (:id r)))))
          "policy fina permite editar o PROPRIO recurso (é_o_próprio)")
      (is (thrown? Exception
                   (az/check! ator :editar-proprio {:id outra}
                              (fn [a r] (rel/e-o-proprio? (:identidade-id a) (:id r)))))
          "policy fina NEGA editar recurso de outra identidade"))))
