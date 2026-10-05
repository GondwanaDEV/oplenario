(ns oplenario.auditoria.entrada-com-tentativa-test
  "INTEGRACAO (PG real, IdP fake) — ADR-0017, adendo de 05/10/2026: a ENTRADA (o mint da sessao, `POST /auth/sessoes`)
  passa a ter o par tentativa/desfecho que a escrita ganhou em 04/10.

  O mint nao tem ator antes de rodar (e' ele que o resolve do token verificado): a tentativa entra DEPOIS de a
  identidade ter vinculo ativo e ANTES de a sessao ser criada. Atravessa a borda real (`rotas/montar`, que passa a
  tabela por `com-tentativa`) com o interceptor global da trilha e o repositorio de verdade.

  A regra do login, diferente da escrita: a trilha fora NUNCA tranca a entrada, nem com AUDITORIA_EXIGIR_TENTATIVA=true
  (quem entra para consertar a trilha nao pode ser o que a trilha deixou de fora)."
  (:require [oplenario.suporte-cpf :refer [cpf-valido]]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.auditoria.components.repositorio :as repo]
            [oplenario.auditoria.diplomat.http.in :as auditoria-http]
            [oplenario.auditoria.logic :as logic]
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
            [oplenario.rotas :as rotas]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- fake-idp [tokens]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify idp/IdentityProvider
    (verificar-token [_ token] (get tokens token))))

(defn- rp
  "O repositorio de verdade. `tolerancia-s` 0 = a tentativa sem desfecho e' acusada na hora (o padrao espera 2 min)."
  ([] (rp 0))
  ([tolerancia-s] (repo/map->RepoAuditoriaPg {:datasource {:ds *ds*} :tolerancia-s tolerancia-s})))

(defn- rp-que-falha
  "O repositorio de verdade, menos na gravacao que `falha?` escolher: ali a gravacao LANCA (o banco da trilha caiu)."
  [falha?]
  (let [real (rp)]
    (reify repo/RepoAuditoria
      (registrar! [_ r] (if (falha? r) (throw (ex-info "banco da trilha fora" {})) (repo/registrar! real r)))
      (trilha [_ e f l] (repo/trilha real e f l))
      (total [_ e] (repo/total real e))
      (verificar [_ e] (repo/verificar real e))
      (sem-desfecho [_ e] (repo/sem-desfecho real e))
      (selos-do-dia [_ e n] (repo/selos-do-dia real e n)))))

(def desfecho? #(not= logic/iniciado (:decisao %)))
(def tentativa? #(= logic/iniciado (:decisao %)))

(defn- repo-identidade
  "O repositorio de identidade de verdade; `antes-de-criar-sessao` roda ANTES de a sessao ser gravada (para ver a corrente
  nesse instante ou para fazer o mint quebrar logo depois da tentativa)."
  [antes-de-criar-sessao]
  (let [real (assoc (repo-id/repositorio) :datasource {:ds *ds*})]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify repo-id/RepoIdentidade
      (criar-identidade! [_ i] (repo-id/criar-identidade! real i))
      (identidade-por-cpf [_ cpf] (repo-id/identidade-por-cpf real cpf))
      (vincular-externa! [_ v] (repo-id/vincular-externa! real v))
      (identidade-por-sub [_ p sub] (repo-id/identidade-por-sub real p sub))
      (snapshot-ator [_ e i] (repo-id/snapshot-ator real e i))
      (snapshot-cidadao [_ e i] (repo-id/snapshot-cidadao real e i))
      (garantir-vinculo-cidadao! [_ e i c] (repo-id/garantir-vinculo-cidadao! real e i c))
      (registrar-primeiro-acesso! [_ e a] (repo-id/registrar-primeiro-acesso! real e a))
      (criar-sessao! [_ s] (antes-de-criar-sessao) (repo-id/criar-sessao! real s)))))

(defn- servico [tokens {:keys [rp-escrita exigir? antes-de-criar-sessao] :or {antes-de-criar-sessao (fn [])}}]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (fake-idp tokens)
                                   :repo-identidade (repo-identidade antes-de-criar-sessao)
                                   :info-ente (constantly {:nome-oficial "Câmara" :nome-curto "Câmara"})})
                    (it/globais-com [(auditoria-http/interceptor (or rp-escrita (rp)) {:exigir-tentativa? exigir?})]))
      ph/create-server ::ph/service-fn))

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- mint! [svc token]
  (pt/response-for svc :post "/auth/sessoes" :headers {"Content-Type" "application/json"}
                   :body (json/write-value-as-string {:token token})))

(defn- govbr [ente cpf] {:sub "kc" :ente-id ente :idp "govbr" :govbr-sub cpf :nome "Maria das Dores"})

(defn- corrente
  "A corrente crua da Casa, em ordem — o que esta' no banco, sem o filtro da tela."
  [ente]
  (tenancy/com-tenant* *ds* ente
    #(mapv (fn [l] (update l :detalhe comum/jsonb->kw))
           (jdbc/execute! % ["SELECT seq, acao, classe, decisao, status_http, detalhe, rotulo, ator_tipo FROM auditoria.registro
                              WHERE ente_id = ? ORDER BY seq" ente]
                          {:builder-fn rs/as-unqualified-lower-maps}))))

(def ^:private resumo (juxt :acao :classe :decisao :status_http (comp :tentativa :detalhe)))

(deftest a-entrada-grava-a-tentativa-antes-e-o-desfecho-depois
  (let [ente (random-uuid) cpf (cpf-valido)
        svc (servico {"tok" (govbr ente cpf)} {})
        r (mint! svc "tok")]
    (is (= 200 (:status r)))
    (is (some? (:sessao (ler r))))
    (is (= [["identidade/mint-sessao" "entrada" "iniciado" nil nil]
            ["identidade/mint-sessao" "entrada" "permitido" 200 1]]
           (mapv resumo (corrente ente)))
        "a tentativa (seq 1) e o desfecho que a aponta")
    (is (= ["cidadao" "cidadao"] (mapv :ator_tipo (corrente ente))))
    (is (= "entrou pelo gov.br" (:rotulo (second (corrente ente)))))
    (is (= {:total 0 :primeiro nil} (repo/sem-desfecho (rp) ente)) "o par fechado nao e' acusado")
    (is (:integra (repo/verificar (rp) ente)))))

(deftest a-tentativa-esta-na-corrente-antes-de-a-sessao-existir
  ;; a ordem e' o ponto: se a sessao for criada e o processo cair, a tentativa ja' esta' la'
  (let [ente (random-uuid) cpf (cpf-valido)
        vista (atom nil)
        svc (servico {"tok" (govbr ente cpf)}
                     {:antes-de-criar-sessao #(reset! vista (mapv resumo (corrente ente)))})]
    (is (= 200 (:status (mint! svc "tok"))))
    (is (= [["identidade/mint-sessao" "entrada" "iniciado" nil nil]] @vista)
        "quando a sessao e' criada a corrente ja' tem a tentativa — e so' ela")))

(deftest o-login-de-servidor-tem-o-mesmo-par
  (let [ente (random-uuid) cpf (cpf-valido) iid (random-uuid)]
    (id/inserir! *ds* {:id iid :cpf cpf :nome "Secretária"})
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "servidor"})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id iid :papel "secretario"})))
    (let [svc (servico {"tok" {:sub "kc" :ente-id ente :identidade-id iid}} {})]
      (is (= 200 (:status (mint! svc "tok"))))
      (is (= [["identidade/mint-sessao" "entrada" "iniciado" nil nil]
              ["identidade/mint-sessao" "entrada" "permitido" 200 1]]
             (mapv resumo (corrente ente))))
      (is (= ["pessoa" "pessoa"] (mapv :ator_tipo (corrente ente)))))))

(deftest o-desfecho-que-nao-grava-deixa-a-tentativa-a-vista-e-o-login-segue
  (let [ente (random-uuid) cpf (cpf-valido)
        svc (servico {"tok" (govbr ente cpf)} {:rp-escrita (rp-que-falha desfecho?)})
        r (mint! svc "tok")]
    (is (= 200 (:status r)) "a resposta nao muda: a sessao foi criada")
    (is (some? (:sessao (ler r))))
    (is (= [["identidade/mint-sessao" "entrada" "iniciado" nil nil]] (mapv resumo (corrente ente)))
        "sem a tentativa a corrente estaria vazia: a sessao existiria sem rastro nenhum")
    (testing "a leitura e a conferencia acusam: 'acao iniciada, desfecho nao registrado'"
      (is (= {:total 1 :primeiro 1} (repo/sem-desfecho (rp) ente)))
      (let [{:keys [registros total]} (repo/trilha (rp) ente {:escopo {:tipo :casa}} 10)]
        (is (= 1 total))
        (is (= [["identidade/mint-sessao" "entrada" "iniciado"]]
               (mapv (juxt :acao :classe :decisao) registros)))))))

(deftest a-trilha-fora-nunca-tranca-a-entrada-nem-com-a-exigencia-ligada
  (doseq [exigir? [nil false true]]
    (testing (str "AUDITORIA_EXIGIR_TENTATIVA = " exigir?)
      (testing "so' a tentativa falha: a sessao abre, o desfecho entra sem apontamento"
        (let [ente (random-uuid) cpf (cpf-valido)
              svc (servico {"tok" (govbr ente cpf)} {:exigir? exigir? :rp-escrita (rp-que-falha tentativa?)})
              r (mint! svc "tok")]
          (is (= 200 (:status r)) "a entrada NUNCA e' recusada por causa da trilha")
          (is (some? (:sessao (ler r))))
          (is (= [["identidade/mint-sessao" "entrada" "permitido" 200 nil]] (mapv resumo (corrente ente))))))
      (testing "a trilha inteira fora: a sessao abre, nada entra na corrente (so' o log acusa)"
        (let [ente (random-uuid) cpf (cpf-valido)
              svc (servico {"tok" (govbr ente cpf)} {:exigir? exigir? :rp-escrita (rp-que-falha (constantly true))})
              r (mint! svc "tok")]
          (is (= 200 (:status r)))
          (is (some? (:sessao (ler r))))
          (is (= [] (corrente ente))))))))

(deftest o-login-que-quebra-depois-da-tentativa-tem-desfecho-falhou-de-entrada
  (let [ente (random-uuid) cpf (cpf-valido)
        svc (servico {"tok" (govbr ente cpf)}
                     {:antes-de-criar-sessao #(throw (ex-info "banco caiu na sessao" {}))})]
    (is (= 500 (:status (mint! svc "tok"))))
    (is (= [["identidade/mint-sessao" "entrada" "iniciado" nil nil]
            ["identidade/mint-sessao" "entrada" "falhou" 500 1]]
           (mapv resumo (corrente ente)))
        "a falha e' da entrada (nao 'escrita') e aponta a tentativa: nao sobra tentativa solta")
    (is (= {:total 0 :primeiro nil} (repo/sem-desfecho (rp) ente)))))

(deftest sem-vinculo-ou-token-invalido-nao-ha-ato-a-registrar
  ;; sem identidade com vinculo nao ha ator nem Casa a quem atribuir: segue sem registro, como antes do adendo
  (let [ente (random-uuid)
        svc (servico {"sem-vinculo" {:sub "kc" :ente-id ente :identidade-id (random-uuid)}} {})]
    (is (= 401 (:status (mint! svc "sem-vinculo"))))
    (is (= 401 (:status (mint! svc "token-que-o-idp-nao-conhece"))))
    (is (= [] (corrente ente)))))
