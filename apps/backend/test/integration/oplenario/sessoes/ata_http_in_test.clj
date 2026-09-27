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
            [oplenario.kernel.tempo :as tempo]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.diplomat.http.in :as sessoes-http])
  (:import (java.time Instant)))

(def ente #uuid "10000000-0000-0000-0000-000000000001")
(def sid #uuid "30000000-0000-0000-0000-000000000003")
(def quem #uuid "40000000-0000-0000-0000-000000000004")

(defn- versao [n m]
  (merge {:id (random-uuid) :sessao-id sid :versao n :origem-redacao "redigida_externamente" :conteudo-sha256 "sha256:ab"
          :publicada-por quem :publicada-em (Instant/parse "2026-09-26T21:00:00Z")} m))

(def rid #uuid "70000000-0000-0000-0000-000000000007")
(def pronto {:solicitacao-id (random-uuid) :situacao "pronto" :rascunho-id rid :modelo-llm-id "fake:fake-1"
             :prompt-versao "ata-v1" :incerteza "revisar_com_atencao" :n-pontos-a-confirmar 1
             :solicitado-em (Instant/parse "2026-09-26T21:00:00Z") :ocorrido-em (Instant/parse "2026-09-26T21:02:00Z")})

(defn- fake-repo [{:keys [casa estado tipo versoes gravadas transcricoes pedidos ultimo]
                   :or {casa ente estado "encerrada" tipo "ordinaria" versoes [] transcricoes [{:situacao "concluida"}]}}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ _ id] (when (= id sid) {:id id :ente-id casa :estado estado :tipo-sessao tipo
                                              :gera-ata-regimental true}))
    (ata-da-sessao [_ _ _] {:atual (some-> (first versoes) (assoc :texto "Aos vinte e seis dias...")) :versoes versoes
                            :rascunho ultimo})
    (listar-transcricoes [_ _ _] transcricoes)
    (solicitar-rascunho-ata! [_ _ m]
      (when-not ((:pode-pedir? m) ultimo)
        (throw (ex-info "a IA ja' esta' redigindo o rascunho desta ata: aguarde" {:tipo :conflito/rascunho-em-curso})))
      (swap! pedidos conj m)
      {:solicitacao-id (:solicitacao-id m)})
    (buscar-rascunho-pronto [_ _ s r] (when (and (= s sid) (= r rid)) pronto))
    (publicar-ata! [_ e m] (swap! gravadas conj [e m])
      (if (and (seq versoes) (nil? (:motivo-retificacao m)))
        (throw (ex-info "a ata ja' foi publicada: para retificar, informe o motivo" {:tipo :validacao/retificacao-sem-motivo}))
        {:id (random-uuid) :versao (inc (count versoes)) :conteudo-sha256 (:conteudo-sha256 m)}))))

(defn- fake-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ _] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- servico [& {:keys [papeis nome ler-rascunho]
                   :or {papeis #{"secretario"} nome (fn [_ i] (when (= i quem) "Maria Secretária"))}
                   :as opts}]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-identidade papeis))]
    (-> (http/servico (config/carregar)
                      (sessoes-http/rotas {:auth auth :repo-sessoes (fake-repo opts)
                                           :roster-da-casa-em-datas (fn [& _]) :resumir-proposicoes (fn [& _])
                                           :nome-na-casa nome :ler-rascunho-ata ler-rascunho
                                           :relogio (tempo/relogio-fixo (Instant/parse "2026-09-26T21:10:00Z"))})
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
  (is (= 403 (:status (post (servico :papeis #{"vereador"} :gravadas (atom [])) {"texto" "Ata."}))))
  (is (= 403 (:status (pt/response-for (servico :casa (random-uuid)) :get (str "/sessoes/" sid "/ata") :headers (cab))))
      "sessao de outra Casa (escapou da RLS): a camada fina nega"))

;; ---------- A.6b: o rascunho da IA ----------

(defn- pedir [svc] (pt/response-for svc :post (str "/sessoes/" sid "/ata/rascunhos") :headers (cab)))

(deftest pede-o-rascunho
  (let [pedidos (atom [])
        r (pedir (servico :pedidos pedidos))
        [m] @pedidos]
    (is (= 202 (:status r)))
    (is (= (str (:solicitacao-id m)) (:solicitacao-id (ler r))))
    (is (= [eu sid (Instant/parse "2026-09-26T21:10:00Z")] [(:solicitado-por m) (:sessao-id m) (:ocorrido-em m)])
        "quem pede vem do ator; a hora, do relogio do servidor"))
  (testing "recusas com a razao"
    (doseq [[opts re] [[{:estado "aberta"} #"nao tem ata"]
                       [{:tipo "secreta"} #"secreta nao vai para a IA"]
                       [{:transcricoes [{:situacao "falhou"}]} #"transcricao concluida"]
                       [{:ultimo (assoc pronto :situacao "solicitado" :solicitado-em (Instant/parse "2026-09-26T21:00:00Z"))}
                        #"ja' esta' redigindo"]]]
      (let [r (pedir (apply servico (mapcat identity (assoc opts :pedidos (atom [])))))]
        (is (= 409 (:status r)) (pr-str opts))
        (is (re-find re (:erro (ler r))) (pr-str opts)))))
  (is (= 202 (:status (pedir (servico :pedidos (atom [])
                                      :ultimo (assoc pronto :situacao "solicitado"
                                                            :solicitado-em (Instant/parse "2026-09-26T20:30:00Z"))))))
      "pedido sem resposta ha' mais de 30 min: pode pedir de novo")
  (is (= 403 (:status (pedir (servico :papeis #{"vereador"} :pedidos (atom [])))))))

(deftest a-situacao-do-rascunho-vem-na-ata
  (let [b (ler (pt/response-for (servico :ultimo pronto) :get (str "/sessoes/" sid "/ata") :headers (cab)))]
    (is (= ["pronto" (str rid) "revisar_com_atencao" 1 "2026-09-26T21:00:00Z"]
           ((juxt :situacao :rascunho-id :incerteza :n-pontos-a-confirmar :solicitado-em) (:rascunho b))))))

(def conteudo-ia
  {:id (str rid) :texto "Ana falou. [[transcricao:t#1 | Senhor presidente]]" :texto-limpo "Ana falou."
   :incerteza {:nivel "revisar_com_atencao" :motivos ["conteudo_de_terceiro"]}
   :citacoes [{:fonte-id "transcricao:t#1" :trecho "Senhor presidente" :inicio 11 :fim 51 :status "conferida"
               :rotulo "Ana, 0:10–0:40" :extra "nao passa"}]
   :paragrafos-sem-fonte [] :pontos-a-confirmar [] :vendor "fake" :segredo-interno "x"})

(deftest le-o-rascunho-da-ia
  (let [pedidos (atom [])
        ler-fn (fn [e r] (swap! pedidos conj [e r]) conteudo-ia)
        r (pt/response-for (servico :ler-rascunho ler-fn) :get (str "/sessoes/" sid "/ata/rascunhos/" rid) :headers (cab))
        b (ler r)]
    (is (= 200 (:status r)))
    (is (= [[ente rid]] @pedidos) "o tenant do ator vai para a IA")
    (is (= ["Ana falou." "conferida" "fake:fake-1" "ata-v1"]
           [(:texto-limpo b) (get-in b [:citacoes 0 :status]) (:modelo-llm-id b) (:prompt-versao b)]))
    (is (nil? (:vendor b)) "so' o contrato atravessa")
    (testing "rascunho que o core nao registrou para a sessao: 404 sem perguntar a IA"
      (reset! pedidos [])
      (is (= 404 (:status (pt/response-for (servico :ler-rascunho ler-fn) :get
                                           (str "/sessoes/" sid "/ata/rascunhos/" (random-uuid)) :headers (cab)))))
      (is (empty? @pedidos))))
  (let [r (pt/response-for (servico :ler-rascunho (fn [_ _] (throw (ex-info "x" {:tipo :ia/indisponivel}))))
                           :get (str "/sessoes/" sid "/ata/rascunhos/" rid) :headers (cab))]
    (is (= 503 (:status r)))
    (is (re-find #"Siga pela tela" (:erro (ler r))))))

(deftest publica-a-ata-que-partiu-do-rascunho
  (let [gravadas (atom [])
        r (post (servico :gravadas gravadas) {"texto" "Ana falou." "origem-redacao" "gerada_automaticamente"
                                             "rascunho-id" (str rid) "modelo-llm-id" "inventado"})
        [[_ m]] @gravadas]
    (is (= 201 (:status r)))
    (is (= ["gerada_automaticamente" rid "fake:fake-1" "ata-v1"]
           [(:origem-redacao m) (:rascunho-id m) (:modelo-llm-id m) (:prompt-versao m)])
        "a proveniencia vem do ponteiro do core, nunca do corpo"))
  (is (= 409 (:status (post (servico :gravadas (atom [])) {"texto" "x" "origem-redacao" "gerada_automaticamente"
                                                          "rascunho-id" (str (random-uuid))})))
      "rascunho que nao e' desta sessao")
  (is (= 400 (:status (post (servico :gravadas (atom [])) {"texto" "x" "origem-redacao" "gerada_automaticamente"})))
      "gerada sem rascunho-id"))
