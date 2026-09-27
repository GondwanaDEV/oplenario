(ns oplenario.legislativo.resumo-http-in-test
  "Faixa A / A.8: o resumo cidadao na borda do legislativo. DB-free: Repo FAKE. Prova o contrato de leitura (rascunho,
  versao publicada, o que ficou para tras), a leitura do rascunho so' de um id registrado para a proposicao (e o 503
  R-IA-1), a publicacao (quem publica vem do ator), os 409/400 e o gate de papel."
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.diplomat.http.in :as leg-http])
  (:import (java.time Instant)))

(def ente #uuid "10000000-0000-0000-0000-000000000001")
(def pid #uuid "a0000000-0000-0000-0000-000000000001")
(def rid #uuid "70000000-0000-0000-0000-000000000007")
(def eu #uuid "60000000-0000-0000-0000-000000000006")

(def ponteiro {:situacao "pronto" :rascunho-id rid :texto-base-sha256 "sha256:v1" :modelo-llm-id "fake:fake-1"
               :prompt-versao "resumo-v1" :incerteza "revisar_com_atencao" :n-citacoes 2 :n-citacoes-conferidas 2
               :n-paragrafos-sem-fonte 1 :ocorrido-em (Instant/parse "2026-09-27T01:00:00Z")})

(defn- versao [n base]
  {:versao n :conteudo-sha256 "sha256:c" :texto-base-sha256 base :origem-redacao "gerada_automaticamente"
   :rascunho-id rid :modelo-llm-id "fake:fake-1" :prompt-versao "resumo-v1" :publicado-por eu
   :publicado-em (Instant/parse "2026-09-27T02:00:00Z")})

(defn- fake-repo [{:keys [base publicados publicar]
                   :or {base "sha256:v1" publicados (atom [])}}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-leg/RepoLegislativo
    (resumo-da-proposicao [_ _ id]
      (when (= id pid)
        {:proposicao {:id pid} :texto-base-sha256 base :rascunho ponteiro
         :atual (assoc (versao 1 "sha256:v1") :texto "Cria hortas.") :versoes [(versao 1 "sha256:v1")]}))
    (buscar-rascunho-resumo-pronto [_ _ p r] (when (and (= p pid) (= r rid)) ponteiro))
    (publicar-resumo! [_ e m]
      (when (= (:proposicao-id m) pid)
        (if publicar (publicar m) (do (swap! publicados conj [e m]) {:versao 2 :conteudo-sha256 "sha256:novo"}))))))

(defn- fake-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ _] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- servico [repo & {:keys [papeis ler] :or {papeis #{"secretario"}}}]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-identidade papeis))]
    (-> (http/servico (config/carregar) (leg-http/rotas {:auth auth :repo-legislativo repo :ler-rascunho-resumo ler})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- cab [] {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente)
                                                                            :identidade-id (str eu)}))
               "Content-Type" "application/json"})
(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(def base-url (str "/legislativo/proposicoes/" pid "/resumo"))

(deftest le-o-rascunho-a-versao-e-o-que-ficou-para-tras
  (let [b (ler (pt/response-for (servico (fake-repo {})) :get base-url :headers (cab)))]
    (is (= ["pronto" (str rid) false "revisar_com_atencao"]
           ((juxt :situacao :rascunho-id :desatualizado :incerteza) (:rascunho b))))
    (is (= [1 "Cria hortas." false] [(get-in b [:atual :versao :versao]) (get-in b [:atual :texto])
                                     (get-in b [:atual :versao :desatualizado])])))
  (testing "o texto mudou: rascunho e versao ficaram para tras"
    (let [b (ler (pt/response-for (servico (fake-repo {:base "sha256:v2"})) :get base-url :headers (cab)))]
      (is (true? (get-in b [:rascunho :desatualizado])))
      (is (true? (get-in b [:atual :versao :desatualizado])))))
  (is (= 404 (:status (pt/response-for (servico (fake-repo {})) :get (str "/legislativo/proposicoes/" (random-uuid) "/resumo")
                                       :headers (cab))))))

(deftest rascunho-so-de-id-registrado-e-ia-fora-e-503
  (let [ia (fn [e r] (when (and (= ente e) (= rid r))
                       {:texto "Resumo. [[proposicao:x#ementa | Resumo]]" :texto-limpo "Resumo."
                        :incerteza {:nivel "revisar_com_atencao" :motivos ["conteudo_de_terceiro"]}
                        :citacoes [{:fonte-id "proposicao:x#ementa" :trecho "Resumo" :inicio 8 :fim 40 :status "conferida"
                                    :rotulo "PL 7/2026 — ementa"}]
                        :paragrafos-sem-fonte [] :texto-base-sha256 "sha256:v1"}))
        svc (servico (fake-repo {}) :ler ia)
        r (pt/response-for svc :get (str base-url "/rascunhos/" rid) :headers (cab))
        b (ler r)]
    (is (= 200 (:status r)))
    (is (= ["Resumo." "fake:fake-1" false] [(:texto-limpo b) (:modelo-llm-id b) (:desatualizado b)]))
    (is (= ["conferida"] (mapv :status (:citacoes b))))
    (is (= 404 (:status (pt/response-for svc :get (str base-url "/rascunhos/" (random-uuid)) :headers (cab))))
        "id que o core nao registrou para esta proposicao: nunca vai a IA")
    (let [r (pt/response-for (servico (fake-repo {})) :get (str base-url "/rascunhos/" rid) :headers (cab))]
      (is (= 503 (:status r)))
      (is (re-find #"Siga pela tela" (:erro (ler r)))))))

(deftest publica-com-o-ator-e-traduz-conflitos
  (let [publicados (atom [])
        svc (servico (fake-repo {:publicados publicados}))
        r (pt/response-for svc :post base-url :headers (cab)
                           :body (json/write-value-as-string {:texto "  Cria hortas.  " :rascunho-id (str rid)}))]
    (is (= 201 (:status r)))
    (is (= {:versao 2 :conteudo-sha256 "sha256:novo"} (ler r)))
    (is (= [[ente {:proposicao-id pid :texto "Cria hortas." :publicado-por eu :rascunho-id rid}]] @publicados)))
  (doseq [corpo [{} {:texto "   "} {:texto (apply str (repeat 4001 "a"))} {:texto "X" :rascunho-id "nao-uuid"}]]
    (is (= 400 (:status (pt/response-for (servico (fake-repo {})) :post base-url :headers (cab)
                                         :body (json/write-value-as-string corpo))))
        (pr-str corpo)))
  (doseq [[tipo msg] [[:conflito/rascunho-desconhecido #"rascunho pronto"] [:conflito/resumo-versao #"recarregue"]]]
    (let [r (pt/response-for (servico (fake-repo {:publicar (fn [_] (throw (ex-info "x" {:tipo tipo})))}))
                             :post base-url :headers (cab) :body (json/write-value-as-string {:texto "X"}))]
      (is (= 409 (:status r)))
      (is (re-find msg (:erro (ler r)))))))

(deftest so-a-secretaria
  (let [svc (servico (fake-repo {}) :papeis #{"vereador"})]
    (is (= 403 (:status (pt/response-for svc :get base-url :headers (cab)))))
    (is (= 403 (:status (pt/response-for svc :post base-url :headers (cab) :body "{\"texto\":\"X\"}"))))))
