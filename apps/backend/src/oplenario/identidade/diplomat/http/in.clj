(ns oplenario.identidade.diplomat.http.in
  "Superficie ADMINISTRATIVA de identidade (§22.10 diplomat, ADR-0001) — gated `admin_ente`. Separada de
  diplomat/http/auth_in.clj, que e' a superficie PUBLICA de login (descoberta/mint/logout): responsabilidades
  distintas, gates opostos.

  Estas 3 rotas sao os passos (1) e (3) do fluxo de provisionamento; o passo (2) e' do `cadastros`. Quem
  ORQUESTRA e' o front (§22.10:26 — a administracao do ente e' area de UI, nao modulo backend). A ordem
  importa e e' 'acesso por ultimo': conceder-acesso! e' o unico passo que abre a porta.

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

  Reconceder acesso a um vinculo SUSPENSO (`:estado \"suspenso\"`) NAO reativa (MINOR-1, review Task 8):
  `repo/conceder-acesso!` -> `vinc/criar!` faz UPSERT por (ente,identidade,tipo) e o `:do-update-set` so'
  toca `:tipo`, nunca `:estado` (decisao deliberada da Task 7 — suspensao so' sai por
  `mudar-estado-vinculo!`, nunca de-lado por um re-conceder). Ou seja: este handler passa `:estado
  \"ativo\"` no INSERT, mas num conflito o UPDATE ignora esse campo -> a resposta 201 + 'convite enviado'
  E' HONESTA sobre o convite (o e-mail sai de verdade) mas NAO sobre reativacao — o vinculo continua
  suspenso e `resolver-sessao` continua recusando login. Fail-closed (nao e' vulnerabilidade), mas quem
  chamar este endpoint pra 'desbloquear' alguem precisa saber que isso e' job de
  `mudar-estado-vinculo!`, nao deste endpoint."
  [repo-identidade idp-comp]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          {:keys [identidade-id tipo papeis email]} (adapters-in/conceder-acesso->dominio ator (:json-params req))
          ;; identidade-id vem do wire, validado contra o schema (adapters-in) mas nao contra o banco
          ;; ainda; `vinculo.identidade_id REFERENCES identidade.identidade(id)` faz `conceder-acesso!`
          ;; (que roda ANTES, logo acima) falhar por FK antes que `nome-por-id` pudesse ver um id
          ;; inexistente — por isso `nome` abaixo nunca precisa tratar nil aqui (MINOR-2, review Task 8).
          r (repo/conceder-acesso! repo-identidade ente-id
                                   {:id (random-uuid) :ente-id ente-id :identidade-id identidade-id
                                    :tipo tipo :estado "ativo"}
                                   papeis)
          nome (:nome (repo/nome-por-id repo-identidade identidade-id))]
      (idp/provisionar-realm! idp-comp ente-id)
      (idp/criar-usuario! idp-comp ente-id {:identidade-id identidade-id :nome nome :email email})
      (idp/convidar! idp-comp ente-id identidade-id)
      (http/json-resposta 201 {:vinculo-id (str (:vinculo-id r)) :convite "enviado"}))))

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

(defn rotas
  "Fragmento administrativo. TODAS exigem `admin_ente` (§22.5.1 — 'cadastrada pelo admin do ente')."
  [{:keys [auth repo-identidade idp]}]
  (let [papel (it/exige-papel "admin_ente")]
    #{["/identidade/identidades" :post
       [auth papel it/corpo-json (criar-identidade-handler repo-identidade)]
       :route-name :identidade/criar-identidade]
      ["/identidade/acessos" :post
       [auth papel it/corpo-json (conceder-acesso-handler repo-identidade idp)]
       :route-name :identidade/conceder-acesso]
      ["/identidade/acessos/:identidade-id/convite" :post
       [auth papel (reenviar-convite-handler idp)]
       :route-name :identidade/reenviar-convite]}))
