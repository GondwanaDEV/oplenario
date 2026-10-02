(ns oplenario.admin-sistema.encerrar-casa-test
  "INTEGRACAO (PG real, cadeia real de `rotas/montar`): ENCERRAR uma Casa (ADR-0018 fatia 2, Eixos 4 e 5).

  A sequencia inteira, com fakes nos dois seams do plano de dados (`:exportar-casa`, `:apagar-casa`) e o relogio
  controlado: a exportacao (o admin_ente gera quando quiser; o operador, so' no encerramento; uma por vez), o download
  so' pelo admin_ente da propria Casa, a confirmacao de recebimento (uma vez; ou por oficio), a janela de guarda de 90
  dias, o apagamento two-person (falha -> retomar), a Casa `encerrado` imutavel e o 410 nas rotas da Casa e do portal."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.admin-sistema.components.exportacao :as exportacao]
            [oplenario.admin-sistema.components.idp-admin :as idp-admin]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.admin-sistema.db.atuacao :as atuacao]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.components.objeto-store :as os]
            [oplenario.kernel.segredo :as segredo]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas])
  (:import (java.time Duration Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-op [] (assoc (repo/repositorio) :datasource {:ds *ds*}))

;; ---- a Casa: o administrador (admin_ente) e uma secretaria (sem o papel) ----

(def admin (random-uuid))
(def secretaria (random-uuid))

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ iid]
      (cond (= iid admin) {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis #{"admin_ente"}}
            (= iid secretaria) {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis #{"secretario"}}))))

;; ---- o object storage e os dois seams do plano de dados (fakes) ----

(def guardados (atom {}))

(def store
  (reify os/ObjetoStore
    (guardar! [_ k b _] (swap! guardados assoc k b) k)
    (guardar-stream! [_ k in _] (swap! guardados assoc k (.readAllBytes ^java.io.InputStream in)) k)
    (obter [_ k] (get @guardados k))
    (abrir [_ k] (some-> (get @guardados k) java.io.ByteArrayInputStream.))
    (remover! [_ k] (swap! guardados dissoc k) nil)))

(defn- exportar-fake
  "Grava um 'ZIP' no store e devolve o que o seam real devolve."
  [ente-id exportacao-id]
  (let [conteudo (str "PK-exportacao-" ente-id "-" exportacao-id)
        chave (str "exportacoes/" ente-id "/" exportacao-id ".zip")]
    (swap! guardados assoc chave (.getBytes conteudo "UTF-8"))
    {:chave chave :sha256 (segredo/sha256-hex conteudo) :bytes (count conteudo)
     :manifesto {:versao "1" :tabelas {:legislativo.proposicao 3 :sessoes.sessao 2} :arquivos ["a.pdf" "b.pdf"]
                 :linhas-total 5}}))

(def resumo-do-apagamento
  {:tabelas {:legislativo.proposicao 3 :sessoes.sessao 2} :linhas-total 5 :objetos 7 :realm-apagado? true
   :ia {:documentos 4} :exportacoes-apagadas 2})

(defn- apagar-que-falha-uma-vez
  "O apagamento que para no meio na 1a chamada (o realm nao respondeu) e termina na 2a — retomavel."
  []
  (let [chamadas (atom [])]
    {:chamadas chamadas
     :apagar (fn [ente-id pedido-id]
               (swap! chamadas conj [ente-id pedido-id])
               (if (= 1 (count @chamadas))
                 (throw (ex-info "o Keycloak nao respondeu ao apagar o realm" {}))
                 resumo-do-apagamento))}))

(defn- servico
  "O sistema de verdade (`rotas/montar`), no relogio `agora`, com os seams dados (nil = indisponivel)."
  [{:keys [agora exportar apagar executar]
    :or {exportar exportar-fake executar exportacao/agora-mesmo}}]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-identidade)
                                   :idp-operacao (idp-admin/idp-operacao-dev)
                                   :repo-admin-sistema (repo-op)
                                   :objeto-store store
                                   :relogio (tempo/relogio-fixo (or agora (Instant/now)))
                                   :cache-estado-da-casa-ms 0
                                   :info-ente (constantly {:nome-oficial "Câmara Municipal de Baturité"})
                                   :exportar-casa exportar
                                   :apagar-casa apagar
                                   :executar-exportacao executar
                                   :operacao {:realm "operacao" :client-id "oplenario-console"
                                              :sessao {:absoluta-h 8 :ociosa-min 15}}})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- operador! [nome]
  (repo/criar-operador! (repo-op) {:id (random-uuid) :email (str "op-" (random-uuid) "@oplenario.dev") :nome nome}))

(defn- como-operador [o] {"authorization" (str "Bearer " (json/write-value-as-string {:operador-id (str (:id o))}))
                          "Content-Type" "application/json"})

(defn- como-casa [ente iid]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str iid)}))
   "Content-Type" "application/json"})

(defn- casa-ativa! [o]
  (let [ente (random-uuid)]
    (repo/registrar-casa! (repo-op) {:ente-id ente :nome "Câmara Municipal de Baturité" :uf "CE"
                                     :municipio-ibge "2302008" :municipio-nome "Baturité"}
                          {:operador-id (:id o)})
    (jdbc/with-transaction [tx *ds*] (repo/ativar-casa-em-tx! tx ente {}))
    ente))

(defn- post-op [svc o caminho corpo]
  (pt/response-for svc :post caminho :headers (como-operador o) :body (json/write-value-as-string corpo)))

(defn- post-casa [svc ente iid caminho corpo]
  (pt/response-for svc :post caminho :headers (como-casa ente iid) :body (json/write-value-as-string corpo)))

(defn- get-casa [svc ente iid caminho] (pt/response-for svc :get caminho :headers (como-casa ente iid)))

(defn- ficha [svc o ente] (ler (pt/response-for svc :get (str "/operacao/casas/" ente) :headers (como-operador o))))

(defn- encerramento-em-curso!
  "Ana pede o encerramento, Beto aprova: a Casa fica suspensa com o encerramento em curso."
  [svc ana beto ente]
  (let [p (get-in (ler (post-op svc ana (str "/operacao/casas/" ente "/encerramento")
                                {:origem "fim_de_contrato" :justificativa "Contrato 7/2025 encerrado sem renovacao."}))
                  [:pedido :id])]
    (post-op svc beto (str "/operacao/pedidos/" p "/aprovacao") {})))

(defn- eventos-da-casa [ente]
  (mapv (juxt :outbox/tipo #(some-> ^org.postgresql.util.PGobject (:outbox/payload %) .getValue
                                    (json/read-value json/keyword-keys-object-mapper)))
        (jdbc/execute! *ds* ["SELECT tipo, payload FROM shared.outbox WHERE ente_id = ? AND tipo LIKE 'admin_sistema.%' ORDER BY id"
                             ente])))

(deftest a-exportacao-e-do-admin-ente-da-propria-casa
  (let [svc (servico {}) ana (operador! "Ana") ente (casa-ativa! ana) outra (casa-ativa! ana)]
    (testing "o bloco do admin_ente: disponivel, sem encerramento, nada gerado ainda"
      (let [r (get-casa svc ente admin "/administracao/exportacoes")]
        (is (= 200 (:status r)) (:body r))
        (is (= {:disponivel true :em-encerramento false :exportacoes []} (ler r)))))
    (testing "o admin_ente gera a qualquer momento (portabilidade): 202, e o executor fecha `pronta`"
      (let [r (post-casa svc ente admin "/administracao/exportacoes" {}) b (ler r)]
        (is (= 202 (:status r)) (:body r))
        (is (= "pronta" (:estado b)))
        (is (= "admin_ente" (:solicitada-por b)))
        (is (re-matches #"[0-9a-f]{64}" (:sha256 b)))
        (is (= {:versao "1" :tabelas {:itens 2} :arquivos {:itens 2} :linhas-total 5} (:manifesto b))
            "o manifesto sai resumido: colecoes viram contagem")
        (is (not (contains? b :chave)) "a chave do object storage nao sai")))
    (let [exp (first (:exportacoes (ler (get-casa svc ente admin "/administracao/exportacoes"))))
          arquivo (str "/administracao/exportacoes/" (:id exp) "/arquivo")]
      (testing "quem baixa e' o admin_ente da propria Casa, em stream, com o hash no cabecalho"
        (let [r (get-casa svc ente admin arquivo)]
          (is (= 200 (:status r)))
          (is (= "application/zip" (get-in r [:headers "Content-Type"])))
          (is (str/starts-with? (get-in r [:headers "Content-Disposition"]) "attachment; filename=\"exportacao-completa-"))
          (is (= (:sha256 exp) (segredo/sha256-hex (:body r))) "os bytes sao os do hash")))
      (testing "o admin_ente de OUTRA Casa nao enxerga a exportacao (404, nem a existencia vaza)"
        (is (= 404 (:status (get-casa svc outra admin arquivo))))
        (is (= 404 (:status (post-casa svc outra admin (str "/administracao/exportacoes/" (:id exp) "/confirmacao")
                                       {:sha256 (:sha256 exp)}))))
        (is (= [] (:exportacoes (ler (get-casa svc outra admin "/administracao/exportacoes"))))))
      (testing "quem nao e' admin_ente da Casa nao baixa (403); o operador nao abre rota de Casa (401)"
        (is (= 403 (:status (get-casa svc ente secretaria arquivo))))
        (is (= 401 (:status (pt/response-for svc :get arquivo :headers (como-operador ana))))))
      (testing "o operador nao tem rota de download: ve so' metadado na ficha"
        (let [f (ficha svc ana ente)]
          (is (= (:sha256 exp) (get-in f [:encerramento :exportacoes 0 :sha256])))
          (is (nil? (get-in f [:encerramento :confirmacao])) "fora do encerramento, nao ha' confirmacao que valha")))
      (testing "fora do encerramento, a Operacao nao manda gerar (quem gera e' a Casa)"
        (let [r (post-op svc ana (str "/operacao/casas/" ente "/exportacoes") {})]
          (is (= 409 (:status r)))
          (is (= "fora-do-encerramento" (:causa (ler r)))))))))

(deftest uma-geracao-por-vez-e-a-abandonada-fecha-sozinha
  (let [agora (Instant/now)
        parado (servico {:agora agora :executar (fn [_] nil)})   ; o executor nunca roda: a linha fica `gerando`
        ana (operador! "Ana") ente (casa-ativa! ana)
        r1 (post-casa parado ente admin "/administracao/exportacoes" {})]
    (is (= 202 (:status r1)))
    (is (= "gerando" (:estado (ler r1))))
    (let [r2 (post-casa parado ente admin "/administracao/exportacoes" {})]
      (is (= 409 (:status r2)) "uma geracao por vez")
      (is (= "exportacao-em-andamento" (:causa (ler r2)))))
    (testing "7 h depois, a geracao que morreu com o processo fecha como falha e a nova anda"
      (let [svc (servico {:agora (.plus agora (Duration/ofHours 7))})
            r (post-casa svc ente admin "/administracao/exportacoes" {})
            lista (:exportacoes (ler (get-casa svc ente admin "/administracao/exportacoes")))]
        (is (= 202 (:status r)))
        (is (= "pronta" (:estado (ler r))))
        (is (= ["pronta" "falhou"] (mapv :estado lista)))
        (is (re-find #"interrompido" (:erro (second lista))))))
    (testing "a geracao que falha fecha `falhou` com a mensagem (nunca 500)"
      (let [svc (servico {:exportar (fn [_ _] (throw (ex-info "disco cheio no object storage" {})))})
            r (post-casa svc ente admin "/administracao/exportacoes" {})]
        (is (= 202 (:status r)))
        (is (= "falhou" (:estado (ler r))))
        (is (= "disco cheio no object storage" (:erro (ler r))))))))

(deftest sem-o-plano-de-dados-e-503-nomeado
  (let [svc (servico {:exportar nil :apagar nil}) ana (operador! "Ana") ente (casa-ativa! ana)]
    (let [r (post-casa svc ente admin "/administracao/exportacoes" {})]
      (is (= 503 (:status r)))
      (is (= "exportacao-indisponivel" (:causa (ler r)))))
    (is (false? (:disponivel (ler (get-casa svc ente admin "/administracao/exportacoes")))))
    (let [r (post-op svc ana (str "/operacao/casas/" ente "/apagamento") {:justificativa "Fim da guarda de 90 dias."})]
      (is (= 503 (:status r)))
      (is (= "apagamento-indisponivel" (:causa (ler r)))))))

(deftest o-encerramento-inteiro
  (let [svc (servico {}) ana (operador! "Ana") beto (operador! "Beto") ente (casa-ativa! ana)
        vizinha (casa-ativa! ana)
        {:keys [apagar chamadas]} (apagar-que-falha-uma-vez)
        _ (post-casa svc ente admin "/administracao/exportacoes" {})
        portabilidade (first (:exportacoes (ler (get-casa svc ente admin "/administracao/exportacoes"))))]
    (testing "a confirmacao de antes do encerramento (portabilidade) fica, mas nao abre a guarda"
      (is (= 409 (:status (post-casa svc ente admin (str "/administracao/exportacoes/" (:id portabilidade) "/confirmacao")
                                     {:sha256 (apply str (repeat 64 "0"))})))
          "o codigo tem de ser o desta exportacao")
      (is (= 200 (:status (post-casa svc ente admin (str "/administracao/exportacoes/" (:id portabilidade) "/confirmacao")
                                     {:sha256 (:sha256 portabilidade)}))))
      (let [r (post-casa svc ente admin (str "/administracao/exportacoes/" (:id portabilidade) "/confirmacao")
                         {:sha256 (:sha256 portabilidade)})]
        (is (= 409 (:status r)) "uma vez confirmada, nao se confirma de novo")
        (is (= "ja-confirmada" (:causa (ler r))))))
    (is (= "encerramento_em_curso" (get-in (ler (encerramento-em-curso! svc ana beto ente)) [:casa :restricao :motivo])))
    (let [f (ficha svc ana ente)]
      (is (true? (get-in f [:encerramento :em-curso])))
      (is (nil? (get-in f [:encerramento :confirmacao])) "a confirmacao de antes nao conta")
      (is (false? (get-in f [:encerramento :pode-pedir-apagamento]))))
    (testing "sem confirmacao no encerramento, o apagamento nem se pede"
      (let [r (post-op (servico {:apagar apagar}) ana (str "/operacao/casas/" ente "/apagamento")
                       {:justificativa "Fim da guarda de 90 dias."})]
        (is (= 409 (:status r)))
        (is (= "sem-confirmacao" (:causa (ler r))))))
    (testing "na Casa suspensa, o admin_ente ainda exporta e confirma (allowlist); o operador manda gerar tambem"
      (let [r (post-op svc ana (str "/operacao/casas/" ente "/exportacoes") {})]
        (is (= 202 (:status r)))
        (is (= "operador" (:solicitada-por (ler r)))))
      (is (= 202 (:status (post-casa svc ente admin "/administracao/exportacoes" {}))))
      (is (true? (:em-encerramento (ler (get-casa svc ente admin "/administracao/exportacoes"))))))
    (let [[da-casa do-operador] (:exportacoes (ler (get-casa svc ente admin "/administracao/exportacoes")))
          t0 (Instant/now)
          svc0 (servico {:agora t0 :apagar apagar})]
      (testing "o operador registra a confirmacao que veio por oficio (com o texto), selada"
        (is (= 400 (:status (post-op svc0 ana (str "/operacao/exportacoes/" (:id do-operador) "/oficio") {:texto "curto"}))))
        (let [r (post-op svc0 ana (str "/operacao/exportacoes/" (:id do-operador) "/oficio")
                         {:texto "Oficio 12/2026 da Mesa Diretora: arquivo recebido."})]
          (is (= 200 (:status r)) (:body r))
          (is (= "oficio" (:confirmada-por (ler r))))))
      (testing "o admin_ente confirma a dele, vendo o codigo"
        (let [r (post-casa svc0 ente admin (str "/administracao/exportacoes/" (:id da-casa) "/confirmacao")
                           {:sha256 (str/upper-case (:sha256 da-casa))})]
          (is (= 200 (:status r)) (:body r))
          (is (= "admin_ente" (:confirmada-por (ler r))))))
      (testing "a janela de guarda: antes de 90 dias, 409 nomeado com a data"
        (let [f (ficha svc0 ana ente)
              r (post-op svc0 ana (str "/operacao/casas/" ente "/apagamento") {:justificativa "Fim da guarda de 90 dias."})]
          (is (= (:sha256 da-casa) (get-in f [:encerramento :confirmacao :sha256])) "vale a confirmacao mais recente")
          (is (some? (get-in f [:encerramento :apagamento-possivel-em])))
          (is (= 409 (:status r)))
          (is (= "guarda-em-curso" (:causa (ler r))))
          (is (= (get-in f [:encerramento :apagamento-possivel-em]) (:apagamento-possivel-em (ler r))))
          (is (= 409 (:status (post-op (servico {:agora (.plus t0 (Duration/ofDays 89)) :apagar apagar}) ana
                                       (str "/operacao/casas/" ente "/apagamento")
                                       {:justificativa "Fim da guarda de 90 dias."}))))))
      (testing "o destino do acervo: so' https"
        (is (= 400 (:status (post-op svc0 ana (str "/operacao/casas/" ente "/destino-acervo") {:url "http://camara.ce.gov.br"}))))
        (let [r (post-op svc0 ana (str "/operacao/casas/" ente "/destino-acervo") {:url "https://camarabaturite.ce.gov.br/acervo"})]
          (is (= 200 (:status r)) (:body r))
          (is (= "https://camarabaturite.ce.gov.br/acervo" (get-in (ler r) [:casa :destino-acervo-url])))))
      (let [svc91 (servico {:agora (.plus t0 (Duration/ofDays 91)) :apagar apagar})
            r (post-op svc91 ana (str "/operacao/casas/" ente "/apagamento") {:justificativa "Fim da guarda de 90 dias."})
            pedido (get-in (ler r) [:pedido :id])]
        (testing "depois da guarda: Ana pede, e ela mesma nao aprova"
          (is (= 200 (:status r)) (:body r))
          (is (= "apagar" (get-in (ler r) [:pedido :acao])))
          (is (true? (get-in (ficha svc91 ana ente) [:encerramento :pode-pedir-apagamento])))
          (let [r (post-op svc91 ana (str "/operacao/pedidos/" pedido "/aprovacao") {})]
            (is (= 409 (:status r)))
            (is (re-find #"outro operador" (:erro (ler r))))))
        (testing "Beto aprova: o apagamento para no meio — o pedido fica aprovado e o console oferece retomar"
          (let [r (post-op svc91 beto (str "/operacao/pedidos/" pedido "/aprovacao") {}) b (ler r)]
            (is (= 200 (:status r)) (:body r))
            (is (= "apagamento-interrompido" (:efeito b)))
            (is (re-find #"Keycloak" (:erro b)))
            (is (= "suspenso" (get-in b [:casa :estado])))
            (is (= "aprovado" (get-in b [:pedido :estado]))))
          (is (= [[ente (parse-uuid pedido)]] @chamadas) "o seam recebe a Casa e o pedido aprovado")
          (is (= pedido (get-in (ficha svc91 ana ente) [:encerramento :apagamento-pendente :id])))
          (let [r (post-op svc91 ana (str "/operacao/casas/" ente "/reativacao") {:justificativa "Tentando voltar atras."})]
            (is (= 409 (:status r)) "o apagamento aprovado nao tem volta")
            (is (= "apagamento-aprovado" (:causa (ler r))))))
        (testing "retomar: termina, e a Casa vira `encerrado` com o resumo e o hash da exportacao entregue"
          (let [r (post-op svc91 ana (str "/operacao/casas/" ente "/apagamento/retomada") {}) b (ler r)]
            (is (= 200 (:status r)) (:body r))
            (is (= "encerrada" (:efeito b)))
            (is (= "encerrado" (get-in b [:casa :estado])))
            (is (some? (get-in b [:casa :encerrada-em])))
            (is (nil? (get-in b [:casa :restricao]))))
          (let [f (ficha svc91 ana ente)
                ap (get-in f [:encerramento :apagamento])]
            (is (= "encerrado" (get-in f [:casa :estado])))
            (is (= 5 (:linhas-total ap)))
            (is (= 7 (:objetos ap)))
            (is (= {:legislativo.proposicao 3 :sessoes.sessao 2} (:tabelas ap)))
            (is (= (:sha256 da-casa) (get-in ap [:exportacao :sha256])))
            (is (= "admin_ente" (get-in ap [:exportacao :confirmada-por])))
            (is (nil? (get-in f [:encerramento :apagamento-pendente])))
            (is (= ["casa-encerrada" "apagamento-interrompido" "apagamento-aprovado" "apagamento-pedido"]
                   (take 4 (map :acao (:atuacao f)))))
            (is (= ["legislativo.proposicao=3" "sessoes.sessao=2"]
                   (get-in (first (:atuacao f)) [:detalhe :tabelas])) "selado com a lista de tabelas e os totais"))
          (let [[tipo payload] (last (eventos-da-casa ente))]
            (is (= "admin_sistema.casa.encerrada" tipo))
            (is (= (:sha256 da-casa) (:exportacao-sha256 payload))))
          (is (= 409 (:status (post-op svc91 ana (str "/operacao/casas/" ente "/apagamento/retomada") {})))
              "nada a retomar"))
        (testing "encerrado -> * nunca: nem pelo console, nem pelo banco"
          (is (= 409 (:status (post-op svc91 ana (str "/operacao/casas/" ente "/reativacao")
                                       {:justificativa "Pagamento regularizado."}))))
          (is (= 409 (:status (post-op svc91 ana (str "/operacao/casas/" ente "/suspensao")
                                       {:motivo "inadimplencia" :justificativa "Motivo registrado no processo."}))))
          (is (= 409 (:status (post-op svc91 ana (str "/operacao/casas/" ente "/encerramento")
                                       {:origem "fim_de_contrato" :justificativa "De novo, por engano aqui."}))))
          (is (= 409 (:status (post-op svc91 ana (str "/operacao/casas/" ente "/exportacoes") {}))))
          (is (thrown-with-msg? Exception #"Casa encerrada nao muda de estado"
                                (jdbc/execute! *ds* ["UPDATE admin_sistema.ente SET estado = 'ativo', encerrada_em = NULL
                                                      WHERE ente_id = ?" ente])))
          (is (thrown-with-msg? Exception #"nao se apaga"
                                (jdbc/execute! *ds* ["DELETE FROM admin_sistema.ente WHERE ente_id = ?" ente]))))
        (testing "a Casa encerrada responde 410 — rotas da Casa (inclusive leitura), portal e login"
          (doseq [[nome r] [["/eu" (get-casa svc91 ente admin "/eu")]
                            ["exportacoes (leitura)" (get-casa svc91 ente admin "/administracao/exportacoes")]
                            ["exportar (escrita)" (post-casa svc91 ente admin "/administracao/exportacoes" {})]
                            ["portal" (pt/response-for svc91 :get (str "/portal/casa/" ente))]
                            ["portal, materias" (pt/response-for svc91 :get (str "/portal/casa/" ente "/materias"))]
                            ["login" (pt/response-for svc91 :get (str "/auth/descoberta/" ente))]]]
            (is (= 410 (:status r)) nome)
            (when (= 410 (:status r))
              (is (= "https://camarabaturite.ce.gov.br/acervo" (:destino-acervo-url (ler r))) nome)
              (is (some? (:encerrada-em (ler r))) nome)
              (is (= "Câmara Municipal de Baturité" (:nome (ler r))) "o nome publico do registro sobrevive")))
          (is (not= 410 (:status (pt/response-for svc91 :get (str "/portal/casa/" vizinha)))) "a vizinha segue no ar"))
        (testing "o destino do acervo ainda se corrige depois de encerrada (o portal passa a apontar para la')"
          (is (= 200 (:status (post-op svc91 ana (str "/operacao/casas/" ente "/destino-acervo")
                                       {:url "https://transparencia.baturite.ce.gov.br/camara"}))))
          (is (= "https://transparencia.baturite.ce.gov.br/camara"
                 (:destino-acervo-url (ler (pt/response-for svc91 :get (str "/portal/casa/" ente)))))))))
    (is (true? (:integra? (atuacao/verificar-corrente *ds*))) "a corrente da Operacao segue integra")))

(deftest o-pedido-de-apagamento-recusado-libera-a-casa
  (let [ana (operador! "Ana") beto (operador! "Beto") ente (casa-ativa! ana)
        svc (servico {:apagar (fn [_ _] resumo-do-apagamento)})]
    (encerramento-em-curso! svc ana beto ente)
    (post-op svc ana (str "/operacao/casas/" ente "/exportacoes") {})
    (let [e (first (:exportacoes (ler (get-casa svc ente admin "/administracao/exportacoes"))))
          t0 (Instant/now)]
      (post-casa (servico {:agora t0}) ente admin (str "/administracao/exportacoes/" (:id e) "/confirmacao") {:sha256 (:sha256 e)})
      (let [svc91 (servico {:agora (.plus t0 (Duration/ofDays 91)) :apagar (fn [_ _] resumo-do-apagamento)})
            pedido (get-in (ler (post-op svc91 ana (str "/operacao/casas/" ente "/apagamento")
                                         {:justificativa "Fim da guarda de 90 dias."}))
                           [:pedido :id])]
        (is (= "apagar" (get-in (ficha svc91 beto ente) [:pedido-aberto :acao])) "a mesma fila 'aguardando 2o operador'")
        (is (= 409 (:status (post-op svc91 ana (str "/operacao/casas/" ente "/reativacao")
                                     {:justificativa "Contrato renovado afinal."})))
            "com o apagamento pedido, reativar espera a decisao")
        (let [r (post-op svc91 beto (str "/operacao/pedidos/" pedido "/recusa") {:justificativa "A Casa renovou."})]
          (is (= 200 (:status r)))
          (is (= "recusado" (get-in (ler r) [:pedido :estado]))))
        (is (= "apagamento-recusado" (:acao (first (:atuacao (ficha svc91 ana ente))))))
        (testing "recusado o apagamento, a Casa ainda pode voltar (a encerrada, nunca)"
          (let [r (post-op svc91 ana (str "/operacao/casas/" ente "/reativacao") {:justificativa "Contrato renovado afinal."})]
            (is (= 200 (:status r)))
            (is (= "ativo" (get-in (ler r) [:casa :estado])))))))))
