(ns oplenario.transparencia.lista-vereadores-http-test
  "INTEGRACAO (PG real + borda Pedestal) — a LISTA PUBLICA dos vereadores em exercicio do portal:
  GET /portal/casa/:ente/vereadores. Antes dela o perfil publico (`.../vereadores/:vereador_id`) so' abria por UUID.

  Rota SEM auth e escopada por Casa: o que se prova aqui e' (a) QUEM entra — so' o mandato VIGENTE na data
  (o licenciado, o que nunca tomou posse e o ex-vereador ficam fora), (b) o ISOLAMENTO — nenhum vereador de outra
  Casa, (c) o SIGILO — nem a identidade interna nem o estado do mandato saem, (d) os 404/400 das irmas.

  Harness: `rotas/montar` de verdade com o seam REAL `rotas/vereadores-em-exercicio` sobre o Repo de `cadastros`
  em Postgres (a semeadura e' pelo produtor: `criar-vereador!`/`criar-mandato!`/`registrar-licenca!`), e `info-ente`
  FAKE (a existencia da Casa e' decisao de outro seam, ja' testado). O `hoje` do seam e' fixo para o teste ser
  determinista."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas])
  (:import (java.time LocalDate)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

(def ^:private hoje (LocalDate/of 2026 10 4))

(defn- casa! [ente]
  (let [leg (random-uuid)]
    (repo/criar-legislatura! *repo* ente {:id leg :ente-id ente :numero 1 :ano-inicio 2025 :ano-fim 2028 :vigente true})
    leg))

(defn- vereador! [ente nome nome-parlamentar]
  (let [id (random-uuid)]
    ;; `identidade-id` preenchido DE PROPOSITO: e' o dado que a rota anonima nunca pode devolver.
    (repo/criar-vereador! *repo* ente {:id id :ente-id ente :nome nome :nome-parlamentar nome-parlamentar
                                       :identidade-id (random-uuid)})
    id))

(defn- mandato! [ente leg vereador-id inicio fim partido]
  (repo/criar-mandato! *repo* ente {:id (random-uuid) :ente-id ente :vereador-id vereador-id :legislatura-id leg
                                    :estado "vigente" :partido partido :vigencia-inicio inicio :vigencia-fim fim}))

(defn- service-fn [casas-que-existem]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade nil
                                   :info-ente (fn [ente-id] (when (casas-que-existem ente-id)
                                                              {:ente-id ente-id :nome-oficial "Câmara Municipal de Teste"}))
                                   :ficha-e-janelas-publicas (constantly nil)
                                   :vereadores-em-exercicio (fn [ente-id] (rotas/vereadores-em-exercicio *repo* ente-id hoje))})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- GET [svc ente] (pt/response-for svc :get (str "/portal/casa/" ente "/vereadores")))
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest so-o-mandato-vigente-aparece-e-so-o-que-e-publico-sai
  (let [ente (random-uuid) leg (casa! ente)
        ana (vereador! ente "Ana Maria Prado" "Ana Prado")
        bruno (vereador! ente "Bruno Lima" nil)
        licenciado (vereador! ente "Dario Nogueira" "Dario")
        _nunca-empossado (vereador! ente "Elza Matos" "Elza")
        ex (vereador! ente "Fabio Reis" "Fabio")]
    (mandato! ente leg ana (LocalDate/of 2025 1 1) nil "PSB")
    (mandato! ente leg bruno (LocalDate/of 2025 1 1) nil "PT")
    (mandato! ente leg licenciado (LocalDate/of 2025 1 1) nil "PL")
    (mandato! ente leg ex (LocalDate/of 2021 1 1) (LocalDate/of 2024 12 31) "MDB")
    ;; a licenca e' gravada pelo PRODUTOR real, que decide a string do estado — nunca um 'licenciado' redigitado.
    (repo/registrar-licenca! *repo* ente licenciado
                             {:id (random-uuid) :ente-id ente :inicio (LocalDate/of 2026 9 1) :fim nil :motivo "Saúde"}
                             (LocalDate/of 2026 9 1))
    (let [r (GET (service-fn #{ente}) ente)
          corpo (ler-json r)
          lista (:vereadores corpo)]
      (is (= 200 (:status r)))
      (is (= #{(str ana) (str bruno)} (set (map :vereador-id lista)))
          "so' quem tem mandato vigente HOJE: licenciado, nunca empossado e ex-vereador ficam fora")
      (is (= #{:vereadores} (set (keys corpo))))
      (is (every? #(= #{:vereador-id :nome-parlamentar :nome-civil :partido :cargo-mesa} (set (keys %))) lista)
          "so' as cinco chaves publicas: nem identidade-id, nem ente-id, nem o estado do mandato")
      (let [a (first (filter #(= (str ana) (:vereador-id %)) lista))
            b (first (filter #(= (str bruno) (:vereador-id %)) lista))]
        (is (= {:nome-parlamentar "Ana Prado" :nome-civil "Ana Maria Prado" :partido "PSB" :cargo-mesa nil}
               (dissoc a :vereador-id)))
        (is (nil? (:nome-parlamentar b)) "sem apelido sai nulo: o nome civil e' fallback de apresentacao")
        (is (= "PT" (:partido b)))))))

(deftest nunca-mostra-vereador-de-outra-casa
  (let [a (random-uuid) b (random-uuid)
        leg-a (casa! a) leg-b (casa! b)
        va (vereador! a "Vereadora da A" "Da A")
        vb (vereador! b "Vereador da B" "Da B")]
    (mandato! a leg-a va (LocalDate/of 2025 1 1) nil "PSB")
    (mandato! b leg-b vb (LocalDate/of 2025 1 1) nil "PT")
    (let [svc (service-fn #{a b})]
      (is (= [(str va)] (mapv :vereador-id (:vereadores (ler-json (GET svc a))))))
      (is (= [(str vb)] (mapv :vereador-id (:vereadores (ler-json (GET svc b)))))))))

(deftest casa-sem-ninguem-em-exercicio-e-200-com-lista-vazia
  (let [ente (random-uuid)]
    (casa! ente)
    (let [r (GET (service-fn #{ente}) ente)]
      (is (= 200 (:status r)) "Casa que existe e ainda nao tem vereador cadastrado: lista vazia, nao erro")
      (is (= {:vereadores []} (ler-json r))))))

(deftest casa-inexistente-e-404-id-malformado-e-400-e-nao-exige-auth
  (testing "404: nunca 200 com lista vazia de uma Casa que nao existe (como a rota-pai e as irmas)"
    (is (= 404 (:status (GET (service-fn #{}) (random-uuid))))))
  (testing "400: o :ente que nao coage a UUID, fail-closed como as demais rotas publicas"
    (is (= 400 (:status (pt/response-for (service-fn #{}) :get "/portal/casa/nao-e-um-uuid/vereadores")))))
  (testing "rota publica: sem header Authorization, nunca 401"
    (let [ente (random-uuid)]
      (is (not= 401 (:status (GET (service-fn #{ente}) ente)))))))

(deftest o-perfil-por-uuid-continua-no-mesmo-nivel-sem-colisao-de-rota
  ;; `/vereadores` (lista) e `/vereadores/:vereador_id` (perfil) coexistem no prefix-tree do Pedestal.
  (let [ente (random-uuid)
        svc (service-fn #{ente})
        r (pt/response-for svc :get (str "/portal/casa/" ente "/vereadores/" (random-uuid)))]
    (is (= 404 (:status r)) "o perfil de um id que nao existe segue 404 (e nao foi engolido pela lista)")))
