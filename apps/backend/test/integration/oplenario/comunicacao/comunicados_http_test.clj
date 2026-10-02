(ns oplenario.comunicacao.comunicados-http-test
  "INTEGRACAO (PG real + borda HTTP): ADR-0020 — os comunicados internos da Casa. Os seams do host (quem e' do setor,
  da comissao, quem pode enviar a grupo, os nomes) sao FAKES aqui (o host tem o teste proprio, `destinatarios-test`):
  o que se prova e' o modulo — a lista congelada, as tres marcas (primeira ocorrencia, so' do destinatario), quem ve o
  que, o protocolo por ano, a substituicao, os enviados, o prazo calculado na leitura, os anexos e a RLS + a
  imutabilidade no banco."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.comunicacao.components.repositorio :as repo]
            [oplenario.comunicacao.diplomat.http.in :as comunicacao-http]
            [oplenario.comunicacao.wire.out :as wire]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.components.objeto-store :as store]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.migracao :as migracao])
  (:import (java.time Instant)))

(def ^:dynamic *c* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*c* c] (try (t) (finally (component/stop c)))))))

;; ---------- as pessoas ----------

(def sec (random-uuid))      ; secretaria (servidor, papel secretario)
(def admin (random-uuid))    ; administrador (servidor, admin_ente)
(def joao (random-uuid))     ; servidor sem papel
(def ana (random-uuid))      ; servidora
(def bruno (random-uuid))    ; servidor
(def carla (random-uuid))    ; servidora
(def dave (random-uuid))     ; servidor que entra no setor DEPOIS
(def mesa (random-uuid))     ; vereador da Mesa vigente
(def vera (random-uuid))     ; vereadora comum
(def cid (random-uuid))      ; cidada

(def nomes {sec "Secretária Sílvia" admin "Admin Arnaldo" joao "João Servidor" ana "Ana Lima" bruno "Bruno Sales"
            carla "Carla Dias" dave "Dave Nunes" mesa "Mário da Mesa" vera "Vera Vereadora" cid "Cida Cidadã"})

(def papeis {sec #{"secretario"} admin #{"admin_ente"} joao #{} ana #{} bruno #{} carla #{} dave #{}
             mesa #{"vereador"} vera #{"vereador"}})

(defn- fake-identidade []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ iid]
      (cond (= iid cid) {:vinculo-ativo {:id (random-uuid) :tipo "cidadao"} :papeis #{}}
            (contains? papeis iid) {:vinculo-ativo {:id (random-uuid) :tipo (if (#{mesa vera} iid) "vereador" "servidor")}
                                    :papeis (papeis iid)}))
    (snapshot-cidadao [_ _ iid] (when (= iid cid) {:vinculo-ativo {:id (random-uuid) :tipo "cidadao"} :papeis #{}}))))

;; ---------- os seams do host (fakes) ----------

(def setor-juridico (random-uuid))
(def setor-inativo (random-uuid))
(def comissao-fin (random-uuid))
(def vereador-sem-acesso (random-uuid))

(defn- seams [setores]
  (let [pessoa (fn [i] {:identidade-id i :nome (nomes i)})]
    {:resolver-destino
     (fn [_ente {:keys [tipo alvo-id]}]
       (case tipo
         "pessoa" (when (and (nomes alvo-id) (not= alvo-id cid))
                    {:alvo-nome (nomes alvo-id) :pessoas [(pessoa alvo-id)] :sem-acesso 0})
         "vereador" (cond (= alvo-id vereador-sem-acesso) {:alvo-nome "Vereador Sem Login" :pessoas [] :sem-acesso 1}
                          (#{mesa vera} alvo-id) {:alvo-nome (nomes alvo-id) :pessoas [(pessoa alvo-id)] :sem-acesso 0})
         "setor" (when-let [s (get @setores alvo-id)]
                   (when (:ativo s) {:alvo-nome (:nome s) :pessoas (mapv pessoa (:membros s)) :sem-acesso 0}))
         "comissao" (when (= alvo-id comissao-fin)
                      {:alvo-nome "Comissão de Finanças" :pessoas [(pessoa vera) (pessoa mesa)] :sem-acesso 1})
         "todos" {:alvo-nome "todos os setores" :pessoas (mapv pessoa [sec admin joao ana bruno carla]) :sem-acesso 0}))
     :pode-enviar-a-grupos? (fn [ator] (boolean (or (some #{"secretario" "admin_ente"} (:papeis ator))
                                                    (= mesa (:identidade-id ator)))))
     :destinos (fn [_ente grupos?]
                 {:pessoas (mapv pessoa [ana bruno]) :vereadores [{:id mesa :nome "Mário" :tem-acesso true}]
                  :setores (if grupos? [{:id setor-juridico :nome "Jurídico" :membros 2}] [])
                  :comissoes (if grupos? [{:id comissao-fin :nome "Comissão de Finanças" :membros 2 :sem-acesso 1}] [])
                  :todos-os-setores (if grupos? 6 0)})
     :nome-de (fn [_ente iid] (nomes iid))}))

(defn- objeto-store-memoria []
  (let [m (atom {})]
    #_{:clj-kondo/ignore [:missing-protocol-method]}
    (reify store/ObjetoStore
      (guardar! [_ k b _] (swap! m assoc k b) k)
      (abrir [_ k] (some-> (get @m k) java.io.ByteArrayInputStream.))
      (remover! [_ k] (swap! m dissoc k) nil))))

(defn- cenario
  "Um servico novo com a sua Casa, os seus setores e o seu relogio."
  []
  (let [instante (atom (Instant/parse "2026-10-02T13:00:00Z"))
        setores (atom {setor-juridico {:nome "Jurídico" :ativo true :membros [ana bruno]}
                       setor-inativo {:nome "Antigo" :ativo false :membros [carla]}})
        deps {:repo-comunicacao (repo/map->RepoComunicacaoPg {:datasource *c*})
              :objeto-store (objeto-store-memoria)
              :relogio (reify tempo/Relogio (agora [_] @instante))
              :seams (seams setores)}
        svc (-> (http/servico (config/carregar)
                              (comunicacao-http/rotas (assoc deps :auth (it/autenticacao (idp-dev/idp-dev) (fake-identidade))))
                              it/globais)
                ph/create-server ::ph/service-fn)]
    {:svc svc :ente (random-uuid) :instante instante :setores setores :deps deps}))

(defn- cabecalhos [ente quem]
  {"Authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente) :identidade-id (str quem)}))})

(defn- ler-json [r] (when (seq (:body r)) (json/read-value (:body r) json/keyword-keys-object-mapper)))

(defn- pedir [{:keys [svc ente]} metodo caminho quem & [corpo]]
  (let [r (pt/response-for svc metodo caminho
                           :headers (cond-> (cabecalhos ente quem) corpo (assoc "Content-Type" "application/json"))
                           :body (when corpo (json/write-value-as-string corpo)))]
    {:status (:status r) :corpo (ler-json r)}))

(defn- enviar [c quem corpo] (pedir c :post "/comunicados" quem corpo))

(def ^:private aviso-de-sessao
  {:assunto "Sessão extraordinária na quinta" :corpo "Fica convocada a sessão extraordinária de quinta, às 9h."})

(defn- valida! [schema x] (is (m/validate schema x) (pr-str (m/explain schema x))) x)

;; ---------- o fluxo ----------

(deftest enviar-receber-ler-e-dar-ciencia
  (let [c (cenario)
        r (enviar c sec (assoc aviso-de-sessao :exige-ciencia true :ciencia-ate "2026-10-09"
                               :destinos [{:tipo "setor" :alvo-id (str setor-juridico)}
                                          {:tipo "pessoa" :alvo-id (str carla)}
                                          {:tipo "pessoa" :alvo-id (str ana)}]))
        cid-id (get-in r [:corpo :id])]
    (testing "201: o protocolo, a lista congelada, o remetente sem marcas"
      (is (= 201 (:status r)))
      (valida! wire/EnviadoOut (:corpo r))
      (is (re-matches #"COM-2026-\d{6}" (get-in r [:corpo :protocolo])))
      (is (= 3 (get-in r [:corpo :destinatarios])) "Ana chega por dois caminhos e aparece uma vez")
      (is (= {:identidade-id (str sec) :nome "Secretária Sílvia"} (get-in r [:corpo :remetente])))
      (is (nil? (get-in r [:corpo :minhas-marcas])))
      (is (true? (get-in r [:corpo :pode-ver-leitura])))
      (is (true? (get-in r [:corpo :pode-anexar])))
      (is (= ["setor" "pessoa" "pessoa"] (mapv :tipo (get-in r [:corpo :destinos])))))
    (testing "o numero do topo conta sem marcar: o contador nao e' a caixa chegando a pessoa"
      (let [ct (:corpo (pedir c :get "/meu/comunicados/contagem" ana))]
        (valida! wire/ContagemOut ct)
        (is (= {:nao-lidos 1 :pendentes-ciencia 1 :proxima-ciencia-ate "2026-10-09"} ct)))
      ;; sob a RLS da Casa (sem o tenant, a consulta voltaria vazia de qualquer jeito)
      (is (zero? (:n (tenancy/com-tenant* (:ds *c*) (:ente c)
                       #(jdbc/execute-one! % ["SELECT count(*)::int AS n FROM comunicacao.marca WHERE comunicado_id = ?::uuid"
                                              cid-id]
                                           {:builder-fn rs/as-unqualified-maps}))))
          "nenhuma marca nasceu da contagem"))
    (testing "a caixa grava `recebido` (a primeira vez vale)"
      (let [cx (:corpo (pedir c :get "/meu/comunicados" ana))
            [item] (:itens cx)]
        (valida! wire/CaixaOut cx)
        (is (= cid-id (:id item)))
        (is (= "setor Jurídico" (:via item)) "o primeiro caminho")
        (is (some? (:recebido-em item)))
        (is (nil? (:lido-em item)))
        (is (= 1 (:nao-lidos cx)))
        (is (= 1 (:pendentes-ciencia cx)))
        (is (= "2026-10-09" (:proxima-ciencia-ate cx)))
        (swap! (:instante c) #(.plusSeconds ^Instant % 60))
        (is (= (:recebido-em item) (:recebido-em (first (:itens (:corpo (pedir c :get "/meu/comunicados" ana))))))
            "pedir de novo nao sobrescreve")))
    (testing "o detalhe grava `lido` so' para o destinatario (e `recebido` se faltava)"
      (let [d (:corpo (pedir c :get (str "/comunicados/" cid-id) bruno))]
        (valida! wire/ComunicadoOut d)
        (is (some? (get-in d [:minhas-marcas :lido-em])))
        (is (some? (get-in d [:minhas-marcas :recebido-em])) "abriu sem passar pela caixa")
        (is (false? (:pode-ver-leitura d)) "o destinatario nao ve o painel")
        (is (false? (:pode-anexar d))))
      (let [d (:corpo (pedir c :get (str "/comunicados/" cid-id) sec))]
        (is (nil? (:minhas-marcas d)) "quem enviou nao ganha marca")))
    (testing "ciencia: so' do destinatario, idempotente"
      (let [r1 (pedir c :post (str "/comunicados/" cid-id "/ciencia") ana)
            _ (swap! (:instante c) #(.plusSeconds ^Instant % 60))
            r2 (pedir c :post (str "/comunicados/" cid-id "/ciencia") ana)]
        (is (= 200 (:status r1)))
        (valida! wire/CienciaOut (:corpo r1))
        (is (some? (get-in r1 [:corpo :minhas-marcas :ciente-em])))
        (is (some? (get-in r1 [:corpo :minhas-marcas :lido-em])) "ciente grava lido se faltava")
        (is (= (get-in r1 [:corpo :minhas-marcas]) (get-in r2 [:corpo :minhas-marcas])) "a primeira vale"))
      (is (= 403 (:status (pedir c :post (str "/comunicados/" cid-id "/ciencia") sec))) "quem enviou nao da ciencia")
      (is (= 404 (:status (pedir c :post (str "/comunicados/" cid-id "/ciencia") joao))) "quem nao ve: 404"))
    (testing "o painel de leitura: 12 de 15 leram, 3 faltam"
      (let [l (:corpo (pedir c :get (str "/comunicados/" cid-id "/leitura") sec))]
        (valida! wire/LeituraOut l)
        (is (= {:destinatarios 3 :recebidos 2 :lidos 2 :cientes 1 :faltam-ler 1 :faltam-ciencia 2 :vencidos 0}
               (:totais l)))
        (is (= ["Ana Lima" "Bruno Sales" "Carla Dias"] (mapv :nome (:linhas l))))
        (is (= ["setor Jurídico" "setor Jurídico" "direto"] (mapv :via (:linhas l)))))
      (is (= 200 (:status (pedir c :get (str "/comunicados/" cid-id "/leitura") admin))) "a Casa responde: o admin ve")
      (is (= 403 (:status (pedir c :get (str "/comunicados/" cid-id "/leitura") ana))) "a destinataria nao ve o painel")
      (is (= 404 (:status (pedir c :get (str "/comunicados/" cid-id "/leitura") joao)))))
    (testing "quem nao e' destinatario nem remetente nem secretaria: 404"
      (is (= 404 (:status (pedir c :get (str "/comunicados/" cid-id) joao))))
      (is (= 404 (:status (pedir c :get "/comunicados/nao-e-uuid" ana)))))
    (testing "comunicado sem ciencia: dar ciencia e' 409"
      (let [id (get-in (enviar c sec (assoc aviso-de-sessao :destinos [{:tipo "pessoa" :alvo-id (str ana)}])) [:corpo :id])]
        (is (= 409 (:status (pedir c :post (str "/comunicados/" id "/ciencia") ana))))))))

(deftest a-lista-e-congelada-no-envio
  (let [c (cenario)
        id (get-in (enviar c sec (assoc aviso-de-sessao :exige-ciencia true
                                        :destinos [{:tipo "setor" :alvo-id (str setor-juridico)}]))
                   [:corpo :id])]
    ;; a lotacao muda depois do envio: Bruno sai, Dave entra
    (swap! (:setores c) assoc-in [setor-juridico :membros] [ana dave])
    (is (= ["Ana Lima" "Bruno Sales"] (mapv :nome (get-in (pedir c :get (str "/comunicados/" id "/leitura") sec)
                                                           [:corpo :linhas])))
        "quem saiu continua devendo; quem entrou nao recebe o antigo")
    (is (empty? (get-in (pedir c :get "/meu/comunicados" dave) [:corpo :itens])))
    (is (= 1 (count (get-in (pedir c :get "/meu/comunicados" bruno) [:corpo :itens]))))
    (is (= 200 (:status (pedir c :post (str "/comunicados/" id "/ciencia") bruno))))))

(deftest quem-pode-enviar
  (let [c (cenario)
        a-setor (assoc aviso-de-sessao :destinos [{:tipo "setor" :alvo-id (str setor-juridico)}])]
    (testing "a pessoa ou o vereador: qualquer pessoa da Casa"
      (is (= 201 (:status (enviar c joao (assoc aviso-de-sessao :destinos [{:tipo "pessoa" :alvo-id (str ana)}])))))
      (is (= 201 (:status (enviar c vera (assoc aviso-de-sessao :destinos [{:tipo "vereador" :alvo-id (str mesa)}]))))))
    (testing "grupo sem papel nem Mesa: 403"
      (is (= 403 (:status (enviar c joao a-setor))))
      (is (= 403 (:status (enviar c vera (assoc aviso-de-sessao :destinos [{:tipo "comissao" :alvo-id (str comissao-fin)}])))))
      (is (= 403 (:status (enviar c joao (assoc aviso-de-sessao :destinos [{:tipo "todos"}]))))))
    (testing "a secretaria, o admin e o vereador da Mesa enviam a grupo"
      (is (= 201 (:status (enviar c sec a-setor))))
      (is (= 201 (:status (enviar c admin (assoc aviso-de-sessao :destinos [{:tipo "todos"}])))))
      (let [r (enviar c mesa (assoc aviso-de-sessao :destinos [{:tipo "comissao" :alvo-id (str comissao-fin)}]))]
        (is (= 201 (:status r)))
        (is (= 1 (get-in r [:corpo :destinatarios])) "o proprio membro da Mesa nao entra na lista")
        (is (= 1 (get-in r [:corpo :sem-acesso])) "o membro sem acesso e' contado para a tela avisar")))
    (testing "cidadao: nunca"
      (is (= 403 (:status (enviar c cid (assoc aviso-de-sessao :destinos [{:tipo "pessoa" :alvo-id (str ana)}])))))
      (is (= 403 (:status (pedir c :get "/meu/comunicados" cid))))
      (is (= 403 (:status (pedir c :get "/meu/comunicados/destinos" cid)))))
    (testing "422: destino inexistente, setor desativado, lista vazia"
      (is (= 422 (:status (enviar c sec (assoc aviso-de-sessao :destinos [{:tipo "setor" :alvo-id (str (random-uuid))}])))))
      (is (= 422 (:status (enviar c sec (assoc aviso-de-sessao :destinos [{:tipo "setor" :alvo-id (str setor-inativo)}])))))
      (let [r (enviar c sec (assoc aviso-de-sessao :destinos [{:tipo "vereador" :alvo-id (str vereador-sem-acesso)}]))]
        (is (= 422 (:status r)))
        (is (= 1 (get-in r [:corpo :sem-acesso]))))
      (is (= 422 (:status (enviar c sec (assoc aviso-de-sessao :destinos [{:tipo "pessoa" :alvo-id (str sec)}]))))
          "so' quem envia: ninguem recebe"))
    (testing "400: corpo invalido"
      (is (= 400 (:status (enviar c sec (assoc aviso-de-sessao :destinos [])))))
      (is (= 400 (:status (enviar c sec (assoc aviso-de-sessao :destinos [{:tipo "todos" :alvo-id (str ana)}])))))
      (is (= 400 (:status (enviar c sec (assoc aviso-de-sessao :destinos [{:tipo "setor"}])))))
      (is (= 400 (:status (enviar c sec (assoc aviso-de-sessao :remetente "forjado"
                                               :destinos [{:tipo "pessoa" :alvo-id (str ana)}]))))
          "campo fora do contrato"))
    (testing "as opcoes do formulario: grupos so' para quem pode"
      (let [o (:corpo (pedir c :get "/meu/comunicados/destinos" sec))]
        (valida! wire/DestinosOut o)
        (is (true? (:pode-enviar-a-grupos o)))
        (is (= 1 (count (:setores o)))))
      (let [o (:corpo (pedir c :get "/meu/comunicados/destinos" joao))]
        (is (false? (:pode-enviar-a-grupos o)))
        (is (empty? (:setores o)))))))

(deftest numero-por-casa-e-ano
  (let [c (cenario)
        para-ana (assoc aviso-de-sessao :destinos [{:tipo "pessoa" :alvo-id (str ana)}])
        p1 (get-in (enviar c sec para-ana) [:corpo :protocolo])
        p2 (get-in (enviar c sec para-ana) [:corpo :protocolo])]
    (is (= "COM-2026-000001" p1))
    (is (= "COM-2026-000002" p2))
    (reset! (:instante c) (Instant/parse "2027-01-01T12:00:00Z"))
    (is (= "COM-2027-000001" (get-in (enviar c sec para-ana) [:corpo :protocolo])) "o numero recomeca no ano")
    (is (= "COM-2027-000001" (get-in (enviar (assoc c :ente (random-uuid)) sec para-ana) [:corpo :protocolo]))
        "cada Casa tem a sua numeracao (a outra Casa tambem comeca em 1, mesmo depois desta)")))

(deftest substituir-um-comunicado
  (let [c (cenario)
        para (assoc aviso-de-sessao :destinos [{:tipo "pessoa" :alvo-id (str ana)}])
        a (get-in (enviar c sec para) [:corpo])
        b (:corpo (enviar c sec (assoc para :corpo "Correcao: a sessao e' na sexta." :substitui-id (:id a))))]
    (is (= {:id (:id a) :protocolo (:protocolo a)} (:substitui b)))
    (is (= {:id (:id b) :protocolo (:protocolo b)}
           (get-in (pedir c :get (str "/comunicados/" (:id a)) ana) [:corpo :substituido-por]))
        "o antigo mostra 'substituido por'")
    (is (= {:id (:id b) :protocolo (:protocolo b)}
           (:substituido-por (first (filter #(= (:id a) (:id %)) (get-in (pedir c :get "/meu/comunicados" ana) [:corpo :itens]))))))
    (is (= 409 (:status (enviar c sec (assoc para :substitui-id (:id a))))) "um comunicado e' substituido uma vez")
    (is (= 422 (:status (enviar c joao (assoc para :substitui-id (:id b))))) "so' quem enviou (ou a secretaria) corrige")
    (is (= 201 (:status (enviar c admin (assoc para :substitui-id (:id b))))) "a Casa responde: o admin corrige")))

(deftest enviados-meus-e-da-casa
  (let [c (cenario)
        para (assoc aviso-de-sessao :destinos [{:tipo "pessoa" :alvo-id (str ana)} {:tipo "pessoa" :alvo-id (str bruno)}])
        _ (enviar c sec para)
        id2 (get-in (enviar c sec para) [:corpo :id])
        _ (enviar c joao para)]
    (pedir c :get (str "/comunicados/" id2) ana)
    (let [e (:corpo (pedir c :get "/meu/comunicados/enviados" sec))]
      (valida! wire/EnviadosOut e)
      (is (= "meus" (:escopo e)))
      (is (= 2 (count (:itens e))))
      (is (= {:destinatarios 2 :recebidos 1 :lidos 1 :cientes 0 :pendentes-vencidos 0}
             (select-keys (first (:itens e)) [:destinatarios :recebidos :lidos :cientes :pendentes-vencidos]))
          "o mais recente primeiro"))
    (is (= 3 (count (get-in (pedir c :get "/meu/comunicados/enviados?escopo=casa" sec) [:corpo :itens]))))
    (is (= 403 (:status (pedir c :get "/meu/comunicados/enviados?escopo=casa" joao))))
    (is (= 400 (:status (pedir c :get "/meu/comunicados/enviados?escopo=outra" sec))))
    (is (= 1 (count (get-in (pedir c :get "/meu/comunicados/enviados" joao) [:corpo :itens]))))))

(deftest prazo-de-ciencia-calculado-na-leitura
  (let [c (cenario)
        para (assoc aviso-de-sessao :exige-ciencia true
                    :destinos [{:tipo "pessoa" :alvo-id (str ana)} {:tipo "pessoa" :alvo-id (str bruno)}])]
    (testing "o prazo: futuro (hoje vale) e so' com exige-ciencia"
      (is (= 400 (:status (enviar c sec (assoc para :ciencia-ate "2026-10-01")))))
      (is (= 400 (:status (enviar c sec (assoc para :exige-ciencia false :ciencia-ate "2026-10-09")))))
      (is (= 400 (:status (enviar c sec (assoc para :ciencia-ate "09/10/2026"))))))
    (let [id (get-in (enviar c sec (assoc para :ciencia-ate "2026-10-02")) [:corpo :id])]
      (pedir c :post (str "/comunicados/" id "/ciencia") ana)
      (is (false? (get-in (pedir c :get (str "/comunicados/" id "/leitura") sec) [:corpo :comunicado :prazo-vencido])))
      ;; o dia seguinte ao prazo: vence para quem nao deu ciencia — sem agendador, na leitura
      (reset! (:instante c) (Instant/parse "2026-10-03T13:00:00Z"))
      (let [l (:corpo (pedir c :get (str "/comunicados/" id "/leitura") sec))]
        (is (true? (get-in l [:comunicado :prazo-vencido])))
        (is (= {"Ana Lima" false "Bruno Sales" true} (into {} (map (juxt :nome :vencido)) (:linhas l))))
        (is (= 1 (get-in l [:totais :vencidos]))))
      (is (= 1 (:pendentes-vencidos (first (get-in (pedir c :get "/meu/comunicados/enviados" sec) [:corpo :itens])))))
      (let [cx (:corpo (pedir c :get "/meu/comunicados" bruno))]
        (is (true? (:vencido (first (:itens cx)))))
        (is (= 1 (:pendentes-ciencia cx)))
        (is (nil? (:proxima-ciencia-ate cx)) "o vencido nao e' o 'proximo' prazo")))))

;; ---------- anexos ----------

(defn- multipart [nome tipo conteudo]
  (let [fronteira "----oplenario-teste"]
    {:ct (str "multipart/form-data; boundary=" fronteira)
     :body (str "--" fronteira "\r\n"
                "Content-Disposition: form-data; name=\"arquivo\"; filename=\"" nome "\"\r\n"
                "Content-Type: " tipo "\r\n\r\n"
                conteudo "\r\n"
                "--" fronteira "--\r\n")}))

(defn- anexar [{:keys [svc ente]} id quem nome conteudo]
  (let [{:keys [ct body]} (multipart nome "text/plain" conteudo)
        r (pt/response-for svc :post (str "/comunicados/" id "/anexos")
                           :headers (assoc (cabecalhos ente quem) "Content-Type" ct) :body body)]
    {:status (:status r) :corpo (ler-json r)}))

(deftest anexos-do-comunicado
  (let [c (cenario)
        id (get-in (enviar c sec (assoc aviso-de-sessao :destinos [{:tipo "pessoa" :alvo-id (str ana)}])) [:corpo :id])
        r (anexar c id sec "pauta.txt" "Item 1: PL 12/2026")]
    (testing "o remetente anexa: nome, tipo, tamanho e sha256"
      (is (= 201 (:status r)))
      (valida! wire/AnexoOut (:corpo r))
      (is (= {:nome "pauta.txt" :tipo-midia "text/plain" :bytes 18} (select-keys (:corpo r) [:nome :tipo-midia :bytes])))
      (is (re-matches #"[0-9a-f]{64}" (get-in r [:corpo :sha256]))))
    (testing "quem pode ver baixa; quem nao pode, 404"
      (let [d (pt/response-for (:svc c) :get (str "/comunicados/" id "/anexos/" (get-in r [:corpo :id]))
                               :headers (cabecalhos (:ente c) ana))]
        (is (= 200 (:status d)))
        (is (= "Item 1: PL 12/2026" (:body d)))
        (is (str/starts-with? (get-in d [:headers "Content-Disposition"]) "attachment; filename=\"pauta.txt\"")))
      (is (= 404 (:status (pt/response-for (:svc c) :get (str "/comunicados/" id "/anexos/" (get-in r [:corpo :id]))
                                           :headers (cabecalhos (:ente c) joao)))))
      (is (= 1 (count (get-in (pedir c :get (str "/comunicados/" id) ana) [:corpo :anexos])))))
    (testing "so' quem enviou"
      (is (= 403 (:status (anexar c id ana "x.txt" "x")))))
    (testing "ate' 5"
      (dotimes [i 4] (is (= 201 (:status (anexar c id sec (str "a" i ".txt") "conteudo")))))
      (is (= 409 (:status (anexar c id sec "sexto.txt" "conteudo")))))
    (testing "ate' 10 MB"
      (let [id2 (get-in (enviar c sec (assoc aviso-de-sessao :destinos [{:tipo "pessoa" :alvo-id (str ana)}])) [:corpo :id])]
        (is (= 413 (:status (anexar c id2 sec "grande.txt" (apply str (repeat (inc (* 10 1024 1024)) "a"))))))
        (is (= 400 (:status (anexar c id2 sec "vazio.txt" ""))))
        (testing "so' nos 10 minutos depois do envio"
          (swap! (:instante c) #(.plusSeconds ^Instant % 601))
          (is (= 409 (:status (anexar c id2 sec "tarde.txt" "x")))))))))

;; ---------- o banco: RLS e imutabilidade ----------

(deftest rls-e-imutabilidade
  (let [c (cenario)
        id (parse-uuid (get-in (enviar c sec (assoc aviso-de-sessao :exige-ciencia true
                                                    :destinos [{:tipo "pessoa" :alvo-id (str ana)}]))
                               [:corpo :id]))
        ds (:ds *c*)
        conta (fn [ente tabela]
                (tenancy/com-tenant* ds ente
                  #(:n (jdbc/execute-one! % [(str "SELECT count(*)::int AS n FROM comunicacao." tabela)]
                                          {:builder-fn rs/as-unqualified-maps}))))]
    (pedir c :post (str "/comunicados/" id "/ciencia") ana)
    (testing "outra Casa nao ve nada: nem pela borda, nem no banco"
      (is (= 404 (:status (pedir (assoc c :ente (random-uuid)) :get (str "/comunicados/" id) sec))))
      (doseq [t ["comunicado" "destino" "destinatario" "marca"]]
        (is (pos? (conta (:ente c) t)) t)
        (is (zero? (conta (random-uuid) t)) t)))
    (testing "o role da aplicacao nao altera nem apaga (sem GRANT)"
      (doseq [sql ["UPDATE comunicacao.comunicado SET assunto = 'x' WHERE id = ?"
                   "DELETE FROM comunicacao.marca WHERE comunicado_id = ?"
                   "DELETE FROM comunicacao.destinatario WHERE comunicado_id = ?"]]
        (is (thrown? Exception (tenancy/com-tenant* ds (:ente c) #(jdbc/execute! % [sql id]))) sql)))
    (testing "nem o dono: o trigger append-only recusa UPDATE/DELETE"
      (doseq [sql ["UPDATE comunicacao.comunicado SET assunto = 'x' WHERE id = ?"
                   "DELETE FROM comunicacao.destino WHERE comunicado_id = ?"
                   "UPDATE comunicacao.marca SET em = now() WHERE comunicado_id = ?"]]
        (is (thrown? Exception (jdbc/execute! ds [sql id])) sql)))
    (testing "ciente so' existe em comunicado que pede ciencia (o banco recusa o resto)"
      (let [sem (parse-uuid (get-in (enviar c sec (assoc aviso-de-sessao :destinos [{:tipo "pessoa" :alvo-id (str ana)}]))
                                    [:corpo :id]))]
        (is (thrown? Exception
                     (tenancy/com-tenant* ds (:ente c)
                       #(jdbc/execute! % ["INSERT INTO comunicacao.marca (ente_id, comunicado_id, identidade_id, tipo)
                                           VALUES (?, ?, ?, 'ciente')" (:ente c) sem ana]))))))
    (testing "marca so' de quem esta' na lista (FK)"
      (is (thrown? Exception
                   (tenancy/com-tenant* ds (:ente c)
                     #(jdbc/execute! % ["INSERT INTO comunicacao.marca (ente_id, comunicado_id, identidade_id, tipo)
                                         VALUES (?, ?, ?, 'lido')" (:ente c) id joao])))))))
