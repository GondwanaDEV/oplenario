(ns oplenario.identidade.credencial-agente-interceptor-test
  "INTEGRACAO (PG real): ADR-0010 — a credencial delegada do agente e a sessao das telas sao portas separadas. A
  credencial so' abre o interceptor de agente (as rotas do catalogo); o interceptor das telas nao a reconhece nem
  como cookie nem como bearer. E o interceptor de agente nao aceita a sessao de uma pessoa."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.interceptors :as interceptors]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo [] (assoc (repo-id/repositorio) :datasource {:ds *ds*}))

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- secretaria! [ente]
  (let [iid (random-uuid)]
    (id/inserir! *ds* {:id iid :cpf (cpf-valido) :nome "Secretaria"})
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "servidor"})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id iid :papel "secretario"})))
    (auten/resolver-sessao (repo) {:identidade-id iid :ente-id ente})))

(defn- entrar [interceptor headers]
  (let [ctx ((:enter interceptor) {:request {:headers headers}})]
    {:status (get-in ctx [:response :status]) :ator (get-in ctx [:request :ator])}))

(deftest portas-separadas
  (let [ente (random-uuid)
        ator (secretaria! ente)
        {:keys [credencial]} (auten/emitir-credencial-agente! (repo) ator {:agente "assistente-da-casa"
                                                                           :publico :secretaria
                                                                           :classes #{:leitura}})
        sessao (repo-id/criar-sessao! (repo) {:identidade-id (:identidade-id ator) :ente-id ente
                                              :expira-em (.plusSeconds (Instant/now) 600)
                                              :ocioso-ate (.plusSeconds (Instant/now) 600)})
        telas (interceptors/autenticacao (idp-dev/idp-dev) (repo))
        agente (interceptors/autenticacao-agente (repo))]
    (let [r (entrar agente {"authorization" (str "Bearer " credencial)})]
      (is (nil? (:status r)))
      (is (= "assistente-da-casa" (get-in r [:ator :via :agente])) "a credencial abre a porta do agente"))
    (is (= 401 (:status (entrar telas {"authorization" (str "Bearer " credencial)})))
        "como bearer, a credencial do agente nao abre rota de tela")
    (is (= 401 (:status (entrar telas {"cookie" (str "sessao=" credencial)})))
        "nem como cookie de sessao")
    (is (= 401 (:status (entrar agente {"authorization" (str "Bearer " sessao)})))
        "e a sessao da pessoa nao abre a porta do agente")
    (is (= 401 (:status (entrar agente {"cookie" (str "sessao=" sessao)}))))
    (is (= 401 (:status (entrar agente {}))))))
