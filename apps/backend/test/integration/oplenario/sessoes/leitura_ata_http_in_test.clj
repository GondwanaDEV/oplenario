(ns oplenario.sessoes.leitura-ata-http-in-test
  "Faixa A / A.7: a LEITURA DA ATA ANTERIOR na borda de sessoes. DB-free: RepoSessoes FAKE. Prova o contrato do painel
  (anterior, ata vigente, leitura registrada com nome), o registro (quem registra vem do ator; so' com a sessao
  aberta; os 409 com a razao) e o gate de entrada."
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.diplomat.http.in :as sessoes-http])
  (:import (java.time Instant)))

(def ente #uuid "10000000-0000-0000-0000-000000000001")
(def sid #uuid "30000000-0000-0000-0000-000000000003")
(def ant #uuid "30000000-0000-0000-0000-000000000002")
(def quem #uuid "40000000-0000-0000-0000-000000000004")
(def eu #uuid "60000000-0000-0000-0000-000000000006")

(def ata {:versao 2 :texto "Aos doze dias..." :conteudo-sha256 "sha256:ab" :origem-redacao "gerada_automaticamente"
          :publicada-em (Instant/parse "2026-09-13T12:00:00Z") :publicada-por quem})

(defn- fake-repo [{:keys [estado leitura registros erro] :or {estado "aberta"}}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ _ id] (when (= id sid) {:id id :ente-id ente :estado estado :tipo-sessao "ordinaria"}))
    (leitura-da-ata [_ _ _] {:anterior {:id ant :tipo-sessao "ordinaria" :numero-sequencial 11
                                        :aberta-em (Instant/parse "2026-09-12T18:00:00Z")}
                             :ata ata :leitura leitura})
    (registrar-leitura-ata! [_ e m]
      (when erro (throw (ex-info (first erro) {:tipo (second erro)})))
      (swap! registros conj [e m]) {:modo (:modo m)})))

(defn- fake-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ _] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- servico [& {:keys [papeis] :or {papeis #{"secretario"}} :as opts}]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-identidade papeis))]
    (-> (http/servico (config/carregar)
                      (sessoes-http/rotas {:auth auth :repo-sessoes (fake-repo opts)
                                           :roster-da-casa-em-datas (fn [& _]) :resumir-proposicoes (fn [& _])
                                           :nome-na-casa (fn [_ i] ({quem "Maria Secretária" eu "João Mesa"} i))})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- cab [] {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente)
                                                                            :identidade-id (str eu)}))
               "Content-Type" "application/json"})
(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- post [svc corpo] (pt/response-for svc :post (str "/sessoes/" sid "/leitura-ata") :headers (cab)
                                         :body (json/write-value-as-string corpo)))
(def corpo {"modo" "voz_sintetizada" "ata-sessao-id" (str ant) "ata-versao" 2})

(deftest o-painel-da-leitura
  (let [b (ler (pt/response-for (servico) :get (str "/sessoes/" sid "/leitura-ata") :headers (cab)))]
    (is (true? (:pode-registrar b)))
    (is (= [11 2 "Aos doze dias..." "Maria Secretária"]
           [(get-in b [:anterior :numero-sequencial]) (get-in b [:ata :versao]) (get-in b [:ata :texto])
            (get-in b [:ata :publicada-por-nome])]))
    (is (nil? (:leitura b))))
  (testing "ja' registrada: mostra quem e como; nao se registra de novo"
    (let [b (ler (pt/response-for (servico :leitura {:modo "dispensada" :ata-sessao-id ant :ata-versao 2
                                                     :registrada-por eu
                                                     :registrada-em (Instant/parse "2026-09-15T18:10:00Z")})
                                  :get (str "/sessoes/" sid "/leitura-ata") :headers (cab)))]
      (is (false? (:pode-registrar b)))
      (is (= ["dispensada" "João Mesa"] ((juxt :modo :registrada-por-nome) (:leitura b))))))
  (is (false? (:pode-registrar (ler (pt/response-for (servico :estado "encerrada") :get
                                                     (str "/sessoes/" sid "/leitura-ata") :headers (cab)))))))

(deftest registra-a-leitura
  (let [registros (atom [])
        r (post (servico :registros registros) corpo)
        [[e m]] @registros]
    (is (= 201 (:status r)))
    (is (= ente e))
    (is (= ["voz_sintetizada" ant 2 eu] [(:modo m) (:ata-sessao-id m) (:ata-versao m) (:registrada-por m)])
        "quem registra vem do ator"))
  (is (= 409 (:status (post (servico :estado "encerrada" :registros (atom [])) corpo))) "sessao ja' encerrada")
  (doseq [[msg tipo] [["a ata a ler mudou" :conflito/ata-mudou] ["ja' registrada" :conflito/leitura-registrada]]]
    (let [r (post (servico :erro [msg tipo] :registros (atom [])) corpo)]
      (is (= 409 (:status r)))
      (is (= msg (:erro (ler r))))))
  (doseq [c [(assoc corpo "modo" "cantada") (dissoc corpo "ata-versao") (assoc corpo "ata-versao" 0)
             (assoc corpo "ata-sessao-id" "x")]]
    (is (= 400 (:status (post (servico :registros (atom [])) c))) (pr-str c)))
  (is (= 403 (:status (post (servico :papeis #{"vereador"} :registros (atom [])) corpo)))))
