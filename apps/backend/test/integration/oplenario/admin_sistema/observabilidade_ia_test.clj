(ns oplenario.admin-sistema.observabilidade-ia-test
  "INTEGRACAO (PG real para a sessao do operador; satelite FAKE pelo seam do host): a OBSERVABILIDADE DA IA no console
  (Onda E, `observabilidade-ia`). So' o operador le (sem credencial -> 401); a janela e' 24 h ou 7 dias (outra -> 400);
  a borda projeta o que o satelite devolve pela allowlist (o que vier a mais nao atravessa) e, com a IA fora, responde
  200 com `disponivel: false` — nunca 500 (R-IA-1)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.admin-sistema.components.idp-admin :as idp-admin]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.admin-sistema.diplomat.http.in :as admin-sistema-http]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-op [] (assoc (repo/repositorio) :datasource {:ds *ds*}))

(def satelite
  "O que o satelite devolve (json -> chaves keyword), com um campo a mais que nao pode atravessar."
  {:desde "2026-09-28T13:00:00Z" :ate "2026-09-29T13:25:40Z" :horas 25 :casas 3 :moeda "USD"
   :total {:execucoes 8 :indisponiveis 2 :latencia-p50-ms 300 :latencia-p95-ms 5000 :custo "6" :parcial true}
   :por-operacao [{:operacao "ata.redigir" :execucoes 5 :indisponiveis 0 :latencia-p50-ms 300 :latencia-p95-ms 5000
                   :custo "5" :parcial false}]
   :por-fornecedor [{:vendor "fake" :modelo nil :execucoes 2 :indisponiveis 2 :latencia-p50-ms 50
                     :latencia-p95-ms 50 :custo "0" :parcial false}]
   :motivos-indisponivel [{:motivo "cota" :execucoes 1}]
   :por-hora [{:inicio "2026-09-28T13:00:00Z" :execucoes 5 :indisponiveis 0}]
   :ente-id "nunca-sai"})

(defn- servico [observabilidade-ia]
  (-> (http/servico (config/carregar)
                    (admin-sistema-http/rotas {:idp-operacao (idp-admin/idp-operacao-dev) :repo-admin-sistema (repo-op)
                                               :relogio (tempo/relogio-sistema)
                                               :operacao {:realm "operacao" :client-id "oplenario-console"
                                                          :sessao {:absoluta-h 8 :ociosa-min 15}}
                                               :deps-registro {} :observabilidade-ia observabilidade-ia})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- operador! []
  (repo/criar-operador! (repo-op) {:id (random-uuid) :email (str "op-" (random-uuid) "@oplenario.dev") :nome "Rafaela Operação"}))

(defn- como [o] {"authorization" (str "Bearer " (json/write-value-as-string {:operador-id (str (:id o))}))})

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest o-operador-le-a-saude-da-ia-de-todas-as-casas
  (let [pedidos (atom [])
        svc (servico (fn [h] (swap! pedidos conj h) satelite))
        o (operador!)]
    (is (= 401 (:status (pt/response-for svc :get "/operacao/ia"))) "sem sessao do console: 401")
    (let [r (pt/response-for svc :get "/operacao/ia" :headers (como o)) b (ler r)]
      (is (= 200 (:status r)))
      (is (true? (:disponivel b)))
      (is (= [3 "USD" 5000 "6"] [(:casas b) (:moeda b) (get-in b [:total :latencia-p95-ms]) (get-in b [:total :custo])]))
      (is (= ["ata.redigir"] (mapv :operacao (:por-operacao b))))
      (is (= [["fake" nil]] (mapv (juxt :vendor :modelo) (:por-fornecedor b))))
      (is (= [{:motivo "cota" :execucoes 1}] (:motivos-indisponivel b)))
      (is (not (contains? b :ente-id)) "allowlist: o campo a mais do satelite nao atravessa"))
    (is (= 200 (:status (pt/response-for svc :get "/operacao/ia?horas=168" :headers (como o)))))
    (is (= [24 168] @pedidos) "sem ?horas a janela e' 24 h")
    (testing "janela fora das oferecidas: 400, e o satelite nem e' chamado"
      (is (= [400 400 400] (mapv #(:status (pt/response-for svc :get (str "/operacao/ia?horas=" %) :headers (como o)))
                                 ["5" "0" "abc"])))
      (is (= 2 (count @pedidos))))))

(deftest ia-fora-nao-e-500
  (let [o (operador!)]
    (doseq [[caso seam] [["satelite indisponivel" (fn [_] (throw (ex-info "fora" {:tipo :ia/indisponivel})))]
                         ["seam nao ligado" nil]]]
      (testing caso
        (let [r (pt/response-for (servico seam) :get "/operacao/ia?horas=168" :headers (como o)) b (ler r)]
          (is (= 200 (:status r)))
          (is (= [false 168 nil []] [(:disponivel b) (:horas b) (:total b) (:por-hora b)])))))))
