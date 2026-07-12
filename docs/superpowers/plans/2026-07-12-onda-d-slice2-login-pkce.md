# Onda D · Slice 2 — Login PKCE (custódia BFF de sessão opaca) · Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Substituir o dev-token (`?token=`/`NEXT_PUBLIC_DEV_TOKEN`) por login OAuth2 Authorization Code + PKCE(S256) real contra o Keycloak realm-por-tenant, com custódia de sessão opaca no padrão BFF (o navegador só guarda um cookie opaco httpOnly).

**Architecture:** BFF em Next.js Route Handlers roda a dança PKCE server-side; o backend Clojure re-verifica o access token do KC (`verificar-token`, Slice 1) como âncora de confiança e cria uma sessão opaca supratenant (`identidade.sessao`, SHA-256 do segredo no banco, zero tokens de IdP em repouso); o `ator` é derivado do banco a cada request via `resolver-sessao` reusado; um interceptor de cookie coexiste com o bearer (dev-token). `/entrar/[ente]` (UUID) resolve o realm.

**Tech Stack:** Clojure/Pedestal/Malli/next.jdbc/Migratus (backend); Next.js 16 App Router/TS (frontend); Keycloak 26 (IdP); Postgres.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-07-12-onda-d-slice2-login-pkce-design.md` (autoridade; em conflito, a spec prevalece).
- **`ente-id` SEMPRE do issuer verificado, nunca de claim** (invariante Slice 1). Nenhum caminho alternativo alcança `ente-id`.
- **Fail-closed em toda borda:** malformado/ausente/expirado/sem-vínculo → 401/400/404, nunca claims/sessão parcial.
- **Tabela de sessão SUPRATENANT** (schema `identidade`, sem RLS) gated ao role `oplenario_id_resolver`; `oplenario_app` cego a ela (disciplina anti-enumeração F1.3).
- **Zero tokens de IdP em repouso** (decisão Daouda 12/07): a linha de sessão guarda só `sessao_hash`+`identidade_id`+`ente_id`+prazos.
- **Cookie `sessao`:** `httpOnly; Secure; SameSite=Lax`. O banco guarda `sha256(segredo)`, nunca o segredo cru.
- **Dev-token permanece** para dev/test em paralelo (interceptor aceita cookie OU bearer); guardas anti-prod do `next.config.ts`/`AuthProvider` mantidos.
- **TDD-first** por task; **revisão adversarial Opus** obrigatória nas tasks marcadas `[REVISÃO OPUS]` (T4 mint, T6 interceptor, T10 callback).
- **Migração corre como role DONO `oplenario`** (fixtures chamam `migrar!`); pool assume `oplenario_pool` via `SET ROLE` dentro de `com-tenant*`. MinIO nos testes: `MINIO_ENDPOINT=http://localhost:9100 MINIO_ACCESS_KEY=oplenario MINIO_SECRET_KEY=dev12345`.
- **Rodar testes do backend:** container efêmero de Clojure na mesma rede docker (nunca `clojure` direto no host) — ver `oplenario-c3-execucao` / `oplenario-rodar-local`. Frontend dockerizado (mandato Docker).
- **Silhueta ADR-0001:** `wire/in`·`wire/out`, sem `port/`, recursos via Component, sem ORM. Import-lint §22.10 (sem cross-schema JOIN, sem import cross-módulo indevido).

---

## FASE 1 — Substrato backend de sessão

### Task 1: Migration `identidade.sessao` (supratenant)

**Files:**
- Create: `apps/backend/resources/migrations/20260620000058-identidade-sessao.up.sql`
- Create: `apps/backend/resources/migrations/20260620000058-identidade-sessao.down.sql`
- Reference (padrão a espelhar): `apps/backend/resources/migrations/20260620000011-identidade.up.sql:1-37` (grants a `oplenario_id_resolver`, tabela supratenant sem RLS)

**Interfaces:**
- Produces: tabela `identidade.sessao(sessao_hash bytea PK, identidade_id uuid, ente_id uuid, criada_em timestamptz, expira_em timestamptz, ocioso_ate timestamptz)`; GRANTs SELECT/INSERT/UPDATE/DELETE só a `oplenario_id_resolver`.

- [ ] **Step 1: Escrever a migration up**

```sql
-- Onda D Slice 2: sessao opaca de LOGIN (custodia BFF). SUPRATENANT (sem RLS) como identidade/identidade_externa:
-- a resolucao de sessao precede o contexto de tenant. Zero tokens de IdP em repouso: so o hash do segredo do
-- cookie + identidade + ente + prazos. Gated ao oplenario_id_resolver (anti-enumeracao, disc.1 F1.3).
CREATE TABLE IF NOT EXISTS identidade.sessao (
  sessao_hash   bytea PRIMARY KEY,            -- sha256(segredo-opaco-do-cookie); nunca o segredo cru
  identidade_id uuid NOT NULL,                -- ref supratenant a identidade.identidade(id)
  ente_id       uuid NOT NULL,                -- tenant da sessao (do issuer verificado no mint)
  criada_em     timestamptz NOT NULL DEFAULT now(),
  expira_em     timestamptz NOT NULL,         -- teto ABSOLUTO
  ocioso_ate    timestamptz NOT NULL          -- expiracao por OCIOSIDADE (deslizante)
);
CREATE INDEX IF NOT EXISTS idx_sessao_expira ON identidade.sessao (expira_em);

-- oplenario_app (role efetivo dentro de com-tenant*) NAO enxerga sessao (mesma disciplina do CPF).
GRANT SELECT, INSERT, UPDATE, DELETE ON identidade.sessao TO oplenario_id_resolver;
```

- [ ] **Step 2: Escrever a migration down**

```sql
DROP TABLE IF EXISTS identidade.sessao;
```

- [ ] **Step 3: Rodar a migration e verificar que aplica** — via o fluxo de teste do projeto (fixture `migrar!` como role `oplenario`). Esperado: migration `20260620000058` aplicada, tabela existe, sem erro de grant.

- [ ] **Step 4: Commit**

```bash
git add apps/backend/resources/migrations/20260620000058-identidade-sessao.*.sql
git commit -m "feat(be): migration identidade.sessao — sessao opaca supratenant (Onda D S2)"
```

---

### Task 2: `RepoSessao` — criar/resolver/apagar sessão

**Files:**
- Create: `apps/backend/src/oplenario/identidade/db/sessao.clj` (SQL puro, role `oplenario_id_resolver`)
- Create: `apps/backend/src/oplenario/identidade/adapters/out/sessao.clj` (Repo protocol + impl) — OU estender o RepoIdentidade existente se o padrão do módulo for repo único; seguir o que `apps/backend/src/oplenario/identidade/adapters/out/` já faz.
- Test: `apps/backend/test/oplenario/identidade/sessao_test.clj`
- Reference: como o `identidade` usa o role `oplenario_id_resolver` para dados supratenant — ver `apps/backend/src/oplenario/identidade/db/` (as fns que tocam `identidade.identidade`), espelhar a aquisição de conexão com esse role.

**Interfaces:**
- Consumes: datasource/Component do módulo identidade; `kernel/tempo` para `agora`.
- Produces:
  - `(criar-sessao! repo {:identidade-id uuid :ente-id uuid :expira-em Instant :ocioso-ate Instant}) → segredo-cru:String` — gera segredo CSPRNG ≥256 bits (base64url), INSERT com `sha256(segredo)`, devolve o **cru**.
  - `(resolver-sessao-por-segredo repo segredo:String) → {:identidade-id uuid :ente-id uuid} | nil` — SELECT por `sha256(segredo)`; nil se ausente/`now()>expira_em`/`now()>ocioso_ate`; em acerto válido, UPDATE desliza `ocioso_ate = now()+janela` na mesma chamada.
  - `(apagar-sessao! repo segredo:String) → nil` — DELETE por hash, idempotente.
  - helper `sha256-bytes [s:String] → bytes` (privado; determinístico).

- [ ] **Step 1: Escrever os testes falhando**

```clojure
;; sessao_test.clj — usa fixture de banco do projeto (migrar! + role id_resolver)
(deftest criar-e-resolver
  (let [seg (criar-sessao! repo {:identidade-id id :ente-id ente
                                 :expira-em (.plusSeconds (agora) 3600)
                                 :ocioso-ate (.plusSeconds (agora) 1800)})]
    (is (string? seg))
    (is (= {:identidade-id id :ente-id ente} (resolver-sessao-por-segredo repo seg)))))

(deftest hash-nao-guarda-o-cru
  (let [seg (criar-sessao! repo {...})]
    ;; ler a coluna sessao_hash crua e conferir que != seg e == sha256(seg)
    (is (not (contains-cru? repo seg)))))

(deftest expira-por-teto-absoluto
  (let [seg (criar-sessao! repo {:expira-em (.minusSeconds (agora) 1) ...})]
    (is (nil? (resolver-sessao-por-segredo repo seg)))))

(deftest expira-por-ocioso
  (let [seg (criar-sessao! repo {:expira-em (.plusSeconds (agora) 3600)
                                 :ocioso-ate (.minusSeconds (agora) 1)})]
    (is (nil? (resolver-sessao-por-segredo repo seg)))))

(deftest resolver-desliza-ocioso
  ;; criar com ocioso curto-mas-futuro; resolver; conferir que ocioso_ate avancou
  ...)

(deftest apagar-idempotente
  (let [seg (criar-sessao! repo {...})]
    (apagar-sessao! repo seg) (apagar-sessao! repo seg)
    (is (nil? (resolver-sessao-por-segredo repo seg)))))

(deftest segredo-desconhecido-nil
  (is (nil? (resolver-sessao-por-segredo repo "nao-existe"))))
```

- [ ] **Step 2: Rodar os testes — esperar FAIL** (símbolos indefinidos).
- [ ] **Step 3: Implementar `db/sessao.clj` + Repo.** Segredo: `SecureRandom` 32 bytes → base64url. `sha256-bytes` via `java.security.MessageDigest`. Aquisição de conexão com role `oplenario_id_resolver` (espelhar o padrão do CPF em `identidade/db/`). `resolver` faz SELECT+UPDATE de `ocioso_ate` numa tx (prazos comparados no SQL com `now()`, não em Clojure, para consistência com o relógio do banco). Janela de ociosidade lida de config (passar como arg ou via Repo state).
- [ ] **Step 4: Rodar os testes — esperar PASS.**
- [ ] **Step 5: Commit** `feat(be): RepoSessao — criar/resolver/apagar sessao opaca (hash-nao-cru, prazos)`.

---

### Task 3: Endpoint `GET /auth/descoberta/:ente`

**Files:**
- Create: `apps/backend/src/oplenario/identidade/diplomat/http/auth_in.clj` (ou fragmento `autenticacao/rotas` — seguir onde as rotas de auth vão viver; ver `rotas.clj:43,97-121` para o padrão de fragmento).
- Modify: `apps/backend/src/oplenario/rotas.clj` (splicar o fragmento novo no `montar`).
- Modify: `apps/backend/resources/config.edn` (`:keycloak` ganha `:web-client-id "oplenario-web"`, `:redirect-uris`, `:web-origins`, `:base-url-publico`).
- Test: `apps/backend/test/oplenario/identidade/auth_http_test.clj`
- Reference: coerção UUID fail-closed — `apps/backend/src/oplenario/transparencia/adapters/in/portal.clj:19-24` (`ente-param->uuid`).

**Interfaces:**
- Consumes: `cadastros` (existência do ente) via seam injetado pelo host (NUNCA import direto — §22.5.3; injetar `ente-existe?` como fn, espelhar `resolver-vereador`/`resolver-ente-publico` já injetados no `rotas.clj`).
- Produces: `GET /auth/descoberta/:ente` → 200 `{:ente-id uuid :realm "ente-<uuid>" :base-url <publico> :client-id "oplenario-web"}`; 400 se UUID malformado; **404 se ente não existe**.

- [ ] **Step 1: Testes falhando** — happy (ente existe → 200 com realm `ente-<uuid>` e client-id); malformado → 400; ente inexistente → 404 (seam `ente-existe?` retorna false).
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implementar** — coagir `:ente` a UUID (fail-closed 400); `ente-existe?` (seam) → 404 se não; montar realm `(str realm-prefixo ente-id)`; devolver base-url público + client-id da config. Rota pública (sem `auth`).
- [ ] **Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(be): GET /auth/descoberta/:ente — resolve realm/client-id do tenant (fail-closed 404)`.

---

### Task 4: Endpoint `POST /auth/sessoes` (mint) — `[REVISÃO OPUS]`

**Files:**
- Modify: `apps/backend/src/oplenario/identidade/diplomat/http/auth_in.clj` (adiciona o handler de mint)
- Modify: `apps/backend/src/oplenario/rotas.clj` (rota + deps: `idp`, `repo-identidade`, `repo-sessao`)
- Test: `apps/backend/test/oplenario/identidade/auth_http_test.clj`
- Reference: cadeia de confiança — `apps/backend/src/oplenario/interceptors.clj:25-37` (`autenticacao`), `apps/backend/src/oplenario/identidade/autenticacao.clj:16-29` (`resolver-sessao`).

**Interfaces:**
- Consumes: `verificar-token` (idp), `resolver-sessao` (repo-identidade), `criar-sessao!` (repo-sessao), config `:sessao {:absoluta-h :ociosa-min}`.
- Produces: `POST /auth/sessoes` body `{:token <access-token-KC>}` → 200 `{:sessao <segredo-cru>}` (o BFF põe no cookie); 401 se token inválido OU sem vínculo ativo.

**Ordem de segurança (INEGOCIÁVEL — espelha o interceptor bearer):**
```clojure
(if-let [claims (idp/verificar-token idp (:token body))]        ; nil => 401 "token invalido"; INFRA lanca => 500
  (if-let [ator (auten/resolver-sessao repo-identidade claims)]  ; nil => 401 "sem vinculo ativo"
    (let [seg (criar-sessao! repo-sessao
                {:identidade-id (:identidade-id ator)            ; do claims VERIFICADO
                 :ente-id       (:ente-id ator)                  ; do issuer VERIFICADO, nunca do corpo HTTP
                 :expira-em     (.plus (agora) absoluta)
                 :ocioso-ate    (.plus (agora) ociosa)})]
      (ok {:sessao seg}))
    (nega 401 "sem vinculo ativo"))
  (nega 401 "token invalido"))
```
`ente-id`/`identidade-id` vêm SÓ do `ator` (derivado do claims verificado); **nenhum campo do corpo HTTP** alcança-os.

- [ ] **Step 1: Testes falhando:**
  - `token-invalido-401` (idp stub → nil).
  - `sem-vinculo-401` (verificar ok, resolver-sessao → nil).
  - `infra-propaga-500` (idp lança → 500, não 401).
  - `happy-cria-sessao` (verificar ok + vínculo ativo → 200, cookie-secret resolve para o mesmo `{:identidade-id :ente-id}`).
  - `ente-do-issuer-nao-do-corpo` (corpo tenta injetar `:ente-id` falso → ignorado; a sessão criada tem o ente do claims verificado).
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implementar** exatamente na ordem acima. Rota pública (sem `auth` — este ato CRIA a sessão). Malli no corpo (`{:token :string}`), limite de tamanho.
- [ ] **Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(be): POST /auth/sessoes — mint de sessao ancorado em verificar-token (ente do issuer)`.
- [ ] **Step 6: `[REVISÃO OPUS]`** — dispatch revisor adversarial: "tente forjar ente-id/identidade-id pelo corpo; confirme ordem verificar→vinculo→criar; confirme infra-propaga-500; confirme que nenhum caminho alternativo alcança ente-id". Incorporar achados + regressão antes de seguir.

---

### Task 5: Endpoint `DELETE /auth/sessoes` (logout backend)

**Files:**
- Modify: `apps/backend/src/oplenario/identidade/diplomat/http/auth_in.clj`
- Modify: `apps/backend/src/oplenario/rotas.clj` (rota atrás do interceptor de cookie da T6)
- Test: `auth_http_test.clj`

**Interfaces:**
- Consumes: `apagar-sessao!` (repo-sessao); o segredo vem do cookie (interceptor de cookie o expõe, ou lê-se o cookie no handler).
- Produces: `DELETE /auth/sessoes` (autenticado por cookie) → 204; idempotente.

- [ ] **Step 1: Teste falhando** — sessão existente → 204 + `resolver-sessao-por-segredo` depois → nil; sem cookie → 401 (via interceptor).
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implementar** — ler segredo do cookie, `apagar-sessao!`, 204.
- [ ] **Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(be): DELETE /auth/sessoes — encerra sessao (idempotente)`.

---

### Task 6: Interceptor de cookie (cookie OU bearer) + wiring — `[REVISÃO OPUS]`

**Files:**
- Modify: `apps/backend/src/oplenario/interceptors.clj` (novo `autenticacao-cookie-ou-bearer` OU estender `autenticacao`)
- Modify: `apps/backend/src/oplenario/rotas.clj:43` (construir no `let` do `montar`, threa­dar como `:auth`)
- Test: `apps/backend/test/oplenario/interceptors_test.clj`
- Reference: `apps/backend/src/oplenario/interceptors.clj:18-37` (`bearer`, `autenticacao`).

**Interfaces:**
- Consumes: `repo-sessao` (resolver-sessao-por-segredo), `idp`+`repo-identidade` (path bearer), `bearer` helper, novo `cookie-sessao` helper (extrai o cookie `sessao`).
- Produces: interceptor que põe `ator` em `[:request :ator]`. **Precedência:** cookie de sessão primeiro; senão bearer (dev-token/serviço); senão 401. Mantém o dev-token vivo.

```clojure
(defn autenticacao
  [idp repo-identidade repo-sessao]
  {:name ::autenticacao
   :enter (fn [ctx]
            (if-let [seg (cookie-sessao (:request ctx))]
              (if-let [claims (sessao/resolver-sessao-por-segredo repo-sessao seg)]  ; {:identidade-id :ente-id}
                (if-let [ator (auten/resolver-sessao repo-identidade claims)]
                  (assoc-in ctx [:request :ator] ator)
                  (nega! ctx 401 "sem vinculo ativo"))
                (nega! ctx 401 "sessao invalida"))
              (if-let [tok (bearer (:request ctx))]                                   ; fallback dev-token/servico
                (if-let [claims (idp/verificar-token idp tok)]
                  (if-let [ator (auten/resolver-sessao repo-identidade claims)]
                    (assoc-in ctx [:request :ator] ator)
                    (nega! ctx 401 "sem vinculo ativo"))
                  (nega! ctx 401 "token invalido"))
                (nega! ctx 401 "sem credencial"))))})
```
Nota: a sessão de cookie roda `resolver-sessao` de novo (authz viva) — vínculo revogado mata na hora.

- [ ] **Step 1: Testes falhando:** cookie válido → ator; cookie inválido/expirado → 401; **sem cookie mas bearer válido → ator (dev-token vive)**; sem nada → 401; cookie válido mas vínculo revogado → 401.
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implementar** `cookie-sessao` (lê `Cookie` header, extrai `sessao=`) + o interceptor acima; atualizar `montar` para passar `repo-sessao`.
- [ ] **Step 4: PASS** + rodar a suíte inteira (nenhuma rota autenticada existente quebra — o bearer segue funcionando).
- [ ] **Step 5: Commit** `feat(be): interceptor de auth aceita cookie de sessao OU bearer (dev-token coexiste)`.
- [ ] **Step 6: `[REVISÃO OPUS]`** — "confirme precedência cookie→bearer sem bypass; confirme que vínculo revogado derruba sessão de cookie; confirme fail-closed sem credencial; confirme que o SSE e as rotas existentes seguem autenticando".

---

### Task 7: Provisionar client público `oplenario-web` (PKCE S256)

**Files:**
- Modify: `apps/backend/src/oplenario/kernel/components/keycloak_idp.clj:197-228` (`provisionar-realm-impl` — adicionar o client, idempotente)
- Modify: `apps/backend/resources/config.edn` (`:keycloak :web-client-id`, `:redirect-uris`, `:web-origins`)
- Test: `apps/backend/test/.../keycloak_idp_test.clj` (unit do payload; live gated no T18)
- Reference: bloco de criação de client existente em `keycloak_idp.clj:209-227`; helpers `admin-req!` (164-177), `admin-token!` (144-162).

**Interfaces:**
- Produces: após `provisionar-realm!`, o realm tem o client `oplenario-web` (`publicClient true`, `standardFlowEnabled true`, `directAccessGrantsEnabled false`, `redirectUris`, `webOrigins`, `attributes {"pkce.code.challenge.method" "S256"}`), criado idempotentemente.

- [ ] **Step 1: Teste falhando** — unit que captura o payload do POST de client e afirma: clientId `oplenario-web`, `publicClient true`, `pkce.code.challenge.method` `S256`, `redirectUris` não-vazio, `directAccessGrantsEnabled false`. Idempotência: GET `?clientId=oplenario-web` existente → não recria.
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implementar** — espelhar o bloco de client existente; guardar com GET-then-create; ler redirect-uris/web-origins da config.
- [ ] **Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(be): provisionar-realm! cria client publico oplenario-web (PKCE S256)`.

---

## FASE 2 — BFF no frontend

> Todos os Route Handlers são **server-side**. Portar as guardas de munex: `resolveAppOrigin` (origem pública real atrás de proxy p/ o `redirect_uri` bater exato) e re-validação same-origin do redirect path. Ver `/Users/daoudatraore/munex/apps/frontend/src/app/api/auth/*` como referência de forma (NÃO copiar env-specifics; adaptar a este backend).

### Task 8: `GET /api/auth/login`

**Files:**
- Create: `apps/frontend/src/app/api/auth/login/route.ts`
- Create: `apps/frontend/src/app/api/auth/appOrigin.ts` (`resolveAppOrigin`), `apps/frontend/src/app/api/auth/redirect.ts` (`resolveRedirectPath` same-origin)
- Test: `apps/frontend/src/app/api/auth/login/route.test.ts` (ou co-located vitest)

**Interfaces:**
- Produces: `GET /api/auth/login?ente=<uuid>&redirect=<path>` → 302 para o `authorize` do KC; grava cookie httpOnly `pkce` (`{codeVerifier, state, redirectPath}`, ~300s, path `/api/auth`).
- Consumes: `/api/auth/descoberta/:ente` (proxy → backend T3) para realm/base-url/client-id.

- [ ] **Step 1: Testes falhando:** gera `code_challenge` S256 do `code_verifier`; seta cookie `pkce` httpOnly; monta URL `authorize` com `response_type=code`, `client_id=oplenario-web`, `code_challenge_method=S256`, `state`; `redirect` não-same-origin → rejeitado/ignora (usa default).
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implementar** — `crypto.randomBytes` p/ verifier+state; SHA-256+base64url p/ challenge; fetch discovery; `NextResponse.redirect` + `cookies.set('pkce', ..., {httpOnly, secure, sameSite:'lax', maxAge:300, path:'/api/auth'})`.
- [ ] **Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(fe): GET /api/auth/login — inicia PKCE (verifier/state em cookie httpOnly)`.

---

### Task 9: `GET /api/auth/callback` — `[REVISÃO OPUS]`

**Files:**
- Create: `apps/frontend/src/app/api/auth/callback/route.ts`
- Test: `.../callback/route.test.ts`

**Interfaces:**
- Consumes: cookie `pkce`; troca `code`→token no `token` endpoint do KC (issuer interno); `POST /api/auth/sessoes` (proxy → backend mint T4).
- Produces: valida `state` **antes** de usar `code`; grava cookie httpOnly `sessao` (o segredo do backend); limpa `pkce`; 302 para o `redirect` validado.

- [ ] **Step 1: Testes falhando:** `state` mismatch → rejeita ANTES de trocar o code; happy → troca code, chama mint, seta cookie `sessao` httpOnly/secure/sameSite=lax, limpa `pkce`, redireciona ao path validado; redirect não-same-origin → default.
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implementar** — ler `pkce` cookie; comparar `state`; `fetch` token endpoint com `grant_type=authorization_code`+`code_verifier`+`client_id`; pegar `access_token`; `POST /api/auth/sessoes {token}`; `cookies.set('sessao', segredo, {httpOnly, secure, sameSite:'lax', path:'/'})`; `cookies.delete('pkce')`.
- [ ] **Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(fe): GET /api/auth/callback — state-check, code exchange, mint, cookie de sessao`.
- [ ] **Step 6: `[REVISÃO OPUS]`** — "state validado antes do code? open-redirect fechado? o access token nunca chega ao navegador (só o cookie opaco)? pkce cookie limpo? cookie sessao httpOnly+secure?".

---

### Task 10: `POST /api/auth/logout`

**Files:**
- Create: `apps/frontend/src/app/api/auth/logout/route.ts`
- Test: co-located

**Interfaces:**
- Produces: `POST /api/auth/logout` → `DELETE /api/auth/sessoes` (encaminha o cookie — Node fetch não repassa sozinho); limpa cookie `sessao`; 302 para o `end-session` do KC (`client_id`+`post_logout_redirect_uri`).

- [ ] **Step 1: Teste falhando** — chama backend DELETE com o cookie encaminhado; limpa `sessao`; redireciona ao end-session mesmo se o backend falhar (best-effort, cookie limpo sempre).
- [ ] **Step 2: FAIL.** — [ ] **Step 3: Implementar.** — [ ] **Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(fe): POST /api/auth/logout — encerra sessao backend + RP-logout no KC`.

---

### Task 11: `middleware.ts` (gate de rotas protegidas)

**Files:**
- Create: `apps/frontend/src/middleware.ts`
- Test: `apps/frontend/src/middleware.test.ts`

**Interfaces:**
- Produces: gate de presença do cookie `sessao` em `(interno)`, `(vereador)`, `/sessoes/[id]/plenario`. Sem cookie → 302 `/entrar?redirect=<path>`. `(publico)` nunca gated. **Dev:** `?token=` presente → bypass (não redireciona).

- [ ] **Step 1: Testes falhando:** rota protegida sem cookie → redireciona `/entrar`; com cookie → passa; `(publico)` sem cookie → passa; dev `?token=` → passa.
- [ ] **Step 2: FAIL.** — [ ] **Step 3: Implementar** `matcher` + checagem de cookie + exceção dev. — [ ] **Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(fe): middleware gate de sessao (redirect /entrar; dev ?token= bypass)`.

---

### Task 12: Páginas `/entrar/[ente]` e `/entrar`

**Files:**
- Create: `apps/frontend/src/app/(publico)/entrar/[ente]/page.tsx`, `apps/frontend/src/app/(publico)/entrar/page.tsx` (grupo publico = sem auth-guard)
- Test: co-located (view-model se houver lógica)
- Reference: shell público `apps/frontend/src/app/(publico)/layout.tsx`; design-system (`../sistema/`), `GUIDELINES-CHECKLIST.md`.

**Interfaces:**
- Produces: `/entrar/[ente]` mostra o nome da câmara (via descoberta) + botão "Entrar" → `GET /api/auth/login?ente=<uuid>`. `/entrar` = placeholder ("acesse pela URL da sua câmara"); em dev, atalho dev-token.

- [ ] **Step 1** (se houver view-model, teste puro do estado: ente resolvido/erro/carregando). Caso trivial, pular direto ao render.
- [ ] **Step 2–4:** implementar as páginas (dual-theme, AA medida nos 2 temas por `GUIDELINES-CHECKLIST`), `tsc/eslint/build` limpos.
- [ ] **Step 5: Commit** `feat(fe): telas /entrar/[ente] e /entrar (login PKCE)`.

---

## FASE 3 — Cutover do anexo de token

### Task 13: Boundary `apiFetch`

**Files:**
- Create: `apps/frontend/src/lib/api-fetch.ts`
- Test: `apps/frontend/src/lib/api-fetch.test.ts`

**Interfaces:**
- Produces: `apiFetch(path, {token?, ...init}) → Promise<Response>`. **Modo real** (sem token): same-origin, cookie `sessao` viaja automático (`credentials:'same-origin'`), **sem** header Authorization. **Modo dev** (token presente): adiciona `Authorization: Bearer <token>`.

- [ ] **Step 1: Testes falhando:** sem token → nenhum header Authorization; com token → header presente; `credentials` same-origin sempre.
- [ ] **Step 2: FAIL.** — [ ] **Step 3: Implementar.** — [ ] **Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(fe): apiFetch — boundary de auth (cookie no real, bearer no dev)`.

---

### Task 14: Migrar os hooks de dados para `apiFetch`

**Files:**
- Modify: os ~10 hooks em `apps/frontend/src/lib/` e features que setam `Authorization` manualmente (`use-mesa.ts`, `use-plenario.ts`, `use-pauta.ts`, `use-tramitacao-board.ts`, `use-meu-painel.ts`, `use-proposicao-detalhe.ts`, `use-sli-sessoes.ts`, `use-sessao-pauta.ts`, `use-meu-parecer.ts`, `use-meu-emitir-parecer.ts`, `use-confirmar-presenca.ts`, `use-meu-voto.ts`, `use-minha-sessao-atual.ts`, `use-acusar-ciencia.ts` — enumerar via grep `Authorization` no início da task).
- Test: as suítes existentes de cada hook devem seguir verdes (comportamento inalterado no modo dev; cookie no real).

**Interfaces:**
- Consumes: `apiFetch` (T13).

- [ ] **Step 1:** grep `Authorization.*Bearer` em `apps/frontend/src` — listar todos os call-sites.
- [ ] **Step 2:** substituir cada `fetch(...Authorization...)` por `apiFetch(path, {token})` (mantendo `token` opcional p/ dev). Rodar as suítes existentes por hook.
- [ ] **Step 3:** `tsc/eslint/build` limpos; todas as suítes FE verdes.
- [ ] **Step 4: Commit** `refactor(fe): hooks de dados usam apiFetch (cookie de sessao no modo real)`.

---

### Task 15: SSE via cookie

**Files:**
- Modify: `apps/frontend/src/app/api/sessoes/[id]/plenario/route.ts` + `apps/frontend/src/lib/sse-proxy.ts` (`proxiarPlenario` encaminha o header `cookie` ao backend, além de `Authorization`/`Last-Event-ID`)
- Modify: `apps/frontend/src/lib/sse.ts` (`consumirSse`: modo real sem header, cookie same-origin; modo dev mantém header)
- Test: suíte de `sse` existente + novo caso de encaminhamento de cookie.

- [ ] **Step 1: Teste falhando** — `proxiarPlenario` encaminha `cookie`; `consumirSse` no modo real não seta Authorization.
- [ ] **Step 2: FAIL.** — [ ] **Step 3: Implementar.** — [ ] **Step 4: PASS** (o interceptor de cookie da T6 autentica o SSE).
- [ ] **Step 5: Commit** `feat(fe): SSE autentica por cookie de sessao (encaminha cookie no proxy)`.

---

### Task 16: Guardas de UI via `/eu` (papéis no modo real)

**Files:**
- Modify: `apps/frontend/src/lib/auth.tsx` (`papeisDoToken` → no modo real, papéis vêm de `GET /eu`, não de decode; guardas seguem NÃO-autoritativas)
- Create: `apps/frontend/src/lib/use-eu.ts` (hook leve p/ `GET /eu` autenticado por cookie)
- Modify: `GuardVereador` (usa papéis de `/eu` no modo real, do token no dev)
- Test: guarda renderiza/nega conforme papéis; suíte existente verde.
- Reference: `GET /eu` já existe (`rotas.clj` `["/eu" :get [auth http/eu]]`).

- [ ] **Step 1: Testes falhando** — papéis de `/eu` alimentam a guarda; dev-token ainda funciona.
- [ ] **Step 2: FAIL.** — [ ] **Step 3: Implementar.** — [ ] **Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(fe): guardas de UI derivam papeis de /eu no modo real (nao-autoritativas)`.

---

## FASE 4 — Prova ao vivo

### Task 17: Provisionar + provar o loop completo contra Keycloak 26

**Files:**
- (sem código novo; script/seed se necessário em `apps/backend/demo/`)

- [ ] **Step 1:** subir o stack (`cd apps/backend && docker compose --profile auth up -d --build`); porta KC deste dev (8090). Rebuild `app` + `frontend` se necessário (backend NÃO faz hot-reload).
- [ ] **Step 2:** provisionar um realm de ente demo via `provisionar-realm!` (agora cria `oplenario-web`) + `criar-usuario!` + senha (admin-API, só p/ teste minerar sem browser não é o caso aqui — usar o browser de fato).
- [ ] **Step 3:** no browser (claude-in-chrome, 2 temas): visitar `/entrar/<ente-uuid>` → "Entrar" → login no Keycloak → callback → cair no app interno; confirmar que o cookie `sessao` é httpOnly (DevTools) e que **nenhum token do KC** está em storage/JS.
- [ ] **Step 4:** navegar entre páginas internas (sessão persiste sem `?token=`); recarregar (sobrevive); abrir o plenário ao vivo (SSE autentica por cookie).
- [ ] **Step 5:** logout → cookie limpo → rota protegida redireciona a `/entrar`.
- [ ] **Step 6:** confirmar que o **path dev-token** (`?token=`/`NEXT_PUBLIC_DEV_TOKEN`) ainda funciona em dev (não-regressão).
- [ ] **Step 7:** rodar a suíte backend + frontend inteiras (verdes; flake conhecido do `outbox-relay-test` sob contenção de lock é ambiental, não-regressão).
- [ ] **Step 8: Commit** `test(e2e): prova ao vivo do login PKCE (realm+client provisionados, loop completo, dev-token intacto)` (+ qualquer seed/script).

---

## Self-Review (contra a spec)

- **§2 custódia opaca:** T1 (tabela hash-não-cru) + T2 (Repo) + T4 (mint) + T6 (interceptor) + T9 (cookie httpOnly) ✓
- **§2.1 zero tokens IdP em repouso:** T1 (colunas só id/ente/prazos) ✓
- **§2.2 logout:** T5 (backend) + T10 (RP-logout) ✓
- **§3 resolução por `/entrar/[ente]` UUID:** T3 (discovery 404 fail-closed) + T12 (páginas) ✓; `[GAP]` de entrada a frio documentado (§8 spec).
- **§4.1–4.5 substrato:** T1–T6 ✓; **§4.6 client oplenario-web:** T7 ✓; config em T3/T7.
- **§5 BFF:** T8–T12 ✓; **§5.4 apiFetch/cutover:** T13–T14 ✓; **§5.5 SSE:** T15 ✓; **§5.6 guardas /eu:** T16 ✓.
- **§6 ordem interna:** as 4 fases seguem 1(substrato)→2(BFF)→3(cutover)→4(prova).
- **§7 testes:** cada task é TDD-first; ponta-a-ponta em T17.
- **Invariantes §9:** ente-do-issuer (T4), vínculo=sessão (T4/T6), split supratenant (T1), fail-closed (T3/T4/T6), dev-token não-prod (T14/T16 preservam guardas).
- **Placeholders:** os `[REVISÃO OPUS]` são gates de processo, não placeholders de código; os blocos de código security-críticos (T4/T6) estão completos; boilerplate de convenção referencia file:line exato a espelhar (aceitável nesta base densa; os subagentes leem os arquivos reais).

---

## Roteamento de execução (Execução · Modelo · Effort · Motivo)

- **T1, T3, T5, T7, T8, T10, T11, T12, T13, T15, T16:** Agent · **sonnet** · medium — mecânico sobre seam cravado / mirror de arquivo existente.
- **T2 (RepoSessao), T14 (cutover ~10 hooks):** Agent · **sonnet** · medium — cruza arquivos, mas padrão pronto.
- **T4 (mint), T6 (interceptor), T9 (callback):** Agent · **sonnet** · medium impl **+ `[REVISÃO OPUS]`** (revisor adversarial opus, high) — superfície de auth/custódia (invariante §22.1, ordem de segurança).
- **T17 (prova ao vivo):** Direto (Opus) — julgamento no browser + verificação ponta-a-ponta.
- Orquestração + reviews inter-task: Opus (eu).
