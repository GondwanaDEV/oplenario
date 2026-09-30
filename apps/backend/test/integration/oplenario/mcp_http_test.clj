(ns oplenario.mcp-http-test
  "INTEGRACAO (PG real + borda HTTP): B.3 — o servidor MCP do core servindo o catalogo ao agente. A credencial
  delegada abre a porta; `tools/list` devolve o conjunto do publico em JSON Schema; `tools/call` executa como a
  pessoa; erro de ferramenta volta como resultado, erro de protocolo como JSON-RPC."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.catalogo :as catalogo]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.mcp :as mcp]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-identidade [] (assoc (repo-id/repositorio) :datasource {:ds *ds*}))

(defn- servico []
  (-> (http/servico (config/carregar)
                    (mcp/rotas {:repo-identidade (repo-identidade)
                                :deps {:repo-legislativo (repo-leg/map->RepoLegislativoPg {:datasource {:ds *ds*}})
                                       :repo-sessoes (repo-sessoes/map->RepoSessoesPg {:datasource {:ds *ds*}})
                                       :registrar-chamada (catalogo/registrador
                                                           (repo-ia/map->RepoIntegracaoIAPg {:datasource {:ds *ds*}}))}})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- credencial! [ente papel publico]
  (let [iid (random-uuid)]
    (id/inserir! *ds* {:id iid :cpf (cpf-valido) :nome "Pessoa da Casa"})
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "servidor"})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id iid :papel papel})))
    (:credencial (auten/emitir-credencial-agente!
                  (repo-identidade) (auten/resolver-sessao (repo-identidade) {:identidade-id iid :ente-id ente})
                  {:agente "assistente-da-casa" :publico publico :classes #{:leitura}}))))

(defn- rpc [svc credencial corpo]
  (let [r (pt/response-for svc :post "/integracao/ia/v1/mcp"
                           :headers (cond-> {"Content-Type" "application/json"}
                                      credencial (assoc "Authorization" (str "Bearer " credencial)))
                           :body (json/write-value-as-string corpo))]
    {:status (:status r) :corpo (when (seq (:body r)) (json/read-value (:body r)))}))

(defn- chamada [id metodo params] {"jsonrpc" "2.0" "id" id "method" metodo "params" params})

(deftest conversa-mcp-de-ponta-a-ponta
  (let [ente (random-uuid)
        p (tenancy/com-tenant* *ds* ente
            #(proposicao/protocolar! % {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                        :municipio-nome "Fortaleza" :ementa "Dispoe sobre a merenda escolar."
                                        :autor-texto "Ver. Ana"}))
        svc (servico)
        cred (credencial! ente "secretario" :secretaria)]
    (testing "initialize"
      (let [{:keys [status corpo]} (rpc svc cred (chamada 1 "initialize" {"protocolVersion" "2025-06-18"
                                                                           "capabilities" {}
                                                                           "clientInfo" {"name" "t" "version" "1"}}))]
        (is (= 200 status))
        (is (= "2025-06-18" (get-in corpo ["result" "protocolVersion"])))
        (is (contains? (get-in corpo ["result" "capabilities"]) "tools"))))
    (testing "notificacao nao tem resposta"
      (is (= 202 (:status (rpc svc cred {"jsonrpc" "2.0" "method" "notifications/initialized"})))))
    (testing "tools/list: o conjunto do publico, em JSON Schema de objeto"
      (let [ferramentas (get-in (:corpo (rpc svc cred (chamada 2 "tools/list" {}))) ["result" "tools"])
            situacao (first (filter #(= "situacao_da_materia" (get % "name")) ferramentas))]
        (is (= #{"situacao_da_materia" "tramitacao_da_materia" "pauta_da_sessao" "ata_da_sessao" "buscar_dispositivos"
                 "ler_dispositivo" "comissoes_da_casa" "pareceres_juridicos_da_materia"}
               (set (map #(get % "name") ferramentas))))
        (is (= "object" (get-in situacao ["inputSchema" "type"])) "a regra entre campos fica no servidor")
        (is (contains? (get-in situacao ["inputSchema" "properties"]) "sequencial"))
        (is (true? (get-in situacao ["annotations" "readOnlyHint"])))))
    (testing "tools/call pelo numero que a pessoa fala"
      (let [r (get (:corpo (rpc svc cred (chamada 3 "tools/call" {"name" "situacao_da_materia"
                                                                  "arguments" {"tipo" "projeto_lei"
                                                                               "sequencial" (:sequencial p)
                                                                               "ano" 2026}})))
                   "result")]
        (is (false? (get r "isError")))
        (is (= (str (:id p)) (get-in r ["structuredContent" "id"])))
        (is (= "interno" (get-in r ["_meta" "oplenario/origem"])) "a origem do conteudo vai marcada (Eixo 4.5)")
        (is (= "publico" (get-in r ["_meta" "oplenario/sigilo"])))
        (is (re-find #"merenda" (get-in r ["content" 0 "text"])))))
    (testing "erro de ferramenta volta como resultado, para o agente corrigir o rumo"
      (let [invalida (get (:corpo (rpc svc cred (chamada 4 "tools/call" {"name" "situacao_da_materia"
                                                                          "arguments" {"ano" 2026}})))
                          "result")
            nada (get (:corpo (rpc svc cred (chamada 5 "tools/call" {"name" "situacao_da_materia"
                                                                      "arguments" {"proposicao-id" (str (random-uuid))}})))
                      "result")]
        (is (true? (get invalida "isError")))
        (is (re-find #"Entrada invalida" (get-in invalida ["content" 0 "text"])))
        (is (true? (get nada "isError")))
        (is (nil? (get nada "structuredContent")))))
    (testing "ferramenta ou metodo desconhecido e' erro de protocolo"
      (is (= -32602 (get-in (:corpo (rpc svc cred (chamada 6 "tools/call" {"name" "apagar_tudo" "arguments" {}})))
                            ["error" "code"])))
      (is (= -32601 (get-in (:corpo (rpc svc cred (chamada 7 "resources/list" {}))) ["error" "code"]))))
    (testing "chave de cliente desconhecida nao vira nada"
      (let [r (get (:corpo (rpc svc cred (chamada 8 "tools/call" {"name" "situacao_da_materia"
                                                                  "arguments" {"proposicao-id" (str (:id p))
                                                                               "qualquer-coisa" 1}})))
                   "result")]
        (is (false? (get r "isError")))))))

(deftest sem-credencial-de-agente-nao-entra
  (let [svc (servico)]
    (is (= 401 (:status (rpc svc nil (chamada 1 "tools/list" {})))))
    (is (= 401 (:status (rpc svc "inventada" (chamada 1 "tools/list" {})))))))

(deftest a-pessoa-sem-papel-ve-lista-vazia
  (let [ente (random-uuid)
        cred (credencial! ente "admin_ente" :secretaria)]
    (is (= [] (get-in (:corpo (rpc (servico) cred (chamada 1 "tools/list" {}))) ["result" "tools"])))))
