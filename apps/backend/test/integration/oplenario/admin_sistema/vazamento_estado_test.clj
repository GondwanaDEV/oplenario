(ns oplenario.admin-sistema.vazamento-estado-test
  "INTEGRACAO (PG real, cadeia real de `rotas/montar`) — o teste de vazamento na 3a dimensao: o ESTADO da Casa
  (ADR-0018, Eixo 3). As outras duas: cross-tenant (`kernel.tenancy-test`) e cross-esfera (`console-login-test`).

  Uma Casa SUSPENSA nao escreve fora da allowlist: TODA rota de escrita montada que passa pelo interceptor de Casa (a
  pessoa ou o agente) e nao esta' na allowlist recebe 423 com o motivo publico — inclusive a rota que ainda nao existe
  hoje (a rota nova nasce bloqueada: o teste percorre as rotas montadas, nao uma lista). Leitura passa; os protocolos
  do cidadao e as respostas a eles seguem; a mesma escrita numa Casa ATIVA nao leva 423 (controle). A trilha de
  auditoria registra a recusa, com o ator. A faixa \"acesso restrito\": o interno ve o motivo, a cidada e o portal nao."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.http.route :as route]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.admin-sistema.components.idp-admin :as idp-admin]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.auditoria.components.repositorio :as repo-aud]
            [oplenario.auditoria.diplomat.http.in :as auditoria-http]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.restricao-da-casa :as restricao-casa]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-op [] (assoc (repo/repositorio) :datasource {:ds *ds*}))

(def servidora (random-uuid))
(def cidada (random-uuid))

(defn- fake-identidade
  "A pessoa da Casa (servidora com os papeis de quem escreve) e a cidada; `cred-<ente>` e' a credencial delegada de um
  agente da servidora naquela Casa."
  []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ iid]
      (cond (= iid servidora) {:vinculo-ativo {:id (random-uuid) :tipo "servidor"}
                               :papeis #{"secretario" "admin_ente" "vereador" "juridico" "auditor"}}
            (= iid cidada) {:vinculo-ativo {:id (random-uuid) :tipo "cidadao"} :papeis #{}}))
    (resolver-credencial-agente [_ seg]
      (when (str/starts-with? (str seg) "cred-")
        {:execucao-id (random-uuid) :ente-id (parse-uuid (subs seg 5)) :identidade-id servidora
         :agente "assistente" :publico "secretaria" :classes ["leitura"]}))))

(defn- rotas-montadas []
  (rotas/montar {:idp (idp-dev/idp-dev)
                 :repo-identidade (fake-identidade)
                 :idp-operacao (idp-admin/idp-operacao-dev)
                 :repo-admin-sistema (repo-op)
                 :repo-participacao (repo-part/->RepoParticipacaoPg {:ds *ds*} (outbox/bus))
                 :repo-integracao-ia :lint
                 :repo-auditoria :lint
                 :info-ente (constantly {:nome-oficial "Câmara Municipal de Baturité"})
                 :cache-estado-da-casa-ms 0
                 :operacao {:realm "operacao" :client-id "oplenario-console" :sessao {:absoluta-h 8 :ociosa-min 15}}}))

(defn- rp-aud [] (repo-aud/map->RepoAuditoriaPg {:datasource {:ds *ds*}}))

(defn- servico []
  (-> (http/servico (config/carregar) (rotas-montadas)
                    (it/globais-com [(auditoria-http/interceptor (rp-aud) {})]))
      ph/create-server ::ph/service-fn))

(defn- casa!
  "Uma Casa no registro, ativa; `suspensa?` = suspensa por incidente (um operador basta)."
  [suspensa?]
  (let [o (repo/criar-operador! (repo-op) {:id (random-uuid) :email (str "op-" (random-uuid) "@oplenario.dev") :nome "Op"})
        ente (random-uuid)]
    (repo/registrar-casa! (repo-op) {:ente-id ente :nome "Câmara" :uf "CE" :municipio-ibge "2302008"
                                     :municipio-nome "Baturité"}
                          {:operador-id (:id o)})
    (jdbc/with-transaction [tx *ds*] (repo/ativar-casa-em-tx! tx ente {}))
    (when suspensa?
      (repo/pedir-restricao! (repo-op) {:ente-id ente :acao "suspender" :motivo "incidente_de_seguranca"
                                        :justificativa "Credenciais vazadas em 30/09." :pedido-por (:id o)}
                             (Instant/now)))
    ente))

(defn- como [ente iid]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))
   "Content-Type" "application/json"})

(defn- nomes-dos-interceptors [r] (set (keep :name (:interceptors r))))

(def ^:private autenticacao-da-casa #{::it/autenticacao ::it/autenticacao-agente})

(defn- escritas-da-casa
  "Toda rota de ESCRITA montada que passa pelo interceptor de Casa (a pessoa ou o agente)."
  []
  (->> (route/expand-routes (rotas-montadas))
       (filter #(and (not (#{:get :head :options} (:method %)))
                     (some autenticacao-da-casa (nomes-dos-interceptors %))))
       (map (fn [r] {:nome (:route-name r) :metodo (:method r) :caminho (:path r)
                     :agente? (contains? (nomes-dos-interceptors r) ::it/autenticacao-agente)}))))

(defn- caminho-concreto [p] (str/replace p #":[^/]+" (fn [_] (str (random-uuid)))))

(defn- escrever! [svc ente {:keys [metodo caminho agente?]}]
  (pt/response-for svc metodo (caminho-concreto caminho)
                   :headers (if agente?
                              {"authorization" (str "Bearer cred-" ente) "Content-Type" "application/json"}
                              (como ente servidora))
                   :body "{}"))

(def ^:private allowlist-esperada
  "A allowlist do Eixo 2, escrita aqui de novo DE PROPOSITO: mexer nela exige mexer neste teste (revisao em PR)."
  #{:participacao/protocolar-esic :participacao/interpor-recurso :participacao/protocolar-manifestacao
    :participacao/solicitar-titular :participacao/comentar :participacao/denunciar-comentario
    :transparencia/seguir :transparencia/deixar-de-seguir
    :participacao/responder-pedido :participacao/decidir-recurso :participacao/responder-manifestacao
    :participacao/prorrogar-manifestacao :participacao/arquivar-manifestacao :participacao/responder-solicitacao
    ;; o balcao de atendimento: a prorrogacao do e-SIC (LAI art. 11 §2º), irma da prorrogacao da ouvidoria
    :participacao/prorrogar-pedido
    ;; o complemento da resposta (ADR-0022): a Casa terminando de responder, com o prazo legal ja' cumprido pela resposta
    :participacao/complementar-esic :participacao/complementar-ouvidoria :participacao/complementar-lgpd
    ;; o indeferimento fundamentado (LAI art. 11 §1º II; LGPD art. 18 §4º): negar tambem e' responder, dentro do prazo
    :participacao/indeferir-pedido :participacao/indeferir-solicitacao
    ;; o documento que acompanha a resposta (anexar e' parte de responder; baixar e' GET e ja' passa)
    :participacao/anexar-esic :participacao/anexar-ouvidoria :participacao/anexar-lgpd
    ;; o requerente anexa ao proprio protocolo: parte de protocolar (o direito de pedir nao depende do contrato da Casa)
    :participacao/anexar-meu-esic :participacao/anexar-meu-ouvidoria :participacao/anexar-meu-lgpd
    ;; retirar anexo = contencao de incidente de conteudo (a Casa suspensa pode)
    :participacao/retirar-anexo-esic :participacao/retirar-anexo-ouvidoria :participacao/retirar-anexo-lgpd
    ;; substituir anexo (ADR-0022) = retirar o errado e por o certo, num ato: a mesma contencao
    :participacao/substituir-anexo-esic :participacao/substituir-anexo-ouvidoria :participacao/substituir-anexo-lgpd
    :participacao/moderar-comentario :paineis/marcar-notificacao-lida
    ;; ADR-0020: a ciencia de um comunicado recebido (enviar segue bloqueado)
    :comunicacao/ciencia
    ;; o compliance segue (a remessa ao TCE) e a Casa nomeia o encarregado LGPD
    :compliance/validar-remessa :compliance/submeter-remessa :compliance/resposta-remessa
    :participacao/definir-encarregado
    ;; ADR-0018 (fatia 2): a portabilidade — gerar a exportacao completa e confirmar o recebimento (o encerramento anda)
    :exportacao-da-casa/gerar :exportacao-da-casa/confirmar-recebimento})

(deftest a-allowlist-e-a-revisada
  (is (= allowlist-esperada restricao-casa/allowlist))
  (is (not (restricao-casa/escrita-permitida? :post :identidade/conceder-acesso))
      "conceder acesso fica bloqueado: a Casa suspensa nao ganha gente nova no sistema"))

(deftest casa-suspensa-nao-escreve-fora-da-allowlist
  (let [svc (servico) suspensa (casa! true) ativa (casa! false)
        escritas (escritas-da-casa)
        bloqueadas (remove #(restricao-casa/escrita-permitida? (:metodo %) (:nome %)) escritas)
        permitidas (filter #(restricao-casa/escrita-permitida? (:metodo %) (:nome %)) escritas)]
    (is (> (count bloqueadas) 60) "sanidade: o legislativo, as sessoes, os cadastros... estao montados")
    (is (some :agente? bloqueadas) "o agente (MCP) tambem e' da Casa")
    (is (= allowlist-esperada (set (map :nome permitidas)))
        "toda rota da allowlist existe e passa pelo interceptor de Casa (sem entrada fantasma)")
    (testing "fora da allowlist: 423 com o motivo PUBLICO, nunca o comercial"
      (doseq [r bloqueadas]
        (let [resp (escrever! svc suspensa r)]
          (is (= 423 (:status resp)) (str (:nome r) " " (:caminho r)))
          (when (= 423 (:status resp))
            (is (= "acesso restrito" (:erro (json/read-value (:body resp) json/keyword-keys-object-mapper))))
            (is (not (re-find #"incidente" (:body resp))) "o motivo nao sai no fio")))))
    (testing "a allowlist segue (protocolos do cidadao e as respostas a eles)"
      (doseq [r permitidas]
        (is (not= 423 (:status (escrever! svc suspensa r))) (str (:nome r)))))
    (testing "controle: a mesma escrita numa Casa ATIVA nao leva 423"
      (doseq [r (take 12 bloqueadas)]
        (is (not= 423 (:status (escrever! svc ativa r))) (str (:nome r)))))
    (testing "leitura passa na Casa suspensa"
      (is (= 200 (:status (pt/response-for svc :get "/eu" :headers (como suspensa servidora))))))
    (testing "a trilha registra a recusa, com o ator (o 423 nao quebra a auditoria)"
      (let [regs (tenancy/com-tenant* *ds* suspensa
                   #(jdbc/execute! % ["SELECT acao, status_http, rotulo, identidade_id FROM auditoria.registro
                                       WHERE ente_id = ? AND status_http = 423" suspensa]))]
        (is (seq regs))
        (is (every? #(= "recusado: Casa com acesso restrito" (:registro/rotulo %)) regs))
        (is (some #(= servidora (:registro/identidade_id %)) regs))))))

(deftest a-faixa-de-acesso-restrito
  (let [svc (servico) suspensa (casa! true) ativa (casa! false)
        eu (fn [ente iid] (json/read-value (:body (pt/response-for svc :get "/eu" :headers (como ente iid)))
                                           json/keyword-keys-object-mapper))]
    (testing "o interno ve desde quando e o motivo"
      (let [b (eu suspensa servidora)]
        (is (= "incidente_de_seguranca" (get-in b [:acesso-restrito :motivo])))
        (is (some? (get-in b [:acesso-restrito :desde])))))
    (testing "a cidada (e o portal) so' \"acesso restrito\" — o motivo nao e' publico"
      (let [b (eu suspensa cidada)]
        (is (some? (get-in b [:acesso-restrito :desde])))
        (is (not (contains? (:acesso-restrito b) :motivo))))
      (let [p (json/read-value (:body (pt/response-for svc :get (str "/portal/casa/" suspensa)))
                               json/keyword-keys-object-mapper)]
        (is (some? (:acesso-restrito-desde p)) "o portal segue no ar, com a faixa")
        (is (not (re-find #"incidente" (pr-str p))))))
    (testing "Casa ativa: sem faixa"
      (is (nil? (:acesso-restrito (eu ativa servidora))))
      (is (nil? (:acesso-restrito-desde (json/read-value (:body (pt/response-for svc :get (str "/portal/casa/" ativa)))
                                                         json/keyword-keys-object-mapper)))))
    (testing "Casa fora do registro (demo, testes de outras verticais): sem restricao"
      (is (nil? (:acesso-restrito (eu (random-uuid) servidora)))))))

(deftest o-recibo-do-protocolo-diz-que-a-casa-esta-restrita
  (let [svc (servico) suspensa (casa! true) ativa (casa! false)
        protocolar (fn [ente] (pt/response-for svc :post "/portal/esic/pedidos" :headers (como ente cidada)
                                               :body (json/write-value-as-string {:assunto "Contratos 2026"
                                                                                  :descricao "Solicito a lista de contratos."})))
        ler #(json/read-value (:body %) json/keyword-keys-object-mapper)
        r (protocolar suspensa)]
    (is (= 201 (:status r)) "o pedido do cidadao segue na Casa suspensa")
    (is (some? (:protocolo (ler r))))
    (is (some? (:acesso-restrito-desde (ler r))) "o recibo diz que a Casa esta' com o sistema restrito")
    (is (not (re-find #"incidente" (:body r))) "sem o motivo")
    (let [r2 (protocolar ativa)]
      (is (= 201 (:status r2)))
      (is (not (contains? (ler r2) :acesso-restrito-desde))))))

;; ---------- ADR-0018 (fatia 2): a Casa ENCERRADA nao responde nada ----------

(defn- casa-encerrada!
  "Uma Casa no registro ja' encerrada (o caminho inteiro ate' aqui e' do encerrar_casa_test)."
  []
  (let [ente (casa! false)]
    (jdbc/execute! *ds* ["UPDATE admin_sistema.ente SET estado = 'encerrado', encerrada_em = now(),
                          destino_acervo_url = 'https://camara.exemplo.gov.br/acervo' WHERE ente_id = ?" ente])
    ente))

(defn- rotas-da-casa-montadas
  "Toda rota montada que e' da Casa: as que passam pelo interceptor de Casa (a pessoa, o agente) e as publicas que
  trazem a Casa no caminho (o portal, a descoberta do login). Leitura e escrita."
  []
  (->> (route/expand-routes (rotas-montadas))
       (filter #(or (some autenticacao-da-casa (nomes-dos-interceptors %))
                    (restricao-casa/rota-publica-da-casa? (:path %))))
       (map (fn [r] {:nome (:route-name r) :metodo (:method r) :caminho (:path r)
                     :agente? (contains? (nomes-dos-interceptors r) ::it/autenticacao-agente)
                     :publica? (restricao-casa/rota-publica-da-casa? (:path r))}))))

(deftest casa-encerrada-responde-410-em-toda-rota-dela
  (let [svc (servico) encerrada (casa-encerrada!) ativa (casa! false)
        rotas (rotas-da-casa-montadas)
        chamar (fn [ente {:keys [metodo caminho agente? publica?]}]
                 (pt/response-for svc metodo (if publica?
                                               (str/replace (caminho-concreto caminho)
                                                            #"^(/portal/casa|/auth/descoberta)/[^/]+"
                                                            (str "$1/" ente))
                                               (caminho-concreto caminho))
                                  :headers (cond agente? {"authorization" (str "Bearer cred-" ente)
                                                          "Content-Type" "application/json"}
                                                 publica? {"Content-Type" "application/json"}
                                                 :else (como ente servidora))
                                  :body "{}"))]
    (is (> (count rotas) 150) "sanidade: leitura e escrita da Casa, o agente, o portal")
    (is (some :publica? rotas))
    (testing "toda rota da Casa — inclusive LEITURA, o portal e o login — responde 410 com a data e o destino"
      (doseq [r rotas]
        (let [resp (chamar encerrada r)]
          (is (= 410 (:status resp)) (str (:nome r) " " (:metodo r) " " (:caminho r)))
          (when (= 410 (:status resp))
            (let [b (json/read-value (:body resp) json/keyword-keys-object-mapper)]
              (is (= "https://camara.exemplo.gov.br/acervo" (:destino-acervo-url b)))
              (is (some? (:encerrada-em b))))))))
    (testing "controle: a Casa ativa nao leva 410"
      (doseq [r (take 20 rotas)]
        (is (not= 410 (:status (chamar ativa r))) (str (:nome r)))))
    (testing "a trilha da Casa apagada nao ganha registro novo (o 410 sai sem ator)"
      (is (empty? (tenancy/com-tenant* *ds* encerrada
                    #(jdbc/execute! % ["SELECT 1 FROM auditoria.registro WHERE ente_id = ?" encerrada])))))))
