(ns oplenario.identidade.diplomat.http.auth-in
  "Fronteira de IO HTTP de ENTRADA do modulo identidade (§22.10 diplomat/http/in, ADR-0001) — Onda D Slice 2
  Task 3: GET /auth/descoberta/:ente. Rota PUBLICA (SEM `auth`) — e' descoberta PRE-login: o FE (BFF) ainda
  nao tem token, precisa saber QUAL realm Keycloak e QUAIS parametros publicos usar pra comecar o Authorization
  Code+PKCE. O :ente do path e' o UUID do ente-cru (mesmo perfil de resolver-ente-publico do portal
  transparencia/participacao — V1 sem slug humano), coagido fail-closed AQUI (identidade nao pode importar o
  seam de outro modulo — §22.10 — replica a mesma forma localmente).

  `info-ente` (fn ente-id -> ente-map|nil) chega INJETADA pelo host (cross-modulo por inversao de dependencia
  sobre o Repo de cadastros — mesmo padrao de consultar-sessao/membros-da-casa em oplenario.rotas/montar;
  identidade NUNCA importa cadastros). Existencia = `(some? (info-ente id))`; ente inexistente -> 404
  fail-closed (nunca vaza o realm/URL de um tenant que nao existe). A resposta 200 tambem devolve o nome
  PUBLICO do ente (`nome-oficial`/`nome-curto`, mesma convencao de EnteOut da transparencia — nada sensivel)
  p/ o FE mostrar 'Entrar em <Camara>' na tela de login.

  Distinto do mint de token pos-callback (Task 4): la' o ente-id vem do ISSUER do token VERIFICADO (nunca do
  path/corpo — anti-forge). Aqui e' o INVERSO por desenho: e' descoberta pre-auth, nao ha token ainda pra
  extrair ente-id — o path e' a UNICA fonte possivel, e a resposta so' expoe metadado publico de login
  (realm/base-url/client-id), nada sensivel.

  TASK 4 — POST /auth/sessoes (mint): o BFF, apos completar o PKCE contra o Keycloak, POSTa o access token KC
  aqui. O backend RE-VERIFICA o token (NUNCA confia no BFF) — a cadeia de confianca espelha EXATAMENTE o
  interceptor `autenticacao` (interceptors.clj): verificar-token -> resolver-sessao -> criar-sessao!, NESTA
  ORDEM. `:identidade-id`/`:ente-id` da sessao minted vem SO do `ator` resolvido (que deriva do claims
  VERIFICADO pelo idp) — NENHUM campo do corpo HTTP os alcanca, mesmo que o corpo tente forjá-los (o gate
  `corpo->token` descarta tudo exceto `:token`, allowlist estrita). Fail-closed: token invalido -> 401; sem
  vinculo ATIVO -> 401; falha de INFRA do idp (rede/JWKS) LANCA e o throw PROPAGA ate o interceptor global
  `erro` (500) — NUNCA vira 401 (mascarar degradacao de infra como token ruim seria incorreto, contrato do
  port `oplenario.kernel.components.idp`). Rota PUBLICA (sem `auth` — este ato CRIA a sessao, nao ha sessao
  ainda pra exigir). `criar-sessao!` agora vive NO MESMO protocolo `RepoIdentidade` (Task 2 fundiu os metodos
  de sessao — nao ha `repo-sessao` separado).

  TASK 5 — DELETE /auth/sessoes (logout): destroi a sessao opaca (DELETE por hash, `apagar-sessao!`, Task 2)
  p/ que um cookie roubado/velho pare de resolver. DECISAO desta sessao (desvio deliberado do brief
  original, que dizia 'atras do interceptor de cookie da T6'): a T6 (o interceptor `sessao-cookie`) AINDA
  NAO existe — vem DEPOIS desta task. Em vez de bloquear Task 5 numa dependencia futura, a rota le o cookie
  DIRETO no handler via `it/cookie-sessao` (helper novo, PURO, em `oplenario.interceptors` — home cross-
  cutting do host, nao do modulo; ver docstring la'). Isso desacopla T5 de T6 e ainda deixa o helper pronto
  p/ T6 reusar (DRY) quando o interceptor for construido. Rota PUBLICA (sem `auth`) — nao exige sessao
  valida p/ aceitar o DELETE. IDEMPOTENTE por desenho: sem cookie -> 204 (NUNCA 401 — deslogar de uma
  sessao inexistente/ja-expirada e' SUCESSO de UX, nao erro; um cliente com sessao ja vencida ainda precisa
  conseguir limpar o proprio estado). Cookie presente -> `apagar-sessao!` incondicional (o DELETE por hash
  ja e' idempotente — segredo que nao bate com nenhuma linha e' no-op, nao erro)."
  (:require [clojure.string :as str]
            [oplenario.http :as http]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time Duration Instant)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- ente-param->uuid
  "Path-param :ente (rota PUBLICA) -> UUID do ente. Malformado/ausente -> 400 fail-closed. Coercao LOCAL
  (nao importa transparencia/participacao — §22.10 proibe cross-modulo; mesma forma, replicada)."
  [s]
  (when (str/blank? s)
    (throw (ex-info "ente ausente na rota publica" {:tipo :validacao/invalido :campo :ente})))
  (try
    (UUID/fromString s)
    (catch IllegalArgumentException _
      (throw (ex-info "ente invalido" {:tipo :validacao/invalido :campo :ente})))))

(defn- descoberta-handler
  "GET /auth/descoberta/:ente — resolve realm/base-url-publico/client-id + nome PUBLICO do tenant p/ o FE
  comecar o PKCE e exibir o nome da Casa. `info-ente` (fn ente-id -> ente-map|nil) chega INJETADA pelo host
  (inversao de dependencia sobre cadastros — §22.10, identidade nunca importa cadastros). nil -> 404
  fail-closed antes de devolver qualquer metadado. So expoe nome PUBLICO (nome-oficial/nome-curto), nada
  sensivel — mesma convencao de EnteOut da transparencia."
  [info-ente keycloak]
  (fn [req]
    (let [ente-id (ente-param->uuid (get-in req [:path-params :ente]))]
      (if-let [e (info-ente ente-id)]
        (http/json-resposta 200
          {:ente-id      (str ente-id)
           :realm        (str (:realm-prefixo keycloak) ente-id)
           :base-url     (:base-url-publico keycloak)
           :client-id    (:web-client-id keycloak)
           ;; ADR-0015: o realm tem o broker gov.br? O portal so' mostra 'Entrar com gov.br' quando tem.
           :govbr        (some? (:govbr keycloak))
           :nome-oficial (:nome-oficial e)
           :nome-curto   (:nome-curto e)})
        (http/json-resposta 404 {:erro "ente nao encontrado"})))))

(def ^:private max-cpf-chars
  "Teto do campo `cpf` no corpo: 11 digitos com mascara (`000.000.000-00`) cabem folgado; o resto e' lixo."
  32)

(defn- corpo->cpf
  "Corpo JSON (chaves STRING) -> CPF so' com os digitos, valido pelo digito verificador. ALLOWLIST: so' `cpf` e' lido.
  Qualquer outra forma -> 400 sem tocar o banco."
  [json-params]
  (let [bruto (when (map? json-params) (get json-params "cpf"))
        digitos (when (and (string? bruto) (<= (count bruto) max-cpf-chars)) (str/replace bruto #"[^0-9]" ""))]
    (when-not (mod/valido-cpf? digitos)
      (throw (ex-info "cpf invalido" {:tipo :validacao/invalido :campo :cpf})))
    digitos))

(defn- localizar-handler
  "POST /auth/localizar (ADR-0025) — a entrada pelo CPF: em quais Casas a pessoa tem acesso INSTITUCIONAL ativo e o
  `login-hint` (o identidade-id, que e' o usuario dela no realm de cada Casa) para o BFF levar o navegador direto a'
  tela de senha. O CPF fica so' aqui: nao vai ao Keycloak, nao vai para a URL, nao vai para o log.
   - CPF fora do digito verificador -> 400, sem consultar o banco.
   - Sem identidade, sem vinculo, ou so' Casas encerradas -> 200 {:casas []} SEM hint (nao entrega o id de ninguem).
   - `casa-para-login` (fn ente-id -> {:nome-oficial :nome-curto} | nil, injetada pelo host) tira a Casa encerrada ou
     inexistente — identidade nunca importa cadastros (§22.10).
  A resposta revela a quem conhece o CPF em quais Casas a pessoa atua (ver ADR-0025: quase sempre e' publico, e o
  limite por IP do host segura a varredura). A consulta ao banco roda mesmo sem identidade, para o tempo de resposta
  nao contar se o CPF existe."
  [repo-identidade casa-para-login]
  (fn [req]
    (let [cpf (corpo->cpf (:json-params req))
          iid (repo/id-por-cpf repo-identidade cpf)
          entes (repo/casas-com-acesso-institucional repo-identidade (or iid (UUID/randomUUID)))
          casas (when iid
                  (->> entes
                       (keep (fn [ente-id]
                               (when-let [{:keys [nome-oficial nome-curto]} (casa-para-login ente-id)]
                                 {:ente-id (str ente-id) :nome-oficial nome-oficial :nome-curto nome-curto})))
                       (sort-by (juxt :nome-oficial :ente-id))
                       vec))]
      (http/json-resposta 200 (if (seq casas)
                                {:casas casas :login-hint (str iid)}
                                {:casas []})))))

(def ^:private max-token-chars
  "Teto de sanidade do :token no corpo (defesa-em-profundidade; o interceptor global `corpo-json` ja' limita
  o corpo inteiro a 256KiB). JWTs do KC cabem folgado em poucos KB mesmo com muitas roles/claims; 16K e'
  generoso o bastante p/ nunca barrar um token real e ainda assim nao aceitar corpo-do-tamanho-do-teto-global
  so' pra um campo string."
  16384)

(defn- corpo->token
  "Corpo JSON (chaves STRING, via `corpo-json`) -> token cru. ALLOWLIST estrita — SO `:token` e' lido;
  qualquer outro campo do corpo (ex.: `ente-id`/`identidade-id` forjados pelo cliente) e' IGNORADO aqui e
  NUNCA alcanca a sessao — `:identidade-id`/`:ente-id` da sessao minted vem SO do claims VERIFICADO
  (`mint-handler`). 400 fail-closed (`:validacao/invalido`, mapeado p/ 400 pelo interceptor global `erro`)
  se o corpo nao e' objeto, `:token` ausente, vazio ou absurdamente grande."
  [json-params]
  (when-not (map? json-params)
    (throw (ex-info "corpo de mint invalido" {:tipo :validacao/invalido :campo :token})))
  (let [token (get json-params "token")]
    (when-not (and (string? token) (not (str/blank? token)) (<= (count token) max-token-chars))
      (throw (ex-info "token ausente ou invalido" {:tipo :validacao/invalido :campo :token})))
    token))

(defn- mint-handler
  "POST /auth/sessoes — RE-VERIFICA o token KC (nunca confia no BFF) e minta a sessao opaca. CADEIA DE
  CONFIANCA INEGOCIAVEL (espelha `interceptors/autenticacao`): verificar-token -> resolver-sessao ->
  criar-sessao!.
   - `idp/verificar-token` nil -> 401 'token invalido'. Se LANCAR (falha de INFRA — rede/JWKS
     indisponivel), o throw PROPAGA sem catch aqui — o interceptor global `erro` mapeia p/ 500; nunca vira
     401 (contrato do port, ver docstring do ns `oplenario.kernel.components.idp`).
   - `auten/resolver-sessao` nil (identidade sem vinculo ATIVO neste ente) -> 401 'sem vinculo ativo'.
   - Sucesso: `criar-sessao!` recebe `:identidade-id`/`:ente-id` SO do `ator` (que deriva do claims
     VERIFICADO — `resolver-sessao` os repassa apos confirmar o vinculo) — NAO do corpo HTTP, mesmo que o
     corpo contenha esses campos (`corpo->token` ja os descartou). `expira-em`/`ocioso-ate` sao computados
     do `relogio` injetado + a config `:sessao` (`absoluta-h`/`ociosa-min`)."
  [idp repo-identidade relogio {:keys [absoluta-h ociosa-min]}]
  (let [absoluta (Duration/ofHours absoluta-h)
        ;; CARRY (ver docstring de `oplenario.identidade.components.repositorio`): `ociosa-min` (aqui, config
        ;; :sessao) e o default `sessao-janela-ociosa-seg` do Repo (1800s/30min) sao HOJE duas fontes do
        ;; MESMO numero que precisam concordar (o mint crava o `ocioso-ate` INICIAL aqui; o Repo desliza essa
        ;; MESMA janela em cada resolve). Ficam coincidindo por convencao, nao por fiacao — reconciliar
        ;; futuramente fazendo o Repo ler `:sessao :ociosa-min` em vez de ter seu proprio default hardcoded.
        ociosa   (Duration/ofMinutes ociosa-min)]
    (fn [req]
      (let [token (corpo->token (:json-params req))]
        (if-let [verificadas (idp/verificar-token idp token)]
          ;; ADR-0015: o login pelo gov.br cria no 1o acesso o que falta (identidade pelo CPF, vinculo de cidadao,
          ;; consentimento) e a sessao nasce SO' de cidadao — `vinculo-tipo` fica gravado nela.
          (if-let [ator (auten/resolver-sessao repo-identidade
                                               (if (auten/govbr? verificadas)
                                                 {:identidade-id (auten/garantir-cidadao! repo-identidade verificadas)
                                                  :ente-id (:ente-id verificadas) :vinculo-tipo "cidadao"}
                                                 (dissoc verificadas :vinculo-tipo)))]
            (let [^Instant agora-inst (tempo/agora relogio)
                  ;; ADR-0017 (adendo de 05/10/2026): o ator ja' esta' resolvido e a sessao ainda NAO existe — e' aqui que
                  ;; a tentativa da entrada entra na trilha da Casa (o host entrega a funcao; sem ele, nada a fazer). A
                  ;; funcao nunca lanca nem recusa a entrada.
                  _   (when-let [tentativa! (:tentativa-da-entrada! req)] (tentativa! ator))
                  seg (repo/criar-sessao! repo-identidade
                        (cond-> {:identidade-id (:identidade-id ator)
                                 :ente-id       (:ente-id ator)
                                 :expira-em     (.plus agora-inst absoluta)
                                 :ocioso-ate    (.plus agora-inst ociosa)}
                          (auten/govbr? verificadas) (assoc :vinculo-tipo "cidadao")))]
              ;; ADR-0016: o 1o acesso do vinculo vira evento (o registro de Casas ativa a Casa quando e' o 1o admin)
              (when (:vinculo-ativo-id ator)
                (repo/registrar-primeiro-acesso! repo-identidade (:ente-id ator) ator))
              ;; ADR-0017: a entrada vai para a trilha da Casa. O mint nao tem ator na requisicao (e' ele que cria a
              ;; sessao): entrega o ator ao interceptor da trilha pela resposta (a chave nao vai para o fio).
              (assoc (http/json-resposta 200 {:sessao seg})
                     :auditoria {:classe "entrada" :ator ator
                                 :rotulo (if (auten/govbr? verificadas) "entrou pelo gov.br" "entrou")}))
            (http/json-resposta 401 {:erro "sem vinculo ativo"}))
          (http/json-resposta 401 {:erro "token invalido"}))))))

(defn- logout-handler
  "DELETE /auth/sessoes (Task 5) — le o cookie `sessao` DIRETO do request (`it/cookie-sessao`; ver docstring
  do ns p/ a decisao de nao depender do interceptor de cookie da T6, que ainda nao existe) e, se presente,
  `apagar-sessao!` incondicional. Sem cookie -> 204 sem tocar o Repo (idempotente; nunca 401). Cookie
  presente mas ja invalido/expirado/de outra sessao -> tambem 204 (o DELETE por hash e' idempotente,
  Task 2 — segredo que nao bate com nenhuma linha e' no-op, nao erro). Corpo VAZIO (nao json-resposta —
  204 nao carrega corpo por contrato HTTP)."
  [repo-identidade]
  (fn [req]
    (when-let [segredo (it/cookie-sessao req)]
      (repo/apagar-sessao! repo-identidade segredo))
    {:status 204 :headers {} :body nil}))

(defn rotas
  "Fragmento de rotas do modulo identidade (table syntax Pedestal). Recebe `info-ente`/`keycloak` (Task 3,
  ver docstring do ns), `idp`/`repo-identidade`/`relogio`/`sessao` (Task 4 — mint), e reusa
  `repo-identidade` p/ o logout (Task 5 — `apagar-sessao!` ja vive no mesmo `RepoIdentidade`, sem repo
  separado). `sessao` = o mapa `:sessao` da config (`:absoluta-h`/`:ociosa-min`), JA RESOLVIDO pelo host
  (`oplenario.rotas/montar`, mesmo padrao do `keycloak` injetado em Task 3 — o fallback pra
  `config/carregar` vive LA, nao aqui). `oplenario.rotas` funde este fragmento."
  [{:keys [info-ente keycloak idp repo-identidade relogio sessao casa-para-login limite-localizar]}]
  #{["/auth/localizar" :post
     ;; ADR-0025: PUBLICA (pre-login). O limite por IP (host) vem ANTES do parse do corpo: quem passou do limite nem
     ;; tem o corpo lido. Sem `limite-localizar` (testes de borda) a rota segue sem limite.
     (cond-> [] limite-localizar (conj limite-localizar)
             true (conj it/corpo-json (localizar-handler repo-identidade casa-para-login)))
     :route-name :identidade/localizar-casas]
    ["/auth/descoberta/:ente" :get
     [(descoberta-handler info-ente keycloak)]
     :route-name :identidade/descoberta]
    ["/auth/sessoes" :post
     ;; PUBLICA (sem `auth`) — este POST CRIA a sessao; nao ha sessao ainda pra exigir. `it/corpo-json`
     ;; parseia o corpo em (:json-params req) com chaves STRING (mesmo mecanismo de toda rota de escrita).
     [it/corpo-json (mint-handler idp repo-identidade relogio sessao)]
     :route-name :identidade/mint-sessao]
    ["/auth/sessoes" :delete
     ;; PUBLICA (sem `auth`) — decisao desta sessao, ver docstring do ns (Task 5): T6 (interceptor de
     ;; cookie) ainda nao existe, o handler le o cookie direto via `it/cookie-sessao`.
     [(logout-handler repo-identidade)]
     :route-name :identidade/logout-sessao]})
