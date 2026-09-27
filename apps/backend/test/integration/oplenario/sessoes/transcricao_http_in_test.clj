(ns oplenario.sessoes.transcricao-http-in-test
  "Faixa A / A.3 (ADR-0008): as leituras da transcricao na borda de sessoes. DB-free: RepoSessoes FAKE + seam
  `ler-transcricao` FAKE. Prova a authz (secretaria + mesma Casa), que o core so' pede a IA um id que ELE
  registrou para a sessao, e o R-IA-1 (IA fora -> 503 com mensagem de tela, nunca 500)."
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
(def tid #uuid "50000000-0000-0000-0000-000000000005")

(def ponteiro {:id (random-uuid) :segmento-id (random-uuid) :situacao "concluida" :transcricao-id tid :versao 1
               :idioma "pt" :duracao-s 54.96M :n-trechos 2 :cobertura-atribuida 0.5M :modelo-asr "whisper-turbo-int8"
               :modelo-diarizacao nil :ocorrido-em (Instant/parse "2026-09-26T20:53:54Z")})

(defn- fake-repo [casa]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ _ id] {:id id :ente-id casa :estado "encerrada" :tipo-sessao "ordinaria"})
    (listar-transcricoes [_ _ _] [ponteiro (assoc ponteiro :situacao "falhou" :transcricao-id nil :versao nil
                                                  :categoria-erro "entrada" :detalhe-erro "audio ilegivel"
                                                  :retentavel false)])
    (buscar-transcricao [_ _ _ t] (when (= t tid) ponteiro))))

(defn- fake-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ _] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- servico [& {:keys [papeis casa ler] :or {papeis #{"secretario"} casa ente}}]
  (let [auth (it/autenticacao (idp-dev/idp-dev) (fake-identidade papeis))]
    (-> (http/servico (config/carregar)
                      (sessoes-http/rotas {:auth auth :repo-sessoes (fake-repo casa)
                                           :roster-da-casa-em-datas (fn [& _]) :resumir-proposicoes (fn [& _])
                                           :nome-na-casa (fn [& _]) :ler-transcricao ler})
                      it/globais)
        ph/create-server ::ph/service-fn)))

(defn- tok [] {"authorization" (str "Bearer " (json/write-value-as-string {:sub "u" :ente-id (str ente)
                                                                            :identidade-id (str (random-uuid))}))})
(defn- ler [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest lista-a-situacao-de-cada-gravacao
  (let [r (pt/response-for (servico) :get (str "/sessoes/" sid "/transcricoes") :headers (tok))
        [ok falha] (:itens (ler r))]
    (is (= 200 (:status r)))
    (is (= ["concluida" 2 54.96 "whisper-turbo-int8"] [(:situacao ok) (:n-trechos ok) (:duracao-s ok) (:modelo-asr ok)]))
    (is (= ["falhou" "entrada" "audio ilegivel"] [(:situacao falha) (:categoria-erro falha) (:detalhe-erro falha)]))))

(deftest le-o-texto-da-ia-so-para-id-registrado
  (let [pedidos (atom [])
        ler-fn (fn [e t] (swap! pedidos conj [e t])
                 {:trechos [{:inicio 0.61 :fim 4.02 :texto "Declaro aberta a sessão." :grupo "SPK_0"
                             :orador-id "v-1" :orador-nome "Antônio Ferreira"}
                            {:inicio 5 :fim 9 :texto "…" :grupo "SPK_9" :orador-id nil :orador-nome nil}]})
        r (pt/response-for (servico :ler ler-fn) :get (str "/sessoes/" sid "/transcricoes/" tid) :headers (tok))
        b (ler r)]
    (is (= 200 (:status r)))
    (is (= "Antônio Ferreira" (get-in b [:trechos 0 :orador-nome])))
    (is (nil? (get-in b [:trechos 0 :grupo])) "o grupo de voz e' interno da IA: nao sai")
    (is (= 5.0 (get-in b [:trechos 1 :inicio])))
    (is (= [[ente tid]] @pedidos) "o tenant do ator vai para a IA")
    (testing "id que o core nao registrou para a sessao: 404 sem perguntar a IA"
      (reset! pedidos [])
      (is (= 404 (:status (pt/response-for (servico :ler ler-fn) :get (str "/sessoes/" sid "/transcricoes/" (random-uuid))
                                           :headers (tok)))))
      (is (empty? @pedidos)))))

(deftest ia-fora-do-ar-e-r-ia-1
  (let [r (pt/response-for (servico :ler (fn [_ _] (throw (ex-info "x" {:tipo :ia/indisponivel}))))
                           :get (str "/sessoes/" sid "/transcricoes/" tid) :headers (tok))]
    (is (= 503 (:status r)))
    (is (re-find #"Siga pela tela" (:erro (ler r)))))
  (is (= 503 (:status (pt/response-for (servico) :get (str "/sessoes/" sid "/transcricoes/" tid) :headers (tok))))
      "sem o seam (IA nao configurada): 503, nunca 500"))

(deftest authz
  (is (= 403 (:status (pt/response-for (servico :papeis #{"vereador"}) :get (str "/sessoes/" sid "/transcricoes")
                                       :headers (tok)))))
  (is (= 403 (:status (pt/response-for (servico :casa (random-uuid)) :get (str "/sessoes/" sid "/transcricoes")
                                       :headers (tok))))
      "sessao de outra Casa (escapou da RLS): a camada fina nega"))
