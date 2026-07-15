# Onda D Slice 2b — Fast-follow de hardening (login PKCE)

Fecha os dois carries residuais da Slice 2 que têm valor real + a nota de setup dev.
Branch: `onda-d-slice2b-hardening` (off `main` @ `53504a7`).

## Global Constraints (lente de revisão)

- **§22.10 (import discipline):** `identidade` NUNCA importa `cadastros` nem `transparencia`. A
  resolução do nome do ente chega ao `auth-in` por INVERSÃO DE DEPENDÊNCIA (o host `oplenario.rotas/montar`
  injeta o seam `info-ente`), exatamente como `consultar-sessao`/`membros-da-casa`/`painel-compliance`.
- **Fail-closed na borda:** ente inexistente → 404 (nunca vaza realm/URL de tenant que não existe); a
  descoberta NUNCA passa a expor nada sensível (só metadado público de login + nome público da Casa).
- **Defesa em profundidade, nunca autoridade:** o cookie `sessao_kc` é httpOnly+Secure escrito pelo BFF,
  mas seu `baseUrl` NÃO é fonte de autoridade — um valor forjado não pode virar redirect p/ host arbitrário.
- **Convenção de nome do ente:** a superfície pública já expõe `nome-oficial` + `nome-curto` (transparência
  `EnteOut`, `adapters/out/ente.clj`). A descoberta usa OS MESMOS dois campos — NÃO inventar um alias `:nome`.
- **Prod fail-fast espelha o padrão existente** de `next.config.ts` (APP_ORIGIN/KEYCLOAK_INTERNAL_URL): env
  ausente em produção QUEBRA o build/boot, nunca degrada em silêncio.
- **Testes em container (Docker mandatório):** Clojure via container efêmero na rede `oplenario_default`;
  frontend via `docker exec oplenario-frontend-1 node_modules/.bin/vitest` — NUNCA pnpm/npm/node no host.

---

## Task 1 — Backend: descoberta expõe o nome público da Câmara

**Arquivos:** `src/oplenario/identidade/diplomat/http/auth_in.clj`,
`src/oplenario/rotas.clj`, `test/integration/oplenario/identidade/auth_http_test.clj`.

**O quê:** `GET /auth/descoberta/:ente` passa a incluir `:nome-oficial` e `:nome-curto` do ente, para
`/entrar/[ente]` mostrar "Entrar em <Câmara>" em vez do heading neutro.

**Como (substituir o predicado `ente-existe?` pelo seam `info-ente` — existência = `(some? (info-ente id))`):**

1. `auth_in.clj` — `descoberta-handler`: troca o param `ente-existe?` por `info-ente` (fn `ente-id → ente-map | nil`):
   ```clojure
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
              :nome-oficial (:nome-oficial e)
              :nome-curto   (:nome-curto e)})
           (http/json-resposta 404 {:erro "ente nao encontrado"})))))
   ```
2. `auth_in.clj` — `rotas`: troca a chave `:ente-existe?` por `:info-ente` no destructuring e na chamada de
   `descoberta-handler`. Atualiza a docstring da `rotas` (menção a `ente-existe?` → `info-ente`).
3. `rotas.clj` (montar) — a chamada `(auth-http/rotas {:ente-existe? ente-existe? ...})` (linha ~139) passa
   `:info-ente info-ente` (o seam `info-ente` já existe em `montar`, linha ~97). REMOVER a construção agora
   morta de `ente-existe?` (linhas ~99-103) SE ela não tiver outro consumidor — confirmar com grep antes;
   se não houver, apagar o binding + o `:keys` `ente-existe?` do arg de `montar`.
4. `auth_http_test.clj` — onde os testes DB-free montam `auth-http/rotas`, trocar o stub `:ente-existe?`
   (predicado bool) por `:info-ente` retornando um mapa `{:nome-oficial "Câmara X" :nome-curto "Câmara"}`
   (existe) ou `nil` (404). ADICIONAR asserção: a resposta 200 da descoberta contém `:nome-oficial` e
   `:nome-curto`; o caminho 404 (info-ente → nil) continua 404.

**Testes:** rodar o namespace `auth_http_test` (e qualquer teste de `rotas`/sistema que quebre com a mudança
de assinatura de `montar`). Verde antes de reportar.

---

## Task 2 — Frontend: `/entrar` consome o nome real da Câmara

**Arquivos:** `src/lib/entrar-vista.ts`, `src/lib/entrar-vista.test.ts` (se existir; senão o teste que cobre
`derivarVistaEntrada`).

**O quê:** `derivarVistaEntrada` passa a ler os campos reais que o backend (Task 1) agora devolve, preferindo
`nome-curto` (mais curto p/ "Entrar em X"), com fallback p/ `nome-oficial`, mantendo os aliases legados.

**Como:**
1. `RespostaDescoberta`: adicionar `"nome-oficial"?: unknown;` e `"nome-curto"?: unknown;` à interface.
2. Em `derivarVistaEntrada`, trocar:
   ```ts
   const nomeBruto = resultado.corpo.nome ?? resultado.corpo["nome-camara"];
   ```
   por (preferindo curto, depois oficial, depois os aliases forward-compat antigos):
   ```ts
   const nomeBruto =
     resultado.corpo["nome-curto"] ?? resultado.corpo["nome-oficial"] ??
     resultado.corpo.nome ?? resultado.corpo["nome-camara"];
   ```
3. Atualizar o comentário de topo do arquivo: a descoberta AGORA devolve nome (`nome-oficial`/`nome-curto`);
   o caso "sem nome" permanece suportado (heading neutro) mas deixou de ser o caminho esperado.
4. Testes: adicionar caso — corpo 200 com `{"nome-curto":"Câmara de Foo","nome-oficial":"Câmara Municipal de Foo"}`
   → `vista.nome === "Câmara de Foo"`; e um caso só com `nome-oficial` → cai no oficial.

**Testes:** `docker exec oplenario-frontend-1 node_modules/.bin/vitest run src/lib/entrar-vista` (ou o path do
teste). Verde antes de reportar.

---

## Task 3 — Frontend: host-pin do `sessao_kc` no logout + fail-fast + nota de setup dev

**Arquivos:** `src/app/api/auth/kc-cookie.ts`, `src/app/api/auth/logout/route.ts`,
`src/app/api/auth/logout/route.test.ts`, `next.config.ts`, `../backend/docker-compose.yml` (i.e.
`apps/backend/docker-compose.yml`).

**Problema:** `urlLogoutKc` monta a URL de RP-logout do Keycloak a partir de `sessao_kc.baseUrl` (valor de
cookie). Hoje só validamos que é uma URL http(s) — um cookie forjado poderia redirecionar o logout p/ QUALQUER
host https (open-redirect no logout). Como é UM Keycloak (realm-per-tenant), um único host público é legítimo.

**Como (pin do ORIGIN do baseUrl contra um env de confiança):**
1. `kc-cookie.ts` — adicionar helper exportado:
   ```ts
   // Host-pin defensivo (defesa em profundidade): o baseUrl do cookie sessao_kc é browser-facing e vem da
   // descoberta (base-url-publico, confiável), mas NÃO é autoridade. Como é UM Keycloak realm-per-tenant, um
   // único origin público é legítimo — se KEYCLOAK_PUBLIC_URL estiver setado, o origin do baseUrl DEVE bater
   // (senão é cookie forjado -> trata como ausente -> logout local). Sem a env (dev antigo), não pina (mantém
   // o comportamento atual); a env é OBRIGATÓRIA em produção (fail-fast em next.config.ts).
   export function baseUrlPinado(baseUrl: string): boolean {
     const pin = process.env.KEYCLOAK_PUBLIC_URL;
     if (!pin) return true; // sem pin configurado (dev): não restringe
     try {
       return new URL(baseUrl).origin === new URL(pin).origin;
     } catch {
       return false;
     }
   }
   ```
2. `logout/route.ts` — em `lerSessaoKc`, após `validarDescobertaKc` devolver não-nulo, aplicar o pin:
   ```ts
   const payload = validarDescobertaKc(parsed as Record<string, unknown>);
   if (!payload) return null;
   if (!baseUrlPinado(payload.baseUrl)) return null; // origin não bate com o KC oficial -> logout local
   return payload;
   ```
   (importar `baseUrlPinado` de `../kc-cookie`.)
3. `next.config.ts` — adicionar `KEYCLOAK_PUBLIC_URL` ao bloco de fail-fast de produção (mesmo padrão de
   APP_ORIGIN/KEYCLOAK_INTERNAL_URL), com comentário explicando: sem ela o pin do baseUrl de logout ficaria
   inerte em prod (open-redirect no logout via cookie forjado). QUEBRA o build se ausente em produção.
4. `docker-compose.yml` (serviço `frontend`, `apps/backend/docker-compose.yml`): adicionar
   `KEYCLOAK_PUBLIC_URL: "http://keycloak:8080"` (mesmo host público que a descoberta devolve), com comentário
   curto (Onda D Slice 2b: pin do RP-logout).
5. `docker-compose.yml` — ADICIONAR um bloco de comentário curto (perto do serviço `app`/`keycloak`, onde vive
   `APP_ENV`) documentando o **setup dev p/ login PKCE real**, para não redescobrir os 4 gaps do T17:
   ```
   # Login PKCE REAL (perfil `auth`): requer (1) `/etc/hosts` com `127.0.0.1 keycloak` — o browser e os
   #   containers precisam resolver o MESMO host `keycloak` p/ o issuer bater; (2) subir com
   #   `OPLENARIO_APP_ENV=production docker compose --profile auth up -d` — liga o KeycloakIdp real (o
   #   dev-token deixa de valer, é o esperado). No modo dev normal (sem `auth`) o dev-token segue valendo.
   ```
6. Testes (`logout/route.test.ts`): com `vi.stubEnv("KEYCLOAK_PUBLIC_URL", "http://keycloak:8080")`:
   - `sessao_kc` com baseUrl de origin diferente (`https://evil.example`) → logout cai no fallback local
     (`/entrar`), NÃO redireciona pro host forjado, cookies limpos.
   - `sessao_kc` com baseUrl de origin BATENDO → RP-logout normal (mantém o comportamento atual).
   - Sem a env stubada (limpar com `vi.unstubAllEnvs()` no afterEach) → comportamento atual preservado
     (não pina). Garantir isolamento hermético como no `callback/route.test.ts`.

**Testes:** `docker exec oplenario-frontend-1 node_modules/.bin/vitest run src/app/api/auth/logout` (+ o
suite de callback se tocado). Verde antes de reportar.

---

## Nota de execução

Tasks 1 (Clojure) e 2/3 (frontend/vitest) são independentes; ordem 1→2→3. Task 2 depende do contrato de
Task 1 (campos `nome-oficial`/`nome-curto`) mas só no runtime — o teste de FE stuba a resposta, então pode ir
em paralelo lógico. Revisão whole-branch final em Opus (lente de segurança no host-pin).
