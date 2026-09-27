(ns oplenario.busca-http-in-test
  "Faixa A / A.5: a busca intra-camara na borda. DB-free: seams falsos. Prova o contrato (so' passa o que o core
  reencontrou, a proposicao uma vez so', o texto do core), o gate de papel, o 400 e o R-IA-1 (IA fora -> 200 com a
  busca literal da ementa e o aviso)."
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.busca :as busca]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]))

(def ente #uuid "10000000-0000-0000-0000-000000000001")
(def p1 #uuid "a0000000-0000-0000-0000-000000000001")
(def p-alheia #uuid "a0000000-0000-0000-0000-000000000002")
(def sid #uuid "b0000000-0000-0000-0000-000000000001")
(def tid #uuid "c0000000-0000-0000-0000-000000000001")
(def sid-secreta #uuid "b0000000-0000-0000-0000-000000000002")
(def tid-secreta #uuid "c0000000-0000-0000-0000-000000000002")

(defn- trecho [sid tid texto score]
  {:tipo "transcricao" :ref-id (str (random-uuid)) :parte 0 :texto texto :score score
   :meta {:sessao-id (str sid) :segmento-id (str (random-uuid)) :transcricao-id (str tid) :inicio 125.5 :fim 140.0
          :orador "Ver. Ana"}})

(def resposta-ia
  {:modelo "fake-hash-384"
   :resultados [{:tipo "proposicao" :ref-id (str p1) :parte 0 :texto "Merenda escolar. Autoria: Ver. Ana." :score 0.9}
                (trecho sid tid "A merenda da escola do bairro chegou atrasada." 0.8)
                {:tipo "proposicao" :ref-id (str p1) :parte 1 :texto "outro trecho da mesma" :score 0.7}
                {:tipo "proposicao" :ref-id (str p-alheia) :parte 0 :texto "de outra Casa?" :score 0.6}
                (trecho sid-secreta tid-secreta "dito em sessao secreta" 0.5)
                {:tipo "proposicao" :ref-id "nao-e-uuid" :parte 0 :texto "lixo" :score 0.4}]})

(defn- seams [& {:keys [ia-fora? pedidos]}]
  {:buscar-ia (fn [e p]
                (some-> pedidos (swap! conj [e p]))
                (if ia-fora?
                  (throw (ex-info "fora" {:tipo :ia/indisponivel}))
                  resposta-ia))
   :resumir-proposicoes (fn [_ ids] (select-keys {p1 {:tipo "projeto_lei" :ano 2026 :sequencial 7
                                                       :ementa "Dispõe sobre a merenda escolar." :autor-texto "Ver. Ana"}}
                                                 ids))
   :sessoes-das-transcricoes (fn [_ pares] (select-keys {[sid tid] {:id sid :tipo "ordinaria" :numero 12
                                                                    :data "2026-09-20T18:00:00Z"}}
                                                        pares))
   :proposicoes-por-ementa (fn [_ q n] [{:id p1 :tipo "projeto_lei" :ano 2026 :sequencial 7 :ementa (str "com " q)
                                         :limite n}])})

(defn- fake-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ _] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- servico [s & {:keys [papeis] :or {papeis #{"secretario"}}}]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-identidade papeis))]
    (-> (http/servico (config/carregar) (busca/rotas {:auth auth :seams s}) it/globais)
        ph/create-server ::ph/service-fn)))

(defn- cab [] {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente)
                                                                            :identidade-id (str (random-uuid))}))})
(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- get-busca [svc qs] (pt/response-for svc :get (str "/busca?" qs) :headers (cab)))

(deftest so-passa-o-que-o-core-reencontrou
  (let [pedidos (atom [])
        r (get-busca (servico (seams :pedidos pedidos)) "q=merenda%20escolar")
        b (ler r)]
    (is (= 200 (:status r)))
    (is (= "ia" (:modo b)))
    (is (= [[ente {:consulta "merenda escolar" :tipos ["proposicao" "transcricao"] :limite 30}]] @pedidos)
        "o tenant vem do ator, nunca do cliente")
    (is (= ["proposicao" "transcricao"] (mapv :tipo (:resultados b)))
        "a mesma proposicao uma vez so'; a de outra Casa, a da sessao secreta e o id torto somem")
    (let [[p t] (:resultados b)]
      (is (= "Dispõe sobre a merenda escolar." (get-in p [:proposicao :ementa])) "o texto da proposicao e' o do core")
      (is (= (str p1) (get-in p [:proposicao :id])))
      (is (= "Merenda escolar. Autoria: Ver. Ana." (:trecho p)) "o melhor trecho, o primeiro")
      (is (= [(str sid) 12 (str tid) 125.5 "Ver. Ana"]
             [(get-in t [:sessao :id]) (get-in t [:sessao :numero]) (:transcricao-id t) (:inicio t) (:orador t)])))))

(deftest tipos-filtra-o-pedido
  (let [pedidos (atom [])]
    (get-busca (servico (seams :pedidos pedidos)) "q=merenda&tipos=transcricao")
    (is (= ["transcricao"] (:tipos (second (first @pedidos)))))))

(deftest ia-fora-cai-na-ementa-com-aviso
  (let [b (ler (get-busca (servico (seams :ia-fora? true)) "q=merenda"))]
    (is (= "sem-ia" (:modo b)))
    (is (= busca/aviso-sem-ia (:aviso b)))
    (is (= ["com merenda"] (map #(get-in % [:proposicao :ementa]) (:resultados b)))))
  (testing "so' transcricoes pedidas: nada a mostrar sem a IA, mas nunca erro"
    (let [r (get-busca (servico (seams :ia-fora? true)) "q=merenda&tipos=transcricao")]
      (is (= [200 []] [(:status r) (:resultados (ler r))])))))

(deftest pedido-invalido-e-400
  (doseq [qs ["" "q=a" (str "q=" (apply str (repeat 301 "a"))) "q=merenda&tipos=normas"]]
    (is (= 400 (:status (get-busca (servico (seams)) qs))) qs)))

(deftest so-a-secretaria
  (is (= 403 (:status (get-busca (servico (seams) :papeis #{"vereador"}) "q=merenda"))))
  (is (= 401 (:status (pt/response-for (servico (seams)) :get "/busca?q=merenda")))))
