(ns oplenario.sessoes.tempos-regimentais-http-in-test
  "INTEGRACAO HTTP (DB-free): a tela \"Tempos da tribuna\" da secretaria — `GET`/`PUT /tempos-regimentais`
  (papel 'secretario'). A rota mora no TOPO (como `/assiduidade` e `/gravacoes`): sob `/sessoes/` o `:id` de
  `/sessoes/:id` sombrearia o literal (limitacao do router prefix-tree do Pedestal 0.7 — ver
  `assiduidade-rotas-http-in-test`). RepoSessoes FAKE exercitando `rotas/montar` de verdade."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.components.objeto-store :as os]
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]))

(defn- fake-repo-sessoes
  "`tabela` = atom com a tabela da Casa; `chamadas` = atom que registra cada substituir (ente itens autor)."
  [tabela chamadas]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ _ente-id _id] nil)
    (listar-tempos-regimentais [_ _ente-id] @tabela)
    (substituir-tempos-regimentais! [_ ente-id itens autor]
      (swap! chamadas conj [ente-id itens autor])
      (reset! tabela (vec itens)))))

(defn- fake-repo-cadastros []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- fake-objeto-store []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify os/ObjetoStore
    (guardar! [_ chave _b _content-type] chave)
    (obter [_ _chave] nil)))

(defn- servico [papeis repo-s]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :repo-cadastros (fake-repo-cadastros)
                                   :objeto-store (fake-objeto-store)})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- cabecalhos [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- put! [svc tok corpo]
  (pt/response-for svc :put "/tempos-regimentais" :headers (cabecalhos tok)
                   :body (if (string? corpo) corpo (json/write-value-as-string corpo))))

(deftest get-devolve-a-tabela-da-casa
  (let [tabela (atom [{:fase nil :tipo-fala "principal" :segundos 180 :referencia-normativa "RI art. 98"}
                      {:fase "ordem_do_dia" :tipo-fala "principal" :segundos 600 :referencia-normativa nil}])
        svc (servico #{"secretario"} (fake-repo-sessoes tabela (atom [])))
        r (pt/response-for svc :get "/tempos-regimentais" :headers (cabecalhos (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= [{:fase nil :tipo-fala "principal" :segundos 180 :referencia-normativa "RI art. 98"}
            {:fase "ordem_do_dia" :tipo-fala "principal" :segundos 600 :referencia-normativa nil}]
           (:itens body))
        "a linha generica (fase nil) vem antes da especifica do mesmo tipo")))

(deftest put-substitui-a-tabela-com-o-autor-do-login
  (let [ente (random-uuid) ident (random-uuid)
        tabela (atom []) chamadas (atom [])
        svc (servico #{"secretario"} (fake-repo-sessoes tabela chamadas))
        r (put! svc (token ente ident)
                {:itens [{:fase nil :tipo-fala "principal" :segundos 180 :referencia-normativa "RI art. 98"}
                         {:fase "ordem_do_dia" :tipo-fala "aparte" :segundos 60}]})]
    (is (= 200 (:status r)))
    (is (= 2 (count (:itens (ler-json r)))) "devolve a tabela como ficou")
    (let [[e itens autor] (first @chamadas)]
      (is (= ente e) "a Casa vem do token, nunca do corpo")
      (is (= ident autor) "o autor e' quem esta logado")
      (is (= [{:fase nil :tipo-fala "principal" :segundos 180 :referencia-normativa "RI art. 98"}
              {:fase "ordem_do_dia" :tipo-fala "aparte" :segundos 60 :referencia-normativa nil}]
             itens)))))

(deftest put-tabela-vazia-e-valido
  (let [chamadas (atom [])
        svc (servico #{"secretario"} (fake-repo-sessoes (atom []) chamadas))
        r (put! svc (token (random-uuid) (random-uuid)) {:itens []})]
    (is (= 200 (:status r)) "a Casa pode voltar a nao ter limite")
    (is (= [] (second (first @chamadas))))))

(deftest put-invalido-e-400-sem-tocar-o-repo
  (let [chamadas (atom [])
        svc (servico #{"secretario"} (fake-repo-sessoes (atom []) chamadas))
        tok (token (random-uuid) (random-uuid))]
    (doseq [[corpo porque] [[{:itens [{:tipo-fala "aparte" :segundos 60} {:tipo-fala "aparte" :segundos 90}]}
                             "o mesmo (fase, tipo) duas vezes"]
                            [{:itens [{:tipo-fala "aparte" :segundos 0}]} "segundos > 0"]
                            [{:itens [{:tipo-fala "aparte" :segundos 1.5}]} "segundos inteiro"]
                            [{:itens [{:tipo-fala "cochicho" :segundos 60}]} "tipo fora do vocabulario"]
                            [{:itens [{:fase "recreio" :tipo-fala "aparte" :segundos 60}]} "fase fora do vocabulario"]
                            [{:itens [{:tipo-fala "aparte" :segundos 60 :ente-id "x"}]} "campo a mais no item"]
                            [{:itens [{:tipo-fala "aparte" :segundos 60
                                       :referencia-normativa (apply str (repeat 201 "a"))}]}
                             "referencia normativa longa demais"]
                            [{} "sem itens"]
                            ["[1,2]" "corpo que nao e' objeto"]]]
      (is (= 400 (:status (put! svc tok corpo))) porque))
    (is (empty? @chamadas) "nada invalido chega ao banco")))

(deftest so-a-secretaria
  (let [svc (servico #{"vereador"} (fake-repo-sessoes (atom []) (atom [])))
        tok (token (random-uuid) (random-uuid))]
    (is (= 403 (:status (pt/response-for svc :get "/tempos-regimentais" :headers (cabecalhos tok)))))
    (is (= 403 (:status (put! svc tok {:itens []})))))
  (let [svc (servico #{"secretario"} (fake-repo-sessoes (atom []) (atom [])))]
    (is (= 401 (:status (pt/response-for svc :get "/tempos-regimentais"))))))

(deftest a-rota-irma-sessoes-id-continua-roteando
  (let [svc (servico #{"secretario"} (fake-repo-sessoes (atom []) (atom [])))
        r (pt/response-for svc :get (str "/sessoes/" (random-uuid))
                           :headers (cabecalhos (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)) "/sessoes/:id segue no buscar-handler (o fake devolve nil -> 404)")))
