(ns oplenario.fluxo-lgpd-test
  "E2E da BORDA HTTP do PORTAL DO TITULAR LGPD (F6 Slice 4) — a vertical de rota ponta-a-ponta (adapters/in ->
  controller -> repo -> adapters/out -> wire/out) + os TRES perfis de authz. DB-free: RepoParticipacao FAKE
  (reify) + idp-dev real (espelha fluxo_esic_test). Relogio FIXO injetado no fragmento de rotas. Foco de
  seguranca: a rota PUBLICA do contato do DPO NAO vaza interno (ids/atualizado-por) e o :ente malformado
  fail-closa (400, nunca cross-tenant); a rota do titular aplica policy FINA (403); o prazo LGPD e' um CONTADOR
  SEPARADO do e-SIC."
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.participacao.components.repositorio :as repo-part]
            [oplenario.participacao.diplomat.http.in :as participacao-http])
  (:import (java.time Instant LocalDate)))

;; relogio fixo: 12:00Z de 2026-07-03 -> zona civil America/Fortaleza = 2026-07-03; vence titular (fixture)
;; 2026-07-18 (recibo + 15 default LGPD) -> dias-restantes = 15 (CONTADOR SEPARADO do e-SIC, que seria 20).
(def ^:private t0 (Instant/parse "2026-07-03T12:00:00Z"))
(def ^:private relogio (tempo/relogio-fixo t0))
(def ^:private vence-titular (LocalDate/of 2026 7 18))

(defn- fake-repo-participacao
  [{:keys [solicitar solicitacao-com-prazo buscar-solicitacao responder definir-encarregado buscar-encarregado]}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-part/RepoParticipacao
    (solicitar-titular! [_ _ente _m] solicitar)
    (solicitacao-titular-com-prazo [_ _ente _id] solicitacao-com-prazo)
    (buscar-solicitacao-titular [_ _ente _id] buscar-solicitacao)
    (responder-solicitacao! [_ _ente _m] responder)
    (definir-encarregado! [_ _ente _m] definir-encarregado)
    (buscar-encarregado [_ _ente] buscar-encarregado)))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "cidadao"} :papeis papeis})))

(defn- service-fn [papeis repo-part]
  (let [auth  (it/autenticacao (idp-dev/idp-dev) (fake-repo-identidade papeis))
        rotas (participacao-http/rotas {:auth auth :repo-participacao repo-part
                                        :resolver-ente-publico participacao-http/resolver-ente-publico-uuid
                                        :relogio relogio})]
    (-> (http/servico (config/carregar) rotas it/globais)
        ph/create-server ::ph/service-fn)))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [tok] {"authorization" (str "Bearer " tok)})
(defn- json-headers [tok] (merge (com-bearer tok) {"Content-Type" "application/json"}))
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

;; ---------- POST /portal/lgpd/solicitacoes (TITULAR atribuido: SO auth, SEM papel) ----------

(deftest solicitar-201-titular-sem-papel
  (let [repo (fake-repo-participacao {:solicitar {:id (random-uuid) :protocolo "LGPD-2026-000001" :recibo-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post "/portal/lgpd/solicitacoes"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:tipo "acessar"}))
        body (ler-json r)]
    (is (= 201 (:status r)) "titular SEM papel solicita -> 201 (qualquer titular pede sobre os proprios dados)")
    (is (= "LGPD-2026-000001" (:protocolo body)) "recibo carrega o protocolo")
    (is (= "2026-07-03T12:00:00Z" (:recibo-em body)) "recibo instantaneo (marco do relogio LGPD)")
    (is (not (contains? body :id)) "id interno NAO vaza no recibo")))

(deftest solicitar-sem-token-401
  (let [repo (fake-repo-participacao {:solicitar {:protocolo "x" :recibo-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post "/portal/lgpd/solicitacoes"
                              :headers {"content-type" "application/json"}
                              :body (json/write-value-as-string {:tipo "acessar"}))]
    (is (= 401 (:status r)) "a rota do titular herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest solicitar-tipo-invalido-400
  (let [repo (fake-repo-participacao {:solicitar {:protocolo "x" :recibo-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post "/portal/lgpd/solicitacoes"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:tipo "vender-meus-dados"}))]  ; fora dos 5 direitos
    (is (= 400 (:status r)) "tipo fora do enum dos 5 direitos -> 400 fail-closed (adapters/in)")))

(deftest solicitar-detalhe-nao-string-400-nao-500
  ;; REGRESSAO: `detalhe` de tipo ERRADO (nao-string) NAO pode crashar o coagir (str/blank? exige CharSequence ->
  ;; ClassCastException -> 500 espurio). Deve cair no m/explain e virar 400 fail-closed, como todo tipo invalido.
  (let [repo (fake-repo-participacao {:solicitar {:protocolo "x" :recibo-em t0}})]
    (doseq [detalhe [42 true {} []]]
      (let [r (pt/response-for (service-fn #{} repo) :post "/portal/lgpd/solicitacoes"
                               :headers (json-headers (token (random-uuid) (random-uuid)))
                               :body (json/write-value-as-string {:tipo "acessar" :detalhe detalhe}))]
        (is (= 400 (:status r))
            (str "detalhe nao-string (" (pr-str detalhe) ") -> 400 fail-closed (nunca 500)"))))))

(deftest solicitar-detalhe-null-ou-vazio-201-tratado-como-ausente
  ;; `detalhe` null (JSON) ou string em branco == AUSENTE -> 201 (nao grava string vazia). Preserva o contrato.
  (let [repo (fake-repo-participacao {:solicitar {:protocolo "LGPD-2026-000001" :recibo-em t0}})]
    (doseq [detalhe [nil "" "   "]]
      (let [r (pt/response-for (service-fn #{} repo) :post "/portal/lgpd/solicitacoes"
                               :headers (json-headers (token (random-uuid) (random-uuid)))
                               :body (json/write-value-as-string {:tipo "acessar" :detalhe detalhe}))]
        (is (= 201 (:status r))
            (str "detalhe " (pr-str detalhe) " tratado como ausente -> 201"))))))

;; ---------- GET /portal/casa/:ente/encarregado (PUBLICA, sem auth) ----------

(deftest encarregado-publico-200-sem-interno
  (let [repo (fake-repo-participacao
              {:buscar-encarregado {:id (random-uuid) :ente-id (random-uuid) :nome "Ana Souza"
                                    :rotulo "Encarregada de Dados (DPO)" :email "dpo@camara.gov.br"
                                    :atualizado-por (random-uuid) :criado-em t0 :atualizado-em t0}})
        r    (pt/response-for (service-fn #{} repo)
                              :get (str "/portal/casa/" (random-uuid) "/encarregado"))
        body (ler-json r)]
    (is (= 200 (:status r)) "rota publica SEM auth -> 200 (contato do DPO e' legalmente publico; ente do path)")
    (is (= "Ana Souza" (:nome body)))
    (is (= "Encarregada de Dados (DPO)" (:rotulo body)))
    (is (= "dpo@camara.gov.br" (:email body)))
    (is (not (contains? body :id)) "id interno NAO vaza")
    (is (not (contains? body :ente-id)) "tenant NAO vaza")
    (is (not (contains? body :atualizado-por)) "atualizado-por (quem definiu, interno) NAO vaza")
    (is (not (contains? body :criado-em)) "timestamps internos NAO vazam")))

(deftest encarregado-publico-ente-malformado-400
  (let [repo (fake-repo-participacao {:buscar-encarregado nil})
        r    (pt/response-for (service-fn #{} repo) :get "/portal/casa/nao-e-uuid/encarregado")]
    (is (= 400 (:status r)) ":ente malformado -> 400 fail-closed (NUNCA vaza cross-tenant nem 500)")))

(deftest encarregado-publico-ausente-404
  (let [repo (fake-repo-participacao {:buscar-encarregado nil})
        r    (pt/response-for (service-fn #{} repo) :get (str "/portal/casa/" (random-uuid) "/encarregado"))]
    (is (= 404 (:status r)) "ente sem DPO definido -> 404")))

;; ---------- GET /portal/lgpd/solicitacoes/:id (titular: auth + policy FINA) ----------

(deftest minha-solicitacao-nao-titular-403
  (let [dono (random-uuid) intruso (random-uuid) sid (random-uuid)
        repo (fake-repo-participacao
              {:solicitacao-com-prazo {:solicitacao {:id sid :protocolo "LGPD-2026-000009" :estado "protocolada"
                                                     :tipo "acessar" :detalhe "meu dado"
                                                     :titular-identidade-id dono :recibo-em t0}
                                       :prazo {:vence-em vence-titular}}})
        r    (pt/response-for (service-fn #{} repo) :get (str "/portal/lgpd/solicitacoes/" sid)
                              :headers (com-bearer (token (random-uuid) intruso)))]
    (is (= 403 (:status r)) "ator != titular -> policy FINA nega (403), mesmo autenticado")))

(deftest minha-solicitacao-dono-200-sem-pii-de-tenant
  (let [dono (random-uuid) sid (random-uuid)
        repo (fake-repo-participacao
              {:solicitacao-com-prazo {:solicitacao {:id sid :protocolo "LGPD-2026-000009" :estado "protocolada"
                                                     :tipo "corrigir" :detalhe "meu dado"
                                                     :titular-identidade-id dono :recibo-em t0}
                                       :prazo {:vence-em vence-titular}}})
        r    (pt/response-for (service-fn #{} repo) :get (str "/portal/lgpd/solicitacoes/" sid)
                              :headers (com-bearer (token (random-uuid) dono)))
        body (ler-json r)]
    (is (= 200 (:status r)) "o titular le a propria solicitacao")
    (is (= "LGPD-2026-000009" (:protocolo body)))
    (is (= "corrigir" (:tipo body)))
    (is (= 15 (:dias-restantes body)) "dias-restantes = vence - hoje (contador separado: 15)")
    (is (not (contains? body :titular-identidade-id)) "id do titular NAO vaza no corpo")
    (is (not (contains? body :ente-id)) "tenant NAO vaza")))

(deftest minha-solicitacao-id-malformado-400
  (let [repo (fake-repo-participacao {:solicitacao-com-prazo nil})
        r    (pt/response-for (service-fn #{} repo) :get "/portal/lgpd/solicitacoes/nao-e-uuid"
                              :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 400 (:status r)) ":id malformado -> 400 fail-closed (id-param->uuid), nunca 500")))

;; ---------- POST /lgpd/solicitacoes/:id/resposta (SERVIDOR — auth + exige-papel "secretario") ----------

(deftest responder-solicitacao-servidor-200
  (let [sid  (random-uuid)
        repo (fake-repo-participacao {:responder {:respondida-em t0}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/lgpd/solicitacoes/" sid "/resposta")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:corpo "Segue a copia dos seus dados."}))
        body (ler-json r)]
    (is (= 200 (:status r)) "servidor com papel responde -> 200")
    (is (= "2026-07-03T12:00:00Z" (:respondida-em body)) "recibo carrega o instante da resposta")))

(deftest responder-solicitacao-sem-papel-403
  (let [sid  (random-uuid)
        repo (fake-repo-participacao {:responder {:respondida-em t0}})
        r    (pt/response-for (service-fn #{} repo) :post (str "/lgpd/solicitacoes/" sid "/resposta")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:corpo "x"}))]
    (is (= 403 (:status r)) "sem papel 'secretario' -> 403 (rota de servidor)")))

(deftest responder-solicitacao-inexistente-404
  (let [sid  (random-uuid)
        repo (fake-repo-participacao {:responder nil :buscar-solicitacao nil})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/lgpd/solicitacoes/" sid "/resposta")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:corpo "x"}))]
    (is (= 404 (:status r)) "solicitacao inexistente no tenant -> 404")))

(deftest responder-solicitacao-ja-terminal-409
  (let [sid  (random-uuid)
        repo (fake-repo-participacao {:responder nil :buscar-solicitacao {:id sid :estado "respondida"}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post (str "/lgpd/solicitacoes/" sid "/resposta")
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:corpo "x"}))]
    (is (= 409 (:status r)) "solicitacao ja respondida (CAS falhou, mas existe) -> 409 conflito")))

(deftest responder-solicitacao-id-malformado-400
  (let [repo (fake-repo-participacao {})
        r    (pt/response-for (service-fn #{"secretario"} repo) :post "/lgpd/solicitacoes/nao-e-uuid/resposta"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:corpo "x"}))]
    (is (= 400 (:status r)) ":id malformado -> 400 fail-closed (nunca 500 nem cross-tenant)")))

;; ---------- PUT /lgpd/encarregado (SERVIDOR — auth + exige-papel "secretario") ----------

(deftest definir-encarregado-servidor-200
  (let [repo (fake-repo-participacao {:definir-encarregado {:id (random-uuid) :nome "Ana Souza"
                                                            :rotulo "Encarregada (DPO)" :email "dpo@camara.gov.br"
                                                            :atualizado-por (random-uuid)}})
        r    (pt/response-for (service-fn #{"secretario"} repo) :put "/lgpd/encarregado"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:nome "Ana Souza" :rotulo "Encarregada (DPO)"
                                                                 :email "dpo@camara.gov.br"}))
        body (ler-json r)]
    (is (= 200 (:status r)) "servidor com papel define o Encarregado -> 200")
    (is (= "Ana Souza" (:nome body)) "devolve o contato publico salvo")
    (is (not (contains? body :atualizado-por)) "atualizado-por (interno) NAO vaza na resposta")))

(deftest definir-encarregado-sem-papel-403
  (let [repo (fake-repo-participacao {:definir-encarregado {:nome "x" :rotulo "y" :email "z@z"}})
        r    (pt/response-for (service-fn #{} repo) :put "/lgpd/encarregado"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:nome "x" :rotulo "y" :email "z@z.br"}))]
    (is (= 403 (:status r)) "sem papel 'secretario' -> 403 (rota de servidor)")))

(deftest definir-encarregado-corpo-invalido-400
  (let [repo (fake-repo-participacao {})
        r    (pt/response-for (service-fn #{"secretario"} repo) :put "/lgpd/encarregado"
                              :headers (json-headers (token (random-uuid) (random-uuid)))
                              :body (json/write-value-as-string {:nome "so nome"}))]  ; falta rotulo/email
    (is (= 400 (:status r)) "corpo sem rotulo/email -> 400 fail-closed (adapters/in)")))
