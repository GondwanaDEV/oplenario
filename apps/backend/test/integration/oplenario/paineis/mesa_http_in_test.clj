(ns oplenario.paineis.mesa-http-in-test
  "F7 (borda HTTP do paineis) — a vertical de rota do dashboard da Mesa (§16.11 item 11.4): prova a silhueta
  de borda end-to-end (controller -> repo rollups + os 4 cards cross-modulo injetados -> adapters/out ->
  wire/out) + a COMPOSICAO (compliance/presenca/esic/relatores embutidos opacos, cada um degradando por card
  em falha) + a authz grossa + 401. DB-free: RepoPaineis FAKE + as 4 fns cross-modulo FAKE (injetadas via
  montar) + idp-dev real (precedente pendencias-http-in-test)."
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

(def ^:private presenca-fake {:media-percentual 78 :sessoes-consideradas 10 :membros-da-casa 43})
(def ^:private esic-fake {:total-encerrados 49 :cumpridos-no-prazo 47 :percentual 96})
(def ^:private relatores-fake {:itens []})

(defn- fake-repo-paineis [rollups]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-paineis/RepoPaineis
    (dashboard-mesa [_ _ente-id] rollups)))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis repo-p painel-compliance presenca-resumo esic-cumprimento relatores-pendentes]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-paineis repo-p
                                   :painel-compliance painel-compliance
                                   :presenca-resumo presenca-resumo
                                   :esic-cumprimento esic-cumprimento
                                   :relatores-pendentes relatores-pendentes})
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
  (review architect MEDIUM). Recebe um `repo-compliance` de verdade (fake). Os 3 cards novos (presenca/esic/
  relatores) NAO tem ramo de producao exercido aqui — este teste e' so' sobre o seam de compliance — por isso
  seguem OVERRIDADOS com fakes (sem isso degradariam por repo nil e quebrariam o MesaOut, que exige a forma
  fechada de cada card, nao o sentinel de indisponivel)."
  [papeis repo-p repo-c]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-paineis repo-p
                                   :repo-compliance repo-c
                                   :presenca-resumo (constantly presenca-fake)
                                   :esic-cumprimento (constantly esic-fake)
                                   :relatores-pendentes (constantly relatores-fake)})
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
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis rollups-fake) painel-compliance
                                       (constantly presenca-fake) (constantly esic-fake) (constantly relatores-fake))
                           :get "/paineis/mesa" :headers (com-bearer (token ente (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /paineis/mesa com papel secretario -> 200")
    (is (= ente @chamou-com) "painel-compliance foi chamada com o ente-id do ator (composicao no tenant certo)")
    (is (= 7 (get-in body [:tramitacao :total])) "rollup de tramitacao composto (5+2)")
    (is (= 5 (get-in body [:pendencias :abertas])) "rollup de pendencias (4+1)")
    (is (= 1 (get-in body [:sessoes :em-curso])))
    (is (= 2 (get-in body [:sessoes :nao-realizadas])))
    (is (= card-compliance-fake (:compliance-tce body)) "o card de compliance foi embutido verbatim (opaco)")
    (is (not (contains? (set (:lacunas body)) "presenca_agregada"))
        "presenca_agregada SAIU das lacunas na FE Onda A1 (materializada, ja' nao e' mais gap)")
    (is (not (contains? body :ente-id)) "tenant nao vaza")))

(deftest mesa-sem-papel-403
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis rollups-fake) (constantly card-compliance-fake)
                                       (constantly presenca-fake) (constantly esic-fake) (constantly relatores-fake))
                           :get "/paineis/mesa" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest mesa-degrada-o-card-quando-compliance-falha
  ;; review architect MAJOR: uma FALHA de leitura de compliance NAO derruba a pagina; vira o sentinel
  ;; {:indisponivel true} naquele card, e os 3 rollups do paineis seguem carregando.
  (let [painel-quebrado (fn [_eid] (throw (ex-info "compliance fora do ar" {})))
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis rollups-fake) painel-quebrado
                                       (constantly presenca-fake) (constantly esic-fake) (constantly relatores-fake))
                           :get "/paineis/mesa" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "a falha de compliance NAO propaga a 500 — a tela degrada")
    (is (= {:indisponivel true} (:compliance-tce body)) "card degradado com o sentinel")
    (is (= 7 (get-in body [:tramitacao :total])) "os rollups saudaveis do paineis seguem presentes")
    (is (= 5 (get-in body [:pendencias :abertas])))))

(deftest mesa-degrada-o-card-quando-presenca-resumo-falha
  ;; Critical review finding (A5+A6 combinado): antes da correcao do wire/out (union com CardIndisponivelOut),
  ;; o sentinel embutido sob um card de forma FECHADA (presenca-resumo/esic-cumprimento/relatores-pendentes)
  ;; violava o contrato MesaOut e fazia adapters-out-mesa/mesa->wire lancar ex-info — propagando a 500 pro
  ;; handler inteiro em vez de degradar so' aquele card. Prova que agora GET /paineis/mesa segue 200, com
  ;; presenca-resumo degradado e o resto da composicao (rollups + outros cards) intacto.
  (let [presenca-quebrada (fn [_eid] (throw (ex-info "sessoes fora do ar" {})))
        r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis rollups-fake) (constantly card-compliance-fake)
                                       presenca-quebrada (constantly esic-fake) (constantly relatores-fake))
                           :get "/paineis/mesa" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "a falha de presenca-resumo NAO propaga a 500 — a tela degrada")
    (is (= {:indisponivel true} (:presenca-resumo body)) "card degradado com o sentinel")
    (is (= card-compliance-fake (:compliance-tce body)) "o card de compliance segue intacto")
    (is (= 96 (get-in body [:esic-cumprimento :percentual])) "o card de esic segue intacto")
    (is (= [] (get-in body [:relatores-pendentes :itens])) "o card de relatores segue intacto")
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
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis rollups-fake) (constantly card-compliance-fake)
                                       (constantly presenca-fake) (constantly esic-fake) (constantly relatores-fake))
                           :get "/paineis/mesa")]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest mesa-200-compoe-os-3-cards-novos
  ;; FE Onda A1: os 3 cards novos (presenca-resumo/esic-cumprimento/relatores-pendentes) sao embutidos opacos
  ;; (mesmo racional do card de compliance) — este teste prova a COMPOSICAO, nao o ramo de producao de cada
  ;; fonte (essas ja' tem cobertura propria em sessoes/participacao/legislativo).
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis rollups-fake) (constantly card-compliance-fake)
                                       (constantly presenca-fake) (constantly esic-fake) (constantly relatores-fake))
                           :get "/paineis/mesa" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 78 (get-in body [:presenca-resumo :media-percentual])))
    (is (= 96 (get-in body [:esic-cumprimento :percentual])))
    (is (= [] (get-in body [:relatores-pendentes :itens])))
    (is (not (contains? (set (:lacunas body)) "presenca_agregada")))))
