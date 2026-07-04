(ns oplenario.paineis.mesa-http-in-test
  "F7 (borda HTTP do paineis) — a vertical de rota do dashboard da Mesa (§16.11 item 11.4): prova a silhueta
  de borda end-to-end (controller -> repo rollups + `painel-compliance` injetada -> adapters/out -> wire/out)
  + a COMPOSICAO (o card de compliance embutido opaco) + a authz grossa + 401. DB-free: RepoPaineis FAKE +
  `painel-compliance` FAKE (injetada via montar) + idp-dev real (precedente pendencias-http-in-test)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.compliance.components.repositorio :as repo-compliance]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.paineis.components.repositorio :as repo-paineis]
            [oplenario.rotas :as rotas]))

(def ^:private card-compliance-fake
  {:resumo {:pendente 3 :cumprida 10 :vencida 1 :dispensada 0 :cancelada 0}
   :em-aberto [] :remessas-recentes []})

(defn- fake-repo-paineis [rollups]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-paineis/RepoPaineis
    (dashboard-mesa [_ _ente-id] rollups)))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis repo-p painel-compliance]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-paineis repo-p
                                   :painel-compliance painel-compliance})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- fake-repo-compliance
  "RepoCompliance fake: `painel` devolve o read-model de dominio (que compliance/adapters/out projeta). Impl
  parcial proposital — so' o metodo exercido pela composicao do dashboard."
  [read-model]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-compliance/RepoCompliance
    (painel [_ _ente-id _opts] read-model)))

(defn- service-fn-default
  "service-fn SEM override de `painel-compliance` — exercita o RAMO DE PRODUCAO de `montar`
  (painel-wire -> controllers/painel -> adapters/out/painel), fechando a lacuna de cobertura do seam real
  (review architect MEDIUM). Recebe um `repo-compliance` de verdade (fake)."
  [papeis repo-p repo-c]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-paineis repo-p
                                   :repo-compliance repo-c})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(def ^:private rollups-fake
  {:tramitacao [{:estado "em_comissao" :n 5} {:estado "protocolada" :n 2}]
   :pendencias [{:estado "pendente" :n 4} {:estado "vencido" :n 1}]
   :sessoes    [{:estado-atual "aberta" :n 1} {:estado-atual "nao_realizada" :n 2}]})

;; ---------- GET /paineis/mesa ----------

(deftest mesa-200-compoe-rollups-e-card-de-compliance
  (let [ente (random-uuid)
        chamou-com (atom nil)
        painel-compliance (fn [eid] (reset! chamou-com eid) card-compliance-fake)
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis rollups-fake) painel-compliance)
                           :get "/paineis/mesa" :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /paineis/mesa com papel secretario -> 200")
    (is (= ente @chamou-com) "painel-compliance foi chamada com o ente-id do ator (composicao no tenant certo)")
    (is (= 7 (get-in body [:tramitacao :total])) "rollup de tramitacao composto (5+2)")
    (is (= 5 (get-in body [:pendencias :abertas])) "rollup de pendencias (4+1)")
    (is (= 1 (get-in body [:sessoes :em-curso])))
    (is (= 2 (get-in body [:sessoes :nao-realizadas])))
    (is (= card-compliance-fake (:compliance-tce body)) "o card de compliance foi embutido verbatim (opaco)")
    (is (contains? (set (:lacunas body)) "presenca_agregada"))
    (is (not (contains? body :ente-id)) "tenant nao vaza")))

(deftest mesa-sem-papel-403
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis rollups-fake) (constantly card-compliance-fake))
                           :get "/paineis/mesa" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest mesa-degrada-o-card-quando-compliance-falha
  ;; review architect MAJOR: uma FALHA de leitura de compliance NAO derruba a pagina; vira o sentinel
  ;; {:indisponivel true} naquele card, e os 3 rollups do paineis seguem carregando.
  (let [painel-quebrado (fn [_eid] (throw (ex-info "compliance fora do ar" {})))
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis rollups-fake) painel-quebrado)
                           :get "/paineis/mesa" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "a falha de compliance NAO propaga a 500 — a tela degrada")
    (is (= {:indisponivel true} (:compliance-tce body)) "card degradado com o sentinel")
    (is (= 7 (get-in body [:tramitacao :total])) "os rollups saudaveis do paineis seguem presentes")
    (is (= 5 (get-in body [:pendencias :abertas])))))

(deftest mesa-ramo-de-producao-compoe-painel-wire-real
  ;; review architect MEDIUM: exercita o seam de PRODUCAO (montar sem override) — painel-wire ->
  ;; controllers/painel -> adapters/out/painel projeta o read-model de dominio num PainelOut valido embutido.
  (let [read-model {:resumo {} :em-aberto [] :remessas-recentes []}
        r (pt/response-for (service-fn-default #{"secretario"} (fake-repo-paineis rollups-fake)
                                               (fake-repo-compliance read-model))
                           :get "/paineis/mesa" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "o ramo de producao compoe e responde 200")
    (is (= 7 (get-in body [:tramitacao :total])))
    (let [card (:compliance-tce body)]
      (is (not (:indisponivel card)) "card real (nao o sentinel de falha)")
      (is (= {:pendente 0 :cumprida 0 :vencida 0 :dispensada 0 :cancelada 0} (:resumo card))
          "painel-wire real projetou o resumo 0-filado pelo logic de compliance")
      (is (= [] (:em-aberto card)))
      (is (not (contains? card :ente-id)) "adapters/out de compliance filtrou o tenant no seam real"))))

(deftest mesa-sem-token-401
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis rollups-fake) (constantly card-compliance-fake))
                           :get "/paineis/mesa")]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))
