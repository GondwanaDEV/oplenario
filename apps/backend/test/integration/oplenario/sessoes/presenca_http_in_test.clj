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
            [oplenario.sessoes.components.repositorio :as repo-sessoes])
  (:import (java.time Instant)))

(defn- sessao-canonica
  "Sessao como buscar-sessao devolve (kebab). Alem do que a authz fina le' (pode-ver-sessao? = mesma Casa),
  carrega `agendada-para`: a revisao da Etapa 2 passou a validar o `vereador-id` contra o ROSTER da Casa na
  DATA DE REFERENCIA da sessao, e sem nenhum marco de data a rota cai em `:conflito/sessao-sem-data` (que e'
  outro caso, testado a parte). A data casa com o `ocorrido` usado nos corpos abaixo."
  [ente-id id]
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria"
   :agendada-para (Instant/parse "2026-06-30T13:00:00Z")})

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial): `buscar-sessao` resolve a sessao; `registrar-presenca!` GRAVA o mapa recebido em
  `capturado` (p/ provar vereador-id/tipo/modalidade/ocorrido-em/fonte/created-by/agora passados ao Repo) e ECOA
  o recibo NO FORMATO DO REPO REAL — {:id :ocorrido-em :registrado-em}. Ecoar so' {:id} manteria este ns verde
  enquanto o wire real (PresencaReciboOut, :closed) exige os dois carimbos: fake que inventa o formato de saida
  e' a mesma armadilha do fixture que redigita vocabulario alheio."
  [busca-fn capturado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (registrar-presenca! [_ ente-id m]
      (reset! capturado (assoc m :ente-id ente-id))
      ;; `registrado-em` e' carimbo do BANCO (default now()); o fake devolve um instante distinto do
      ;; `ocorrido-em` justamente porque o par so' tem valor se os dois puderem divergir.
      {:id (:id m) :ocorrido-em (:ocorrido-em m) :registrado-em (Instant/parse "2026-06-30T15:00:00Z")})))

(defn- fake-repo-sessoes-que-recusa
  "RepoSessoes fake cujo `registrar-presenca!` LANCA a recusa do gate (`:conflito/sessao-nao-aceita-presenca`,
  o que o Repo real faz dentro da tx quando a sessao ja fechou ou a hora esta fora da janela). Aqui se prova
  so' a TRADUCAO da borda (409 + mensagem repassada); que o gate exista e recuse de fato e' o que o
  presenca-gate-estado-test prova contra o Postgres."
  [busca-fn msg]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (busca-fn ente-id id))
    (registrar-presenca! [_ _ente-id m]
      (throw (ex-info msg {:tipo :conflito/sessao-nao-aceita-presenca :motivo :estado-nao-aceita-presenca
                           :sessao-id (:sessao-id m) :estado "encerrada"})))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- fake-repo-cadastros
  "Os DOIS metodos que o host resolve sobre `cadastros` e injeta em `sessoes` por seam:
  `vereador-por-identidade` (o `resolver-vereador` da porta self-service) e `roster-da-casa` (o gate de
  ASSENTO, que a revisao da Etapa 2 passou a aplicar TAMBEM as escritas de presenca).

  `roster` e' a lista de vereador-ids que compoem a Casa — e' o fixture inteiro do gate: quem nao esta nela
  toma 409 `:sem-assento`. Por isso o default e' VAZIO e cada teste declara quem tem cadeira: um default
  permissivo manteria verde justamente a rota que aceitava qualquer UUID."
  [resolver roster]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (vereador-por-identidade [_ ente-id identidade-id]
      (when-let [v (resolver ente-id identidade-id)] {:id v}))
    (roster-da-casa [_ _ente-id _data] (mapv (fn [v] {:vereador-id v}) roster))))

(defn- service-fn*
  [papeis repo-s & {:keys [roster] :or {roster []}}]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :repo-cadastros (fake-repo-cadastros (constantly nil) roster)
                                   :objeto-store nil})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- service-fn-confirmar
  "Onda C3 — variante de `service-fn*` com o `resolver-vereador` exercido, usada so' pelos testes de
  `POST /sessoes/:id/presenca/confirmar`."
  [papeis repo-s resolver-vereador & {:keys [roster] :or {roster []}}]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :repo-cadastros (fake-repo-cadastros resolver-vereador roster)
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
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster [vid])
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
    (is (some? (:created-by @cap)) "o Repo recebeu created-by (do ator, nunca do cliente)")
    (is (some? (:agora @cap))
        "o Repo recebeu `agora` (relogio do SERVIDOR, injetado na borda) — e' o teto do clamp da hora declarada")
    (is (= ocorrido (:ocorrido-em body)) "o recibo devolve a hora do FATO")
    (is (= "2026-06-30T15:00:00Z" (:registrado-em body))
        "o recibo devolve a hora do REGISTRO — o par (fato, registro) e' o que separa 'saiu as 15h' de 'digitaram as 17h'")))

(deftest presenca-sessao-que-nao-aceita-registro-409
  (let [ente (random-uuid) vid (random-uuid)
        msg "a sessao esta 'encerrada' e nao aceita mais registro de presenca. Corrija pela ata."
        repo-s (fake-repo-sessoes-que-recusa (fn [_ id] (sessao-canonica ente id)) msg)
        ;; `vid` COM assento de proposito: o que este teste prova e' a traducao da recusa do GATE DE ESTADO.
        ;; Sem assento, a recusa que chegaria seria a do gate de assento — outra mensagem, outro achado.
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster [vid])
                           :post (url (random-uuid))
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str vid) "tipo" "entrada"
                                         "modalidade" "plenario" "ocorrido-em" ocorrido}))]
    (is (= 409 (:status r)) "recusa do gate -> 409 (nao 403: nao e' permissao; nao 400: o corpo esta bem formado)")
    (is (= msg (:erro (ler-json r))) "a mensagem ACIONAVEL do dominio chega ao cliente, nao um 409 mudo")))

(deftest confirmar-presenca-sessao-que-nao-aceita-registro-409
  (let [ente (random-uuid)
        msg "a sessao esta 'encerrada' e nao aceita mais registro de presenca. Corrija pela ata."
        vid (random-uuid)
        repo-s (fake-repo-sessoes-que-recusa (fn [_ id] (sessao-canonica ente id)) msg)
        r (pt/response-for (service-fn-confirmar #{"vereador"} repo-s (fn [_ _] vid) :roster [vid])
                           :post (str "/sessoes/" (random-uuid) "/presenca/confirmar")
                           :headers (com-json (token ente (random-uuid))))]
    (is (= 409 (:status r)) "a porta self-service traduz a MESMA recusa — nao e' bypass nem 500")
    (is (= msg (:erro (ler-json r))))))

(deftest presenca-fonte-do-cliente-ignorada-201
  ;; mesmo se o cliente mandar uma `fonte` (ex.: painel_eletronico p/ ganhar precedencia indevida), a borda a
  ;; IGNORA (so-esperados nao a inclui) e o servidor forca manual_secretaria — integridade de proveniencia.
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes (fn [_ id] (sessao-canonica ente id)) cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster [vid])
                           :post (url sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str vid) "tipo" "entrada"
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
        r (pt/response-for (service-fn-confirmar #{"vereador"} repo-s (fn [_ _] vid) :roster [vid])
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
