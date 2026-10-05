(ns oplenario.identidade.diplomat.http.in
  "Superficie ADMINISTRATIVA de identidade (§22.10 diplomat, ADR-0001) — gated `admin_ente`, MAIS uma
  excecao (GET /meu/identidade, ver docstring do handler e de `rotas`): gate `auth` apenas, sem papel —
  mesmo padrao de excecao ja usado por `paineis/diplomat/http/in.clj` (`/meu/notificacoes`). Separada de
  diplomat/http/auth_in.clj, que e' a superficie PUBLICA de login (descoberta/mint/logout): responsabilidades
  distintas, gates opostos.

  As 3 rotas ADMINISTRATIVAS sao os passos (1) e (3) do fluxo de provisionamento; o passo (2) e' do
  `cadastros`. Quem ORQUESTRA e' o front (§22.10:26 — a administracao do ente e' area de UI, nao modulo
  backend). A ordem importa e e' 'acesso por ultimo': conceder-acesso! e' o unico passo que abre a porta.

  DESVIO do brief original (Task 8, Step 3): `criar-usuario!` (Keycloak, User Profile) EXIGE `:nome`
  (keycloak_idp.clj `nome->first-last`) — sem ele, o realm real da NPE em `str/trim` de nil. O brief
  original passava `(:nome ator)`, mas `ator` (identidade.autenticacao/resolver-sessao) NUNCA carrega
  `:nome` (so' :identidade-id/:ente-id/:tipo-vinculo/:vinculo-ativo-id/:papeis) e o wire ConcederAcesso
  tambem nao tem esse campo — seria sempre nil. O nome CERTO e' o da identidade sendo provisionada, ja'
  gravado por criar-identidade! (Task 6); aqui buscado via `repo/nome-por-id` (leitura supratenant,
  nao mexe na ordem DB-antes-do-Keycloak — ainda e' so' banco). `nome-por-id` (nao `identidade-por-id`)
  DE PROPOSITO — review Task 8 IMPORTANT-2b: `identidade-por-id` tambem devolve `:cpf`, e este handler
  so' precisa do nome; ler pela projecao mais estreita torna estruturalmente impossivel um CPF entrar
  no payload do Keycloak por aqui, em vez de depender de disciplina de destructuring."
  (:require [oplenario.http :as http]
            [oplenario.identidade.adapters.in.acesso :as adapters-in]
            [oplenario.identidade.adapters.out.acessos :as adapters-out-acessos]
            [oplenario.identidade.adapters.out.agente-institucional :as adapters-out-agente]
            [oplenario.identidade.adapters.out.meu-identidade :as adapters-out-meu]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.identidade.components.repositorio :as repo]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp :as idp]))

(set! *warn-on-reflection* true)

(defn- criar-identidade-handler
  "POST /identidade/identidades. SUPRATENANT (o unico caminho que enxerga CPF). Idempotente por CPF: mesmo
  CPF vereador em Sobral e Fortaleza = a MESMA identidade, dois vinculos (disc.1, §22.5.3). NAO concede
  acesso — sem vinculo, resolver-sessao nao resolve e ninguem entra."
  [repo-identidade]
  (fn [req]
    (let [m (adapters-in/criar-identidade->dominio (:ator req) (:json-params req))]
      (http/json-resposta 201 {:identidade-id (str (repo/criar-identidade! repo-identidade m))}))))

(defn- conceder-acesso-handler
  "POST /identidade/acessos. O passo que ABRE A PORTA — ultimo do fluxo, de proposito.
  BANCO ANTES DO KEYCLOAK: se o KC falhar depois do commit, sobra vinculo sem credencial -> ninguem entra
  -> repetir conserta (fail-closed). A inversao tambem seria fail-closed, mas banco-primeiro mantem a nossa
  fonte de verdade a' frente do sistema externo. Erro de infra do KC PROPAGA -> 500 (nunca 401). O `nome`
  do usuario Keycloak vem do REGISTRO da identidade (`repo/nome-por-id`, ja' gravado na Task 6), NAO do
  `ator` (o admin_ente que esta' chamando — ver docstring do ns); `nome-por-id` e' a leitura ESTREITA
  (sem :cpf) de proposito, ver docstring do ns (review Task 8 IMPORTANT-2b).

  Reconceder acesso a um vinculo SUSPENSO (`:estado \"suspenso\"`) NAO reativa (Task 7, deliberado):
  `repo/conceder-acesso!` -> `vinc/criar!` faz UPSERT por (ente,identidade,tipo) e o `:do-update-set` so'
  toca `:tipo`, nunca `:estado` — suspensao so' sai por `mudar-estado-vinculo!`, nunca de-lado por um
  re-conceder. Fail-closed no BANCO desde a Task 7; a Task 12 fechou o buraco de SEGURANCA que sobrava: o
  proprio `repo/conceder-acesso!` agora LANCA `:conflito/vinculo-nao-ativo` (dentro da mesma tx, antes de
  tocar papeis) quando o vinculo canonico nao ficou ativo — capturado AQUI, LOCALMENTE (mesmo padrao de
  `cadastros/diplomat/http/in.clj`'s `ligar-identidade-handler`; `:conflito/*` NAO e' mapeado no
  interceptor global `erro`) -> 409, ANTES de qualquer chamada ao Keycloak. Sem isso, um admin_ente
  tentando reinstaurar alguem suspenso receberia 201 + 'convite enviado' — uma mentira num controle de
  acesso — e o endpoint virava um primitivo de envio de convite sem limite contra uma pessoa suspensa. O
  409 explica que reativar e' operacao SEPARADA (job de `mudar-estado-vinculo!`) sem prometer uma rota
  HTTP que ainda nao existe (esse gap segue sendo escalado a parte)."
  [repo-identidade idp-comp]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          {:keys [identidade-id tipo papeis email perfil-juridico]}
          (adapters-in/conceder-acesso->dominio ator (:json-params req))]
      (try
        (let [;; identidade-id vem do wire, validado contra o schema (adapters-in) mas nao contra o banco
              ;; ainda; `vinculo.identidade_id REFERENCES identidade.identidade(id)` faz `conceder-acesso!`
              ;; (que roda ANTES, logo abaixo) falhar por FK antes que `nome-por-id` pudesse ver um id
              ;; inexistente — por isso `nome` abaixo nunca precisa tratar nil aqui (MINOR-2, review Task 8).
              vinculo {:id (random-uuid) :ente-id ente-id :identidade-id identidade-id
                       :tipo tipo :estado "ativo"}
              ;; ADR-0019: com o papel `juridico` o perfil (qualificacao + OAB) entra na MESMA tx dos papeis
              r (if perfil-juridico
                  (repo/conceder-acesso! repo-identidade ente-id vinculo papeis perfil-juridico)
                  (repo/conceder-acesso! repo-identidade ente-id vinculo papeis))
              nome (:nome (repo/nome-por-id repo-identidade identidade-id))]
          (idp/provisionar-realm! idp-comp ente-id)
          (idp/criar-usuario! idp-comp ente-id {:identidade-id identidade-id :nome nome :email email})
          (idp/convidar! idp-comp ente-id identidade-id)
          (http/json-resposta 201 {:vinculo-id (str (:vinculo-id r)) :convite "enviado"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/vinculo-nao-ativo (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "vinculo suspenso — reativar e' uma operacao separada, nao este endpoint"})
            (throw e)))))))

;; ADR-0005 (adendo "Revogar acesso") ------------------------------------------------------------------------------
(defn- listar-acessos-handler
  "GET /identidade/acessos — os acessos que a tela concede (vereador, auditor, juridico) da Casa DO ATOR, ativos e
  revogados, um por pessoa e papel. A Casa vem do ator: a RLS faz o resto. Nome, nunca CPF."
  [repo-identidade]
  (fn [req]
    (http/json-resposta 200 (adapters-out-acessos/acessos->wire
                             (repo/acessos-da-casa repo-identidade (:ente-id (:ator req)) adapters-in/papeis-concediveis)))))

(defn- revogar-acesso-handler
  "POST /identidade/acessos/:identidade-id/revogacao {papel, motivo} — o `admin_ente` tira UM dos papeis que concedeu.
  O motivo e' obrigatorio e fica gravado com quem revogou e quando; nada e' apagado, e conceder de novo continua possivel.
  Efeito na PROXIMA chamada da pessoa: o ator e' recalculado a cada requisicao (`resolver-sessao`), a sessao nao guarda
  papel. Sem papel nenhum sobrando na Casa, o vinculo dela e' encerrado e a mesma sessao passa a dar 401.

  404 fail-closed: id que nao e' UUID, pessoa sem esse papel ATIVO nesta Casa, ou papel que so' existe em outra Casa
  (a RLS esconde — o `admin_ente` da Casa B nao descobre nada da Casa A). Papel fora do conjunto da tela (`admin_ente`,
  `secretario`) e' 400 na validacao: a Casa nao perde o ultimo administrador por esta rota."
  [repo-identidade]
  (fn [req]
    (let [ator (:ator req)
          r (adapters-in/revogar-acesso->dominio ator (get-in req [:path-params :identidade-id]) (:json-params req))]
      (if-not r
        (http/json-resposta 404 {:erro "acesso nao encontrado"})
        (let [res (repo/revogar-acesso! repo-identidade (:ente-id ator) r)]
          (if (:revogado? res)
            (http/json-resposta 200 (adapters-out-acessos/revogacao->wire res))
            (http/json-resposta 404 {:erro "acesso nao encontrado"})))))))

(defn- reenviar-convite-handler
  "POST /identidade/acessos/:identidade-id/convite. So' reenvia (o KC invalida o codigo anterior). O e-mail
  mora SO' no Keycloak — nao ha' coluna nossa a consultar, e e' de proposito (PII a menos).

  404 fail-closed nos DOIS sentidos: id que nao parseia como UUID (nunca alcanca o Keycloak) OU UUID
  bem-formado mas nao provisionado neste realm (`idp/convidar!` lanca `:idp/usuario-inexistente` —
  `keycloak_idp.clj convidar-impl`). Sem o catch, o 2o caso caia no `:else` do interceptor global `erro`
  (interceptors.clj) e virava 500 com stack trace logado — um typo de admin_ente nao e' incidente de
  infra. Qualquer OUTRA excecao (infra do KC fora do ar etc.) RE-LANCA de proposito: precisa continuar
  virando 500 (nunca 401/404 mascarando degradacao real — ver conceder-acesso-keycloak-fora-do-ar-500)."
  [idp-comp]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          ident (parse-uuid (get-in req [:path-params :identidade-id]))]
      (if-not ident
        (http/json-resposta 404 {:erro "identidade nao encontrada"})
        (try
          (idp/convidar! idp-comp ente-id ident)
          (http/json-resposta 200 {:convite "reenviado"})
          (catch clojure.lang.ExceptionInfo e
            (if (= :idp/usuario-inexistente (:tipo (ex-data e)))
              (http/json-resposta 404 {:erro "identidade nao encontrada"})
              (throw e))))))))

(defn- meu-identidade-handler
  "GET /meu/identidade — o 'quem sou eu' que QUALQUER ator autenticado le sobre SI MESMO, papel nenhum
  exigido (mesmo racional de GET /meu/notificacoes em paineis/diplomat/http/in.clj: e' escopado por
  IDENTIDADE, nao por cargo — a cidada, sem papel algum, tambem precisa saber quem e' pra o cabecalho da
  tela nao mentir, ver docs/18 'defeito consertado antes deste mapa existir'). NUNCA le' `:identidade-id`
  do cliente (path/query) — so' o `(:ator req)` que o interceptor `auth` ja resolveu do token VERIFICADO;
  ler um id do cliente aqui seria IDOR (qualquer autenticado leria o nome de qualquer outro).

  `:papeis` vem do ATOR ja resolvido (sem tocar o banco de novo); so' `:nome` precisa de uma leitura —
  via `repo/nome-por-id`, a MESMA leitura ESTREITA sem :cpf que `conceder-acesso-handler` usa (ver
  docstring do ns) — nunca `identidade-por-id`, que tambem devolve :cpf. O adapter (`adapters-out-meu`)
  valida contra `MeuIdentidadeOut` (`:closed true`) antes de serializar: um `merge` descuidado que um dia
  tentasse devolver mais campos (ex.: :cpf) reprovaria a validacao (500), nunca vazaria em silencio."
  [repo-identidade]
  (fn [req]
    (let [ator (:ator req)
          nome (:nome (repo/nome-por-id repo-identidade (:identidade-id ator)))]
      (http/json-resposta 200 (adapters-out-meu/meu-identidade->wire {:nome nome :papeis (:papeis ator)})))))

(defn- agentes-da-casa [repo-identidade ente-id]
  (for [a (sort (keys mod/agentes-institucionais))]
    [a (repo/concessao-agente repo-identidade ente-id a)]))

(defn- agentes-handler
  "GET /identidade/agentes-institucionais (B.8, ADR-0013): os agentes da Casa sem pessoa por tras e se cada um esta'
  ligado. O `admin_ente` liga e desliga; a secretaria ve (e' a fila dela que recebe o que o agente produz)."
  [repo-identidade]
  (fn [req]
    (http/json-resposta 200 (adapters-out-agente/agentes->wire (agentes-da-casa repo-identidade (:ente-id (:ator req)))))))

(defn- agente-do-path [req]
  (let [a (get-in req [:path-params :agente])]
    (when (contains? mod/agentes-institucionais a) a)))

(defn- concessao-handler
  "PUT (ligar) / DELETE (desligar) /identidade/agentes-institucionais/:agente/concessao — so' `admin_ente` (docs/25
  3.3: agente institucional e' concedido pelo admin da Casa). Idempotente. Desligar vale na chamada seguinte do agente
  (a credencial dele resolve sem papel), nao so' na proxima execucao."
  [repo-identidade ligar?]
  (fn [req]
    (if-let [agente (agente-do-path req)]
      (let [{:keys [ente-id identidade-id]} (:ator req)]
        (if ligar?
          (repo/conceder-agente! repo-identidade ente-id
                                 {:agente agente :classes (map name (:classes (get mod/agentes-institucionais agente)))
                                  :concedida-por identidade-id})
          (repo/revogar-agente! repo-identidade ente-id agente identidade-id))
        (http/json-resposta 200 (adapters-out-agente/agentes->wire (agentes-da-casa repo-identidade ente-id))))
      (http/json-resposta 404 {:erro "agente institucional desconhecido"}))))

(defn rotas
  "Fragmento de rotas do modulo identidade (table syntax Pedestal). As 3 primeiras sao a superficie
  ADMINISTRATIVA — TODAS exigem `admin_ente` (§22.5.1 — 'cadastrada pelo admin do ente'). `GET
  /meu/identidade` e' a UNICA excecao do fragmento: gate `auth` apenas, sem papel (ver docstring do
  handler) — mesmo padrao ja usado por `paineis/diplomat/http/in.clj` (`/meu/notificacoes`)."
  [{:keys [auth repo-identidade idp]}]
  (let [papel (it/exige-papel "admin_ente")]
    #{["/identidade/identidades" :post
       [auth papel it/corpo-json (criar-identidade-handler repo-identidade)]
       :route-name :identidade/criar-identidade]
      ["/identidade/acessos" :post
       [auth papel it/corpo-json (conceder-acesso-handler repo-identidade idp)]
       :route-name :identidade/conceder-acesso]
      ["/identidade/acessos" :get
       [auth papel (listar-acessos-handler repo-identidade)]
       :route-name :identidade/listar-acessos]
      ["/identidade/acessos/:identidade-id/revogacao" :post
       [auth papel it/corpo-json (revogar-acesso-handler repo-identidade)]
       :route-name :identidade/revogar-acesso]
      ["/identidade/acessos/:identidade-id/convite" :post
       [auth papel (reenviar-convite-handler idp)]
       :route-name :identidade/reenviar-convite]
      ;; B.8 (ADR-0013): os agentes institucionais da Casa — ver e' da secretaria tambem; ligar/desligar, so' do admin
      ["/identidade/agentes-institucionais" :get
       [auth (it/exige-algum-papel ["admin_ente" "secretario"]) (agentes-handler repo-identidade)]
       :route-name :identidade/agentes-institucionais]
      ["/identidade/agentes-institucionais/:agente/concessao" :put
       [auth papel (concessao-handler repo-identidade true)]
       :route-name :identidade/ligar-agente-institucional]
      ["/identidade/agentes-institucionais/:agente/concessao" :delete
       [auth papel (concessao-handler repo-identidade false)]
       :route-name :identidade/desligar-agente-institucional]
      ["/meu/identidade" :get
       [auth (meu-identidade-handler repo-identidade)]
       :route-name :identidade/meu-identidade]}))
