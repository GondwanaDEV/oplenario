(ns oplenario.admin-sistema.console-login-test
  "INTEGRACAO (PG real, IdP fake): o login do OPERADOR e a separacao de esferas (ADR-0016, §22.5 eixo E). O
  operador entra pelo realm proprio e ganha o cookie do console; nenhuma credencial de Casa abre o console e nenhuma
  do console abre uma Casa (2a dimensao do teste de vazamento: cross-esfera). O Keycloak de verdade (realm, chave
  fisica) e' prova do suite :keycloak e do e2e de navegador."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.admin-sistema.components.idp-admin :as idp-admin]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.admin-sistema.db.atuacao :as atuacao]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas]
            [oplenario.suporte-delegante :refer [delegando]])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-op [] (assoc (repo/repositorio) :datasource {:ds *ds*}))

(defn- fake-idp-casa [tokens]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify idp/IdentityProvider (verificar-token [_ t] (get tokens t))))

(defn- servico
  ([tokens-casa] (servico tokens-casa (repo-op)))
  ([tokens-casa repo-operacao]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (fake-idp-casa tokens-casa)
                                   :repo-identidade (assoc (repo-id/repositorio) :datasource {:ds *ds*})
                                   :info-ente (constantly {:nome-oficial "Câmara" :nome-curto "Câmara"})
                                   :idp-operacao (idp-admin/idp-operacao-dev)
                                   :repo-admin-sistema repo-operacao
                                   :operacao {:realm "operacao" :base-url "http://kc" :base-url-publico "http://kc-pub"
                                              :client-id "oplenario-console"
                                              :sessao {:absoluta-h 8 :ociosa-min 15}}})
                    it/globais)
      ph/create-server ::ph/service-fn)))

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- operador! []
  (repo/criar-operador! (repo-op) {:id (random-uuid) :email (str "op-" (random-uuid) "@oplenario.dev")
                                   :nome "Operadora de Plantão"}))

(defn- token-op [o] (json/write-value-as-string {:sub "kc" :operador-id (str (:id o))}))

(defn- mint! [svc token]
  (pt/response-for svc :post "/operacao/sessoes" :headers {"Content-Type" "application/json"}
                   :body (json/write-value-as-string {:token token})))

(defn- cookie-op [seg] {"cookie" (str "sessao_operacao=" seg)})

(deftest descoberta-publica-do-realm-do-operador
  (let [r (pt/response-for (servico {}) :get "/operacao/descoberta")]
    (is (= 200 (:status r)))
    (is (= {:realm "operacao" :base-url "http://kc-pub" :client-id "oplenario-console"} (ler r))
        "a URL do navegador e' a publica")))

(deftest operador-entra-e-ve-quem-e
  (let [svc (servico {}) o (operador!)
        r (mint! svc (token-op o))]
    (is (= 200 (:status r)))
    (let [eu (pt/response-for svc :get "/operacao/eu" :headers (cookie-op (:sessao (ler r))))]
      (is (= 200 (:status eu)))
      (is (= {:id (str (:id o)) :nome "Operadora de Plantão" :email (:email o) :papeis ["operador"]}
             (:operador (ler eu)))))
    (testing "o Bearer do realm do operador tambem vale (servico/automacao)"
      (is (= 200 (:status (pt/response-for svc :get "/operacao/eu"
                                           :headers {"authorization" (str "Bearer " (token-op o))})))))
    (testing "a entrada fica na atuacao, com a corrente integra"
      (is (some #(= "entrou-no-console" (:acao %))
                (jdbc/execute! *ds* ["SELECT acao FROM admin_sistema.atuacao WHERE operador_id = ?" (:id o)]
                               {:builder-fn next.jdbc.result-set/as-unqualified-maps})))
      (is (true? (:integra? (atuacao/verificar-corrente *ds*)))))))

(deftest token-de-operador-desconhecido-nao-entra
  (let [svc (servico {})]
    (is (= 401 (:status (mint! svc (json/write-value-as-string {:operador-id (str (random-uuid))})))))
    (is (= 401 (:status (mint! svc "lixo"))))
    (is (= 400 (:status (mint! svc ""))))))

(deftest operador-desligado-cai-na-hora
  (let [svc (servico {}) o (operador!)
        seg (:sessao (ler (mint! svc (token-op o))))]
    (repo/desligar-operador! (repo-op) (:id o))
    (is (= 401 (:status (pt/response-for svc :get "/operacao/eu" :headers (cookie-op seg))))
        "a sessao dele foi apagada junto")
    (is (= 401 (:status (pt/response-for svc :get "/operacao/eu"
                                         :headers {"authorization" (str "Bearer " (token-op o))})))
        "e o token ainda no prazo tambem nao vale")
    (is (= 401 (:status (mint! svc (token-op o)))))))

(deftest logout-apaga-a-sessao-do-console
  (let [svc (servico {}) o (operador!)
        seg (:sessao (ler (mint! svc (token-op o))))]
    (is (= 204 (:status (pt/response-for svc :delete "/operacao/sessoes" :headers (cookie-op seg)))))
    (is (= 401 (:status (pt/response-for svc :get "/operacao/eu" :headers (cookie-op seg)))))))

;; ---- cross-esfera (2a dimensao do teste de vazamento) ----

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- servidora-da-casa! [ente]
  (let [iid (id/inserir! *ds* {:id (random-uuid) :cpf (cpf-valido) :nome "Servidora"})]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "servidor"})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id iid :papel "admin_ente"})))
    iid))

(deftest credencial-de-casa-nao-abre-o-console
  (let [ente (random-uuid) iid (servidora-da-casa! ente)
        svc (servico {"tok-casa" {:sub "kc" :ente-id ente :identidade-id iid}})
        seg-casa (repo-id/criar-sessao! (assoc (repo-id/repositorio) :datasource {:ds *ds*})
                                        {:identidade-id iid :ente-id ente
                                         :expira-em (.plusSeconds (Instant/now) 3600)
                                         :ocioso-ate (.plusSeconds (Instant/now) 600)})]
    (testing "a credencial vale na Casa (controle)"
      (is (= 200 (:status (pt/response-for svc :get "/eu" :headers {"cookie" (str "sessao=" seg-casa)})))))
    (testing "mas nao no console — nem a admin_ente da Casa"
      (is (= 401 (:status (pt/response-for svc :get "/operacao/eu" :headers {"cookie" (str "sessao=" seg-casa)}))))
      (is (= 401 (:status (pt/response-for svc :get "/operacao/eu" :headers {"authorization" "Bearer tok-casa"}))))
      (is (= 401 (:status (mint! svc "tok-casa"))) "o mint do console nao aceita token de Casa")
      (is (= 401 (:status (pt/response-for svc :get "/operacao/eu"
                                           :headers {"cookie" (str "sessao_operacao=" seg-casa)})))
          "o segredo de uma sessao de Casa nao e' uma sessao do console"))))

(deftest credencial-do-console-nao-abre-uma-casa
  (let [svc (servico {}) o (operador!)
        seg (:sessao (ler (mint! svc (token-op o))))]
    (is (= 401 (:status (pt/response-for svc :get "/eu" :headers (cookie-op seg)))) "o cookie do console nao e' `sessao`")
    (is (= 401 (:status (pt/response-for svc :get "/eu" :headers {"cookie" (str "sessao=" seg)})))
        "nem o segredo dele posto no cookie da Casa")
    (is (= 401 (:status (pt/response-for svc :get "/eu" :headers {"authorization" (str "Bearer " (token-op o))})))
        "nem o token do realm do operador")
    (is (= 401 (:status (pt/response-for svc :post "/auth/sessoes" :headers {"Content-Type" "application/json"}
                                         :body (json/write-value-as-string {:token (token-op o)}))))
        "nem vira sessao de Casa pelo mint das Casas")))

(deftest o-dominio-de-uma-casa-nao-enxerga-o-operador
  (let [o (operador!)]
    (tenancy/com-tenant* *ds* (random-uuid)
      (fn [tx]
        (is (thrown-with-msg? Exception #"permission denied"
                              (jdbc/execute! tx ["SELECT * FROM admin_sistema.operador WHERE id = ?" (:id o)])))))
    (tenancy/com-tenant* *ds* (random-uuid)
      (fn [tx]
        (is (thrown-with-msg? Exception #"permission denied"
                              (jdbc/execute! tx ["SELECT * FROM admin_sistema.sessao_operador"])))))
    (tenancy/com-tenant* *ds* (random-uuid)
      (fn [tx]
        (is (thrown-with-msg? Exception #"permission denied"
                              (jdbc/execute! tx ["SELECT * FROM admin_sistema.atuacao"])))))))

;; ---- ADR-0017 (adendo de 05/10/2026): a entrada do operador tem o par tentativa/desfecho na ATUACAO da Operacao ----

(defn- da-entrada
  "A atuacao do operador `o` (sem Casa: a entrada nao e' de uma Casa), em ordem de gravacao."
  [o]
  (mapv #(update % :detalhe comum/jsonb->kw)
        (jdbc/execute! *ds* ["SELECT id, acao, ente_id, detalhe FROM admin_sistema.atuacao WHERE operador_id = ? ORDER BY seq"
                             (:id o)]
                       {:builder-fn next.jdbc.result-set/as-unqualified-maps})))

(defn- sessoes-do [o]
  (:n (jdbc/execute-one! *ds* ["SELECT count(*) AS n FROM admin_sistema.sessao_operador WHERE operador_id = ?" (:id o)]
                         {:builder-fn next.jdbc.result-set/as-unqualified-maps})))

(deftest a-entrada-do-operador-grava-a-tentativa-ANTES-da-sessao-e-o-desfecho-depois
  (let [o (operador!) visto (atom nil)
        real (repo-op)
        svc (servico {} (delegando real {:criar-sessao-operador!
                                         (fn [r s]
                                           (reset! visto {:atuacao (mapv :acao (da-entrada o)) :sessoes (sessoes-do o)})
                                           (repo/criar-sessao-operador! r s))}))
        r (mint! svc (token-op o))]
    (is (= 200 (:status r)))
    (is (some? (:sessao (ler r))))
    (testing "no instante em que a sessao nasce, a tentativa ja' esta' na atuacao"
      (is (= {:atuacao ["entrada-no-console-iniciada"] :sessoes 0} @visto)))
    (testing "o desfecho vem depois e aponta a tentativa; nada sobra sem desfecho"
      (let [[t d :as linhas] (da-entrada o)]
        (is (= ["entrada-no-console-iniciada" "entrou-no-console"] (mapv :acao linhas)))
        (is (nil? (:ente_id t)) "a entrada e' da Operacao, nao de uma Casa: nenhuma Casa na linha")
        (is (= (str (:id t)) (get-in d [:detalhe :tentativa])))
        (is (empty? (filter #(= (:id o) (:operador-id %))
                            (repo/tentativas-sem-desfecho (repo-op) (.plusSeconds (Instant/now) 5)))))))
    (is (true? (:integra? (atuacao/verificar-corrente *ds*))) "a cadeia de selos segue integra")))

(deftest o-registro-fora-do-ar-nunca-tranca-o-login-do-operador
  (let [o (operador!)
        real (repo-op)
        sem-registro (delegando real {:registrar-atuacao! (fn [_ _] (throw (ex-info "atuacao fora do ar" {})))})
        svc (servico {} sem-registro)
        r (mint! svc (token-op o))]
    (testing "nem a tentativa nem o desfecho gravam: a sessao abre assim mesmo"
      (is (= 200 (:status r)))
      (is (= 200 (:status (pt/response-for svc :get "/operacao/eu" :headers (cookie-op (:sessao (ler r)))))))
      (is (empty? (da-entrada o)) "sem registro nenhum: so' o log"))
    (testing "so' o desfecho nao grava: a sessao abre, e a tentativa fica na atuacao, acusada na conferencia"
      (let [o2 (operador!)
            so-desfecho (delegando real {:registrar-atuacao!
                                         (fn [r reg] (if (= "entrou-no-console" (:acao reg))
                                                       (throw (ex-info "atuacao fora do ar" {}))
                                                       (repo/registrar-atuacao! r reg)))})
            r2 (mint! (servico {} so-desfecho) (token-op o2))]
        (is (= 200 (:status r2)))
        (is (= ["entrada-no-console-iniciada"] (mapv :acao (da-entrada o2))))
        (is (= [(:id (first (da-entrada o2)))]
               (mapv :id (filter #(= (:id o2) (:operador-id %))
                                 (repo/tentativas-sem-desfecho (repo-op) (.plusSeconds (Instant/now) 5))))))))))

(deftest sessao-que-nao-nasce-depois-da-tentativa-fecha-o-par-com-falhou
  (let [o (operador!)
        quebra (delegando (repo-op) {:criar-sessao-operador! (fn [_ _] (throw (ex-info "banco caiu na sessao" {})))})
        svc (servico {} quebra)
        r (mint! svc (token-op o))]
    (is (= 500 (:status r)))
    (let [[t f :as linhas] (da-entrada o)]
      (is (= ["entrada-no-console-iniciada" "entrada-no-console-falhou"] (mapv :acao linhas)))
      (is (= (str (:id t)) (get-in f [:detalhe :tentativa])))
      (is (zero? (sessoes-do o))))
    (is (empty? (filter #(= (:id o) (:operador-id %))
                        (repo/tentativas-sem-desfecho (repo-op) (.plusSeconds (Instant/now) 5)))))))

(deftest entrada-recusada-nao-deixa-tentativa-solta
  (let [svc (servico {})
        o (operador!)
        _ (repo/desligar-operador! (repo-op) (:id o))]
    (is (= 401 (:status (mint! svc (token-op o)))) "operador desligado: nada foi concedido")
    (is (empty? (da-entrada o)) "sem ator ativo nao ha' tentativa")
    (is (= 401 (:status (mint! svc "lixo"))))))
