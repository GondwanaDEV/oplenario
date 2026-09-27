(ns oplenario.normas.normas-indice-test
  "INTEGRACAO (PG real): B.4b / ADR-0011 — a versao publicada de uma norma vai ao indice da IA. Publicar grava
  `norma.versao-vigente` no outbox NA MESMA tx; a fronteira promove a `NormaVigente` v1; o satelite le os dispositivos
  pela rota de servico, que so' entrega a versao vigente e so' da Casa do caminho."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.integracao-ia.diplomat.http.in :as integracao-ia-http]
            [oplenario.integracao-ia.logic :as logic-ia]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.migracao :as migracao]
            [oplenario.normas.components.repositorio :as repo-normas]
            [oplenario.normas.logic :as logic]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo [] (repo-normas/map->RepoNormasPg {:datasource {:ds *ds*} :bus (outbox/bus)}))

(defn- importar! [ente texto]
  (let [{:keys [dispositivos alertas]} (logic/dispositivos texto)]
    (repo-normas/importar-versao! (repo) ente {:camada "casa" :especie "regimento_interno" :titulo "Regimento Interno"}
                                  {:fonte "enviado pela Casa" :texto texto :texto-sha256 (logic/sha256-hex texto)
                                   :alertas alertas}
                                  dispositivos)))

(defn- eventos-do-ente [ente]
  (jdbc/execute! *ds* ["SELECT tipo, payload::text AS payload FROM shared.outbox WHERE ente_id = ? ORDER BY id" ente]))

(def ^:private segredo "segredo-de-teste-da-fronteira-ia-b4b")

(defn- servico []
  (-> (http/servico (config/carregar)
                    (integracao-ia-http/rotas {:segredo segredo
                                               :dispositivos-vigentes (fn [e v] (repo-normas/dispositivos-vigentes (repo) e v))})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- ler [svc ente vid]
  (let [r (pt/response-for svc :get (str logic-ia/prefixo "/entes/" ente "/normas/versoes/" vid "/dispositivos")
                           :headers {"Authorization" (str "Bearer " segredo)})]
    {:status (:status r) :corpo (when (= 200 (:status r)) (json/read-value (:body r) json/keyword-keys-object-mapper))}))

(deftest publicar-emite-e-a-ia-le-so-a-vigente
  (let [ente (random-uuid)
        v1 (importar! ente "Art. 1º A Câmara funciona na sede.\nArt. 2º As sessões são públicas.")
        svc (servico)]
    (testing "em conferencia: nada vai a IA"
      (is (empty? (filter #(= "norma.versao-vigente" (:outbox/tipo %)) (eventos-do-ente ente))))
      (is (= 404 (:status (ler svc ente v1)))))
    (repo-normas/decidir-versao! (repo) ente v1 "publicar" (random-uuid))
    (testing "publicar grava o evento na mesma tx, e a fronteira o promove"
      (let [[ev] (filter #(= "norma.versao-vigente" (:outbox/tipo %)) (eventos-do-ente ente))
            payload (json/read-value (:payload ev) json/keyword-keys-object-mapper)
            integ (logic-ia/promover "norma.versao-vigente" ente payload)]
        (is (= (str v1) (:versao-id payload)))
        (is (= "NormaVigente" (:tipo integ)))
        (is (= (str "NormaVigente:v1:" v1) (:chave integ)))
        (is (= {:norma-id (:norma-id payload) :versao-id (str v1) :especie "regimento_interno"} (:payload integ)))))
    (testing "a IA le os dispositivos da vigente, com endereco e rotulo"
      (let [{:keys [status corpo]} (ler svc ente v1)]
        (is (= 200 status))
        (is (= ["art1" "art2"] (mapv :endereco (:dispositivos corpo))))
        (is (= "art. 2º" (:rotulo (second (:dispositivos corpo)))))
        (is (= "Regimento Interno" (:titulo corpo)))))
    (testing "outra Casa pelo caminho nao le"
      (is (= 404 (:status (ler svc (random-uuid) v1)))))
    (testing "versao substituida sai de cena"
      (let [v2 (importar! ente "Art. 1º Nova redação.")]
        (repo-normas/decidir-versao! (repo) ente v2 "publicar" (random-uuid))
        (is (= 404 (:status (ler svc ente v1))))
        (is (= 200 (:status (ler svc ente v2))))))
    (testing "descartar nao emite"
      (let [v3 (importar! ente "Art. 1º Rascunho.")
            antes (count (eventos-do-ente ente))]
        (repo-normas/decidir-versao! (repo) ente v3 "descartar" (random-uuid))
        (is (= antes (count (eventos-do-ente ente))))))))
