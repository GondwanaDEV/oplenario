(ns oplenario.participacao.atendimento-test
  "INTEGRACAO (PG real) — o BALCAO interno de atendimento (6.1 e-SIC, 6.2 ouvidoria, 5.10 LGPD): as tres filas da
  secretaria pelo prazo que vence primeiro (o MESMO prazo do acompanhamento do cidadao), o filtro
  abertos|respondidos|todos, o detalhe com historico e acoes cabiveis, a prorrogacao do e-SIC (LAI art. 11 §2º), o
  isolamento entre Casas (RLS) e, pela borda HTTP, o 403 de quem nao e' secretario. E as tres regras de identidade:
  e-SIC = nome + CPF mascarado; LGPD = idem; ouvidoria = so' identificada/anonima (Lei 13.460 art. 10 §7º)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.migracao :as migracao]
            [oplenario.participacao.adapters.out.atendimento :as out]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.participacao.controllers :as controllers]
            [oplenario.participacao.diplomat.http.in :as participacao-http])
  (:import (java.time Instant)))

(def ^:dynamic *repo* nil)
(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *repo* (repo-part/->RepoParticipacaoPg c (outbox/bus))]
        (try (t) (finally (component/stop c)))))))

(defn- relogio [iso] (tempo/relogio-fixo (Instant/parse iso)))
(def ^:private hoje (relogio "2026-07-03T15:00:00Z"))   ; 03/07/2026 no fuso civil (Fortaleza)

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- pessoa!
  "Uma identidade real (nome + CPF valido). Devolve [id cpf]."
  [nome]
  (let [iid (random-uuid) cpf (cpf-valido)]
    [(id/inserir! *ds* {:id iid :cpf cpf :nome nome}) cpf]))

(defn- pessoas
  "O seam do host, sobre o banco real; `chamados` guarda cada lote de ids pedido (para provar quem NUNCA e' pedido)."
  [chamados]
  (fn [ids] (swap! chamados conj (set ids)) (id/com-cpf-mascarado-por-ids *ds* ids)))

(defn- cidadao [ente iid] {:ente-id ente :identidade-id iid :papeis #{}})
(defn- servidor [ente iid] {:ente-id ente :identidade-id iid :papeis #{"secretario"}})

(defn- fila [ator especie situacao]
  (out/fila->wire especie situacao (controllers/fila-do-balcao *repo* hoje ator especie situacao)))

;; ---------------------------------------------------------------- e-SIC

(deftest fila-do-esic-pelo-prazo-que-vence-primeiro
  (let [ente (random-uuid) [eu] (pessoa! "Cidada") sec (servidor ente (random-uuid))
        pedir (fn [quando assunto] (controllers/protocolar-pedido *repo* (relogio quando) (cidadao ente eu)
                                                                  {:assunto assunto :descricao "x"}))
        p-junho   (pedir "2026-06-10T12:00:00Z" "Diarias")      ; vence 30/06 -> vencido ha 3 dias
        p-meio    (pedir "2026-06-25T12:00:00Z" "Contratos")    ; vence 15/07 -> 12 dias
        p-recente (pedir "2026-07-01T12:00:00Z" "Folha")        ; vence 21/07 -> 18 dias
        p-velho   (pedir "2026-06-01T12:00:00Z" "Obras")]       ; respondido
    (controllers/responder-pedido! *repo* hoje sec (:id p-velho) {:corpo "Segue."})
    (testing "abertos (o default): o vencido primeiro, depois pelo prazo; o respondido fica de fora"
      (let [itens (:itens (fila sec :esic "abertos"))]
        (is (= (map :protocolo [p-junho p-meio p-recente]) (map :protocolo itens)))
        (is (= [-3 12 18] (map :dias-restantes itens)) "a mesma contagem do acompanhamento do cidadao (corridos)")
        (is (= "2026-06-30" (:prazo-vigente (first itens))))
        (is (every? :aberto itens))
        (is (not-any? :prorrogado itens))))
    (testing "respondidos: so' o respondido, sem dias correndo"
      (let [[i & mais] (:itens (fila sec :esic "respondidos"))]
        (is (nil? mais))
        (is (= (:protocolo p-velho) (:protocolo i)))
        (is (= {:aberto false :estado "respondido" :dias-restantes nil}
               (select-keys i [:aberto :estado :dias-restantes])))))
    (testing "o recurso do cidadao devolve o pedido a fila, com o relogio PROPRIO do recurso"
      (let [rec (controllers/interpor-recurso! *repo* (relogio "2026-07-02T12:00:00Z") (cidadao ente eu)
                                               (:id p-velho) {:motivo "Faltou o valor."})
            itens (:itens (fila sec :esic "abertos"))
            i (first (filter #(= (:protocolo p-velho) (:protocolo %)) itens))]
        (is (= (map :protocolo [p-junho p-velho p-meio p-recente]) (map :protocolo itens))
            "o recurso vence em 12/07 (10 dias): entra entre o vencido e o de 15/07")
        (is (= {:protocolo (:protocolo rec)} (select-keys (:recurso-pendente i) [:protocolo])))
        (is (= 9 (:dias-restantes i)))
        (is (= "respondido" (:estado i)) "o pedido segue respondido; quem espera e' o recurso")))
    (testing "todos: os abertos (pelo prazo) e depois os encerrados"
      (is (= 4 (count (:itens (fila sec :esic "todos"))))))
    (testing "outra Casa nao ve nada (RLS)"
      (is (empty? (:itens (fila (servidor (random-uuid) (random-uuid)) :esic "todos")))))))

(deftest detalhe-do-esic-com-requerente-historico-e-acoes
  (let [ente (random-uuid) [eu cpf] (pessoa! "Maria das Dores") [sid] (pessoa! "Joana Secretaria")
        sec (servidor ente sid) chamados (atom [])
        p (controllers/protocolar-pedido *repo* (relogio "2026-06-25T12:00:00Z") (cidadao ente eu)
                                         {:assunto "Contratos de 2025" :descricao "Quero a lista com valores."})
        ver #(out/esic->wire (controllers/atendimento-esic *repo* hoje (pessoas chamados) sec (:id p)))]
    (testing "aberto: o texto, o requerente pelo NOME e o CPF mascarado"
      (let [d (ver)]
        (is (= "Quero a lista com valores." (:descricao d)))
        (is (= "Maria das Dores" (get-in d [:requerente :nome])))
        (is (= (str "***." (subs cpf 3 6) "." (subs cpf 6 9) "-**") (get-in d [:requerente :cpf-mascarado])))
        (is (not (str/includes? (json/write-value-as-string d) cpf)) "o CPF inteiro nunca sai")
        (is (= {:pode-responder true :pode-prorrogar true :recurso-pendente-id nil} (:acoes d)))
        (is (= 12 (:dias-restantes d)))
        (is (= [] (:historico d)))))
    (testing "prorrogar (LAI art. 11 §2º): +10 sobre o vencimento original, uma vez so'"
      (is (= "2026-07-25" (str (:prorrogado-ate (controllers/prorrogar-pedido! *repo* hoje sec (:id p)
                                                                              {:justificativa "Busca no arquivo."})))))
      (let [d (ver)]
        (is (= {:prazo-vigente "2026-07-25" :prorrogado true :dias-restantes 22}
               (select-keys d [:prazo-vigente :prorrogado :dias-restantes])))
        (is (false? (get-in d [:acoes :pode-prorrogar])))
        (is (= {:tipo "prorrogacao" :texto "Busca no arquivo." :por "Joana Secretaria"
                :de-data "2026-07-15" :para-data "2026-07-25"}
               (select-keys (first (:historico d)) [:tipo :texto :por :de-data :para-data]))))
      (is (= :conflito/participacao
             (:tipo (ex-data (try (controllers/prorrogar-pedido! *repo* hoje sec (:id p) {:justificativa "de novo"})
                                  (catch Exception e e)))))
          "a segunda prorrogacao e' conflito (409)"))
    (testing "responder, o cidadao recorre, a Casa decide: tudo no historico, em ordem"
      (controllers/responder-pedido! *repo* (relogio "2026-07-03T16:00:00Z") sec (:id p) {:corpo "Segue a planilha."})
      (let [rec (controllers/interpor-recurso! *repo* (relogio "2026-07-03T17:00:00Z") (cidadao ente eu) (:id p)
                                               {:motivo "Faltou o valor."})
            d (ver)]
        (is (= {:pode-responder false :pode-prorrogar false :recurso-pendente-id (str (:id rec))} (:acoes d)))
        (is (:aberto d) "o recurso pendente mantem o item aberto")
        (is (= {:protocolo (:protocolo rec) :motivo "Faltou o valor." :estado "protocolado"}
               (select-keys (:recurso d) [:protocolo :motivo :estado])))
        (controllers/decidir-recurso! *repo* (relogio "2026-07-03T18:00:00Z") sec (:id rec) {:corpo "Mantido."})
        (let [d (ver)]
          (is (= ["prorrogacao" "resposta" "recurso" "decisao-recurso"] (map :tipo (:historico d))))
          (is (nil? (:por (first (filter #(= "recurso" (:tipo %)) (:historico d)))))
              "o recurso e' do cidadao: o balcao nao o nomeia ali")
          (is (= "Mantido." (:texto (last (:historico d)))))
          (is (false? (:aberto d)))
          (is (nil? (:dias-restantes d))))))
    (testing "outra Casa: nao encontra"
      (is (nil? (controllers/atendimento-esic *repo* hoje (pessoas chamados) (servidor (random-uuid) sid) (:id p)))))))

;; ---------------------------------------------------------------- ouvidoria

(deftest ouvidoria-so-mostra-se-e-identificada-nunca-quem
  (let [ente (random-uuid) [eu cpf] (pessoa! "Manifestante Identificado") [sid] (pessoa! "Ouvidor")
        sec (servidor ente sid) chamados (atom [])
        ident (controllers/protocolar-manifestacao! *repo* (relogio "2026-06-20T12:00:00Z") (cidadao ente eu)
                                                    {:tipo "reclamacao" :assunto "Fila" :descricao "Demora." :anonima false})
        anon (controllers/protocolar-manifestacao! *repo* (relogio "2026-06-10T12:00:00Z") (cidadao ente eu)
                                                   {:tipo "denuncia" :assunto "Obra" :descricao "Parada." :anonima true})]
    (testing "a fila: anonima primeiro (vence antes), cada uma so' com identificada/anonima"
      (let [itens (:itens (fila sec :ouvidoria "abertos"))]
        (is (= (map :protocolo [anon ident]) (map :protocolo itens)))
        (is (= ["anonima" "identificada"] (map :identificacao itens)))
        (is (= [7 17] (map :dias-restantes itens)) "30 dias corridos (Lei 13.460 art. 10)")))
    (testing "o detalhe da IDENTIFICADA nao traz nome nem CPF, e o seam nunca e' chamado com o manifestante"
      (controllers/prorrogar-manifestacao! *repo* hoje sec (:id ident) {:justificativa "Consulta a secretaria de obras."})
      (let [d (out/ouvidoria->wire (controllers/atendimento-ouvidoria *repo* hoje (pessoas chamados) sec (:id ident)))
            texto (json/write-value-as-string d)]
        (is (= "identificada" (:identificacao d)))
        (is (not-any? #{:requerente :manifestante :nome :cpf-mascarado :manifestante-identidade-id} (keys d)))
        (is (not (str/includes? texto "Manifestante Identificado")))
        (is (not (str/includes? texto (subs cpf 3 9))))
        (is (not (str/includes? texto (str eu))))
        (is (not-any? #(contains? % eu) @chamados) "o manifestante nunca chega ao seam de pessoas")
        (is (= {:prazo-vigente "2026-08-19" :prorrogado true :dias-restantes 47}
               (select-keys d [:prazo-vigente :prorrogado :dias-restantes])) "prorrogada por igual periodo")
        (is (= {:pode-responder true :pode-arquivar true :pode-prorrogar false} (:acoes d)))
        (is (= "Ouvidor" (:por (first (:historico d)))) "quem prorrogou pela Casa, sim, pelo nome")))
    (testing "arquivar: o motivo vira 'arquivamento' no historico e o item sai dos abertos"
      (controllers/arquivar-manifestacao! *repo* hoje sec (:id anon) {:motivo "Fora da competencia da Camara."})
      (let [d (out/ouvidoria->wire (controllers/atendimento-ouvidoria *repo* hoje (pessoas chamados) sec (:id anon)))]
        (is (= "anonima" (:identificacao d)))
        (is (= [["arquivamento" "Fora da competencia da Camara."]] (map (juxt :tipo :texto) (:historico d))))
        (is (= {:pode-responder false :pode-arquivar false :pode-prorrogar false} (:acoes d))))
      (is (= [(:protocolo ident)] (map :protocolo (:itens (fila sec :ouvidoria "abertos")))))
      (is (= [(:protocolo anon)] (map :protocolo (:itens (fila sec :ouvidoria "respondidos"))))))))

;; ---------------------------------------------------------------- LGPD

(deftest lgpd-mostra-o-titular-com-cpf-mascarado
  (let [ente (random-uuid) [eu cpf] (pessoa! "Titular dos Dados") [sid] (pessoa! "Encarregada")
        sec (servidor ente sid) chamados (atom [])
        s (controllers/solicitar-titular! *repo* (relogio "2026-07-01T12:00:00Z") (cidadao ente eu)
                                          {:tipo "acessar" :detalhe "Quero ver o que a Camara guarda."})]
    (let [[i] (:itens (fila sec :lgpd "abertos"))]
      (is (= {:protocolo (:protocolo s) :tipo "acessar" :dias-restantes 13 :aberto true}
             (select-keys i [:protocolo :tipo :dias-restantes :aberto])))
      (is (not-any? #{:titular :nome} (keys i)) "a lista nao precisa de quem pediu"))
    (controllers/responder-solicitacao! *repo* hoje sec (:id s) {:corpo "Seus dados: nome e CPF."})
    (let [d (out/lgpd->wire (controllers/atendimento-lgpd *repo* hoje (pessoas chamados) sec (:id s)))]
      (is (= {:nome "Titular dos Dados" :cpf-mascarado (str "***." (subs cpf 3 6) "." (subs cpf 6 9) "-**")} (:titular d)))
      (is (not (str/includes? (json/write-value-as-string d) cpf)))
      (is (= "Quero ver o que a Camara guarda." (:detalhe d)))
      (is (= [["resposta" "Encarregada"]] (map (juxt :tipo :por) (:historico d))))
      (is (= {:pode-responder false} (:acoes d)))
      (is (empty? (:itens (fila sec :lgpd "abertos")))))))

;; ---------------------------------------------------------------- a borda HTTP

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-repo-identidade papeis))]
    (-> (http/servico (config/carregar)
                      (participacao-http/rotas {:auth auth :repo-participacao *repo* :relogio hoje
                                                :resolver-ente-publico participacao-http/resolver-ente-publico-uuid
                                                :pessoas (fn [ids] (id/com-cpf-mascarado-por-ids *ds* ids))})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- cabecalhos [ente]
  {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente)
                                                               :identidade-id (str (random-uuid))}))
   "Content-Type" "application/json"})

(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest a-borda-http-do-balcao
  (let [ente (random-uuid) [eu cpf] (pessoa! "Requerente HTTP")
        p (controllers/protocolar-pedido *repo* (relogio "2026-06-25T12:00:00Z") (cidadao ente eu)
                                         {:assunto "Contratos" :descricao "Lista."})
        m (controllers/protocolar-manifestacao! *repo* hoje (cidadao ente eu)
                                                {:tipo "sugestao" :assunto "Wi-fi" :descricao "No plenario." :anonima false})
        s (controllers/solicitar-titular! *repo* hoje (cidadao ente eu) {:tipo "corrigir" :detalhe nil})
        sec (service-fn #{"secretario"})
        gets [["/atendimento/esic"] ["/atendimento/ouvidoria"] ["/atendimento/lgpd"]
              [(str "/atendimento/esic/" (:id p))] [(str "/atendimento/ouvidoria/" (:id m))]
              [(str "/atendimento/lgpd/" (:id s))]]]
    (testing "quem nao e' secretario: 403 em tudo (o vereador, o cidadao)"
      (doseq [papeis [#{} #{"vereador"} #{"juridico"}] [caminho] gets]
        (is (= 403 (:status (pt/response-for (service-fn papeis) :get caminho :headers (cabecalhos ente))))
            (str papeis " " caminho)))
      (is (= 403 (:status (pt/response-for (service-fn #{"vereador"}) :post (str "/esic/pedidos/" (:id p) "/prorrogar")
                                           :headers (cabecalhos ente)
                                           :body (json/write-value-as-string {:justificativa "x"}))))))
    (testing "sem token: 401"
      (is (= 401 (:status (pt/response-for sec :get "/atendimento/esic")))))
    (testing "secretario: 200, a forma do fio (chaves kebab) e a identidade de cada especie"
      (let [r (pt/response-for sec :get "/atendimento/esic" :headers (cabecalhos ente))
            b (ler r)]
        (is (= 200 (:status r)))
        (is (= "abertos" (:situacao b)))
        (is (= {:protocolo (:protocolo p) :dias-restantes 12 :prazo-vigente "2026-07-15" :recurso-pendente nil}
               (select-keys (first (:itens b)) [:protocolo :dias-restantes :prazo-vigente :recurso-pendente]))))
      (let [r (pt/response-for sec :get (str "/atendimento/esic/" (:id p)) :headers (cabecalhos ente))]
        (is (= 200 (:status r)))
        (is (= "Requerente HTTP" (get-in (ler r) [:requerente :nome])))
        (is (not (str/includes? (:body r) cpf))))
      (let [r (pt/response-for sec :get (str "/atendimento/ouvidoria/" (:id m)) :headers (cabecalhos ente))]
        (is (= 200 (:status r)))
        (is (= "identificada" (:identificacao (ler r))))
        (is (not (str/includes? (:body r) "Requerente HTTP")))
        (is (not (str/includes? (:body r) (str eu)))))
      (let [r (pt/response-for sec :get (str "/atendimento/lgpd/" (:id s)) :headers (cabecalhos ente))]
        (is (= 200 (:status r)))
        (is (re-matches #"\*\*\*\.\d{3}\.\d{3}-\*\*" (get-in (ler r) [:titular :cpf-mascarado])))))
    (testing "situacao invalida: 400; id malformado: 400"
      (is (= 400 (:status (pt/response-for sec :get "/atendimento/esic?situacao=vencidos" :headers (cabecalhos ente)))))
      (is (= 400 (:status (pt/response-for sec :get "/atendimento/esic/nao-e-uuid" :headers (cabecalhos ente))))))
    (testing "outra Casa (RLS): a lista vem vazia e o detalhe e' 404"
      (let [outra (random-uuid)]
        (is (empty? (:itens (ler (pt/response-for sec :get "/atendimento/esic?situacao=todos" :headers (cabecalhos outra))))))
        (doseq [[caminho] (drop 3 gets)]
          (is (= 404 (:status (pt/response-for sec :get caminho :headers (cabecalhos outra)))) caminho))))
    (testing "o encarregado da propria Casa: 404 antes de definir; depois, o contato salvo; 403 fora da secretaria"
      (is (= 404 (:status (pt/response-for sec :get "/lgpd/encarregado" :headers (cabecalhos ente)))))
      (is (= 200 (:status (pt/response-for sec :put "/lgpd/encarregado" :headers (cabecalhos ente)
                                           :body (json/write-value-as-string {:nome "Camila" :rotulo "Encarregada de Dados"
                                                                              :email "dados@camara.leg.br"})))))
      (is (= {:nome "Camila" :rotulo "Encarregada de Dados" :email "dados@camara.leg.br"}
             (ler (pt/response-for sec :get "/lgpd/encarregado" :headers (cabecalhos ente)))))
      (is (= 404 (:status (pt/response-for sec :get "/lgpd/encarregado" :headers (cabecalhos (random-uuid)))))
          "o de outra Casa nao aparece")
      (is (= 403 (:status (pt/response-for (service-fn #{"vereador"}) :get "/lgpd/encarregado"
                                           :headers (cabecalhos ente))))))
    (testing "prorrogar o e-SIC pela borda: 200 com a data nova; de novo, 409; sem justificativa, 400"
      (let [ir #(pt/response-for sec :post (str "/esic/pedidos/" (:id p) "/prorrogar") :headers (cabecalhos ente)
                                 :body (json/write-value-as-string %))]
        (is (= 400 (:status (ir {:justificativa "  "}))))
        (let [r (ir {:justificativa "Busca no arquivo."})]
          (is (= 200 (:status r)))
          (is (= {:prorrogado-ate "2026-07-25"} (ler r))))
        (is (= 409 (:status (ir {:justificativa "De novo."}))))))))
