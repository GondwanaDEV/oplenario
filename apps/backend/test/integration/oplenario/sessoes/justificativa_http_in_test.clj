(ns oplenario.sessoes.justificativa-http-in-test
  "Etapa 2 da CHAMADA, fatia 2b — a BORDA HTTP da JUSTIFICATIVA DE AUSENCIA (§22.6 eixo C). Quatro rotas para
  as TRES capacidades:

    POST   /sessoes/:id/justificativas              (papel 'secretario') — a Mesa protocola em nome do vereador
    POST   /sessoes/:id/minha-justificativa         (papel 'vereador')   — self-service, vereador-id da IDENTIDADE
    GET    /sessoes/:id/justificativas              (papel 'secretario') — a lista com o token de CAS
    PATCH  /sessoes/:id/justificativas/:jid/decisao (papel 'secretario') — aprovar|indeferir, CAS obrigatorio

  Por que DUAS rotas de abertura e nao uma que aceite os dois papeis: e' o mesmo desenho ja' provado em
  presenca (`POST /sessoes/:id/presenca` da Mesa vs `POST /sessoes/:id/presenca/confirmar` do vereador). Numa
  rota unica, o significado de `vereador-id` no corpo passaria a depender do PAPEL do ator — e quem tivesse os
  dois papeis poderia justificar a falta de um terceiro pela porta self-service. Duas rotas tornam a regra
  estrutural: na porta self-service o campo simplesmente NAO EXISTE no allowlist.

  Por que a self-service NAO e' `/justificativas/minha` (o gemeo literal de `/presenca/confirmar`): o
  prefix-tree do Pedestal nao resolve literal-vs-param IRMAOS — com `/justificativas/:jid/decisao` presente, o
  literal `minha` fica INALCANCAVEL e o servico responde 404 antes de qualquer interceptor. Foi assim que este
  ns pegou o defeito: `minha-justificativa-exige-papel-vereador` esperava 403 (do gate de papel) e recebeu 404
  (do router). O deftest final deste ns pina a rota que a fatia escolheu, para o dia em que alguem a mover de
  volta para debaixo de `/justificativas`.

  DB-free: RepoSessoes FAKE + RepoCadastros fake (o seam `roster-da-casa` e o `resolver-vereador` do host) +
  idp-dev real — espelha o presenca-http-in-test. Que o CAS/UNIQUE/maquina de estados funcionem de verdade e'
  o que `justificativa-db-test` prova contra o Postgres; aqui prova-se a TRADUCAO da borda (status, allowlist,
  anti-forja, papeis)."
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

(defn- sessao-canonica [ente-id id]
  ;; `agendada-para` e' o que resolve a DATA DE COMPOSICAO do roster (controllers/data-de-referencia) — sem
  ;; ela a abertura cai em :conflito/sessao-sem-data, que e' outro caso (testado a parte).
  {:id id :ente-id ente-id :estado "aberta" :tipo-sessao "ordinaria"
   :agendada-para (Instant/parse "2026-06-20T13:00:00Z")})

(defn- fake-repo-sessoes
  "RepoSessoes fake (parcial). `sessao-fn` resolve a sessao; `justificativa` = o que `buscar-justificativa`
  devolve (nil = inexistente); `lista` = o que `listar-justificativas` devolve. As escritas GRAVAM o mapa em
  `cap` e ECOAM o recibo NO FORMATO DO REPO REAL (`criar-justificativa!` devolve os tres campos que o
  adapters/out exige — ecoar so' {:id} manteria este ns verde contra um contrato :closed que pede mais, a
  mesma armadilha do fixture que redigita vocabulario alheio)."
  [& {:keys [sessao-fn justificativa lista criar-fn decidir-fn cap]
      :or {sessao-fn sessao-canonica, cap (atom nil), lista []}}]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-sessoes/RepoSessoes
    (buscar-sessao [_ ente-id id] (sessao-fn ente-id id))
    (buscar-justificativa [_ _ente-id id] (when justificativa (assoc justificativa :id id)))
    (listar-justificativas [_ _ente-id _sessao-id] lista)
    (criar-justificativa! [_ ente-id m]
      (reset! cap (assoc m :ente-id ente-id))
      ((or criar-fn (fn [mm] {:id (:id mm) :estado "pendente" :lock-version 0})) m))
    (decidir-justificativa! [_ ente-id m]
      (reset! cap (assoc m :ente-id ente-id))
      ((or decidir-fn (fn [mm] {:de "pendente" :para (:estado mm)})) m))))

(defn- fake-repo-identidade [papeis]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _ente-id _identidade-id]
      {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})))

(defn- fake-repo-cadastros
  "Os DOIS seams que o host injeta em sessoes e que esta borda exerce: `roster-da-casa` (quem compoe a Casa na
  data da sessao — o gate de assento da abertura) e `vereador-por-identidade` (o `resolver-vereador` do
  self-service e do impedimento da decisao)."
  [roster resolver]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-cadastros-comp/RepoCadastros
    (roster-da-casa [_ ente-id data] (roster ente-id data))
    (vereador-por-identidade [_ ente-id identidade-id]
      (when-let [v (resolver ente-id identidade-id)] {:id v}))))

(defn- service-fn*
  [papeis repo-s & {:keys [roster resolver]
                    :or {roster (constantly []), resolver (constantly nil)}}]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (idp-dev/idp-dev)
                                   :repo-identidade (fake-repo-identidade papeis)
                                   :repo-sessoes repo-s
                                   :repo-cadastros (fake-repo-cadastros roster resolver)
                                   :objeto-store nil})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-json [tok] {"authorization" (str "Bearer " tok) "Content-Type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))
(defn- corpo [m] (json/write-value-as-string m))
(defn- url [sid] (str "/sessoes/" sid "/justificativas"))
(defn- url-minha [sid] (str "/sessoes/" sid "/minha-justificativa"))
(defn- url-decisao [sid jid] (str "/sessoes/" sid "/justificativas/" jid "/decisao"))

(defn- roster-com [& vereador-ids]
  (fn [_ente _data] (mapv (fn [v] {:vereador-id v :nome "Fulano"}) vereador-ids)))

(def ^:private motivo "Internacao hospitalar")

;; ---------- J1/borda: a Mesa protocola em nome do vereador ----------

(deftest abrir-justificativa-201
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes :cap cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster (roster-com vid))
                           :post (url sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str vid) "motivo" motivo}))
        body (ler-json r)]
    (is (= 201 (:status r)) "papel + sessao da mesma Casa + vereador com assento -> 201")
    (is (= (str (:id @cap)) (:id body)) "o recibo carrega o id do ato criado (gerado server-side)")
    (is (= "pendente" (:estado body)))
    (is (= 0 (:lock-version body)) "o token de CAS ja' vem no 201 — decidir nao exige uma segunda leitura")
    (is (= vid (:vereador-id @cap)))
    (is (= sid (:sessao-id @cap)))
    (is (= motivo (:motivo @cap)))
    (is (some? (:created-by @cap)) "created-by INJETADO do ator (trilha de quem protocolou)")))

(deftest abrir-justificativa-de-quem-nao-tem-assento-409
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes)
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster (roster-com (random-uuid)))
                           :post (url sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str vid) "motivo" motivo}))]
    (is (= 409 (:status r)) "o corpo esta bem formado; o que nao bate e' a COMPOSICAO da Casa -> 409, nao 400")))

(deftest abrir-justificativa-sessao-inexistente-404
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes :sessao-fn (fn [_ _] nil))
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster (roster-com vid))
                           :post (url sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str vid) "motivo" motivo}))]
    (is (= 404 (:status r)))))

(deftest abrir-justificativa-casa-alheia-403
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        repo-s (fake-repo-sessoes :sessao-fn (fn [_ id] (sessao-canonica (random-uuid) id)))
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster (roster-com vid))
                           :post (url sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str vid) "motivo" motivo}))]
    (is (= 403 (:status r)) "pode-ver-sessao? (mesma Casa) barra antes de qualquer escrita")))

(deftest abrir-justificativa-409-quando-ja-existe
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        existente (random-uuid)
        repo-s (fake-repo-sessoes
                :criar-fn (fn [_] (throw (ex-info "ja existe justificativa deste vereador nesta sessao"
                                                  {:tipo :conflito/justificativa :motivo :ja-existe
                                                   :justificativa-id existente}))))
        r (pt/response-for (service-fn* #{"secretario"} repo-s :roster (roster-com vid))
                           :post (url sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"vereador-id" (str vid) "motivo" motivo}))
        body (ler-json r)]
    (is (= 409 (:status r)))
    (is (= (str existente) (:justificativa-id body))
        "o 409 e' ACIONAVEL: devolve o id da justificativa que ja' existe, p/ a tela abrir aquela")))

(deftest abrir-justificativa-400s
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        svc (fn [] (service-fn* #{"secretario"} (fake-repo-sessoes) :roster (roster-com vid)))
        post (fn [b] (pt/response-for (svc) :post (url sid)
                                      :headers (com-json (token ente (random-uuid)))
                                      :body (corpo b)))]
    (is (= 400 (:status (post {"vereador-id" (str vid)}))) "motivo ausente")
    (is (= 400 (:status (post {"vereador-id" (str vid) "motivo" "   "}))) "motivo em branco (trim)")
    (is (= 400 (:status (post {"motivo" motivo}))) "vereador-id ausente")
    (is (= 400 (:status (post {"vereador-id" "nao-e-uuid" "motivo" motivo}))) "vereador-id malformado")))

(deftest abrir-justificativa-sem-papel-403-e-sem-token-401
  (let [ente (random-uuid) sid (random-uuid) vid (random-uuid)
        b (corpo {"vereador-id" (str vid) "motivo" motivo})]
    (is (= 403 (:status (pt/response-for (service-fn* #{"vereador"} (fake-repo-sessoes)
                                                      :roster (roster-com vid))
                                         :post (url sid)
                                         :headers (com-json (token ente (random-uuid))) :body b)))
        "a rota da Mesa nao aceita o papel 'vereador' — quem e' vereador usa /minha")
    (is (= 401 (:status (pt/response-for (service-fn* #{"secretario"} (fake-repo-sessoes)
                                                      :roster (roster-com vid))
                                         :post (url sid)
                                         :headers {"Content-Type" "application/json"} :body b))))))

;; ---------- J2: self-service — o vereador-id vem da IDENTIDADE, nunca do corpo ----------

(deftest j2-minha-justificativa-usa-a-identidade-e-ignora-forja-do-corpo
  (let [ente (random-uuid) sid (random-uuid)
        eu (random-uuid) ident (random-uuid) outro (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes :cap cap)
        r (pt/response-for (service-fn* #{"vereador"} repo-s
                                        :roster (roster-com eu outro)
                                        :resolver (fn [_ i] (when (= i ident) eu)))
                           :post (url-minha sid)
                           :headers (com-json (token ente ident))
                           ;; a forja: o corpo tenta justificar a falta de OUTRO vereador
                           :body (corpo {"vereador-id" (str outro) "motivo" motivo}))]
    (is (= 201 (:status r)))
    (is (= eu (:vereador-id @cap))
        "O ASSERT QUE IMPORTA: o Repo recebeu o vereador da IDENTIDADE, nao o do corpo")
    (is (not= outro (:vereador-id @cap))
        "um vereador nao justifica a falta de terceiro pela porta self-service")))

(deftest minha-justificativa-sem-cadastro-vinculado-404
  (let [ente (random-uuid) sid (random-uuid)
        r (pt/response-for (service-fn* #{"vereador"} (fake-repo-sessoes) :resolver (constantly nil))
                           :post (url-minha sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"motivo" motivo}))]
    (is (= 404 (:status r)) "ator sem cadastro de vereador neste ente -> 404 (contrato de /meu)")))

(deftest minha-justificativa-exige-papel-vereador
  (let [ente (random-uuid) sid (random-uuid) eu (random-uuid)
        r (pt/response-for (service-fn* #{"secretario"} (fake-repo-sessoes)
                                        :roster (roster-com eu) :resolver (constantly eu))
                           :post (url-minha sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"motivo" motivo}))]
    (is (= 403 (:status r)))))

;; ---------- J4: a listagem ----------

(deftest j4-listar-justificativas-200
  (let [ente (random-uuid) sid (random-uuid) v (random-uuid) jid (random-uuid) decisor (random-uuid)
        lista [{:id jid :vereador-id v :estado "aprovada" :motivo motivo
                :decidido-por decisor :decidido-em (Instant/parse "2026-06-21T12:00:00Z")
                :lock-version 1}]
        r (pt/response-for (service-fn* #{"secretario"} (fake-repo-sessoes :lista lista))
                           :get (url sid)
                           :headers {"authorization" (str "Bearer " (token ente (random-uuid)))})
        body (ler-json r)
        linha (first (:justificativas body))]
    (is (= 200 (:status r)))
    (is (= (str sid) (:sessao-id body)))
    (is (= (str jid) (:id linha)))
    (is (= (str v) (:vereador-id linha)))
    (is (= "aprovada" (:estado linha)))
    (is (= motivo (:motivo linha)))
    (is (= 1 (:lock-version linha)) "a lista entrega o token de CAS — a Mesa decide direto dela")
    (is (= (str decisor) (:decidido-por linha)))
    (is (= "2026-06-21T12:00:00Z" (:decidido-em linha)))))

(deftest j4-listar-justificativas-papel-errado-403-e-sem-token-401
  (let [ente (random-uuid) sid (random-uuid)]
    (is (= 403 (:status (pt/response-for (service-fn* #{"vereador"} (fake-repo-sessoes))
                                         :get (url sid)
                                         :headers {"authorization" (str "Bearer " (token ente (random-uuid)))})))
        "motivo pode ser dado de saude: a lista e' leitura operacional da Mesa, nao read-model aberto")
    (is (= 401 (:status (pt/response-for (service-fn* #{"secretario"} (fake-repo-sessoes))
                                         :get (url sid) :headers {}))))))

(deftest listar-justificativas-sessao-inexistente-404
  (let [ente (random-uuid) sid (random-uuid)
        r (pt/response-for (service-fn* #{"secretario"} (fake-repo-sessoes :sessao-fn (fn [_ _] nil)))
                           :get (url sid)
                           :headers {"authorization" (str "Bearer " (token ente (random-uuid)))})]
    (is (= 404 (:status r)))))

;; ---------- a decisao ----------

(defn- pendente [sid v] {:sessao-id sid :vereador-id v :estado "pendente" :lock-version 0})

(deftest decidir-justificativa-200
  (let [ente (random-uuid) sid (random-uuid) jid (random-uuid) v (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes :justificativa (pendente sid v) :cap cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-decisao sid jid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"estado" "aprovada" "lock-version" 0}))
        body (ler-json r)]
    (is (= 200 (:status r)) "200 (atualiza o ato existente), nunca 201")
    (is (= (str jid) (:justificativa-id body)))
    (is (= "pendente" (:de body)))
    (is (= "aprovada" (:para body)))
    (is (= 0 (:lock-version @cap)) "o CAS chega ao Repo")
    (is (some? (:decidido-por @cap)) "decidido-por INJETADO do ator, nunca do corpo")))

(deftest decidir-justificativa-409-no-conflito
  (let [ente (random-uuid) sid (random-uuid) jid (random-uuid) v (random-uuid)
        repo-s (fake-repo-sessoes
                :justificativa (pendente sid v)
                :decidir-fn (fn [_] (throw (ex-info "conflito de lock_version"
                                                    {:tipo :conflito/justificativa :motivo :lock-stale}))))
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-decisao sid jid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"estado" "aprovada" "lock-version" 3}))]
    (is (= 409 (:status r)) "lock-stale / ja terminal / inexistente -> 409, nunca 500")))

(deftest j8-borda-vereador-nao-decide-a-propria-justificativa
  (let [ente (random-uuid) sid (random-uuid) jid (random-uuid) eu (random-uuid) ident (random-uuid)
        repo-s (fake-repo-sessoes :justificativa (pendente sid eu))
        ;; o ator TEM o papel 'secretario' (senao pararia no interceptor de papel e o teste seria vacuo) —
        ;; e' um servidor que tambem e' vereador, e a justificativa e' a DELE.
        r (pt/response-for (service-fn* #{"secretario"} repo-s :resolver (fn [_ i] (when (= i ident) eu)))
                           :patch (url-decisao sid jid)
                           :headers (com-json (token ente ident))
                           :body (corpo {"estado" "aprovada" "lock-version" 0}))]
    (is (= 403 (:status r))
        "ninguem e' juiz em causa propria — o papel 'secretario' nao cobre a propria falta")))

(deftest decidir-justificativa-de-outra-sessao-404
  (let [ente (random-uuid) sid (random-uuid) jid (random-uuid) v (random-uuid)
        repo-s (fake-repo-sessoes :justificativa (pendente (random-uuid) v))]
    (is (= 404 (:status (pt/response-for (service-fn* #{"secretario"} repo-s)
                                         :patch (url-decisao sid jid)
                                         :headers (com-json (token ente (random-uuid)))
                                         :body (corpo {"estado" "aprovada" "lock-version" 0}))))
        "a justificativa nao e' desta sessao (anti confused-deputy) -> 404")))

(deftest decidir-justificativa-inexistente-404
  (let [ente (random-uuid) sid (random-uuid) jid (random-uuid)
        repo-s (fake-repo-sessoes :justificativa nil)]
    (is (= 404 (:status (pt/response-for (service-fn* #{"secretario"} repo-s)
                                         :patch (url-decisao sid jid)
                                         :headers (com-json (token ente (random-uuid)))
                                         :body (corpo {"estado" "aprovada" "lock-version" 0})))))))

(deftest decidir-justificativa-400s
  (let [ente (random-uuid) sid (random-uuid) jid (random-uuid) v (random-uuid)
        svc (fn [] (service-fn* #{"secretario"} (fake-repo-sessoes :justificativa (pendente sid v))))
        patch (fn [b] (pt/response-for (svc) :patch (url-decisao sid jid)
                                       :headers (com-json (token ente (random-uuid)))
                                       :body (corpo b)))]
    (is (= 400 (:status (patch {"estado" "pendente" "lock-version" 0})))
        "'pendente' nao e' estado de DECISAO — a borda so' aceita os terminais")
    (is (= 400 (:status (patch {"estado" "talvez" "lock-version" 0}))) "estado fora do enum")
    (is (= 400 (:status (patch {"estado" "aprovada"}))) "lock-version ausente: CAS nao e' opcional")
    (is (= 400 (:status (patch {"estado" "aprovada" "lock-version" -1}))) "lock-version fora do range")))

(deftest decidir-justificativa-campo-extra-decidido-por-e-ignorado
  (let [ente (random-uuid) sid (random-uuid) jid (random-uuid) v (random-uuid) forjado (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes :justificativa (pendente sid v) :cap cap)
        r (pt/response-for (service-fn* #{"secretario"} repo-s)
                           :patch (url-decisao sid jid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"estado" "aprovada" "lock-version" 0
                                         "decidido-por" (str forjado)}))]
    (is (= 200 (:status r)) "campo alheio e' FILTRADO pelo allowlist, nao rejeitado (convencao do modulo)")
    (is (not= forjado (:decidido-por @cap))
        "o decisor forjado no corpo nunca chega ao dominio — quem decidiu vem do ator")))

;; ---------- o router: a porta self-service e' ALCANCAVEL ----------

(deftest a-porta-self-service-nao-fica-sombreada-pelo-param-da-decisao
  ;; REGRESSAO de roteamento, nao de dominio. O prefix-tree do Pedestal nao resolve literal-vs-param IRMAOS:
  ;; enquanto `/justificativas/:jid/decisao` existir, um `/justificativas/<literal>` no mesmo nivel responde
  ;; 404 do ROUTER — antes de auth, antes de papel, antes do handler. O sintoma e' cruel porque 404 e' status
  ;; legitimo destas rotas (sessao inexistente), entao o defeito passa por "comportamento esperado".
  ;; Este teste separa os dois: com credencial valida e papel ERRADO, a resposta tem de ser 403 (o request
  ;; CHEGOU ao gate de papel). Se voltar 404 aqui, a rota sumiu do router.
  (let [ente (random-uuid) sid (random-uuid)
        r (pt/response-for (service-fn* #{"secretario"} (fake-repo-sessoes))
                           :post (url-minha sid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"motivo" motivo}))]
    (is (= 403 (:status r))
        (str "403 = o router ENTREGOU o request ao gate de papel. 404 aqui significa que "
             (url-minha sid) " ficou inalcancavel — o param irmao voltou a sombrear o literal."))))

;; ---------- REGRESSAO: o impedimento "ninguem e' juiz em causa propria" FALHAVA ABERTO ----------

(deftest decidir-a-propria-justificativa-e-negada-mesmo-sem-vinculo-de-identidade
  ;; MAJOR (security). O guard era `(and meu-vereador-id (= meu-vereador-id (:vereador-id j)))`.
  ;; `resolver-vereador` casa por `cadastros.vereador.identidade_id`, e esse vinculo e' um ato SEPARADO,
  ;; `admin_ente`-gated — NULL e' o estado PADRAO. Com o `and`, um membro da Casa com credencial de
  ;; 'secretario' e sem vinculo resolvivel deferia a PROPRIA falta com um PATCH: a falta sumia da apuracao
  ;; de assiduidade, que e' o insumo do art. 55 CF / LOM e do desconto de jeton. Impossibilidade de AVALIAR
  ;; o impedimento tem de NEGAR.
  (let [ente (random-uuid) sid (random-uuid) jid (random-uuid) v (random-uuid)
        cap (atom nil)
        repo-s (fake-repo-sessoes :justificativa (pendente sid v) :cap cap)
        r (pt/response-for (service-fn* #{"secretario" "vereador"} repo-s :resolver (constantly nil))
                           :patch (url-decisao sid jid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"estado" "aprovada" "lock-version" 0}))]
    (is (= 403 (:status r)) "ator com papel 'vereador' e SEM vinculo resolvivel -> negado, nao 200")
    (is (nil? @cap) "a decisao nao chegou ao Repo — o estado e' TERMINAL e nao ha' rota de reabertura")))

(deftest decidir-a-propria-justificativa-com-vinculo-continua-negada
  (let [ente (random-uuid) sid (random-uuid) jid (random-uuid) v (random-uuid)
        repo-s (fake-repo-sessoes :justificativa (pendente sid v))
        r (pt/response-for (service-fn* #{"secretario"} repo-s :resolver (constantly v))
                           :patch (url-decisao sid jid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"estado" "aprovada" "lock-version" 0}))]
    (is (= 403 (:status r)) "o caminho que ja funcionava continua funcionando (nao-regressao)")))

(deftest servidor-sem-papel-vereador-e-sem-vinculo-continua-podendo-decidir
  ;; o fail-closed nao pode fechar a porta do caso NORMAL: o servidor da secretaria nao e' membro da Casa,
  ;; nao carrega o papel 'vereador', e e' exatamente quem lanca a decisao que a Mesa tomou.
  (let [ente (random-uuid) sid (random-uuid) jid (random-uuid) v (random-uuid)
        repo-s (fake-repo-sessoes :justificativa (pendente sid v))
        r (pt/response-for (service-fn* #{"secretario"} repo-s :resolver (constantly nil))
                           :patch (url-decisao sid jid)
                           :headers (com-json (token ente (random-uuid)))
                           :body (corpo {"estado" "aprovada" "lock-version" 0}))]
    (is (= 200 (:status r)))))
