(ns oplenario.sessoes.diplomat.http.audiencia
  "Fronteira HTTP da AUDIENCIA PUBLICA (ADR-0021 Parte A) — as rotas-dado e os handlers, num ns proprio (a borda de
  sessoes ja' e' grande); `sessoes.diplomat.http.in/rotas` funde este fragmento. Tres perfis:
  (1) a MESA (interno): auth + papel na borda (`secretario`; a leitura tambem `vereador`) + mesma Casa no controller;
  (2) o PORTAL anonimo: sem `auth`, a Casa vem do caminho (`resolver-ente-publico`) e a RLS isola; Casa inexistente
      -> 404, como as irmas do portal;
  (3) a CIDADA autenticada (gov.br): SO' `auth`, sem papel (o vinculo de cidadao nao tem papel) — a Casa e' a do ator;
      a inscricao NAO esta' na allowlist da Casa suspensa (423: nao e' protocolo com prazo legal).
  Roteamento (Pedestal): nada de literal ao lado de curinga no mesmo nivel — `/portal/audiencias/:sessao-id/...` e
  `/portal/minhas-inscricoes/...` sao subarvores literais proprias no nivel 2, como `/portal/esic` e
  `/portal/meus-protocolos`."
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.sessoes.adapters.in.audiencia :as adapters-in]
            [oplenario.sessoes.adapters.out.audiencia :as adapters-out]
            [oplenario.sessoes.controllers.audiencia :as controllers]))

(set! *warn-on-reflection* true)

(defn- responder
  "Executa `op` (thunk -> dominio ou nil): nil -> 404 `nao-achado`; `:conflito/audiencia` -> 409 com a frase do dominio;
  `:conflito/audiencia-invalida` -> 422 com a frase. O resto sobe ao interceptor global (400/403/500)."
  [op ->resposta nao-achado]
  (try
    (if-let [r (op)]
      (->resposta r)
      (http/json-resposta 404 {:erro nao-achado}))
    (catch clojure.lang.ExceptionInfo e
      (case (:tipo (ex-data e))
        :conflito/audiencia (http/json-resposta 409 {:erro (ex-message e)})
        :conflito/audiencia-invalida (http/json-resposta 422 {:erro (ex-message e) :campo (some-> (:campo (ex-data e)) name)})
        (throw e)))))

(defn- sid [req k] (adapters-in/id-param->uuid (get-in req [:path-params k]) k))

(defn- com-auditoria [resp rotulo id]
  (assoc resp :auditoria {:rotulo rotulo :recurso-tipo "inscricao_audiencia" :recurso-id (str id)}))

;; ---------- a Mesa ----------

(defn- audiencia-handler [deps]
  (fn [req]
    (responder #(controllers/audiencia-da-sessao deps (:ator req) (sid req :id))
               #(http/json-resposta 200 (adapters-out/audiencia->wire %))
               "audiencia nao encontrada")))

(defn- atualizar-handler [deps]
  (fn [req]
    (let [campos (adapters-in/atualizar->dominio (:json-params req))]
      (responder #(controllers/atualizar-audiencia! deps (:ator req) (sid req :id) campos)
                 #(http/json-resposta 200 (adapters-out/audiencia->wire %))
                 "audiencia nao encontrada"))))

(defn- inscrever-presencial-handler [deps]
  (fn [req]
    (let [m (adapters-in/inscricao-presencial->dominio (:json-params req))]
      (responder #(controllers/inscrever-presencial! deps (:ator req) (sid req :id) m)
                 #(com-auditoria (http/json-resposta 201 (adapters-out/inscricao->wire %)) (:protocolo %) (:id %))
                 "audiencia nao encontrada"))))

(defn- conduzir-handler
  "As tres escritas da Mesa sobre uma inscricao (chamada, encerramento, ausencia)."
  [deps para corpo->extra]
  (fn [req]
    (let [extra (corpo->extra (:json-params req))]
      (responder #(controllers/conduzir-inscricao! deps (:ator req) (sid req :id) (sid req :insc-id) para extra)
                 #(com-auditoria (http/json-resposta 200 (adapters-out/inscricao->wire %)) (:protocolo %) (:id %))
                 "inscricao nao encontrada"))))

;; ---------- o portal (anonimo) ----------

(defn- audiencias-publicas-handler [deps resolver-ente-publico casa-existe?]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))]
      (if (casa-existe? ente-id)
        (http/json-resposta 200 (adapters-out/audiencias-publicas->wire (controllers/audiencias-publicas deps ente-id)))
        (http/json-resposta 404 {:erro "ente nao encontrado"})))))

(defn- audiencia-publica-handler [deps resolver-ente-publico casa-existe?]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))
          id (sid req :sessao-id)]
      (if-let [r (when (casa-existe? ente-id) (controllers/audiencia-publica deps ente-id id))]
        (http/json-resposta 200 (adapters-out/audiencia-publica->wire r))
        (http/json-resposta 404 {:erro "audiencia nao encontrada"})))))

;; ---------- a cidada ----------

(defn- inscrever-pelo-portal-handler [deps]
  (fn [req]
    (let [m (adapters-in/inscricao-portal->dominio (:json-params req))]
      (responder #(controllers/inscrever-pelo-portal! deps (:ator req) (sid req :sessao-id) m)
                 #(com-auditoria (http/json-resposta 201 (adapters-out/recibo-portal->wire %)) (:protocolo %) (:id %))
                 "audiencia nao encontrada"))))

(defn- minhas-inscricoes-handler [deps]
  (fn [req]
    (http/json-resposta 200 (adapters-out/minhas-inscricoes->wire (controllers/minhas-inscricoes deps (:ator req))))))

(defn- desistir-handler [deps]
  (fn [req]
    (responder #(controllers/desistir! deps (:ator req) (sid req :id))
               #(com-auditoria (http/json-resposta 200 (adapters-out/minha-inscricao->wire %)) (:protocolo %) (:id %))
               "inscricao nao encontrada")))

(defn rotas
  "O fragmento da audiencia publica. `deps` = o mapa do controller (ver `controllers.audiencia`)."
  [{:keys [auth resolver-ente-publico casa-existe?] :as deps}]
  (let [mesa [auth (it/exige-papel "secretario")]
        sem-corpo (fn [_] {})]
    #{["/sessoes/:id/audiencia" :get
       [auth (it/exige-algum-papel #{"secretario" "vereador"}) (audiencia-handler deps)]
       :route-name :sessoes/audiencia]
      ["/sessoes/:id/audiencia" :patch (conj mesa it/corpo-json (atualizar-handler deps))
       :route-name :sessoes/atualizar-audiencia]
      ["/sessoes/:id/audiencia/inscricoes" :post (conj mesa it/corpo-json (inscrever-presencial-handler deps))
       :route-name :sessoes/inscrever-na-audiencia]
      ["/sessoes/:id/audiencia/inscricoes/:insc-id/chamada" :post
       (conj mesa (conduzir-handler deps "falando" sem-corpo))
       :route-name :sessoes/chamar-cidadao]
      ["/sessoes/:id/audiencia/inscricoes/:insc-id/encerramento" :post
       (conj mesa it/corpo-json (conduzir-handler deps "falou" adapters-in/encerramento->dominio))
       :route-name :sessoes/encerrar-fala-cidada]
      ["/sessoes/:id/audiencia/inscricoes/:insc-id/ausencia" :post
       (conj mesa (conduzir-handler deps "ausente" sem-corpo))
       :route-name :sessoes/registrar-ausencia-cidada]
      ;; o portal (anonimo): a Casa vem do caminho
      ["/portal/casa/:ente/audiencias" :get
       [(audiencias-publicas-handler deps resolver-ente-publico casa-existe?)]
       :route-name :sessoes/audiencias-publicas]
      ["/portal/casa/:ente/audiencias/:sessao-id" :get
       [(audiencia-publica-handler deps resolver-ente-publico casa-existe?)]
       :route-name :sessoes/audiencia-publica]
      ;; a cidada (gov.br): so' `auth`, sem papel
      ["/portal/audiencias/:sessao-id/inscricoes" :post
       [auth it/corpo-json (inscrever-pelo-portal-handler deps)]
       :route-name :sessoes/inscrever-pelo-portal]
      ["/portal/minhas-inscricoes" :get [auth (minhas-inscricoes-handler deps)]
       :route-name :sessoes/minhas-inscricoes]
      ["/portal/minhas-inscricoes/:id/desistencia" :post [auth (desistir-handler deps)]
       :route-name :sessoes/desistir-da-inscricao]}))
