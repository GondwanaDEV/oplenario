(ns oplenario.sessoes.presenca-http-in-test
  "Slice F4 — eixo C (§22.6): a borda HTTP da PRESENCA. `POST /sessoes/:id/presenca` grava um evento de presenca
  append-only (entrada/saida/retorno/mudanca_modalidade), corpo {vereador-id, tipo, modalidade, ocorrido-em}. O
  Repo ja compoe o ato + emite `presenca.registrada` (que alimenta o quorum ao vivo) na MESMA tx — este slice so
  fecha a borda. Carrega a sessao (nil->404), pode-ver-sessao? (mesma Casa->403); tipo/modalidade fora do enum /
  ocorrido-em ausente ou malformado / vereador-id malformado -> 400. A `fonte` e' FORCADA = manual_secretaria no
  servidor (registro humano autenticado; nunca confia em proveniencia do cliente — a fonte alimenta a precedencia
  de quorum). DB-free: RepoSessoes FAKE + idp-dev real — espelha o sessao-transicao-http-in-test."
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
            [oplenario.rotas :as rotas]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]))

(defn- sessao-canonica
  "Sessao como buscar-sessao devolve (kebab) — so o que a authz fina (pode-ver-sessao? = mesma Casa) le."
  [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria"})

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial): `buscar-sessao` resolve a sessao; `registrar-presenca!` GRAVA o mapa recebido em
  `capturado` (p/ provar vereador-id/tipo/modalidade/ocorrido-em/fonte/created-by passados ao Repo) e ECOA {:id}."
  [busca-fn capturado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (registrar-presenca! [_ ente-id m]
      (reset! capturado (assoc m :ente-id ente-id))
      {:id (:id m)})))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- service-fn*
  [papeis repo-s]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :objeto-store nil})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- fake-repo-cadastros
  "So' o metodo exercido por `resolver-vereador` (host, rotas.clj) — `vereador-por-identidade` — mesmo
  fake de `meu-painel-http-in-test`. `resolver` (fn de teste ente-id/identidade-id -> vereador-id | nil)
  devolve DIRETO o vereador-id; este fake embrulha em {:id ...} p/ casar o contrato real."
  [resolver]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (vereador-por-identidade [_ ente-id identidade-id]
      (when-let [v (resolver ente-id identidade-id)] {:id v}))))

(defn- service-fn-confirmar
  "Onda C3 — variante de `service-fn*` que tambem injeta `repo-cadastros` (p/ `resolver-vereador`), exercida
  so' pelos testes de `POST /sessoes/:id/presenca/confirmar` (`/presenca` classico nao usa resolver-vereador)."
  [papeis repo-s resolver-vereador]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :repo-cadastros (fake-repo-cadastros resolver-vereador)
                                   :objeto-store nil})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))

(defn- com-json [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- url [sid] (str "/sessoes/" sid "/presenca"))
(defn- corpo [m] (json/write-value-as-string m))

(def ^:private ocorrido "2026-06-30T14:00:00Z")

;; ---------- POST /sessoes/:id/presenca ----------

(deftest presenca-201
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str vid) "tipo" "entrada"
                                         "modalidade" "plenario" "ocorrido-em" ocorrido}))
        body (ler-json r)]
    (is (= 201 (:status r)) "papel + sessao da mesma Casa + corpo valido -> 201")
    (is (= (str (:id @cap)) (:id body)) "o recibo carrega o id do evento gravado")
    (is (= sid (:sessao-id @cap)) "o Repo recebeu a sessao-id (uuid coagido do path)")
    (is (= vid (:vereador-id @cap)) "o Repo recebeu o vereador-id (uuid coagido do corpo)")
    (is (= "entrada" (:tipo @cap)) "o Repo recebeu o tipo do evento")
    (is (= "plenario" (:modalidade @cap)) "o Repo recebeu a modalidade")
    (is (= ocorrido (str (:ocorrido-em @cap))) "o Repo recebeu o instante de DOMINIO (ocorrido-em)")
    (is (= "manual_secretaria" (:fonte @cap)) "fonte FORCADA = manual_secretaria (registro humano autenticado)")
    (is (some? (:created-by @cap)) "o Repo recebeu created-by (do ator, nunca do cliente)")))

(deftest presenca-fonte-do-cliente-ignorada-201
  ;; mesmo se o cliente mandar uma `fonte` (ex.: painel_eletronico p/ ganhar precedencia indevida), a borda a
  ;; IGNORA (so-esperados nao a inclui) e o servidor forca manual_secretaria — integridade de proveniencia.
  (let [ente (random-uuid) sid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str (random-uuid)) "tipo" "entrada"
                                         "modalidade" "plenario" "ocorrido-em" ocorrido
                                         "fonte" "painel_eletronico"}))]
    (is (= 201 (:status r)) "corpo com `fonte` espuria ainda passa (campo alheio e' filtrado)")
    (is (= "manual_secretaria" (:fonte @cap)) "a fonte do cliente NAO sobrescreve o forcing do servidor")))

(deftest presenca-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str (random-uuid)) "tipo" "entrada"
                                         "modalidade" "plenario" "ocorrido-em" ocorrido}))]
    (is (= 404 (:status r)) "sessao inexistente no tenant -> 404")))

(deftest presenca-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str (random-uuid)) "tipo" "entrada"
                                         "modalidade" "plenario" "ocorrido-em" ocorrido}))]
    (is (= 403 (:status r)) "sessao de ente alheio -> pode-ver-sessao? nega -> 403")))

(deftest presenca-sem-papel-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"vereador"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str (random-uuid)) "tipo" "entrada"
                                         "modalidade" "plenario" "ocorrido-em" ocorrido}))]
    (is (= 403 (:status r)) "ator sem papel 'secretario' -> authz grossa nega -> 403")))

(deftest presenca-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers {"Content-Type" "application/json"}
                           :body (corpo {"vereador-id" (str (random-uuid)) "tipo" "entrada"
                                         "modalidade" "plenario" "ocorrido-em" ocorrido}))]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest presenca-tipo-desconhecido-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str (random-uuid)) "tipo" "voando"
                                         "modalidade" "plenario" "ocorrido-em" ocorrido}))]
    (is (= 400 (:status r)) "tipo fora do enum -> 400 (fail-closed na borda, nunca 500 do CHECK do db)")))

(deftest presenca-modalidade-invalida-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str (random-uuid)) "tipo" "entrada"
                                         "modalidade" "telepatia" "ocorrido-em" ocorrido}))]
    (is (= 400 (:status r)) "modalidade fora do enum -> 400")))

(deftest presenca-sem-ocorrido-em-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str (random-uuid)) "tipo" "entrada"
                                         "modalidade" "plenario"}))]
    (is (= 400 (:status r)) "ocorrido-em ausente -> 400 (instante de dominio e' obrigatorio na borda)")))

(deftest presenca-ocorrido-em-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str (random-uuid)) "tipo" "entrada"
                                         "modalidade" "plenario" "ocorrido-em" "ontem"}))]
    (is (= 400 (:status r)) "ocorrido-em nao-ISO-8601 -> 400, nunca 500")))

(deftest presenca-vereador-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" "nao-e-uuid" "tipo" "entrada"
                                         "modalidade" "plenario" "ocorrido-em" ocorrido}))]
    (is (= 400 (:status r)) "vereador-id malformado -> 400, nunca 500")))

(deftest presenca-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :post "/sessoes/nao-e-uuid/presenca"
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str (random-uuid)) "tipo" "entrada"
                                         "modalidade" "plenario" "ocorrido-em" ocorrido}))]
    (is (= 400 (:status r)) "sessao :id malformado no path -> 400, nunca 500")))

;; ---------- POST /sessoes/:id/presenca/confirmar (Onda C3, autoatendimento) ----------

(defn- url-confirmar [sid] (str "/sessoes/" sid "/presenca/confirmar"))

(deftest confirmar-presenca-201
  (let [ente (random-uuid) sid (random-uuid) identidade (random-uuid) vid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn-confirmar #{"vereador"} repo-s (fn [_ _] vid))
                           :post (url-confirmar sid)
                           :headers (com-json (token ente identidade)))
        body (ler-json r)]
    (is (= 201 (:status r)) "papel vereador + sessao da mesma Casa + cadastro vinculado -> 201")
    (is (= (str (:id @cap)) (:id body)) "o recibo carrega o id do evento gravado")
    (is (= sid (:sessao-id @cap)) "o Repo recebeu a sessao-id (uuid coagido do path)")
    (is (= vid (:vereador-id @cap)) "o Repo recebeu o vereador-id RESOLVIDO do ator, nunca do corpo")
    (is (= "entrada" (:tipo @cap)) "tipo FORCADO = entrada (reconfirmar e' inofensivo, append-only)")
    (is (= "plenario" (:modalidade @cap)) "modalidade FORCADA = plenario (V1 = Nivel 1)")
    (is (= "autoatendimento" (:fonte @cap)) "fonte FORCADA = autoatendimento (autoatendimento do proprio vereador)")
    (is (some? (:ocorrido-em @cap)) "o instante veio do relogio do servidor, nao do cliente (sem corpo)")
    (is (some? (:created-by @cap)) "o Repo recebeu created-by (do ator, nunca do cliente)")))

(deftest confirmar-presenca-sem-papel-vereador-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn-confirmar #{"secretario"} repo-s (fn [_ _] (random-uuid)))
                           :post (url-confirmar (random-uuid))
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 403 (:status r)) "ator sem papel 'vereador' -> authz grossa nega -> 403 (gate e' papel-vereador, nao papel)")))

(deftest confirmar-presenca-sem-cadastro-vinculado-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn-confirmar #{"vereador"} repo-s (fn [_ _] nil))
                           :post (url-confirmar (random-uuid))
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 404 (:status r)) "resolver-vereador nil (ator sem cadastro de vereador neste ente) -> 404")))

(deftest confirmar-presenca-sessao-inexistente-404
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn-confirmar #{"vereador"} repo-s (fn [_ _] (random-uuid)))
                           :post (url-confirmar (random-uuid))
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 404 (:status r)) "sessao inexistente no tenant -> 404")))

(deftest confirmar-presenca-casa-alheia-403
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica (random-uuid) id)) (atom nil))
        r (pt/response-for (service-fn-confirmar #{"vereador"} repo-s (fn [_ _] (random-uuid)))
                           :post (url-confirmar (random-uuid))
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 403 (:status r)) "sessao de ente alheio -> pode-ver-sessao? nega -> 403")))

(deftest confirmar-presenca-sem-token-401
  (let [repo-s (fake-repo-sessoes (fn [_ _] nil) (atom nil))
        r (pt/response-for (service-fn-confirmar #{"vereador"} repo-s (fn [_ _] (random-uuid)))
                           :post (url-confirmar (random-uuid))
                           :headers {"Content-Type" "application/json"})]
    (is (= 401 (:status r)) "rota herda a cadeia de auth: sem token -> 401 (fail-closed)")))

(deftest confirmar-presenca-id-malformado-400
  (let [ente (random-uuid)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) (atom nil))
        r (pt/response-for (service-fn-confirmar #{"vereador"} repo-s (fn [_ _] (random-uuid)))
                           :post "/sessoes/nao-e-uuid/presenca/confirmar"
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 400 (:status r)) "sessao :id malformado no path -> 400, nunca 500")))
