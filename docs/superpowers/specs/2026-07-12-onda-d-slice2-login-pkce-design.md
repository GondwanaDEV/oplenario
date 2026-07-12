# Onda D · Slice 2 — Login de verdade no frontend (Authorization Code + PKCE, custódia BFF)

**Data:** 2026-07-12
**Fase:** Track FE / Onda D (auth vivo). Consome o adapter Keycloak fechado na Slice 1 (`0fb7bc5`).
**Autoria da decisão:** Daouda Traore (forks de custódia + resolução de realm confirmados nesta sessão).
**Modelo/effort desta fase:** design = Opus high (feito). Implementação = Sonnet medium (mecânico sobre seams cravados);
**revisão adversarial Opus** obrigatória nas tasks de custódia (mint de sessão, interceptor de cookie, rota de callback).

---

## 1. Objetivo

Substituir o esquema de token de desenvolvimento (`?token=` querystring / `NEXT_PUBLIC_DEV_TOKEN`, que carrega
claims JSON não-assinadas confiadas pelo `idp-dev`) por um **login OAuth2 Authorization Code + PKCE(S256)** real
contra o Keycloak realm-por-tenant, com **custódia de token no padrão BFF de sessão opaca** — espelhando o projeto
irmão **munex**, adaptado à multi-tenancy do O Plenário.

Alvo: **ponta-a-ponta vivo** contra o Keycloak 26 real (mesma régua de honestidade da Slice 1 — realm provisionado,
usuário criado, loop de login completo no browser), não só estrutura com `[GAP]`.

O caminho de dev (`idp-dev` / `?token=`) **permanece** para dev/test, em paralelo, selecionável — espelhando a
decisão `idp-para` fail-safe da Slice 1 (dev/test → stub; qualquer outro env → real).

---

## 2. Decisão central de custódia (confirmada) — sessão opaca, padrão munex

O navegador **nunca** recebe um token do Keycloak. Fluxo:

1. O servidor Next.js (Route Handlers, o BFF) roda **toda** a dança PKCE server-side.
2. O `code`↔`token` é trocado server-to-server; o access token resultante é entregue ao backend Clojure.
3. O backend **re-verifica** esse token (`verificar-token` da Slice 1 — a âncora de confiança), deriva
   `identidade-id` + `ente-id` (o `ente-id` **do issuer verificado, nunca de claim** — invariante da Slice 1),
   confirma **vínculo ativo** (`resolver-sessao`) e cria uma **linha de sessão opaca**.
4. O único artefato no navegador é um cookie `httpOnly; Secure; SameSite=Lax` com um **segredo opaco de alta
   entropia** (não um token). JavaScript/XSS nunca lê um token.

**Garantias que isto compra (a razão da escolha):**
- **XSS não exfiltra token** — o navegador não tem token.
- **Cookie roubado sem o banco é inútil** — a tabela guarda o **SHA-256** do segredo opaco (endurecimento sobre
  munex, que guardava o UUID direto); um vazamento de leitura do banco não entrega sessões vivas.
- **Revogação imediata** — a autoridade de revogação do O Plenário é o **vínculo** (`resolver-sessao` falha fechado
  se o vínculo foi suspenso/encerrado, a cada request); deletar a linha de sessão também mata na hora.

### 2.1 Divergência deliberada de munex — zero tokens de IdP em repouso

munex guarda o `refresh_token` do Keycloak em `core_sessoes` e faz refresh silencioso server-side, **porque o
backend munex usa o access token do KC downstream**. No O Plenário **o `ator` é derivado do próprio banco a cada
request** (relações F1.2: mandato/papel/vínculo) — o access token do Keycloak **não participa da authz por-request**.
Logo a tabela de sessão guarda **nenhum segredo do IdP**: só `sessao_hash`, `identidade_id`, `ente_id`, e prazos.

Consequências:
- **A favor (recomendado):** menor superfície de segredo em repouso; não invoca a track de cripto-em-repouso (o carry
  do CPF); menos partes móveis; sem round-trip ao KC no caminho quente.
- **Tradeoff honesto:** revogação **no nível do IdP** (admin desabilita o usuário no Keycloak, ou logout global) só é
  refletida no fim do TTL da sessão, não no meio. Mitigado por (a) o **vínculo** ser a revogação autoritativa e
  imediata no O Plenário, e (b) teto absoluto + ocioso curtos (§4.3).

> **✅ DECIDIDO (Daouda, 12/07):** forma minimalista — **zero tokens de IdP em repouso**. Paridade estrita com munex
> (refresh token em repouso + refresh silencioso) fica descartada nesta fatia (§8); revogação IdP-no-meio-da-sessão
> é aceita como lag até o TTL, com o **vínculo** como revogação imediata autoritativa.

### 2.2 Logout sem segredo em repouso

Logout: (1) `DELETE` da linha de sessão no backend (cookie encaminhado), (2) limpa o cookie, (3) redireciona o
navegador para o `end-session` do Keycloak com `client_id` + `post_logout_redirect_uri` — o KC encerra a sessão dele
pelo **próprio cookie de sessão do KC no navegador**, sem precisar de `id_token_hint` guardado. Por isso não
guardamos nem o `id_token`.

---

## 3. Resolução de tenant → realm (o delta multi-tenant sobre munex)

munex é um-deploy-isolado-por-município (realm fixo no env). O Plenário é multi-tenant compartilhado com realms
`ente-<uuid>`, então precisa dizer **qual câmara** antes de redirecionar ao Keycloak.

**Decisão confirmada: por path/slug `/entrar/[ente]`.** Como não existe slug humano hoje (`cadastros.ente` só tem
`ente_id` UUID + `nome_oficial`; slug é refino futuro já anotado no código da `transparencia`), `[ente]` é o **UUID
do ente**, coagido fail-closed a 400 se malformado — **exatamente a convenção que `/portal/casa/[ente]` já usa**.

- `/entrar/[ente]` = página real de login (mostra o nome da câmara resolvido; botão "Entrar").
- `/entrar` (genérico, sem ente) = placeholder mínimo ("acesse pela URL da sua câmara"); em dev, atalho de dev.
  É para onde o `middleware` manda um acesso protegido sem sessão.

**`[GAP]` de entrada a frio documentado:** um usuário que chega a `/proposicoes` **sem** sessão e sem saber o UUID da
câmara não tem como descobrir o realm nesta fatia. Descoberta humana de câmara (slug / subdomínio / tela de seleção)
é trabalho futuro (§8). O loop provável e provável-ao-vivo desta fatia é: visitar `/entrar/<uuid>` → logar → cair no
app → a sessão persiste entre navegações (o cookie carrega o ente-id no banco).

---

## 4. Backend (Clojure) — o substrato de sessão

### 4.1 Migration `20260620000058-identidade-sessao` (schema `identidade`, SUPRATENANT)

Tabela `identidade.sessao` — **sem RLS**, gated ao role `oplenario_id_resolver` (mesmo padrão de
`identidade.identidade`; a resolução de sessão acontece **antes** de haver contexto de tenant, então não pode ser
tenant-scoped em repouso). Colunas:

| coluna | tipo | nota |
|---|---|---|
| `sessao_hash` | `bytea` PK | SHA-256 do segredo opaco do cookie (nunca o segredo cru) |
| `identidade_id` | `uuid NOT NULL` | ref supratenant a `identidade.identidade(id)` |
| `ente_id` | `uuid NOT NULL` | tenant da sessão (do issuer verificado no mint) |
| `criada_em` | `timestamptz NOT NULL DEFAULT now()` | |
| `expira_em` | `timestamptz NOT NULL` | **teto absoluto** (§4.3) |
| `ocioso_ate` | `timestamptz NOT NULL` | expiração por **ociosidade** deslizante (§4.3) |

Índice para varredura de expiradas. `GRANT`s só ao `oplenario_id_resolver` (SELECT/INSERT/UPDATE/DELETE); o
`oplenario_app` **não** enxerga a tabela (mesma disciplina anti-enumeração que protege o CPF na F1.3). `.down.sql`
correspondente.

### 4.2 `RepoSessao` (adapters/out, role `oplenario_id_resolver`)

- `criar-sessao! [repo {:identidade-id :ente-id :expira-em :ocioso-ate}] → segredo-opaco-cru` — gera o segredo
  (≥256 bits, CSPRNG), INSERT com `sha256(segredo)`, devolve o **cru** (só o BFF o vê, para pôr no cookie).
- `resolver-sessao-por-segredo [repo segredo] → {:identidade-id :ente-id} | nil` — SELECT por `sha256(segredo)`;
  **nil fail-closed** se ausente, ou se `now() > expira_em`, ou se `now() > ocioso_ate`. Em acerto válido, **desliza**
  `ocioso_ate` (bump) na mesma chamada.
- `apagar-sessao! [repo segredo]` — DELETE por hash (idempotente).

### 4.3 Política de prazo

- **Teto absoluto (`expira_em`):** `criada_em + duração-absoluta` (default proposto **8h**; config). Re-login
  obrigatório ao fim, sem exceção.
- **Ocioso (`ocioso_ate`):** `now() + janela-ociosa` (default **30min**; config), deslizado a cada resolução válida.
  Sessão sem atividade por >30min morre antes do teto.

Ambos configuráveis (bloco `:sessao` no `config.edn`, overridável por env). Nenhum valor de prazo do TCE aqui — é
prazo de sessão de login, não obrigação de compliance.

### 4.4 Endpoints novos (supratenant; sem `ator` prévio — eles precedem/estabelecem a sessão)

Todos sob um fragmento de rotas novo `autenticacao/rotas` (ou dentro de `identidade`), splicado no `montar` do
`rotas.clj`, com os interceptors globais (`cabecalhos-seguranca`, `erro`) já aplicados.

1. **`GET /auth/descoberta/:ente`** (público, sem ator) — resolve o UUID do ente; **404 fail-closed** se o ente não
   existe em `cadastros.ente` (impede phishing de realm arbitrário). Devolve `{:ente-id :realm :base-url :client-id
   "oplenario-web"}` — o BFF monta a URL de `authorize` (issuer público) e de `token` (issuer interno; em
   dev/single-KC público==interno). Reusa a coerção UUID fail-closed da `transparencia`.
2. **`POST /auth/sessoes`** (público, sem ator — este ato **cria** a sessão) — corpo `{:token <access-token-do-KC>}`.
   Passos: `verificar-token` (Slice 1) → `{:identidade-id :ente-id}` (ente-id do issuer verificado) →
   `resolver-sessao` (confirma **vínculo ativo**, fail-closed 401 se não há) → `criar-sessao!` →
   devolve `{:sessao <segredo-opaco-cru>}`. **Não é rota aberta:** exige um access token válido do KC. Reusa a
   âncora de verificação da Slice 1 — nenhum caminho alternativo alcança `ente-id`/`identidade-id`.
3. **`DELETE /auth/sessoes`** (autenticado por cookie) — apaga a sessão da requisição; 204. Idempotente.

### 4.5 Interceptor de cookie (`interceptors.clj`)

Novo `autenticacao-cookie [repo-sessao repo-identidade]`, espelho de `it/autenticacao` (bearer), mas:
- extrai o segredo do cookie `sessao` (em vez de `Authorization: Bearer`);
- `resolver-sessao-por-segredo` → `{:identidade-id :ente-id}` (ou 401 fail-closed);
- **reusa `auten/resolver-sessao` sem modificação** → `ator` no `[:request :ator]`;
- mesma disciplina de negação (401 + termina).

Construído uma vez no `let` do `montar` (ao lado de `auth`). **Seleção de caminho:** um interceptor de auth único
que aceita **cookie OU bearer** (bearer para serviço/test/dev-token; cookie para o usuário interativo). Ordem:
tenta cookie de sessão; senão, cai no bearer; senão 401. Isto mantém o `idp-dev`/`?token=` dev funcionando
(bearer) e o path real (cookie) lado a lado, sem duas árvores de rota.

### 4.6 Provisionamento do client público `oplenario-web`

Estender `provisionar-realm-impl` (`keycloak_idp.clj`) para, **idempotentemente** (GET `?clientId=oplenario-web` →
cria se ausente, mesmo padrão do client de audiência atual), criar:

```
POST /admin/realms/<realm>/clients
{:clientId "oplenario-web" :publicClient true :standardFlowEnabled true
 :directAccessGrantsEnabled false
 :redirectUris [<callback-uri(s)>] :webOrigins [<origin>]
 :attributes {"pkce.code.challenge.method" "S256"}}
```

Reusa `admin-req!`/`admin-token!`. `redirectUris`/`webOrigins` vêm de config (a origem pública do FE). PKCE S256
**obrigatório** no client (o atributo acima força).

### 4.7 Config nova (`config.edn`, bloco `:keycloak`/`:sessao`)

- `oplenario-web` client-id (default `"oplenario-web"`), `redirect-uris`, `web-origins` (origem pública do FE).
- `issuer-interno` opcional (split público/interno do munex — em dev igual ao público).
- `:sessao` `{:absoluta-h 8 :ociosa-min 30 :cookie-nome "sessao"}`.

---

## 5. Frontend (Next.js 16 App Router) — o BFF

### 5.1 Route Handlers (o BFF, tudo server-side)

- **`GET /api/auth/login`** (`?ente=<uuid>&redirect=<path>`): chama `/api/auth/descoberta` (proxy → backend
  discovery); gera `code_verifier`/`state` (Node CSPRNG); grava-os em cookie **httpOnly `pkce`** (curto, ~300s,
  scoped a `/api/auth`); redireciona para o `authorize` do KC (issuer público). `redirect` re-validado same-origin
  (defesa anti open-redirect).
- **`GET /api/auth/callback`**: valida `state` **antes** de usar `code` (CSRF); troca `code`→token server-to-server
  (issuer interno, com `code_verifier` do cookie `pkce`); `POST /auth/sessoes` no backend com o access token;
  recebe o segredo opaco; grava cookie **httpOnly `sessao`** (`Secure; SameSite=Lax`); limpa o cookie `pkce`;
  redireciona para o `redirect` validado (ou home interna).
- **`POST /api/auth/logout`**: `DELETE /auth/sessoes` (encaminha o cookie — Node fetch não repassa cookie sozinho,
  como munex); limpa o cookie `sessao`; redireciona ao `end-session` do KC (`client_id` + `post_logout_redirect_uri`).

Guardas de segurança portadas de munex: `resolveAppOrigin` (origem pública real atrás de proxy, para o `redirect_uri`
bater exato no KC), re-validação same-origin do redirect path em login e callback.

### 5.2 Páginas

- **`/entrar/[ente]/page.tsx`** — resolve/mostra o nome da câmara (via descoberta), botão "Entrar" → `/api/auth/login`.
- **`/entrar/page.tsx`** — placeholder genérico (§3); em dev, atalho de dev-token.

### 5.3 `middleware.ts` (Edge)

Gate de presença do cookie `sessao` nas rotas protegidas: grupos `(interno)`, `(vereador)`, e a página avulsa
`/sessoes/[id]/plenario`. Sem cookie → redireciona a `/entrar` (preservando `redirect`). Em **dev**, permite o
bypass `?token=` (para não quebrar o fluxo de dev). `(publico)` nunca é gated.

### 5.4 Cutover do anexo de token — boundary `apiFetch`

Hoje ~10 hooks recebem `token` e setam `Authorization` manualmente (não há wrapper central — gap já anotado). Introduz
um único `apiFetch` (boundary de auth):
- **modo real:** same-origin, o cookie `sessao` viaja automático (`credentials` default same-origin) — **sem header
  Authorization**; o proxy `/api/*` encaminha o cookie ao backend.
- **modo dev:** `?token=`/`NEXT_PUBLIC_DEV_TOKEN` → adiciona `Authorization: Bearer` (path `idp-dev`).

Os ~10 hooks passam a rotear por `apiFetch`. `comToken`/propagação de `?token=` na navegação continua **só no modo
dev**; no modo real a navegação é limpa (cookie carrega a sessão).

### 5.5 SSE

`sse-proxy` (`app/api/sessoes/[id]/plenario/route.ts`) passa a **encaminhar o header `cookie`** ao backend (hoje só
encaminha `Authorization` + `Last-Event-ID`). `consumirSse` no modo real depende do cookie (same-origin); modo dev
mantém o header. O interceptor de cookie (§4.5) autentica o SSE.

### 5.6 `AuthProvider` / guardas de UI

`papeisDoToken` (hoje `JSON.parse` do dev-token) vira **não-aplicável no modo real** (o navegador não tem token). As
guardas de UI (`GuardVereador`) continuam **não-autoritativas** (a authz real é sempre server-side). No modo real, os
papéis para UI vêm de um endpoint leve já existente (`GET /eu`, autenticado por cookie) — não de decode de token.
Guardas permanecem dicas de UX; o 401/403 do backend é o portão real.

---

## 6. Escopo desta fatia (ordem interna sugerida ao plano)

Fatia **grande** (é login: não dá para meio-entregar). Ordenação para o plano (writing-plans):

1. **Substrato backend:** migration `...058` → `RepoSessao` → endpoints discovery/mint/logout → interceptor de
   cookie → wire no `rotas.clj` → estender `provisionar-realm!` (client `oplenario-web`) → config. TDD por task;
   **revisão adversarial Opus** no mint e no interceptor.
2. **BFF FE:** 3 Route Handlers (login/callback/logout) → `middleware.ts` → páginas `/entrar`. **Revisão Opus** no callback.
3. **Cutover:** `apiFetch` boundary → migrar ~10 hooks → SSE cookie → guardas de UI via `/eu`.
4. **Prova ao vivo:** provisionar realm + client `oplenario-web` contra o Keycloak 26 real; rodar o loop completo no
   browser (2 temas), confirmar sessão persistente, logout, e o path dev-token ainda funcionando.

> **✅ DECIDIDO (Daouda, 12/07):** **fatia única e coesa** (itens 1–4 numa branch). O substrato sozinho não tem prova
> de valor demonstrável até o FE consumi-lo; a prova ao vivo (item 4) fecha a fatia.

---

## 7. Testes (TDD-first, régua da Slice 1)

- **Backend:** `RepoSessao` (criar/resolver/expirar-por-teto/expirar-por-ocioso/apagar; hash nunca guarda o cru);
  mint (`verificar-token` inválido → 401; sem vínculo → 401; ente do issuer, nunca de claim; happy path cria sessão);
  interceptor de cookie (cookie válido → ator; ausente/expirado → 401; **cookie e bearer coexistindo**); discovery
  (ente inexistente → 404). **Ponta-a-ponta** contra Keycloak 26 real (gated): realm+client provisionados → authorize
  → callback → mint → resolução por cookie → `ator` real.
- **Frontend:** Route Handlers (state inválido → rejeita antes do code; redirect não-same-origin → rejeitado; callback
  seta cookie httpOnly; logout limpa + redireciona); `apiFetch` (modo real sem header; modo dev com header);
  `middleware` (sem cookie → /entrar; com cookie → passa; dev `?token=` bypass). E2E no browser (2 temas).

---

## 8. Fora de escopo (explícito, não relitigar)

- **Descoberta humana de câmara** (slug / subdomínio / tela de seleção) — `/entrar/[ente]` usa UUID; slug é refino
  futuro. O `[GAP]` de entrada a frio (§3) fica documentado.
- **Refresh silencioso contra o KC / refresh token em repouso** — deliberadamente fora (§2.1), salvo overrule na revisão.
- **passkey/WebAuthn, broker gov.br, IdP do `admin_sistema`, ICP-Brasil real** — subsistemas ortogonais, fatias
  próprias (decidido na Slice 1).
- **CSRF token dedicado** — V1 confia em `SameSite=Lax` + same-origin (como munex); token de CSRF é endurecimento futuro.
- **Cache/limite do JwkProvider** e **limpeza de realms de teste** — carries (c)/(d) da Slice 1, não reabertos aqui.

---

## 9. Invariantes honrados

- **`ente-id` do issuer verificado, nunca de claim** (Slice 1) — preservado no mint.
- **Vínculo ativo = sessão** (§22.5 eixo G) — `resolver-sessao` reusado sem modificação; revogação imediata.
- **Split de privilégio supratenant** (F1.3) — tabela de sessão gated a `oplenario_id_resolver`, `oplenario_app` cego.
- **Fail-closed em toda borda** — malformado/ausente/expirado/sem-vínculo → 401/400/404, nunca parcial.
- **Dev-token não alcança prod** — guardas `next.config.ts`/`AuthProvider` mantidos; path real é o default fora de dev.
