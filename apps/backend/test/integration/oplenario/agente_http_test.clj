(ns oplenario.agente-http-test
  "INTEGRACAO (PG real + borda HTTP): B.3 — a tela pergunta ao assistente pelo core. A credencial delegada nasce para
  a execucao, vai ao satelite (falso aqui) e morre ao fim, com resposta ou com a IA fora. A conversa volta em SSE."
  (:require [oplenario.suporte-cpf :refer [cpf-valido]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.agente :as agente]
            [oplenario.auditoria.components.repositorio :as repo-auditoria]
            [oplenario.auditoria.diplomat.http.in :as auditoria-http]
            [oplenario.catalogo :as catalogo]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.integracao-ia.diplomat.http.out :as plataforma-ia]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.integracao-ia.logic :as logic-ia]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo [] (assoc (repo-id/repositorio) :datasource {:ds *ds*}))

(defn- pessoa! [ente & papeis]
  (let [iid (random-uuid)]
    (id/inserir! *ds* {:id iid :cpf (cpf-valido) :nome "Pessoa"})
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id iid :tipo "servidor"})
        (doseq [p papeis] (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id iid :papel p}))))
    iid))

(def ^:private resposta-ia
  {:passos [{:ferramenta "situacao_da_materia" :argumentos {:tipo "projeto_lei" :sequencial 12 :ano 2026} :ok true
             :enviado-ao-modelo true}]
   :resposta {:execucao-id "e1" :texto "Segundo o sistema da Casa, ementa: Merenda. [[ferramenta:situacao_da_materia#1 | ementa: Merenda]]"
              :citacoes [{:fonte-id "ferramenta:situacao_da_materia#1" :trecho "ementa: Merenda" :status "conferida"}]
              :paragrafos-sem-fonte [] :incerteza "normal" :modelo "fake-1" :contaminado false}
   :indisponivel nil})

(defn- ia [pedidos & {:keys [fora? existe?] :or {existe? true}}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify plataforma-ia/PlataformaIA
    (executar-agente [_ ente-id pedido]
      (swap! pedidos conj [ente-id pedido])
      (if fora? (throw (ex-info "fora" {:tipo :ia/indisponivel :motivo "teste"})) resposta-ia))
    (reportar-erro [_ ente-id execucao-id pedido]
      (swap! pedidos conj [ente-id execucao-id pedido])
      (cond fora? (throw (ex-info "fora" {:tipo :ia/indisponivel :motivo "teste"}))
            existe? {:execucao-id execucao-id :reportado true}
            :else nil))))

(defn- repo-integracao [] (repo-ia/map->RepoIntegracaoIAPg {:datasource {:ds *ds*}}))

(defn- servico
  "O servico da borda. ADR-0024: sem historico a resposta nao sai, entao o repositorio da fronteira e' o de verdade por
  padrao; `:sem-historico? true` monta sem ele (falha fechada)."
  [plataforma & {:keys [repo-integracao-ia sem-historico?]}]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (repo))]
    (-> (http/servico (config/carregar) (agente/rotas {:auth auth :repo-identidade (repo) :ia plataforma
                                                       :repo-integracao-ia (when-not sem-historico?
                                                                             (or repo-integracao-ia
                                                                                 (repo-integracao)))})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- perguntar [svc ente iid corpo]
  (pt/response-for svc :post "/agente/perguntas"
                   :headers {"Content-Type" "application/json"
                             "Authorization" (str "Bearer " (json/write-value-as-string
                                                             {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))}
                   :body (json/write-value-as-string corpo)))

(defn- reportar [svc ente iid execucao-id corpo]
  (pt/response-for svc :post (str "/ia/execucoes/" execucao-id "/reportes")
                   :headers (cond-> {"Content-Type" "application/json"}
                              iid (assoc "Authorization"
                                         (str "Bearer " (json/write-value-as-string
                                                         {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))))
                   :body (json/write-value-as-string corpo)))

(defn- eventos [body]
  (for [bloco (str/split (str/trim body) #"\n\n")
        :let [[_ nome] (re-find #"event: (\S+)" bloco)
              [_ dado] (re-find #"data: (.*)" bloco)]]
    [nome (json/read-value dado)]))

(deftest a-conversa-volta-em-sse-e-a-credencial-morre-ao-fim
  (let [ente (random-uuid)
        iid (pessoa! ente "secretario")
        pedidos (atom [])
        r (perguntar (servico (ia pedidos)) ente iid {:pergunta "Qual a situacao do PL 12/2026?"})
        evs (eventos (:body r))]
    (is (= 200 (:status r)))
    (is (str/starts-with? (get-in r [:headers "Content-Type"]) "text/event-stream"))
    (is (= ["passo" "resposta" "fim"] (map first evs)))
    (is (= "situacao_da_materia" (get-in (first evs) [1 "ferramenta"])))
    (is (= "conferida" (get-in (second evs) [1 "citacoes" 0 "status"])))
    (is (= "e1" (get-in (second evs) [1 "execucao-ia"]))
        "feature 8.4: o id da execucao NA IA vai a tela, para o 'reportar erro' (o `fim` segue com o da credencial)")
    (let [[ente-ia {:keys [credencial correlation_id pergunta]}] (first @pedidos)]
      (is (= ente ente-ia) "a Casa vem do ator, nunca do corpo")
      (is (= "Qual a situacao do PL 12/2026?" pergunta))
      (is (= correlation_id (get-in (last evs) [1 "execucao-id"])))
      (is (nil? (auten/resolver-agente (repo) credencial)) "a credencial foi revogada ao fim da execucao"))))

(deftest ia-fora-e-siga-pela-tela-e-a-credencial-morre-igual
  (let [ente (random-uuid)
        iid (pessoa! ente "vereador")
        pedidos (atom [])
        evs (eventos (:body (perguntar (servico (ia pedidos :fora? true)) ente iid {:pergunta "pauta de amanha?"})))]
    (is (= ["indisponivel" "fim"] (map first evs)))
    (is (re-find #"Siga pela tela" (get-in (first evs) [1 "mensagem"])))
    (is (nil? (auten/resolver-agente (repo) (:credencial (second (first @pedidos))))))))

(deftest publico-sai-do-papel
  (let [ente (random-uuid)
        pedidos (atom [])
        svc (servico (ia pedidos))]
    (testing "vereador nao pede o conjunto da secretaria"
      (is (= 403 (:status (perguntar svc ente (pessoa! ente "vereador") {:pergunta "oi?" :publico "secretaria"})))))
    (testing "quem nao tem papel que alcance a Clara nao pergunta"
      (is (= 403 (:status (perguntar svc ente (pessoa! ente) {:pergunta "oi?"})))))
    (testing "juridico, auditor e admin_ente perguntam com o publico de consulta, e so' com ele"
      (let [outro (servico (ia (atom [])))]
        (doseq [p ["juridico" "auditor" "admin_ente"]]
          (is (= 200 (:status (perguntar outro ente (pessoa! ente p) {:pergunta "oi?"}))) p)
          (is (= 403 (:status (perguntar outro ente (pessoa! ente p) {:pergunta "oi?" :publico "secretaria"}))) p))
        (is (= 403 (:status (perguntar outro ente (pessoa! ente "secretario") {:pergunta "oi?" :publico "consulta"}))))))
    (testing "quem tem os dois escolhe; sem escolha, a secretaria"
      (let [iid (pessoa! ente "secretario" "vereador")]
        (perguntar svc ente iid {:pergunta "oi?"})
        (perguntar svc ente iid {:pergunta "oi?" :publico "vereador"})
        (is (= 2 (count @pedidos)))))
    (testing "pergunta vazia e' 400"
      (is (= 400 (:status (perguntar svc ente (pessoa! ente "secretario") {:pergunta " "})))))))

(deftest proposta-de-ato-da-execucao-vai-a-tela
  ;; B.6 / ADR-0012: a execucao recebe `ato` (que por agente so' propoe); a proposta criada nela vira evento `proposta`
  ;; para a tela levar a pessoa a confirmar. Aqui o satelite falso faz o papel do MCP e grava a proposta.
  (let [ente (random-uuid)
        iid (pessoa! ente "vereador")
        ri (repo-ia/map->RepoIntegracaoIAPg {:datasource {:ds *ds*}})
        classes (atom nil)
        plataforma #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify plataforma-ia/PlataformaIA
          (executar-agente [_ ente-id {:keys [credencial correlation_id]}]
            (reset! classes (get-in (auten/resolver-agente (repo) credencial) [:via :classes]))
            (repo-ia/criar-proposta! ri {:ente-id ente-id :execucao-id (parse-uuid correlation_id) :identidade-id iid
                                         :agente "assistente-da-casa" :ferramenta "protocolar_requerimento"
                                         :entrada {} :titulo "Protocolar o requerimento “X”" :texto "texto"
                                         :ritual "assinatura" :contaminada-por []
                                         :expira-em (java.sql.Timestamp/from (.plusSeconds (java.time.Instant/now) 3600))})
            resposta-ia))
        evs (eventos (:body (perguntar (servico plataforma :repo-integracao-ia ri) ente iid {:pergunta "protocole"})))]
    (is (= #{:leitura :ato} @classes) "ato concedido: por agente, so' proposta")
    (is (= ["passo" "proposta" "resposta" "fim"] (map first evs)))
    (is (= {"titulo" "Protocolar o requerimento “X”" "ritual" "assinatura"}
           (select-keys (second (second evs)) ["titulo" "ritual"])))))

(def ^:private eid-ia "5b0c1c9e-2f4e-4d7a-9d43-0f6f3c2a7e11")

(deftest reportar-erro-da-ia-vai-ao-satelite-com-a-casa-e-a-pessoa-da-sessao
  ;; Feature 8.4: a tela diz que a resposta esta' errada; o core repassa ao registro da Camada de Confianca do satelite
  (let [ente (random-uuid)
        iid (pessoa! ente "vereador")
        pedidos (atom [])
        svc (servico (ia pedidos))
        r (reportar svc ente iid eid-ia {:categoria "citacao_errada"})]
    (is (= 200 (:status r)))
    (is (= {"reportado" true} (json/read-value (:body r))))
    (is (= [[ente eid-ia {:quem (str iid) :categoria "citacao_errada"}]] @pedidos)
        "a Casa e a pessoa saem da sessao, nunca do corpo")
    (testing "a mesma confirmacao de novo (o satelite nao conta duas vezes)"
      (is (= 200 (:status (reportar svc ente iid eid-ia {:categoria "outro"})))))))

(deftest reportar-erro-so-com-o-vocabulario-e-id-valido
  (let [ente (random-uuid)
        iid (pessoa! ente "secretario")
        pedidos (atom [])
        svc (servico (ia pedidos))]
    (is (= 400 (:status (reportar svc ente iid eid-ia {:categoria "inventou o artigo 45"}))) "sem texto livre")
    (is (= 400 (:status (reportar svc ente iid eid-ia {}))))
    (is (= 400 (:status (reportar svc ente iid eid-ia {:categoria "outro" :texto "x"}))) "campo a mais")
    (is (= 400 (:status (reportar svc ente iid "nao-e-uuid" {:categoria "outro"}))))
    (is (= 401 (:status (reportar svc ente nil eid-ia {:categoria "outro"}))))
    (is (empty? @pedidos) "nada chega ao satelite")))

(deftest reportar-erro-execucao-de-outra-casa-e-ia-fora
  (let [ente (random-uuid)
        iid (pessoa! ente "secretario")]
    (testing "o satelite nao acha a execucao nesta Casa -> 404"
      (is (= 404 (:status (reportar (servico (ia (atom []) :existe? false)) ente iid eid-ia {:categoria "outro"})))))
    (testing "IA fora -> 503 honesto, nunca 500"
      (let [r (reportar (servico (ia (atom []) :fora? true)) ente iid eid-ia {:categoria "outro"})]
        (is (= 503 (:status r)))
        (is (re-find #"Tente de novo" (get (json/read-value (:body r)) "erro")))))))

;; ---------- ADR-0024: o historico auditavel da Clara ----------

(defn- interacao-do-fim [ente evs]
  (let [[nome dado] (last evs)]
    (is (= "fim" nome))
    (repo-ia/interacao-assistente (repo-integracao) ente (parse-uuid (get dado "interacao-id")))))

(deftest a-pergunta-e-a-resposta-ficam-no-historico-e-conferem
  (let [ente (random-uuid)
        iid (pessoa! ente "secretario")
        evs (eventos (:body (perguntar (servico (ia (atom []))) ente iid {:pergunta "Qual a situacao do PL 12/2026?"})))
        [_ fim] (last evs)
        i (interacao-do-fim ente evs)]
    (is (= ["passo" "resposta" "fim"] (map first evs)))
    (is (= [iid "secretaria" "resposta" "Qual a situacao do PL 12/2026?"]
           ((juxt :identidade-id :publico :desfecho :pergunta) i)))
    (is (= "Segundo o sistema da Casa, ementa: Merenda. [[ferramenta:situacao_da_materia#1 | ementa: Merenda]]"
           (get-in i [:resposta :texto])))
    (is (= "conferida" (get-in i [:resposta :citacoes 0 :status])))
    (is (= [{:ferramenta "situacao_da_materia" :argumentos {:tipo "projeto_lei" :sequencial 12 :ano 2026} :ok true}]
           (:passos i)) "os passos sem o que e' interno do satelite (enviado-ao-modelo)")
    (is (= ["fake-1" "e1"] ((juxt :modelo :execucao-ia) i)))
    (is (= (get fim "execucao-id") (str (:execucao-id i))) "liga as chamadas de ferramenta da execucao")
    (is (= (get fim "conversa-id") (str (:conversa-id i))))
    (is (re-matches #"[0-9a-f]{64}" (:conteudo-sha256 i)))
    (is (logic-ia/conferir-interacao i) "o hash recalculado sobre a linha lida confere com o gravado")
    (testing "adulterar um campo quebra a conferencia"
      (is (not (logic-ia/conferir-interacao (assoc i :pergunta "outra coisa")))))))

(deftest a-pergunta-sem-resposta-tambem-fica
  (let [ente (random-uuid)
        iid (pessoa! ente "vereador")
        evs (eventos (:body (perguntar (servico (ia (atom []) :fora? true)) ente iid {:pergunta "pauta de amanha?"})))
        i (interacao-do-fim ente evs)]
    (is (= ["indisponivel" "fim"] (map first evs)))
    (is (= ["indisponivel" nil "vereador"] ((juxt :desfecho :resposta :publico) i)))
    (is (logic-ia/conferir-interacao i))))

(deftest a-conversa-continua-so-com-quem-a-comecou
  (let [ente (random-uuid)
        iid (pessoa! ente "secretario")
        svc (servico (ia (atom [])))
        evs1 (eventos (:body (perguntar svc ente iid {:pergunta "primeira?"})))
        conversa (get-in (last evs1) [1 "conversa-id"])
        evs2 (eventos (:body (perguntar svc ente iid {:pergunta "segunda?" :conversa conversa})))]
    (is (= conversa (get-in (last evs2) [1 "conversa-id"])) "a segunda pergunta entra na mesma conversa")
    (is (not= (get-in (last evs1) [1 "interacao-id"]) (get-in (last evs2) [1 "interacao-id"])))
    (testing "conversa de outra pessoa da mesma Casa: 400, e nada chega a IA"
      (let [pedidos (atom [])]
        (is (= 400 (:status (perguntar (servico (ia pedidos)) ente (pessoa! ente "secretario")
                                       {:pergunta "e agora?" :conversa conversa}))))
        (is (empty? @pedidos))))
    (testing "a mesma pessoa, com vinculo em outra Casa, nao alcanca a conversa de la'"
      (let [outra (random-uuid)]
        (tenancy/com-tenant* *ds* outra
          (fn [tx]
            (vinc/criar! tx {:id (random-uuid) :ente-id outra :identidade-id iid :tipo "servidor"})
            (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id outra :identidade-id iid :papel "secretario"})))
        (is (= 400 (:status (perguntar svc outra iid {:pergunta "e agora?" :conversa conversa}))))))
    (testing "conversa que nao e' uuid: 400"
      (is (= 400 (:status (perguntar svc ente iid {:pergunta "e agora?" :conversa "abc"}))))
      (is (= 400 (:status (perguntar svc ente iid {:pergunta "e agora?" :conversa 12})))))))

(deftest sem-historico-a-resposta-nao-sai
  ;; ADR-0024 item 5: falha fechada — resposta de IA sem registro nao existe
  (let [ente (random-uuid)
        iid (pessoa! ente "secretario")
        quebrado #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify repo-ia/RepoIntegracaoIA
          (propostas-da-execucao [_ _ _] [])
          (registrar-interacao-assistente! [_ _] (throw (ex-info "banco fora" {}))))]
    (doseq [[rotulo svc] [["o banco falha ao gravar" (servico (ia (atom [])) :repo-integracao-ia quebrado)]
                          ["nao ha repositorio" (servico (ia (atom [])) :sem-historico? true)]]]
      (testing rotulo
        (let [r (perguntar svc ente iid {:pergunta "Qual a situacao do PL 12/2026?"})
              evs (eventos (:body r))]
          (is (= 200 (:status r)))
          (is (= ["indisponivel" "fim"] (map first evs)))
          (is (not (str/includes? (:body r) "Merenda")) "nada da resposta chega a tela")
          (is (nil? (get-in (last evs) [1 "interacao-id"]))))))))

(deftest o-historico-e-so-de-insercao-e-isolado-por-casa
  (let [ente (random-uuid)
        iid (pessoa! ente "secretario")
        evs (eventos (:body (perguntar (servico (ia (atom []))) ente iid {:pergunta "Qual a situacao do PL 12/2026?"})))
        id (parse-uuid (get-in (last evs) [1 "interacao-id"]))]
    (testing "UPDATE e DELETE sao recusados pelo banco"
      (is (thrown? Exception (tenancy/com-tenant* *ds* ente
                               #(jdbc/execute! % ["UPDATE integracao_ia.interacao_assistente SET pergunta = 'x' WHERE id = ?" id]))))
      (is (thrown? Exception (tenancy/com-tenant* *ds* ente
                               #(jdbc/execute! % ["DELETE FROM integracao_ia.interacao_assistente WHERE id = ?" id])))))
    (testing "outra Casa nao ve a linha"
      (is (nil? (repo-ia/interacao-assistente (repo-integracao) (random-uuid) id)))
      (is (some? (repo-ia/interacao-assistente (repo-integracao) ente id))))))

(deftest a-trilha-ancora-o-hash-da-interacao-sem-o-conteudo
  ;; ADR-0024 item 4, pela borda com a trilha de verdade entre os globais (como o host monta): a entrada do POST aponta
  ;; a interacao e carrega o hash; a pergunta e a resposta nao entram na trilha
  (let [ente (random-uuid)
        iid (pessoa! ente "secretario")
        ra (repo-auditoria/map->RepoAuditoriaPg {:datasource {:ds *ds*}})
        auth (it/autenticacao (idp-dev/idp-dev) (repo))
        svc (-> (http/servico (config/carregar)
                              (auditoria-http/com-tentativa
                               (agente/rotas {:auth auth :repo-identidade (repo) :ia (ia (atom []))
                                              :repo-integracao-ia (repo-integracao)}))
                              (it/globais-com [(auditoria-http/interceptor ra {:ancorar! (fn [_ _])})]))
                ph/create-server ::ph/service-fn)
        evs (eventos (:body (perguntar svc ente iid {:pergunta "Qual a situacao do PL 12/2026?"})))
        i (interacao-do-fim ente evs)
        linhas (tenancy/com-tenant* *ds* ente
                 #(jdbc/execute! % ["SELECT classe, decisao, recurso_tipo, recurso_id, detalhe::text AS detalhe
                                       FROM auditoria.registro WHERE ente_id = ? ORDER BY seq" ente]
                                 {:builder-fn rs/as-unqualified-maps}))
        desfecho (last linhas)]
    (is (= ["escrita" "permitido" "interacao_assistente" (str (:id i))]
           ((juxt :classe :decisao :recurso_tipo :recurso_id) desfecho)))
    (is (= (:conteudo-sha256 i) (get (json/read-value (:detalhe desfecho)) "conteudo-sha256")))
    (is (not-any? #(re-find #"(?i)situacao do PL|Merenda" (pr-str %)) linhas) "a trilha segue sem conteudo")))

;; ---------- ADR-0024 fatia 2: ler o historico ----------

(defn- ler [svc ente iid caminho]
  (let [r (pt/response-for svc :get caminho
                           :headers {"Authorization" (str "Bearer " (json/write-value-as-string
                                                                    {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))})]
    (assoc r :json (when (= 200 (:status r)) (json/read-value (:body r) json/keyword-keys-object-mapper)))))

(defn- com-trilha
  "O servico com a trilha de verdade entre os globais (como o host monta)."
  []
  (let [ra (repo-auditoria/map->RepoAuditoriaPg {:datasource {:ds *ds*}})
        auth (it/autenticacao (idp-dev/idp-dev) (repo))]
    (-> (http/servico (config/carregar)
                      (auditoria-http/com-tentativa
                       (agente/rotas {:auth auth :repo-identidade (repo) :ia (ia (atom []))
                                      :repo-integracao-ia (repo-integracao)}))
                      (it/globais-com [(auditoria-http/interceptor ra {:ancorar! (fn [_ _])})]))
        ph/create-server ::ph/service-fn)))

(defn- leituras-sensiveis [ente]
  (tenancy/com-tenant* *ds* ente
    #(jdbc/execute! % ["SELECT identidade_id, recurso_tipo, recurso_id FROM auditoria.registro
                         WHERE ente_id = ? AND classe = 'leitura_sensivel' ORDER BY seq" ente]
                    {:builder-fn rs/as-unqualified-maps})))

(deftest cada-pessoa-le-o-proprio-historico-o-mais-recente-primeiro
  (let [ente (random-uuid)
        ana (pessoa! ente "secretario")
        rui (pessoa! ente "vereador")
        svc (servico (ia (atom [])))]
    (perguntar svc ente ana {:pergunta "primeira da Ana?"})
    (perguntar svc ente ana {:pergunta "segunda da Ana?"})
    (perguntar svc ente rui {:pergunta "do Rui?"})
    (let [{:keys [status json]} (ler svc ente ana "/agente/historico")]
      (is (= 200 status))
      (is (= ["segunda da Ana?" "primeira da Ana?"] (mapv :pergunta (:interacoes json))))
      (is (= [1 0 "resposta"] ((juxt :n-fontes :n-propostas :desfecho) (first (:interacoes json)))))
      (is (nil? (:nome (first (:interacoes json)))) "a propria pessoa nao precisa do nome")
      (is (false? (:mais json))))
    (testing "paginar pelo instante"
      (let [p1 (:json (ler svc ente ana "/agente/historico?limite=1"))
            p2 (:json (ler svc ente ana (str "/agente/historico?limite=1&antes=" (:antes p1))))]
        (is (= ["segunda da Ana?"] (mapv :pergunta (:interacoes p1))))
        (is (true? (:mais p1)))
        (is (= ["primeira da Ana?"] (mapv :pergunta (:interacoes p2))))
        (is (false? (:mais p2)))))
    (testing "o vereador ve so' o dele"
      (is (= ["do Rui?"] (mapv :pergunta (:interacoes (:json (ler svc ente rui "/agente/historico")))))))))

(deftest a-conversa-guardada-volta-inteira-e-conferida
  (let [ente (random-uuid)
        ana (pessoa! ente "secretario")
        svc (servico (ia (atom [])))
        conversa (get-in (last (eventos (:body (perguntar svc ente ana {:pergunta "primeira?"})))) [1 "conversa-id"])
        _ (perguntar svc ente ana {:pergunta "segunda?" :conversa conversa})
        {:keys [status json]} (ler svc ente ana (str "/agente/conversas/" conversa))]
    (is (= 200 status))
    (is (= ["primeira?" "segunda?"] (mapv :pergunta (:interacoes json))))
    (is (= "Pessoa" (:nome json)))
    (is (every? true? (map :integra (:interacoes json))) "cada pergunta confere com o hash gravado")
    (is (= "conferida" (get-in json [:interacoes 0 :resposta :citacoes 0 :status])))
    (is (= ["fake-1" "e1"] ((juxt :modelo :execucao-ia) (first (:interacoes json)))))
    (is (re-matches #"[0-9a-f]{64}" (:conteudo-sha256 (first (:interacoes json)))))
    (is (nil? (:ente-id (first (:interacoes json)))))))

(deftest quem-nao-e-auditor-nao-le-o-historico-de-outra-pessoa
  (let [ente (random-uuid)
        ana (pessoa! ente "secretario")
        bia (pessoa! ente "secretario")
        svc (servico (ia (atom [])))
        conversa (get-in (last (eventos (:body (perguntar svc ente ana {:pergunta "da Ana?"})))) [1 "conversa-id"])]
    (is (= 403 (:status (ler svc ente bia (str "/agente/historico?pessoa=" ana)))))
    (is (= 403 (:status (ler svc ente bia "/agente/historico?escopo=casa"))))
    (is (= 404 (:status (ler svc ente bia (str "/agente/conversas/" conversa)))) "nem a existencia vaza")
    (is (= 404 (:status (ler svc ente bia (str "/agente/conversas/" (random-uuid))))))
    (testing "outra Casa nao alcanca a conversa, nem a propria dona por la'"
      (let [outra (random-uuid)]
        (is (= 404 (:status (ler svc outra (pessoa! outra "auditor") (str "/agente/conversas/" conversa)))))))
    (testing "sem papel que alcance a Clara: 403"
      (is (= 403 (:status (ler svc ente (pessoa! ente) "/agente/historico")))))))

(deftest o-auditor-le-o-historico-da-casa-e-a-leitura-vai-a-trilha
  (let [ente (random-uuid)
        ana (pessoa! ente "secretario")
        rui (pessoa! ente "vereador")
        aud (pessoa! ente "auditor")
        svc (com-trilha)
        conversa (get-in (last (eventos (:body (perguntar svc ente ana {:pergunta "da Ana?"})))) [1 "conversa-id"])]
    (perguntar svc ente rui {:pergunta "do Rui?"})
    (let [casa (:json (ler svc ente aud "/agente/historico?escopo=casa"))
          da-ana (:json (ler svc ente aud (str "/agente/historico?pessoa=" ana)))
          conv (ler svc ente aud (str "/agente/conversas/" conversa))]
      (is (= #{"da Ana?" "do Rui?"} (set (map :pergunta (:interacoes casa)))))
      (is (every? #(= "Pessoa" (:nome %)) (:interacoes casa)) "o auditor ve quem perguntou")
      (is (= ["da Ana?"] (mapv :pergunta (:interacoes da-ana))))
      (is (= 200 (:status conv)))
      (is (true? (get-in conv [:json :interacoes 0 :integra]))))
    (is (= [[aud "historico_assistente" "casa"]
            [aud "historico_assistente" (str ana)]
            [aud "conversa_assistente" conversa]]
           (mapv (juxt :identidade_id :recurso_tipo :recurso_id) (leituras-sensiveis ente)))
        "cada leitura do auditor sobre o historico de outra pessoa entra na trilha")
    (testing "a pessoa lendo o proprio historico nao e' leitura sensivel"
      (ler svc ente ana "/agente/historico")
      (ler svc ente ana (str "/agente/conversas/" conversa))
      (is (= 3 (count (leituras-sensiveis ente)))))))

(deftest historico-recusa-parametro-torto
  (let [ente (random-uuid)
        ana (pessoa! ente "secretario")
        svc (servico (ia (atom [])))]
    (doseq [q ["?limite=0" "?limite=51" "?limite=abc" "?antes=ontem" "?pessoa=abc"]]
      (is (= 400 (:status (ler svc ente ana (str "/agente/historico" q)))) q))
    (is (= 400 (:status (ler svc ente ana "/agente/conversas/nao-e-uuid"))))))

;; ---------- ADR-0024: buscar no historico ----------

(defn- ia-com-texto
  "O satelite falso cuja resposta depende da pergunta (`texto-de` pergunta -> texto)."
  [texto-de]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify plataforma-ia/PlataformaIA
    (executar-agente [_ _ pedido]
      (assoc-in resposta-ia [:resposta :texto] (texto-de (:pergunta pedido))))))

(defn- perguntas-de [svc ente iid caminho]
  (let [{:keys [status json]} (ler svc ente iid caminho)]
    (is (= 200 status) caminho)
    (mapv :pergunta (:interacoes json))))

(defn- q [s] (java.net.URLEncoder/encode ^String s "UTF-8"))

(deftest buscar-no-historico-pela-pergunta-e-pela-resposta
  (let [ente (random-uuid)
        ana (pessoa! ente "secretario")
        svc (servico (ia-com-texto {"Qual a Situação do PL 12/2026?" "Está em comissão."
                                    "E a pauta de amanhã?" "A pauta traz a Merenda escolar."
                                    "Quanto é 100% do teto?" "Não sei."
                                    "O que é 100 do teto?" "Também não sei."}))]
    (doseq [p ["Qual a Situação do PL 12/2026?" "E a pauta de amanhã?" "Quanto é 100% do teto?" "O que é 100 do teto?"]]
      (perguntar svc ente ana {:pergunta p}))
    (testing "pela pergunta, sem caixa nem acento"
      (is (= ["Qual a Situação do PL 12/2026?"] (perguntas-de svc ente ana "/agente/historico?q=situacao")))
      (is (= ["Qual a Situação do PL 12/2026?"] (perguntas-de svc ente ana (str "/agente/historico?q=" (q "SITUAÇÃO"))))))
    (testing "pelo texto da resposta"
      (is (= ["E a pauta de amanhã?"] (perguntas-de svc ente ana "/agente/historico?q=merenda")))
      (is (= ["Qual a Situação do PL 12/2026?"] (perguntas-de svc ente ana (str "/agente/historico?q=" (q "esta em comissao")))))
      (is (= ["Qual a Situação do PL 12/2026?"] (perguntas-de svc ente ana (str "/agente/historico?q=" (q "  EM COMISSÃO  "))))
          "o q e' aparado"))
    (testing "% e _ sao literais, nunca curinga"
      (is (= ["Quanto é 100% do teto?"] (perguntas-de svc ente ana (str "/agente/historico?q=" (q "100%")))))
      (is (= [] (perguntas-de svc ente ana (str "/agente/historico?q=" (q "100_do")))))
      (is (= [] (perguntas-de svc ente ana (str "/agente/historico?q=" (q "%%"))))))
    (testing "em branco = sem busca"
      (is (= 4 (count (perguntas-de svc ente ana "/agente/historico?q=%20%20")))))
    (testing "a busca pagina como a lista"
      (let [p1 (:json (ler svc ente ana "/agente/historico?q=teto&limite=1"))
            p2 (:json (ler svc ente ana (str "/agente/historico?q=teto&limite=1&antes=" (:antes p1))))]
        (is (= ["O que é 100 do teto?"] (mapv :pergunta (:interacoes p1))))
        (is (true? (:mais p1)))
        (is (= ["Quanto é 100% do teto?"] (mapv :pergunta (:interacoes p2))))
        (is (false? (:mais p2)))))))

(deftest a-busca-respeita-de-quem-e-o-historico
  (let [ente (random-uuid)
        ana (pessoa! ente "secretario")
        rui (pessoa! ente "vereador")
        aud (pessoa! ente "auditor")
        svc (com-trilha)]
    (perguntar svc ente ana {:pergunta "Ana: pauta da sessão?"})
    (perguntar svc ente ana {:pergunta "Ana: outra coisa?"})
    (perguntar svc ente rui {:pergunta "Rui: pauta da sessão?"})
    (testing "a pessoa busca so' no proprio historico"
      (is (= ["Rui: pauta da sessão?"] (perguntas-de svc ente rui "/agente/historico?q=sessao"))))
    (testing "o auditor busca na Casa e no historico de uma pessoa"
      (is (= #{"Ana: pauta da sessão?" "Rui: pauta da sessão?"}
             (set (perguntas-de svc ente aud "/agente/historico?escopo=casa&q=sessao"))))
      (is (= ["Ana: pauta da sessão?"] (perguntas-de svc ente aud (str "/agente/historico?q=sessao&pessoa=" ana)))))
    (testing "buscar nao abre o historico de outra pessoa a quem nao e' auditor"
      (is (= 403 (:status (ler svc ente rui (str "/agente/historico?q=sessao&pessoa=" ana)))))
      (is (= 403 (:status (ler svc ente rui "/agente/historico?q=sessao&escopo=casa")))))
    (is (= [[aud "historico_assistente" "casa"]
            [aud "historico_assistente" (str ana)]]
           (mapv (juxt :identidade_id :recurso_tipo :recurso_id) (leituras-sensiveis ente)))
        "a busca do auditor sobre outra pessoa (ou a Casa) segue indo a' trilha; a da pessoa no proprio, nao")))

(deftest buscar-no-historico-recusa-q-torto
  (let [ente (random-uuid)
        ana (pessoa! ente "secretario")
        svc (servico (ia (atom [])))]
    (doseq [s ["a" " a " (apply str (repeat 101 "a"))]]
      (is (= 400 (:status (ler svc ente ana (str "/agente/historico?q=" (q s))))) (pr-str s)))
    (is (= 200 (:status (ler svc ente ana (str "/agente/historico?q=" (q (apply str (repeat 100 "a"))))))) "100 cabe")
    (is (= 200 (:status (ler svc ente ana "/agente/historico?q=ab"))) "2 cabe")))

;; ---------- fatia 4: a Clara do juridico, do controle interno e do administrador (publico :consulta) ----------

(def ^:private leituras-de-todos
  ["ata_da_sessao" "buscar_dispositivos" "ler_caixa" "ler_comunicado" "ler_dispositivo" "ler_leitura_do_comunicado"
   "pauta_da_sessao" "vereadores_da_casa"])

(def ^:private matriz-da-consulta
  "O que a Clara de consulta oferece a cada papel: so' leitura, e so' o que a tela dele ja' le."
  {"juridico" (vec (sort (into leituras-de-todos ["contas_da_casa" "pareceres_juridicos_da_materia"
                                                  "prestacao_de_contas" "situacao_da_materia"
                                                  "tramitacao_da_materia"])))
   "auditor" leituras-de-todos
   "admin_ente" leituras-de-todos})

(defn- ia-que-olha
  "O satelite falso que, como o de verdade, resolve a credencial da execucao e ve o que o core lhe oferece."
  [visto]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify plataforma-ia/PlataformaIA
    (executar-agente [_ _ {:keys [credencial]}]
      (let [a (auten/resolver-agente (repo) credencial)]
        (reset! visto {:credencial credencial :publico (get-in a [:via :publico]) :classes (get-in a [:via :classes])
                       :ferramentas (mapv :nome (catalogo/ferramentas a))}))
      resposta-ia)))

(deftest juridico-auditor-e-admin-perguntam-a-clara-so-para-ler
  (doseq [[papel esperadas] matriz-da-consulta]
    (testing papel
      (let [ente (random-uuid)
            iid (pessoa! ente papel)
            visto (atom nil)
            r (perguntar (servico (ia-que-olha visto)) ente iid {:pergunta "Qual a situacao do PL 12/2026?"})
            evs (eventos (:body r))
            i (interacao-do-fim ente evs)]
        (is (= 200 (:status r)))
        (is (= ["passo" "resposta" "fim"] (map first evs)) "a resposta da IA chega a tela")
        (is (= {:publico :consulta :classes #{:leitura}} (select-keys @visto [:publico :classes]))
            "a credencial de consulta so' le: nada de ato, nem como proposta")
        (is (= esperadas (:ferramentas @visto)))
        (is (= [iid "consulta" "resposta"] ((juxt :identidade-id :publico :desfecho) i)) "e vai ao historico")
        (is (logic-ia/conferir-interacao i))
        (is (nil? (auten/resolver-agente (repo) (:credencial @visto))) "a credencial morre ao fim")))))

(deftest quem-tem-tambem-a-secretaria-escolhe-a-consulta-e-fica-sem-ato
  (let [ente (random-uuid)
        iid (pessoa! ente "secretario" "juridico")
        visto (atom nil)
        svc (servico (ia-que-olha visto))]
    (perguntar svc ente iid {:pergunta "oi?"})
    (is (= {:publico :secretaria :classes #{:leitura :ato}} (select-keys @visto [:publico :classes]))
        "sem escolher, a secretaria")
    (perguntar svc ente iid {:pergunta "oi?" :publico "consulta"})
    (is (= {:publico :consulta :classes #{:leitura}} (select-keys @visto [:publico :classes])))
    (is (not-any? #{"pedir_parecer_juridico" "encaminhar_as_comissoes" "designar_relator" "enviar_comunicado"}
                  (:ferramentas @visto)))))

(deftest juridico-e-admin-leem-o-proprio-historico-e-so-ele
  (let [ente (random-uuid)
        ana (pessoa! ente "secretario")
        svc (com-trilha)
        conversa-da-ana (get-in (last (eventos (:body (perguntar svc ente ana {:pergunta "da Ana?"})))) [1 "conversa-id"])]
    (doseq [papel ["juridico" "admin_ente"]]
      (testing papel
        (let [eu (pessoa! ente papel)
              minha (get-in (last (eventos (:body (perguntar svc ente eu {:pergunta (str "do " papel "?")}))))
                            [1 "conversa-id"])]
          (is (= [(str "do " papel "?")] (mapv :pergunta (:interacoes (:json (ler svc ente eu "/agente/historico"))))))
          (let [c (ler svc ente eu (str "/agente/conversas/" minha))]
            (is (= 200 (:status c)))
            (is (= "consulta" (get-in c [:json :interacoes 0 :publico]))))
          (is (= 403 (:status (ler svc ente eu "/agente/historico?escopo=casa"))) "o da Casa e' so' do auditor")
          (is (= 403 (:status (ler svc ente eu (str "/agente/historico?pessoa=" ana)))))
          (is (= 404 (:status (ler svc ente eu (str "/agente/conversas/" conversa-da-ana)))) "nem a existencia vaza"))))
    (is (empty? (leituras-sensiveis ente)) "ler o proprio historico nao e' leitura sensivel")))

(deftest o-auditor-sem-escopo-le-o-proprio-historico
  (let [ente (random-uuid)
        ana (pessoa! ente "secretario")
        aud (pessoa! ente "auditor")
        svc (com-trilha)]
    (perguntar svc ente ana {:pergunta "da Ana?"})
    (let [minha (get-in (last (eventos (:body (perguntar svc ente aud {:pergunta "do auditor?"})))) [1 "conversa-id"])]
      (is (= ["do auditor?"] (mapv :pergunta (:interacoes (:json (ler svc ente aud "/agente/historico"))))))
      (is (= 200 (:status (ler svc ente aud (str "/agente/conversas/" minha)))))
      (is (empty? (leituras-sensiveis ente)) "o proprio historico, sem a marca")
      (is (= #{"da Ana?" "do auditor?"}
             (set (map :pergunta (:interacoes (:json (ler svc ente aud "/agente/historico?escopo=casa")))))))
      (is (= [[aud "historico_assistente" "casa"]]
             (mapv (juxt :identidade_id :recurso_tipo :recurso_id) (leituras-sensiveis ente)))
          "o escopo da Casa segue so' do auditor, e com a marca"))))
