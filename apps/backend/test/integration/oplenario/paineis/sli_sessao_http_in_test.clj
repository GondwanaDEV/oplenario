(ns oplenario.paineis.sli-sessao-http-in-test
  "F7 E3 (borda HTTP do paineis) — a vertical de rota do SLI de janela de sessao (Inv.9): prova a silhueta de
  borda end-to-end (controller -> repo -> adapters/out -> wire/out), a DERIVACAO (situacao/duracao), a authz
  grossa (exige-papel) + 401. DB-free: RepoPaineis FAKE (reify) + idp-dev real (precedente
  tramitacao-http-in-test)."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.paineis.components.repositorio :as repo-paineis]
            [oplenario.rotas :as rotas])
  (:import (java.time Instant)))

(defn- fake-repo-paineis
  "`total` default = (count resultado) — os testes que nao se importam com o par sessoes/sessoes-total
  continuam validos; o teste que PROVA que sessoes-total e' autoritativo (nao derivado do tamanho da
  lista) passa um `total` explicito e diferente."
  ([resultado] (fake-repo-paineis resultado (count resultado)))
  ([resultado total]
   #_{:clj-kondo/ignore [:missing-protocol-method]}
   (reify repo-paineis/RepoPaineis
     (sli-sessoes [_ _ente-id] {:sessoes resultado :sessoes-total total}))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn [papeis repo-p]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-paineis repo-p})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(defn- sessao-encerrada []
  {:sessao-id (random-uuid) :estado-atual "encerrada"
   :agendada-para (Instant/parse "2026-07-01T13:00:00Z")
   :aberta-em (Instant/parse "2026-07-01T13:00:00Z")
   :encerrada-em (Instant/parse "2026-07-01T15:30:00Z")})   ; 2h30 = 9000s

(defn- sessao-em-curso []
  {:sessao-id (random-uuid) :estado-atual "aberta"
   :aberta-em (Instant/parse "2026-07-01T13:00:00Z") :encerrada-em nil})

;; ---------- GET /paineis/sli/sessoes ----------

(deftest sli-200-deriva-situacao-e-duracao
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis [(sessao-encerrada)]))
                           :get "/paineis/sli/sessoes" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)) "GET /paineis/sli/sessoes com papel secretario -> 200")
    (is (= 1 (count (:sessoes body))))
    (let [s (first (:sessoes body))]
      (is (= "realizada" (:situacao s)) "encerrada -> situacao derivada 'realizada'")
      (is (= 9000 (:duracao-segundos s)) "janela fechada em segundos (2h30)")
      (is (= "2026-07-01T13:00:00Z" (:agendada-para s)) "agendada-para exposto no wire (p/ o FE derivar no-show)")
      (is (string? (:sessao-id s)) "sessao-id como string")
      (is (not (contains? s :ente-id)) "ente-id (tenant) nao vaza"))))

(deftest sli-em-curso-sem-duracao
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis [(sessao-em-curso)]))
                           :get "/paineis/sli/sessoes" :headers (com-bearer (token (random-uuid) (random-uuid))))
        s (first (:sessoes (ler-json r)))]
    (is (= 200 (:status r)))
    (is (= "em_curso" (:situacao s)) "aberta -> 'em_curso'")
    (is (nil? (:duracao-segundos s)) "sessao em curso nao tem janela fechada (duracao viva e' do cliente)")))

(deftest sli-vazio-200
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis []))
                           :get "/paineis/sli/sessoes" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= [] (:sessoes (ler-json r))))
    (is (= 0 (:sessoes-total (ler-json r))))))

;; ---------- fatia "truncamento-familia" sitio (a): sessoes-total e' AUTORITATIVO, nao count(sessoes) ----------

(deftest sli-sessoes-total-e-independente-do-tamanho-da-lista
  ;; regra 3/4 da familia: se o wire alguma vez regredisse para `(count sessoes)` em vez de repassar o
  ;; `sessoes-total` que o Repo mandou, este teste nomeia o numero errado — o fake devolve 2 sessoes na
  ;; lista e um total de 7 (simulando o corte: so' 2 de 7 sessoes vistas couberam no teto).
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis [(sessao-encerrada) (sessao-em-curso)] 7))
                           :get "/paineis/sli/sessoes" :headers (com-bearer (token (random-uuid) (random-uuid))))
        body (ler-json r)]
    (is (= 200 (:status r)))
    (is (= 2 (count (:sessoes body))) "a lista trouxe so' 2 (o corte)")
    (is (= 7 (:sessoes-total body)) "o total e' o numero real do servidor, nao count(sessoes)")))

(deftest sli-papel-sem-leitura-403
  (let [r (pt/response-for (service-fn #{"cidadao"} (fake-repo-paineis []))
                           :get "/paineis/sli/sessoes" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 403 (:status r)) "papel sem leitura (nem secretario nem vereador) -> 403")))

(deftest sli-vereador-le-200
  (let [r (pt/response-for (service-fn #{"vereador"} (fake-repo-paineis []))
                           :get "/paineis/sli/sessoes" :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 200 (:status r)) "vereador agora LE o SLI de sessoes")))

(deftest sli-sem-token-401
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-paineis []))
                           :get "/paineis/sli/sessoes")]
    (is (= 401 (:status r)) "rota de modulo herda a cadeia de auth: sem token -> 401 (fail-closed)")))
