(ns oplenario.comunicacao.comunicados-ponta-a-ponta-test
  "INTEGRACAO (PG real, a cadeia inteira de `rotas/montar`, identidade/cadastros/comunicacao/registro de Casas REAIS):
  ADR-0020 de ponta a ponta. O admin_ente cria o setor e lota as pessoas; a secretaria envia ao setor; a servidora ve
  na caixa (/meu/comunicados) e da ciencia. Casa SUSPENSA (ADR-0018): dar ciencia segue (allowlist), enviar leva 423.
  E o AGENTE: le a caixa sem marcar `recebido` e so' PROPOE o envio (ADR-0012)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [oplenario.admin-sistema.components.idp-admin :as idp-admin]
            [oplenario.admin-sistema.components.repositorio :as repo-op]
            [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.catalogo :as catalogo]
            [oplenario.comunicacao.components.repositorio :as repo-com]
            [oplenario.config :as config]
            [oplenario.destinatarios :as destinatarios]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.interceptors :as it]
            [oplenario.kernel.catalogo :as kcat]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(def ^:dynamic *c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c] (try (t) (finally (component/stop c)))))))

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- rp-op [] (assoc (repo-op/repositorio) :datasource *c*))
(defn- rp-id [] (assoc (repo-id/repositorio) :datasource *c*))

(defn- objeto-store-memoria []
  (let [m (atom {})]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify store/ObjetoStore
      (guardar! [_ k b _] (swap! m assoc k b) k)
      (abrir [_ k] (some-> (get @m k) java.io.ByteArrayInputStream.))
      (remover! [_ k] (swap! m dissoc k) nil))))

(def agora (Instant/parse "2026-10-02T13:00:00Z"))

(defn- servico []
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev) :repo-identidade (rp-id)
                                   :repo-cadastros (repo-cad/->RepoCadastrosPg *c*)
                                   :repo-comunicacao (repo-com/map->RepoComunicacaoPg {:datasource *c*})
                                   :repo-admin-sistema (rp-op) :idp-operacao (idp-admin/idp-operacao-dev)
                                   :objeto-store (objeto-store-memoria) :cache-estado-da-casa-ms 0
                                   :relogio (tempo/relogio-fixo agora)
                                   :info-ente (constantly {:nome-oficial "Câmara"})
                                   :operacao {:realm "operacao" :client-id "oplenario-console"
                                              :sessao {:absoluta-h 8 :ociosa-min 15}}})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- casa!
  "Uma Casa no registro (ativa), com a secretaria, o administrador e duas servidoras."
  []
  (let [ds (:ds *c*)
        o (repo-op/criar-operador! (rp-op) {:id (random-uuid) :email (str "op-" (random-uuid) "@oplenario.dev") :nome "Op"})
        ente (random-uuid)
        p (into {} (map (fn [[k n]] [k (id/inserir! ds {:id (random-uuid) :cpf (cpf) :nome n})]))
                {:sec "Sílvia Secretária" :adm "Arnaldo Admin" :ana "Ana Lima" :bia "Bia Souza"})]
    (repo-op/registrar-casa! (rp-op) {:ente-id ente :nome "Câmara" :uf "CE" :municipio-ibge "2304400"
                                      :municipio-nome "Fortaleza"}
                             {:operador-id (:id o)})
    (jdbc/with-transaction [tx ds] (repo-op/ativar-casa-em-tx! tx ente {}))
    (try (referencia/inserir-municipio! ds {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 1})
         (catch Exception _ nil))
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400" :nome-oficial "Câmara"})
        (doseq [[k papel] [[:sec "secretario"] [:adm "admin_ente"] [:ana nil] [:bia nil]]]
          (vinc/criar! tx {:id (random-uuid) :ente-id ente :identidade-id (p k) :tipo "servidor"})
          (when papel (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id (p k) :papel papel})))))
    {:ente ente :p p :operador (:id o)}))

(defn- pedir [svc ente quem metodo caminho & [corpo]]
  (let [r (pt/response-for svc metodo caminho
                           :headers {"authorization" (str "Bearer " (json/write-value-as-string
                                                                     {:sub "u" :ente-id (str ente) :identidade-id (str quem)}))
                                     "Content-Type" "application/json"}
                           :body (when corpo (json/write-value-as-string corpo)))]
    {:status (:status r) :corpo (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper))}))

(deftest setor-envio-caixa-ciencia-e-casa-suspensa
  (let [{:keys [ente p operador]} (casa!)
        svc (servico)
        setor (get-in (pedir svc ente (p :adm) :post "/administracao/setores" {:nome "Protocolo"}) [:corpo :id])]
    (testing "o admin lota o setor (so' pessoas da Casa)"
      (let [r (pedir svc ente (p :adm) :put (str "/administracao/setores/" setor "/membros")
                     {:identidades [(str (p :ana)) (str (p :bia))]})]
        (is (= 200 (:status r)))
        (is (= #{"Ana Lima" "Bia Souza"} (set (map :nome (get-in r [:corpo :membros])))))))
    (testing "as opcoes do formulario vem do cadastro e da identidade reais"
      (let [o (:corpo (pedir svc ente (p :sec) :get "/meu/comunicados/destinos"))]
        (is (true? (:pode-enviar-a-grupos o)))
        (is (= [{:id setor :nome "Protocolo" :membros 2}] (:setores o)))
        (is (= 4 (:todos-os-setores o)))))
    (let [r (pedir svc ente (p :sec) :post "/comunicados"
                   {:assunto "Novo fluxo de protocolo" :corpo "A partir de segunda, o protocolo abre às 8h."
                    :exige-ciencia true :ciencia-ate "2026-10-09" :destinos [{:tipo "setor" :alvo-id setor}]})
          cid (get-in r [:corpo :id])]
      (testing "a secretaria envia ao setor: a lista congelada, o nome de quem envia"
        (is (= 201 (:status r)))
        (is (= 2 (get-in r [:corpo :destinatarios])))
        (is (= "Sílvia Secretária" (get-in r [:corpo :remetente :nome]))))
      (testing "a servidora ve na caixa e da ciencia"
        (is (= cid (get-in (pedir svc ente (p :ana) :get "/meu/comunicados") [:corpo :itens 0 :id])))
        (is (= 200 (:status (pedir svc ente (p :ana) :post (str "/comunicados/" cid "/ciencia"))))))
      (testing "quem nao e' admin nao mexe em setor"
        (is (= 403 (:status (pedir svc ente (p :sec) :post "/administracao/setores" {:nome "X"})))))
      (testing "Casa SUSPENSA: dar ciencia segue (allowlist); enviar leva 423"
        (repo-op/pedir-restricao! (rp-op) {:ente-id ente :acao "suspender" :motivo "incidente_de_seguranca"
                                           :justificativa "Credenciais vazadas." :pedido-por operador}
                                  (Instant/now))
        (let [r (pedir svc ente (p :bia) :post (str "/comunicados/" cid "/ciencia"))]
          (is (= 200 (:status r)))
          (is (some? (get-in r [:corpo :minhas-marcas :ciente-em]))))
        (let [r (pedir svc ente (p :sec) :post "/comunicados"
                       {:assunto "x" :corpo "y" :destinos [{:tipo "pessoa" :alvo-id (str (p :ana))}]})]
          (is (= 423 (:status r)))
          (is (= "acesso restrito" (get-in r [:corpo :erro]))))
        (is (= 200 (:status (pedir svc ente (p :sec) :get (str "/comunicados/" cid "/leitura")))) "ler segue")))))

(deftest o-agente-le-sem-marcar-e-so-propoe-o-envio
  (let [{:keys [ente p]} (casa!)
        svc (servico)
        cid (get-in (pedir svc ente (p :sec) :post "/comunicados"
                           {:assunto "Recesso" :corpo "O expediente de sexta e' ate' 12h."
                            :destinos [{:tipo "pessoa" :alvo-id (str (p :ana))}]})
                    [:corpo :id])
        deps-comunicacao {:repo-comunicacao (repo-com/map->RepoComunicacaoPg {:datasource *c*})
                          :relogio (tempo/relogio-fixo agora)
                          :seams (destinatarios/seams-de-comunicacao
                                  {:repo-cadastros (repo-cad/->RepoCadastrosPg *c*) :repo-identidade (rp-id)
                                   :hoje (constantly (java.time.LocalDate/parse "2026-10-02"))
                                   :cargo-na-mesa (constantly nil)})}
        propostas (atom [])
        chamadas (atom [])
        deps {:comunicacao deps-comunicacao
              :propor (fn [_ator e entrada apresentacao]
                        (swap! propostas conj {:ferramenta (:nome e) :entrada entrada :apresentacao apresentacao})
                        {:proposta-id (str (random-uuid)) :titulo (:titulo apresentacao)
                         :estado "aguardando_confirmacao" :mensagem "Nada foi feito ainda."})
              :registrar-chamada (fn [_ e desfecho _] (swap! chamadas conj [(:nome e) desfecho]))}
        agente (fn [quem papeis] {:identidade-id quem :ente-id ente :papeis papeis :tipo-vinculo "servidor"
                                  :via {:agente "assistente" :execucao-id (random-uuid) :publico :secretaria
                                        :classes #{:leitura :ato} :institucional? false}})]
    (testing "ler a caixa pelo agente nao grava `recebido` (nem ler o comunicado grava `lido`)"
      (let [cx (catalogo/executar! deps (agente (p :ana) #{"secretario"}) "ler_caixa" {})]
        (is (= cid (:id (first (:itens cx)))))
        (is (nil? (:recebido-em (first (:itens cx))))))
      (is (nil? (get-in (catalogo/executar! deps (agente (p :ana) #{"secretario"}) "ler_comunicado" {:comunicado-id cid})
                        [:minhas-marcas :lido-em])))
      (is (nil? (:recebido-em (first (:linhas (:corpo (pedir svc ente (p :sec) :get (str "/comunicados/" cid "/leitura"))))))))
      (is (= 0 (get-in (pedir svc ente (p :sec) :get (str "/comunicados/" cid "/leitura")) [:corpo :totais :recebidos]))))
    (testing "enviar pelo agente: so' a PROPOSTA, com a lista e o texto para a pessoa conferir"
      (let [r (catalogo/executar! deps (agente (p :sec) #{"secretario"}) "enviar_comunicado"
                                  {"assunto" "Reunião" "corpo" "Reunião do setor na terça."
                                   "destinos" [{"tipo" "pessoa" "alvo-id" (str (p :bia))}]})]
        (is (= "aguardando_confirmacao" (:estado r)))
        (is (re-find #"Para: Bia Souza \(1 pessoa\)" (get-in (first @propostas) [:apresentacao :texto])))
        (is (= [["enviar_comunicado" "proposta"]] @chamadas))
        (is (empty? (get-in (pedir svc ente (p :bia) :get "/meu/comunicados") [:corpo :itens]))
            "nada foi enviado")))
    (testing "a pessoa confirma: a mesma entrada roda como ela (ator sem :via) e envia"
      (let [entrada (:entrada (first @propostas))
            ;; a confirmacao em /propostas roda a entrada do catalogo como a pessoa (kernel, sem `:via`)
            r (kcat/executar (get catalogo/por-nome "enviar_comunicado") deps
                             {:identidade-id (p :sec) :ente-id ente :papeis #{"secretario"} :tipo-vinculo "servidor"}
                             entrada)]
        (is (re-matches #"COM-2026-\d{6}" (:protocolo r)))
        (is (= 1 (count (get-in (pedir svc ente (p :bia) :get "/meu/comunicados") [:corpo :itens]))))))
    (testing "registrar_ciencia nao e' oferecida a nenhum agente (a ciencia e' a prova da pessoa)"
      (is (not-any? #(contains? % "registrar_ciencia") (vals catalogo/conjuntos))))))
