(ns oplenario.sessoes.ata-http-in-test
  "Faixa A / A.6a: a ATA na borda de sessoes. DB-free: RepoSessoes FAKE. Prova o contrato de leitura (vigente +
  historico, com o nome de quem publicou), a publicacao (hash do texto, quem publica vem do ator), os 409/422/403 e
  que o nome e' enriquecimento (seam quebrado nao derruba a leitura)."
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
(def quem #uuid "40000000-0000-0000-0000-000000000004")

(defn- versao [n m]
  (merge {:id (random-uuid) :sessao-id sid :versao n :origem-redacao "redigida_externamente" :conteudo-sha256 "sha256:ab"
          :publicada-por quem :publicada-em (Instant/parse "2026-09-26T21:00:00Z")} m))

(defn- fake-repo [{:keys [casa estado versoes gravadas] :or {casa ente estado "encerrada" versoes []}}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ _ id] (when (= id sid) {:id id :ente-id casa :estado estado :tipo-sessao "ordinaria"
                                              :gera-ata-regimental true}))
    (ata-da-sessao [_ _ _] {:atual (some-> (first versoes) (assoc :texto "Aos vinte e seis dias...")) :versoes versoes})
    (publicar-ata! [_ e m] (swap! gravadas conj [e m])
      (if (and (seq versoes) (nil? (:motivo-retificacao m)))
        (throw (ex-info "a ata ja' foi publicada: para retificar, informe o motivo" {:tipo :validacao/retificacao-sem-motivo}))
        {:id (random-uuid) :versao (inc (count versoes)) :conteudo-sha256 (:conteudo-sha256 m)}))))

(defn- fake-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ _] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- servico [& {:keys [papeis nome] :or {papeis #{"secretario"} nome (fn [_ i] (when (= i quem) "Maria Secretária"))}
                   :as opts}]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-identidade papeis))]
    (-> (http/servico (config/carregar)
                      (sessoes-http/rotas {:auth auth :repo-sessoes (fake-repo opts)
                                           :roster-da-casa-em-datas (fn [& _]) :resumir-proposicoes (fn [& _])
                                           :nome-na-casa nome})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(def eu #uuid "60000000-0000-0000-0000-000000000006")
(defn- cab [] {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente)
                                                                            :identidade-id (str eu)}))
               "Content-Type" "application/json"})
(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- post [svc corpo] (pt/response-for svc :post (str "/sessoes/" sid "/ata") :headers (cab)
                                         :body (json/write-value-as-string corpo)))

(deftest le-a-vigente-e-o-historico
  (let [r (pt/response-for (servico :versoes [(versao 2 {:motivo-retificacao "nome errado"}) (versao 1 {})])
                           :get (str "/sessoes/" sid "/ata") :headers (cab))
        b (ler r)]
    (is (= 200 (:status r)))
    (is (true? (:pode-ter-ata b)))
    (is (= [2 "Aos vinte e seis dias..." "Maria Secretária"]
           [(get-in b [:atual :versao :versao]) (get-in b [:atual :texto]) (get-in b [:atual :versao :publicada-por-nome])]))
    (is (= [2 1] (mapv :versao (:versoes b))))
    (is (= "nome errado" (get-in b [:versoes 0 :motivo-retificacao]))))
  (testing "sem ata ainda: atual nil, historico vazio"
    (let [b (ler (pt/response-for (servico) :get (str "/sessoes/" sid "/ata") :headers (cab)))]
      (is (nil? (:atual b)))
      (is (= [] (:versoes b)))))
  (testing "o nome e' enriquecimento: seam quebrado nao derruba a leitura"
    (let [r (pt/response-for (servico :versoes [(versao 1 {})] :nome (fn [& _] (throw (ex-info "fora" {}))))
                             :get (str "/sessoes/" sid "/ata") :headers (cab))]
      (is (= 200 (:status r)))
      (is (nil? (get-in (ler r) [:atual :versao :publicada-por-nome])))))
  (is (= 404 (:status (pt/response-for (servico) :get (str "/sessoes/" (random-uuid) "/ata") :headers (cab))))))

(deftest publica-com-hash-e-autor-do-ator
  (let [gravadas (atom [])
        r (post (servico :gravadas gravadas) {"texto" "Ata." "publicada-por" (str (random-uuid))})
        [[e m]] @gravadas]
    (is (= 201 (:status r)))
    (is (= 1 (:versao (ler r))))
    (is (= ente e))
    (is (= eu (:publicada-por m)) "quem publica vem do ator; o corpo nao escolhe")
    (is (= "sha256:a8bfaded3f2b096f9d2d83df4724bbe3cbe3b5b26c2137d9f3a1a8332f716d07" (:conteudo-sha256 m))
        "o texto e' congelado por SHA-256")))

(deftest recusas
  (is (= 409 (:status (post (servico :estado "aberta" :gravadas (atom [])) {"texto" "Ata."})))
      "sessao que ainda nao acabou nao tem ata")
  (is (= 422 (:status (post (servico :versoes [(versao 1 {})] :gravadas (atom [])) {"texto" "Ata v2."})))
      "retificar sem motivo")
  (is (= 201 (:status (post (servico :versoes [(versao 1 {})] :gravadas (atom [])) {"texto" "Ata v2." "motivo-retificacao" "erro"}))))
  (is (= 400 (:status (post (servico :gravadas (atom [])) {"texto" ""}))))
  (is (= 400 (:status (post (servico :gravadas (atom [])) {"texto" "Ata." "origem-redacao" "gerada_automaticamente"})))
      "o rascunho da IA ainda nao existe nesta fatia")
  (is (= 403 (:status (post (servico :papeis #{"vereador"} :gravadas (atom [])) {"texto" "Ata."}))))
  (is (= 403 (:status (pt/response-for (servico :casa (random-uuid)) :get (str "/sessoes/" sid "/ata") :headers (cab))))
      "sessao de outra Casa (escapou da RLS): a camada fina nega"))
