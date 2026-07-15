# Onda D Slice 5 — Identidade do vereador (Tier 2) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** O `admin_ente` concede acesso ao sistema a um vereador já cadastrado; o vereador recebe convite por e-mail, cadastra passkey e entra — sem dev-token. Fecha o Marco **MFE-4 "entrada real"**.

**Architecture:** O núcleo já existe (`identidade` tem criar-identidade!/criar-vinculo!/adicionar-papel! testados; o adapter Keycloak tem criar-usuario!/provisionar-realm! reais contra o KC 26). Esta fatia entrega **borda + fio + convite + CI**. O **front orquestra** 3 chamadas (§22.10:26 proíbe módulo backend orquestrador sem verdade própria), ordenadas por **"acesso por último"** — todo estado intermediário é fail-closed. O convite é e-mail **do próprio Keycloak** (config de realm), não da aplicação.

**Tech Stack:** Clojure (HoneySQL, next.jdbc, Malli, Pedestal, Component, Migratus); Keycloak 26 admin API; Mailpit; Next.js 16 / React 19 / TS; vitest; kaocha.

**Spec:** `docs/superpowers/specs/2026-07-14-onda-d-slice5-identidade-tier2-design.md` (commit `8460270`).
**Branch:** `onda-d-slice5-identidade-tier2` (já criada, off `main`, 2 commits de docs).

## Global Constraints

- **Escopo.** IN: identidade por CPF · vínculo `vereador` + papel · usuário Keycloak · convite por e-mail com passkey obrigatório · `admin_ente` fiado pela 1ª vez · Mailpit + Keycloak no CI · a tela. OUT (com motivo): **consentimento LGPD** (§22.5:144 — é erro conceitual para vereador; base legal é função pública), gov.br/cidadão, cifra de CPF em repouso (carry pré-prod), servidor/`admin_ente` como sujeitos provisionáveis, step-up (não existe mecanismo), telas de enrollment do passkey (são do Keycloak).
- **Silhueta ADR-0001:** `wire/in` (Malli `:closed`) → `adapters/in` (valida/coage → domínio) → `diplomat/http/in` (route table) → Repo-Component. Sem `port/`, sem ORM.
- **§22.10 — módulos não se importam.** `identidade` NUNCA importa `cadastros` e vice-versa. O que cruza vem por **guard de serviço injetado pelo host** (padrão `info-ente` em `rotas.clj`). Sem FK/JOIN cross-schema.
- **Split de privilégio de CPF é lei.** Caminho supratenant (`identidade`/`identidade_externa`) roda no `:ds` cru (role efetivo herda `oplenario_id_resolver`); caminho tenant roda em `transacao` (= `com-tenant*` → `SET LOCAL ROLE oplenario_app`, que **perde** o acesso a CPF). **NUNCA inventar transação que atravesse os dois níveis** — dissolveria o split.
- **Gate:** `it/exige-papel "admin_ente"` em **toda** rota desta fatia. As rotas de cadastro da Slice 4 seguem em `"secretario"` — não tocar.
- **Contrato de status:** `201` criar · `200` idempotente/já-feito · `400` corpo inválido (auto, via interceptor global `erro` quando `adapters/in` lança `:validacao/invalido`) · `403` sem `admin_ente` (auto, via `exige-papel`) · `401` sem token · `404` id que não parseia OU de outro tenant (nunca 500, nunca vaza existência) · `409` conflito (capturado **localmente** no handler — `:conflito/*` NÃO é mapeado globalmente) · **`500` para erro de infra do Keycloak — NUNCA 401** (disciplina existente: `verificar-token*` propaga `NetworkException`/`RateLimitReachedException` de propósito).
- **`me/humanize` guarda só nomes de campo** — `m/explain` embute `:value`, e logar isso vazaria CPF. Ver `cadastros/adapters/in/vereador.clj:validar!`.
- **`keywordizar` sem filtrar** — preserva chave forjada até a validação, senão o `:closed` do schema nunca a enxerga para recusar. A checagem anti-forja mora no schema, não numa allowlist manual antes dele.
- **Inv.10 — sem DELETE.** Grant tenant é `SELECT, INSERT, UPDATE`.
- **Português** em comentários/copy; kebab-case no wire (jsonista).
- **NUNCA editar migration aplicada** (Migratus registra o id e não re-roda) — sempre arquivo NOVO.
- **Testes de backend em container efêmero** (mandato Docker — nunca `clojure` no host). Da raiz do repo (1º run baixa `.m2` ~2min → rodar em background com timeout alto; confirmar a rede com `docker network ls`):
  ```bash
  docker run --rm --network oplenario_default \
    -v "$(pwd)":/app -w /app/apps/backend \
    -v oplenario_backend_m2:/root/.m2 \
    -e DATABASE_URL='jdbc:postgresql://postgres:5432/oplenario' \
    -e MINIO_ENDPOINT='http://minio:9000' \
    -e VALKEY_URI='redis://valkey:6379' \
    clojure:temurin-21-tools-deps \
    clojure -M:test --focus <ns> --reporter documentation
  ```
  Fixtures chamam `migracao/migrar!` que exige o role OWNER `oplenario` (a `DATABASE_URL` acima), NÃO `oplenario_pool`. Stack de pé antes: `cd apps/backend && docker compose up -d`.
- **Testes `:keycloak`** exigem o profile `auth` + Mailpit: `cd apps/backend && docker compose --profile auth up -d`. Adicionar `-e KEYCLOAK_BASE_URL='http://keycloak:8080'` e `-e MAILPIT_URL='http://mailpit:8025'` ao `docker run` acima, e trocar `--focus <ns>` por `--focus oplenario.keycloak.<ns>`. **1º boot do Keycloak leva ~3-5min** (Quarkus augmentation) — esperar o healthcheck de verdade, não assumir pronto porque o container subiu.
- **Frontend em container:** `docker compose exec frontend npm test` · `npx vitest run <file>` · `npm run lint` · `npx tsc --noEmit`.
- **AA nos 2 temas**, medida em pixel composto (`produto/design-system/o-plenario/GUIDELINES-CHECKLIST.md` §5.1). Usar a skill `independent-accessibility-verification` — **nunca** escrever um ratio de memória.

---

## Estrutura de arquivos

**Criar:**
- `apps/backend/src/oplenario/identidade/wire/in/acesso.clj` — schemas Malli `:closed` dos 2 corpos.
- `apps/backend/src/oplenario/identidade/adapters/in/acesso.clj` — valida/coage → domínio (é aqui que o CPF passa a ser validado de verdade).
- `apps/backend/src/oplenario/identidade/diplomat/http/in.clj` — a superfície **administrativa** (gated `admin_ente`). Separada de `auth_in.clj`, que é a superfície **pública** de login — responsabilidades distintas, não misturar.
- `apps/backend/resources/migrations/20260714000060-vereador-identidade-unica.up.sql` (+`.down.sql`)
- `apps/backend/test/unit/oplenario/identidade/acesso_adapters_in_test.clj`
- `apps/backend/test/integration/oplenario/identidade/acesso_http_test.clj`
- `apps/backend/test/integration/oplenario/identidade/fail_closed_test.clj` — o coração da fatia.
- `apps/backend/test/keycloak/oplenario/keycloak/convite_test.clj`
- `apps/frontend/src/lib/use-conceder-acesso.ts`, `src/app/(interno)/cadastros/vereadores/conceder-acesso-form.tsx`

**Modificar:**
- `apps/backend/src/oplenario/kernel/components/idp.clj` — port ganha `convidar!`.
- `apps/backend/src/oplenario/kernel/components/idp_dev.clj` — stub.
- `apps/backend/src/oplenario/kernel/components/keycloak_idp.clj` — realm com passkey+SMTP; `criar-usuario!` get-or-create; `convidar!`.
- `apps/backend/src/oplenario/identidade/db/vinculo.clj` — `criar!` idempotente.
- `apps/backend/src/oplenario/identidade/components/repositorio.clj` — `conceder-acesso!`.
- `apps/backend/src/oplenario/cadastros/db/vereador.clj`, `components/repositorio.clj`, `diplomat/http/in.clj` — ligar `identidade_id`.
- `apps/backend/src/oplenario/rotas.clj` — registrar o fragmento novo + guard `identidade-existe?`.
- `apps/backend/resources/config.edn`, `src/oplenario/config.clj` — SMTP do realm.
- `apps/backend/docker-compose.yml` — Mailpit.
- `.github/workflows/ci.yml` — Keycloak + Mailpit; tirar `--skip :keycloak`.

---

## Task 1: Port do IdP ganha `convidar!`

**Files:**
- Modify: `apps/backend/src/oplenario/kernel/components/idp.clj`
- Modify: `apps/backend/src/oplenario/kernel/components/idp_dev.clj`
- Modify: `apps/backend/src/oplenario/kernel/components/keycloak_idp.clj` (só o `defrecord`, para compilar)
- Test: `apps/backend/test/unit/oplenario/kernel/idp_port_test.clj`

**Interfaces:**
- Produces: `(convidar! [idp ente-id identidade-id])` → `true` (enviado) | lança em falha de infra. Consumido pelas Tasks 4, 8, 9.

- [ ] **Step 1: Escrever o teste que falha**

Criar `apps/backend/test/unit/oplenario/kernel/idp_port_test.clj`:

```clojure
(ns oplenario.kernel.idp-port-test
  "O port do IdP e' seam estavel (§22.5): quem consome o protocolo nao sabe se e' Keycloak ou dev."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.idp-dev :as idp-dev]))

(deftest convidar-existe-no-port
  (is (some? (resolve 'oplenario.kernel.components.idp/convidar!))
      "o port expoe convidar! (bootstrap de 1o acesso, §22.5.2 eixo F)"))

(deftest idp-dev-recusa-convidar
  (is (thrown? Exception (idp/convidar! (idp-dev/idp-dev) (random-uuid) (random-uuid)))
      "idp-dev nao provisiona nem envia e-mail — lanca, como as outras 3 ops de provisionamento"))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `docker run ... clojure -M:test --focus oplenario.kernel.idp-port-test` (invocação completa em Global Constraints)
Expected: FAIL — `No such var: idp/convidar!`

- [ ] **Step 3: Adicionar `convidar!` ao port**

Em `apps/backend/src/oplenario/kernel/components/idp.clj`, dentro do `defprotocol IdentityProvider`, após `criar-usuario!`:

```clojure
  (convidar! [idp ente-id identidade-id]
    "Dispara o BOOTSTRAP de 1o acesso: o IdP envia codigo de uso unico ao e-mail institucional, que abre
    a sessao e OBRIGA o cadastro de passkey antes de qualquer acao (§22.5.2 eixo F). Operacao PROPRIA (nao
    dobrada em criar-usuario!) porque 'reenviar convite' e' acao de produto separada. Idempotente: reenviar
    invalida o codigo anterior. O e-mail sai do IdP, NAO da aplicacao — nao confundir com o carry F6
    (e-mail transacional da app). Erro de infra LANCA (borda -> 500), nunca devolve false.")
```

Em `idp_dev.clj`, no `defrecord`, junto das outras ops que lançam:

```clojure
  (convidar! [_ _ente-id _identidade-id]
    (throw (ex-info "idp-dev nao envia convite (use o KeycloakIdp)" {:tipo :idp/nao-suportado})))
```

Em `keycloak_idp.clj`, no `defrecord KeycloakIdp` (impl real vem na Task 4):

```clojure
  (convidar! [this ente-id identidade-id] (convidar-impl this ente-id identidade-id))
```

E, **acima** do `defrecord`, um stub temporário para compilar (removido na Task 4):

```clojure
(defn- convidar-impl [_ _ente-id _identidade-id]
  (throw (ex-info "convidar!: nao implementado (Task 4)" {:tipo :idp/nao-implementado})))
```

- [ ] **Step 4: Rodar para ver passar**

Run: `docker run ... clojure -M:test --focus oplenario.kernel.idp-port-test`
Expected: PASS (2 testes)

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/kernel/components/idp.clj \
        apps/backend/src/oplenario/kernel/components/idp_dev.clj \
        apps/backend/src/oplenario/kernel/components/keycloak_idp.clj \
        apps/backend/test/unit/oplenario/kernel/idp_port_test.clj
git commit -m "feat(kernel): port do IdP ganha convidar! (bootstrap de 1o acesso)"
```

---

## Task 2: `provisionar-realm!` habilita passkey + configura SMTP

**Files:**
- Modify: `apps/backend/src/oplenario/kernel/components/keycloak_idp.clj`
- Modify: `apps/backend/resources/config.edn`
- Modify: `apps/backend/src/oplenario/config.clj`
- Test: `apps/backend/test/keycloak/oplenario/keycloak/provisionamento_test.clj` (existe — estender)

**Interfaces:**
- Consumes: `provisionar-realm!` (já existe).
- Produces: realm com `webauthn-register-passwordless` **habilitada** e `smtpServer` configurado. Consumido pelas Tasks 3, 4.

**Contexto que o implementador precisa:** no Keycloak, as required actions de WebAuthn vêm **desabilitadas de fábrica**. Sem habilitar no realm, marcar o usuário com ela é **silenciosamente ignorado** — mesma classe de armadilha do User Profile que a Slice 1 já documentou em `declarar-atributo-identidade!`.

- [ ] **Step 1: Escrever o teste que falha**

Em `apps/backend/test/keycloak/oplenario/keycloak/provisionamento_test.clj`, adicionar:

```clojure
(deftest realm-habilita-passkey-e-smtp
  (let [ente (random-uuid)
        idp (keycloak-idp/keycloak-idp (:keycloak (config/carregar)))]
    (idp/provisionar-realm! idp ente)
    (let [r (realm-representation idp ente)]        ; helper ja existente no ns
      (is (true? (->> (:requiredActions r)
                      (filter #(= "webauthn-register-passwordless" (:alias %)))
                      first :enabled))
          "passkey vem DESABILITADA de fabrica no KC — provisionar-realm! precisa habilitar, senao marcar
           o usuario com ela e' silenciosamente ignorado")
      (is (= "mailpit" (get-in r [:smtpServer :host]))
          "realm aponta p/ o servidor de e-mail — quem envia o convite e' o KC, nao a app"))))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `cd apps/backend && docker compose --profile auth up -d` e então o `docker run` com `--focus oplenario.keycloak.provisionamento-test`
Expected: FAIL — `requiredActions` sem a entrada habilitada; `smtpServer` vazio

- [ ] **Step 3: Implementar**

Em `config.edn`, dentro do mapa `:keycloak`:

```clojure
             :smtp {:host "mailpit" :port 1025 :from "nao-responda@oplenario.local"
                    :ssl false :starttls false :auth false}
```

Em `config.clj`, junto dos outros overrides de env:

```clojure
   :smtp {:host (or (System/getenv "KEYCLOAK_SMTP_HOST") "mailpit")
          :port (parse-long (or (System/getenv "KEYCLOAK_SMTP_PORT") "1025"))
          :from (or (System/getenv "KEYCLOAK_SMTP_FROM") "nao-responda@oplenario.local")
          :ssl (= "true" (System/getenv "KEYCLOAK_SMTP_SSL"))
          :starttls (= "true" (System/getenv "KEYCLOAK_SMTP_STARTTLS"))
          :auth (= "true" (System/getenv "KEYCLOAK_SMTP_AUTH"))
          :usuario (System/getenv "KEYCLOAK_SMTP_USUARIO")
          :senha (System/getenv "KEYCLOAK_SMTP_SENHA")}
```

Em `keycloak_idp.clj`, duas fns privadas novas, acima de `provisionar-realm-impl`:

```clojure
(defn- habilitar-passkey!
  "A required action de passkey vem DESABILITADA de fabrica no Keycloak; sem isto, marcar o usuario com ela
  e' silenciosamente ignorado (mesma armadilha do User Profile, ver declarar-atributo-identidade!).
  Idempotente: PUT do mesmo estado nao falha."
  [http-client token base-url realm]
  (admin-req! http-client token :put
              (str "/admin/realms/" realm "/authentication/required-actions/webauthn-register-passwordless")
              {:alias "webauthn-register-passwordless" :name "Webauthn Register Passwordless"
               :providerId "webauthn-register-passwordless" :enabled true :defaultAction false
               :priority 30 :config {}}
              base-url))

(defn- configurar-smtp!
  "Aponta o realm p/ o relay. Quem envia o convite e' o Keycloak — p/ nos e' config, nao codigo (nao
  confundir com o carry F6, que e' o e-mail TRANSACIONAL da app). Prod = relay BR (§22.9 Eixo 12)."
  [http-client token base-url realm {:keys [host port from ssl starttls auth usuario senha]}]
  (admin-req! http-client token :put (str "/admin/realms/" realm)
              {:realm realm
               :smtpServer (cond-> {:host host :port (str port) :from from
                                    :ssl (str (boolean ssl)) :starttls (str (boolean starttls))
                                    :auth (str (boolean auth))}
                             auth (assoc :user usuario :password senha))}
              base-url))
```

E, dentro de `provisionar-realm-impl`, **após** o realm existir e antes/junto de `garantir-client!`:

```clojure
    (habilitar-passkey! http-client token base-url realm)
    (configurar-smtp! http-client token base-url realm (:smtp config))
```

- [ ] **Step 4: Rodar para ver passar**

Run: mesmo comando do Step 2
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/kernel/components/keycloak_idp.clj \
        apps/backend/resources/config.edn apps/backend/src/oplenario/config.clj \
        apps/backend/test/keycloak/oplenario/keycloak/provisionamento_test.clj
git commit -m "feat(keycloak): realm habilita passkey e aponta p/ o relay de e-mail"
```

---

## Task 3: `criar-usuario!` vira get-or-create + exige passkey + sai o `emailVerified` [GAP]

**Files:**
- Modify: `apps/backend/src/oplenario/kernel/components/keycloak_idp.clj:264-287`
- Test: `apps/backend/test/keycloak/oplenario/keycloak/provisionamento_test.clj`

**Interfaces:**
- Produces: `(criar-usuario! [idp ente-id {:identidade-id :nome :email}])` → `{:keycloak-user-id "..."}`, **idempotente**. Consumido pelas Tasks 8, 9.

- [ ] **Step 1: Escrever o teste que falha**

```clojure
(deftest criar-usuario-idempotente-e-exige-passkey
  (let [ente (random-uuid) ident (random-uuid)
        idp (keycloak-idp/keycloak-idp (:keycloak (config/carregar)))
        _ (idp/provisionar-realm! idp ente)
        u1 (idp/criar-usuario! idp ente {:identidade-id ident :nome "Helena Matos"
                                         :email "helena@camara.local"})
        u2 (idp/criar-usuario! idp ente {:identidade-id ident :nome "Helena Matos"
                                         :email "helena@camara.local"})]
    (is (= (:keycloak-user-id u1) (:keycloak-user-id u2))
        "get-or-create: re-provisionar devolve o MESMO usuario (hoje lanca em != 201)")
    (let [r (usuario-representation idp ente ident)]  ; helper: GET ?q=identidade-id:
      (is (= ["webauthn-register-passwordless"] (:requiredActions r))
          "nasce obrigado a cadastrar passkey antes de qualquer acao (§22.5.2 eixo F)")
      (is (false? (:emailVerified r))
          "emailVerified=true era [GAP] por nao haver SMTP; agora ha' — o KC verifica de verdade"))))
```

- [ ] **Step 2: Rodar para ver falhar**

Expected: FAIL — 2ª chamada lança `keycloak-idp: falha ao criar usuario (infra)` (status 409)

- [ ] **Step 3: Implementar**

Substituir `criar-usuario-impl` por:

```clojure
(defn- criar-usuario-impl
  "GET-then-create (idempotente, mesma forma de garantir-client!/provisionar-realm-impl). Nasce com a
  required action de passkey: o KC OBRIGA o cadastro antes de qualquer acao (§22.5.2 eixo F).
  emailVerified NAO e' mais forcado — o [GAP] existia so' porque nao havia SMTP (Task 2 resolveu)."
  [{:keys [config http-client]} ente-id {:keys [identidade-id nome email]}]
  (let [{:keys [base-url realm-prefixo]} config
        realm (str realm-prefixo ente-id)
        token (admin-token! config http-client)]
    (if-let [existente (buscar-usuario-por-identidade http-client token base-url realm identidade-id)]
      {:keycloak-user-id (:id existente)}
      (let [[primeiro ultimo] (nome->first-last nome)
            {:keys [status corpo headers]}
            (admin-req! http-client token :post (str "/admin/realms/" realm "/users")
                        {:username (str identidade-id)
                         :enabled true
                         :email email
                         :firstName primeiro
                         :lastName ultimo
                         :requiredActions ["webauthn-register-passwordless"]
                         :attributes {:identidade-id [(str identidade-id)]}}
                        base-url)]
        (when-not (= 201 status)
          (throw (ex-info "keycloak-idp: falha ao criar usuario (infra)" {:status status :corpo corpo})))
        (let [location (.firstValue headers "location")]
          {:keycloak-user-id (when (.isPresent location) (last (str/split (.get location) #"/")))})))))
```

**Nota:** `buscar-usuario-por-identidade` já existe (`keycloak_idp.clj:288+`) e devolve o mapa do usuário ou nil. Se ele devolver a lista crua, ajustar para `(first ...)` — conferir a impl antes de assumir.

- [ ] **Step 4: Rodar para ver passar**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/kernel/components/keycloak_idp.clj \
        apps/backend/test/keycloak/oplenario/keycloak/provisionamento_test.clj
git commit -m "feat(keycloak): criar-usuario! idempotente, exige passkey, sai o [GAP] de emailVerified"
```

---

## Task 4: `convidar!` — o convite de verdade

**Files:**
- Modify: `apps/backend/src/oplenario/kernel/components/keycloak_idp.clj`
- Test: `apps/backend/test/keycloak/oplenario/keycloak/convite_test.clj` (criar)

**Interfaces:**
- Consumes: `criar-usuario!` (Task 3), realm com SMTP (Task 2).
- Produces: `(convidar! [idp ente-id identidade-id])` → `true`. Consumido pelas Tasks 8, 9.

- [ ] **Step 1: Escrever o teste que falha**

Criar `apps/backend/test/keycloak/oplenario/keycloak/convite_test.clj`:

```clojure
(ns oplenario.keycloak.convite-test
  "Prova que o CONVITE de 1o acesso sai de verdade (§22.5.2 eixo F). Quem envia e' o Keycloak; nos so'
  pedimos. O Mailpit captura o e-mail e expoe a caixa por HTTP (nada sai p/ o mundo)."
  (:require [clojure.test :refer [deftest is]]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.keycloak-idp :as keycloak-idp]))

(defn- mailpit-url [] (or (System/getenv "MAILPIT_URL") "http://mailpit:8025"))

(defn- limpar-caixa! []
  (-> (java.net.http.HttpClient/newHttpClient)
      (.send (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str (mailpit-url) "/api/v1/messages")))
                 (.DELETE) (.build))
             (java.net.http.HttpResponse$BodyHandlers/ofString))))

(defn- mensagens []
  (-> (java.net.http.HttpClient/newHttpClient)
      (.send (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str (mailpit-url) "/api/v1/messages")))
                 (.GET) (.build))
             (java.net.http.HttpResponse$BodyHandlers/ofString))
      (.body)
      (json/read-value json/keyword-keys-object-mapper)))

(deftest convite-chega-na-caixa
  (limpar-caixa!)
  (let [ente (random-uuid) ident (random-uuid)
        idp (keycloak-idp/keycloak-idp (:keycloak (config/carregar)))]
    (idp/provisionar-realm! idp ente)
    (idp/criar-usuario! idp ente {:identidade-id ident :nome "Helena Matos"
                                  :email "helena@camara.local"})
    (is (true? (idp/convidar! idp ente ident)) "convidar! devolve true no envio")
    (let [msgs (:messages (mensagens))]
      (is (= 1 (count msgs)) "exatamente 1 e-mail na caixa")
      (is (= "helena@camara.local" (-> msgs first :To first :Address))
          "foi p/ o e-mail institucional do usuario — que mora SO' no Keycloak, nao em tabela nossa"))))

(deftest convidar-usuario-inexistente-lanca
  (let [ente (random-uuid)
        idp (keycloak-idp/keycloak-idp (:keycloak (config/carregar)))]
    (idp/provisionar-realm! idp ente)
    (is (thrown? clojure.lang.ExceptionInfo (idp/convidar! idp ente (random-uuid)))
        "sem usuario no realm nao ha' convite — lanca (fail-closed), nunca 'true' mentiroso")))
```

- [ ] **Step 2: Rodar para ver falhar**

Expected: FAIL — `convidar!: nao implementado (Task 4)`

- [ ] **Step 3: Implementar**

Substituir o stub `convidar-impl` (criado na Task 1) por:

```clojure
(defn- convidar-impl
  "PUT execute-actions-email: o KC gera o codigo de uso unico, envia ao e-mail institucional e, no resgate,
  OBRIGA o cadastro do passkey antes de qualquer acao. lifespan = janela do codigo (12h — cobre posse de
  legislatura em dia util sem virar credencial standing, §22.5.2 eixo F)."
  [{:keys [config http-client]} ente-id identidade-id]
  (let [{:keys [base-url realm-prefixo client-id]} config
        realm (str realm-prefixo ente-id)
        token (admin-token! config http-client)
        usuario (buscar-usuario-por-identidade http-client token base-url realm identidade-id)]
    (when-not usuario
      (throw (ex-info "keycloak-idp: usuario inexistente no realm — nao ha' quem convidar"
                      {:tipo :idp/usuario-inexistente})))
    (let [{:keys [status corpo]}
          (admin-req! http-client token :put
                      (str "/admin/realms/" realm "/users/" (:id usuario)
                           "/execute-actions-email?client_id=" client-id "&lifespan=43200")
                      ["webauthn-register-passwordless"]
                      base-url)]
      (when-not (#{200 204} status)
        (throw (ex-info "keycloak-idp: falha ao enviar convite (infra)" {:status status :corpo corpo})))
      true)))
```

**Nota:** `admin-req!` hoje serializa mapas. Este corpo é um **vetor** JSON — conferir que a serialização o aceita; se `admin-req!` assumir mapa, generalizar para `json/write-value-as-string` de qualquer estrutura.

- [ ] **Step 4: Rodar para ver passar**

Expected: PASS (2 testes)

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/kernel/components/keycloak_idp.clj \
        apps/backend/test/keycloak/oplenario/keycloak/convite_test.clj
git commit -m "feat(keycloak): convidar! dispara o codigo de uso unico com passkey obrigatorio"
```

---

## Task 5: Mailpit no compose + Keycloak e Mailpit no CI

**Files:**
- Modify: `apps/backend/docker-compose.yml`
- Modify: `.github/workflows/ci.yml`

**Contexto:** hoje `ci.yml:61` roda `--skip :e2e --skip :keycloak` — **o adapter Keycloak não tem rede de proteção automática**. Decisão do Daouda (14/07): resolver o carry, não registrá-lo. `:e2e` **segue skipado** (outra frente).

- [ ] **Step 1: Adicionar o Mailpit ao compose**

Em `apps/backend/docker-compose.yml`, junto do serviço `keycloak`:

```yaml
  # Captura de e-mail em dev/CI: o Keycloak manda o convite p/ ca' e nada sai p/ o mundo.
  # Caixa em http://localhost:${OPLENARIO_MAILPIT_PORT:-8025}. API: /api/v1/messages.
  mailpit:
    image: axllent/mailpit:v1.20
    ports:
      - "${OPLENARIO_MAILPIT_PORT:-8025}:8025"   # UI/API
      - "${OPLENARIO_SMTP_PORT:-1025}:1025"      # SMTP
    profiles: ["auth"]
    healthcheck:
      test: ["CMD", "/mailpit", "readyz"]
      interval: 5s
      timeout: 3s
      retries: 20
```

- [ ] **Step 2: Ligar o Keycloak e o Mailpit no CI**

Em `.github/workflows/ci.yml`, substituir o step `Subir Postgres + MinIO (docker compose)`:

```yaml
      - name: Subir Postgres + MinIO + Keycloak + Mailpit (docker compose)
        run: docker compose --profile auth up -d postgres minio keycloak mailpit
```

Adicionar, após o step de espera do MinIO:

```yaml
      - name: Esperar Keycloak ficar pronto
        run: |
          # 1o boot faz Quarkus augmentation — pode levar minutos. Nao assumir pronto pelo "started".
          for i in $(seq 1 90); do
            curl -sf http://localhost:8080/realms/master >/dev/null && exit 0
            sleep 4
          done
          echo "Keycloak nao subiu a tempo"; exit 1

      - name: Esperar Mailpit ficar pronto
        run: |
          for i in $(seq 1 30); do
            curl -sf http://localhost:8025/readyz && exit 0
            sleep 2
          done
          echo "Mailpit nao subiu a tempo"; exit 1
```

E o step de teste passa a:

```yaml
      - name: Testes (unit + integracao + keycloak) — import-lint + leak 3-dim + authz sao gates
        env:
          DATABASE_URL: jdbc:postgresql://localhost:5432/oplenario
          MINIO_ENDPOINT: http://localhost:9000
          KEYCLOAK_BASE_URL: http://localhost:8080
          KEYCLOAK_SMTP_HOST: localhost
          MAILPIT_URL: http://localhost:8025
        run: clojure -M:test --skip :e2e
```

**Nota:** o realm precisa alcançar o Mailpit **de dentro** do container do Keycloak. No CI, com `docker compose`, os dois estão na mesma rede → `KEYCLOAK_SMTP_HOST=mailpit` (nome do serviço), não `localhost`. Corrigir para `mailpit` e validar no Step 3 — este é o erro mais provável desta task.

- [ ] **Step 3: Provar o CI localmente**

Run: `cd apps/backend && docker compose --profile auth up -d` e então o `docker run` de Global Constraints com `--skip :e2e` (sem `--skip :keycloak`)
Expected: PASS — suíte inteira incluindo `:keycloak`

- [ ] **Step 4: Commit**

```bash
git add apps/backend/docker-compose.yml .github/workflows/ci.yml
git commit -m "ci: Keycloak e Mailpit no CI — testes :keycloak deixam de ser skipados"
```

---

## Task 6: CPF validado na borda (bug real) + wire schemas

**Files:**
- Create: `apps/backend/src/oplenario/identidade/wire/in/acesso.clj`
- Create: `apps/backend/src/oplenario/identidade/adapters/in/acesso.clj`
- Test: `apps/backend/test/unit/oplenario/identidade/acesso_adapters_in_test.clj`

**Contexto — o bug:** `identidade/db/identidade.clj:17` valida CPF por `{:pre [(mod/valido-cpf? cpf)]}`. **Assertions somem** quando a JVM roda com `*assert*` false / `-da` — configuração de produção comum. Não há CHECK no banco (a migration diz *"validacao em app/adapter"*). Hoje a única barreira pode evaporar sem sinal. A assertion **fica** como rede interna, mas deixa de ser a única.

**Interfaces:**
- Produces: `(criar-identidade->dominio [ator wire-in])` → `{:id :cpf :nome}`; `(conceder-acesso->dominio [ator wire-in])` → `{:identidade-id :tipo :papeis :email}`. Consumido pelas Tasks 7, 8.

- [ ] **Step 1: Escrever o teste que falha**

Criar `apps/backend/test/unit/oplenario/identidade/acesso_adapters_in_test.clj`:

```clojure
(ns oplenario.identidade.acesso-adapters-in-test
  "Gate de entrada da superficie ADMINISTRATIVA de identidade. O CPF e' validado AQUI (nao so' pela
  assertion do db/, que some com -da) — CPF invalido = 400, nunca linha ruim no banco."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.identidade.adapters.in.acesso :as adapters-in]))

(def ^:private ator {:ente-id (random-uuid) :identidade-id (random-uuid)})

(defn- tipo-do [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:tipo (ex-data e)))))

(deftest cpf-invalido-e-recusado-na-borda
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/criar-identidade->dominio ator {"cpf" "11111111111" "nome" "X"})))
      "11-iguais passa no regex mas falha no digito verificador — a borda recusa")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/criar-identidade->dominio ator {"cpf" "12345678900" "nome" "X"})))
      "digito verificador errado -> 400")
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/criar-identidade->dominio ator {"cpf" "529.982.247-25" "nome" "X"})))
      "formatado com pontuacao -> 400 (o wire e' 11 digitos crus; nao coagimos silenciosamente)"))

(deftest cpf-valido-passa
  (let [m (adapters-in/criar-identidade->dominio ator {"cpf" "52998224725" "nome" "Helena Matos"})]
    (is (= "52998224725" (:cpf m)))
    (is (= "Helena Matos" (:nome m)))
    (is (uuid? (:id m)) "id gerado aqui, nunca vindo do corpo")))

(deftest chave-forjada-e-recusada
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/criar-identidade->dominio ator {"cpf" "52998224725" "nome" "X"
                                                                "id" "00000000-0000-0000-0000-000000000000"})))
      "o :closed do schema recusa `id` forjado — por isso keywordizar NAO filtra antes de validar"))

(deftest conceder-acesso-so-aceita-papeis-conhecidos
  (let [ident (random-uuid)]
    (is (= :validacao/invalido
           (tipo-do #(adapters-in/conceder-acesso->dominio
                      ator {"identidade-id" (str ident) "tipo" "vereador"
                            "papeis" ["admin_ente"] "email" "h@c.local"})))
        "conceder admin_ente por esta rota seria escalada de privilegio — allowlist recusa")
    (let [m (adapters-in/conceder-acesso->dominio
             ator {"identidade-id" (str ident) "tipo" "vereador"
                   "papeis" ["vereador"] "email" "helena@camara.local"})]
      (is (= ident (:identidade-id m)))
      (is (= ["vereador"] (:papeis m))))))

(deftest email-malformado-recusado
  (is (= :validacao/invalido
         (tipo-do #(adapters-in/conceder-acesso->dominio
                    ator {"identidade-id" (str (random-uuid)) "tipo" "vereador"
                          "papeis" ["vereador"] "email" "sem-arroba"})))
      "e-mail malformado -> 400 (o convite nunca chegaria; falhar cedo e' honesto)"))
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `docker run ... --focus oplenario.identidade.acesso-adapters-in-test`
Expected: FAIL — namespace não existe

- [ ] **Step 3: Implementar**

Criar `apps/backend/src/oplenario/identidade/wire/in/acesso.clj`:

```clojure
(ns oplenario.identidade.wire.in.acesso
  "Corpos de requisicao da superficie ADMINISTRATIVA de identidade (§22.10 wire/in, ADR-0001).
  `:closed` em todos: chave forjada (id, ente-id) e' RECUSADA, nao ignorada.")

;; Papeis concedíveis por ESTA rota. admin_ente fica DE FORA de proposito: conceder o papel que concede
;; papeis seria escalada de privilegio por auto-servico. Bootstrap do 1o admin_ente = admin_sistema (carry).
(def papeis-concediveis #{"vereador"})

(def CriarIdentidade
  [:map {:closed true}
   [:cpf [:re #"^\d{11}$"]]
   [:nome [:string {:min 1}]]])

(def ConcederAcesso
  [:map {:closed true}
   [:identidade-id :string]
   [:tipo [:enum "vereador"]]
   [:papeis [:vector {:min 1} (into [:enum] (sort papeis-concediveis))]]
   [:email [:re #"^[^@\s]+@[^@\s]+\.[^@\s]+$"]]])
```

Criar `apps/backend/src/oplenario/identidade/adapters/in/acesso.clj`:

```clojure
(ns oplenario.identidade.adapters.in.acesso
  "Gate de ENTRADA wire/in -> dominio da superficie ADMINISTRATIVA (§22.10 adapters/in, ADR-0001).
  Chamado SO' pelo diplomat/. Valida (fail-closed -> :validacao/invalido -> 400), coage e INJETA o que nao
  vem do corpo (`id` gerado, `ente-id` do ator). Espelha cadastros/adapters/in/vereador.clj.

  O CPF e' validado AQUI, de verdade: db/identidade.clj:17 valida por {:pre}, e assertion SOME com -da —
  em prod a unica barreira poderia evaporar sem sinal (nao ha CHECK no banco). A assertion fica como rede
  interna; esta e' a barreira real."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.identidade.wire.in.acesso :as wire]))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- keywordizar
  "Converte TODAS as chaves string p/ keyword (sem filtrar) — preservar chave forjada ate' a validacao e'
  o que permite o :closed do schema recusa-la. A checagem anti-forja mora no schema, nao numa allowlist."
  [m]
  (reduce-kv (fn [acc k v] (assoc acc (keyword k) v)) {} m))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    ;; SO' os nomes-de-campo — m/explain embute :value, e aqui :value e' CPF. Nunca logar o payload cru.
    (invalido! msg {:campos (keys (me/humanize erros))})))

(defn- ->uuid! [s campo]
  (or (parse-uuid s) (invalido! "identificador invalido" {:campos [campo]})))

(defn criar-identidade->dominio [_ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/CriarIdentidade mm "corpo de criar identidade invalido")
    ;; digito verificador — o regex do schema so' garante 11 digitos.
    (when-not (mod/valido-cpf? (:cpf mm)) (invalido! "cpf invalido" {:campos [:cpf]}))
    {:id (random-uuid) :cpf (:cpf mm) :nome (:nome mm)}))

(defn conceder-acesso->dominio [_ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/ConcederAcesso mm "corpo de conceder acesso invalido")
    {:identidade-id (->uuid! (:identidade-id mm) :identidade-id)
     :tipo (:tipo mm)
     :papeis (:papeis mm)
     :email (:email mm)}))
```

- [ ] **Step 4: Rodar para ver passar**

Expected: PASS (6 testes)

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/identidade/wire/ \
        apps/backend/src/oplenario/identidade/adapters/ \
        apps/backend/test/unit/oplenario/identidade/acesso_adapters_in_test.clj
git commit -m "fix(identidade): CPF validado na borda (assertion do db/ some com -da) + wire schemas"
```

---

## Task 7: `conceder-acesso!` no Repo — vínculo + papéis numa tx, idempotente

**Files:**
- Modify: `apps/backend/src/oplenario/identidade/db/vinculo.clj:12-16`
- Modify: `apps/backend/src/oplenario/identidade/components/repositorio.clj`
- Test: `apps/backend/test/integration/oplenario/identidade/db_test.clj`

**Contexto:** `adicionar-papel!` **já é idempotente** (`ON CONFLICT [:ente_id :identidade_id :papel] :do-nothing`). `criar!` **não é** — a `UNIQUE (ente_id, identidade_id, tipo)` faz a 2ª chamada estourar. A tabela tem `PRIMARY KEY (ente_id, id)` e `UNIQUE (ente_id, identidade_id, tipo)`.

**Interfaces:**
- Produces: `(conceder-acesso! [this ente-id vinculo papeis])` → `{:vinculo-id uuid}`. Consumido pela Task 8.

- [ ] **Step 1: Escrever o teste que falha**

Em `apps/backend/test/integration/oplenario/identidade/db_test.clj`:

```clojure
(deftest conceder-acesso-idempotente-numa-tx
  (let [ente (random-uuid)
        ident (:id (criar-identidade-fixture! "52998224725" "Helena Matos"))  ; helper do ns
        repo (repo-identidade)
        v {:id (random-uuid) :ente-id ente :identidade-id ident :tipo "vereador" :estado "ativo"}
        r1 (repo/conceder-acesso! repo ente v ["vereador"])
        r2 (repo/conceder-acesso! repo ente (assoc v :id (random-uuid)) ["vereador"])]
    (is (= (:vinculo-id r1) (:vinculo-id r2))
        "idempotente por (ente,identidade,tipo): repetir devolve o vinculo CANONICO, nao duplica nem estoura")
    (is (= 1 (count (repo/vinculos-de repo ente ident))) "um vinculo, nao dois")
    (is (= #{"vereador"} (repo/papeis-de repo ente ident)) "papel concedido")))
```

- [ ] **Step 2: Rodar para ver falhar**

Expected: FAIL — `No such var: repo/conceder-acesso!`

- [ ] **Step 3: Implementar**

Em `db/vinculo.clj`, substituir `criar!`:

```clojure
(defn criar!
  "Cria o vinculo. Idempotente por (ente_id, identidade_id, tipo) — RETORNA o id CANONICO (o existente, em
  caso de conflito); o caller DEVE usar este id, nao o que passou (mesma disciplina de db/identidade/inserir!).
  DO UPDATE (no-op sobre `tipo`) em vez de DO NOTHING: DO NOTHING nao devolveria RETURNING na colisao."
  [tx {:keys [id ente-id identidade-id tipo estado]}]
  (:vinculo/id
   (jdbc/execute-one! tx
     (sql/format {:insert-into :identidade.vinculo
                  :values [{:id id :ente_id ente-id :identidade_id identidade-id
                            :tipo tipo :estado (or estado "ativo")}]
                  :on-conflict [:ente_id :identidade_id :tipo]
                  :do-update-set {:tipo :excluded.tipo}
                  :returning [:id]}))))
```

**Atenção:** `criar!` mudou de retorno (era o mapa do `execute-one!`, agora é o uuid). Rodar a suíte inteira de `identidade` e ajustar os call sites — `snapshot-ator` e os testes existentes.

Em `components/repositorio.clj`, adicionar ao `defprotocol RepoIdentidade` (seção TENANT):

```clojure
  (conceder-acesso! [this ente-id vinculo papeis]
    "Vinculo + papeis numa UNICA tx (§22.5 eixo D). Idempotente. E' o passo que ABRE A PORTA — por isso
    e' o ULTIMO do fluxo de provisionamento (spec §4.2 'acesso por ultimo'): antes dele, resolver-sessao
    nao acha vinculo ativo e ninguem entra.")
```

E ao `defrecord RepoIdentidadePg`:

```clojure
  (conceder-acesso! [this ente-id v papeis]
    (transacao this ente-id
      (fn [tx]
        (let [vinculo-id (vinc/criar! tx v)]
          (doseq [p papeis]
            (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente-id
                                       :identidade-id (:identidade-id v) :papel p}))
          {:vinculo-id vinculo-id}))))
```

- [ ] **Step 4: Rodar para ver passar**

Run: `docker run ... --focus oplenario.identidade.db-test`
Expected: PASS (todos, incluindo os 7 pré-existentes)

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/identidade/db/vinculo.clj \
        apps/backend/src/oplenario/identidade/components/repositorio.clj \
        apps/backend/test/integration/oplenario/identidade/db_test.clj
git commit -m "feat(identidade): conceder-acesso! (vinculo+papeis numa tx, idempotente)"
```

---

## Task 8: A superfície administrativa HTTP de `identidade`

**Files:**
- Create: `apps/backend/src/oplenario/identidade/diplomat/http/in.clj`
- Modify: `apps/backend/src/oplenario/rotas.clj`
- Test: `apps/backend/test/integration/oplenario/identidade/acesso_http_test.clj`

**Interfaces:**
- Consumes: `adapters-in/criar-identidade->dominio`, `conceder-acesso->dominio` (Task 6); `repo/criar-identidade!`, `repo/conceder-acesso!` (Task 7); `idp/provisionar-realm!`, `criar-usuario!`, `convidar!` (Tasks 2-4).
- Produces: `POST /identidade/identidades` · `POST /identidade/acessos` · `POST /identidade/acessos/:identidade-id/convite`. Consumido pela Task 12.

- [ ] **Step 1: Escrever o teste que falha**

Criar `apps/backend/test/integration/oplenario/identidade/acesso_http_test.clj` (padrão da casa: DB-free, repo fake por `reify`, ator/papel por token JSON do `idp-dev`):

```clojure
(ns oplenario.identidade.acesso-http-test
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as ph]
            [io.pedestal.test :as pt]
            [jsonista.core :as json]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.idp :as idp]
            [oplenario.kernel.components.idp-dev :as idp-dev]
            [oplenario.rotas :as rotas]))

(def ^:private cpf-valido "52998224725")

(defn- fake-repo-identidade [papeis capturado]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify repo-id/RepoIdentidade
    (snapshot-ator [_ _e _i] {:vinculo-ativo {:id (random-uuid) :tipo "servidor"} :papeis papeis})
    (criar-identidade! [_ m] (swap! capturado conj [:criar-identidade m]) (:id m))
    (conceder-acesso! [_ e v p] (swap! capturado conj [:conceder-acesso e v p]) {:vinculo-id (random-uuid)})))

(defn- fake-idp [capturado & {:keys [convidar-lanca?]}]
  (reify idp/IdentityProvider
    (verificar-token [_ t] (json/read-value t json/keyword-keys-object-mapper))
    (provisionar-realm! [_ e] (swap! capturado conj [:provisionar-realm e]) true)
    (criar-usuario! [_ e u] (swap! capturado conj [:criar-usuario e u]) {:keycloak-user-id "kc-1"})
    (convidar! [_ e i]
      (when convidar-lanca? (throw (ex-info "keycloak fora do ar" {:tipo :infra})))
      (swap! capturado conj [:convidar e i]) true)
    (resetar-mfa! [_ _e _i] true)))

(defn- service-fn [papeis capturado & {:keys [convidar-lanca?]}]
  (-> (http/servico (config/carregar)
                    (rotas/montar {:idp (fake-idp capturado :convidar-lanca? convidar-lanca?)
                                   :repo-identidade (fake-repo-identidade papeis capturado)})
                    it/globais)
      ph/create-server ::ph/service-fn))

(defn- token [ente-id ident-id]
  (json/write-value-as-string {:sub "u" :ente-id (str ente-id) :identidade-id (str ident-id)}))
(defn- com-bearer [t] {"authorization" (str "Bearer " t) "content-type" "application/json"})
(defn- ler-json [r] (json/read-value (:body r) json/keyword-keys-object-mapper))

(deftest criar-identidade-201
  (let [cap (atom []) ente (random-uuid)
        r (pt/response-for (service-fn #{"admin_ente"} cap)
                           :post "/identidade/identidades"
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:cpf cpf-valido :nome "Helena Matos"}))]
    (is (= 201 (:status r)))
    (is (uuid? (parse-uuid (:identidade-id (ler-json r)))) "devolve o id canonico")))

(deftest criar-identidade-sem-admin-ente-403
  (let [cap (atom [])
        r (pt/response-for (service-fn #{"secretario"} cap)
                           :post "/identidade/identidades"
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:cpf cpf-valido :nome "X"}))]
    (is (= 403 (:status r))
        "secretario cria vereador mas NAO concede acesso — senao cria 'vereador' com o proprio e-mail e vota")
    (is (empty? @cap) "nada foi tocado")))

(deftest criar-identidade-cpf-invalido-400
  (let [cap (atom [])
        r (pt/response-for (service-fn #{"admin_ente"} cap)
                           :post "/identidade/identidades"
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:cpf "11111111111" :nome "X"}))]
    (is (= 400 (:status r)) "digito verificador errado -> 400")
    (is (not (re-find #"11111111111" (:body r))) "o CPF NUNCA volta no corpo do erro")))

(deftest conceder-acesso-201-e-a-ordem-importa
  (let [cap (atom []) ente (random-uuid) ident (random-uuid)
        r (pt/response-for (service-fn #{"admin_ente"} cap)
                           :post "/identidade/acessos"
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string
                                  {:identidade-id (str ident) :tipo "vereador"
                                   :papeis ["vereador"] :email "helena@camara.local"}))]
    (is (= 201 (:status r)))
    (is (= [:conceder-acesso :provisionar-realm :criar-usuario :convidar] (mapv first @cap))
        "DB ANTES do Keycloak: se o KC cair, sobra vinculo sem credencial = ninguem entra (fail-closed)")))

(deftest conceder-acesso-keycloak-fora-do-ar-500
  (let [cap (atom [])
        r (pt/response-for (service-fn #{"admin_ente"} cap :convidar-lanca? true)
                           :post "/identidade/acessos"
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string
                                  {:identidade-id (str (random-uuid)) :tipo "vereador"
                                   :papeis ["vereador"] :email "h@c.local"}))]
    (is (= 500 (:status r))
        "infra fora do ar -> 500, NUNCA 401 — mascarar degradacao como credencial ruim vira incidente mudo")))

(deftest reenviar-convite-200
  (let [cap (atom []) ente (random-uuid) ident (random-uuid)
        r (pt/response-for (service-fn #{"admin_ente"} cap)
                           :post (str "/identidade/acessos/" ident "/convite")
                           :headers (com-bearer (token ente (random-uuid))))]
    (is (= 200 (:status r)))
    (is (= [[:convidar ente ident]] @cap) "so' reenvia — nao recria vinculo nem usuario")))

(deftest reenviar-convite-id-malformado-404
  (let [cap (atom [])
        r (pt/response-for (service-fn #{"admin_ente"} cap)
                           :post "/identidade/acessos/nao-e-uuid/convite"
                           :headers (com-bearer (token (random-uuid) (random-uuid))))]
    (is (= 404 (:status r)) "id que nao parseia -> 404, nunca 500")))
```

- [ ] **Step 2: Rodar para ver falhar**

Expected: FAIL — namespace de rotas não existe

- [ ] **Step 3: Implementar**

Criar `apps/backend/src/oplenario/identidade/diplomat/http/in.clj`:

```clojure
(ns oplenario.identidade.diplomat.http.in
  "Superficie ADMINISTRATIVA de identidade (§22.10 diplomat, ADR-0001) — gated `admin_ente`. Separada de
  diplomat/http/auth_in.clj, que e' a superficie PUBLICA de login (descoberta/mint/logout): responsabilidades
  distintas, gates opostos.

  Estas 2 rotas sao os passos (1) e (3) do fluxo de provisionamento; o passo (2) e' do `cadastros`. Quem
  ORQUESTRA e' o front (§22.10:26 — a administracao do ente e' area de UI, nao modulo backend). A ordem
  importa e e' 'acesso por ultimo': conceder-acesso! e' o unico passo que abre a porta."
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
  fonte de verdade a' frente do sistema externo. Erro de infra do KC PROPAGA -> 500 (nunca 401)."
  [repo-identidade idp-comp]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          {:keys [identidade-id tipo papeis email]} (adapters-in/conceder-acesso->dominio ator (:json-params req))
          r (repo/conceder-acesso! repo-identidade ente-id
                                   {:id (random-uuid) :ente-id ente-id :identidade-id identidade-id
                                    :tipo tipo :estado "ativo"}
                                   papeis)]
      (idp/provisionar-realm! idp-comp ente-id)
      (idp/criar-usuario! idp-comp ente-id {:identidade-id identidade-id :nome (:nome ator) :email email})
      (idp/convidar! idp-comp ente-id identidade-id)
      (http/json-resposta 201 {:vinculo-id (str (:vinculo-id r)) :convite "enviado"}))))

(defn- reenviar-convite-handler
  "POST /identidade/acessos/:identidade-id/convite. So' reenvia (o KC invalida o codigo anterior). O e-mail
  mora SO' no Keycloak — nao ha' coluna nossa a consultar, e e' de proposito (PII a menos)."
  [idp-comp]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          ident (parse-uuid (get-in req [:path-params :identidade-id]))]
      (if-not ident
        (http/json-resposta 404 {:erro "identidade nao encontrada"})
        (do (idp/convidar! idp-comp ente-id ident)
            (http/json-resposta 200 {:convite "reenviado"}))))))

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
```

Em `rotas.clj`: adicionar o require `[oplenario.identidade.diplomat.http.in :as identidade-http]` e, no `into` final de `montar`:

```clojure
        (into (identidade-http/rotas {:auth auth :repo-identidade repo-identidade :idp idp}))
```

- [ ] **Step 4: Rodar para ver passar**

Expected: PASS (7 testes)

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src/oplenario/identidade/diplomat/http/in.clj \
        apps/backend/src/oplenario/rotas.clj \
        apps/backend/test/integration/oplenario/identidade/acesso_http_test.clj
git commit -m "feat(identidade): superficie administrativa (criar identidade, conceder acesso, reenviar convite)"
```

---

## Task 9: `cadastros` liga o vereador à identidade

**Files:**
- Create: `apps/backend/resources/migrations/20260714000060-vereador-identidade-unica.up.sql` (+ `.down.sql`)
- Modify: `apps/backend/src/oplenario/cadastros/db/vereador.clj`
- Modify: `apps/backend/src/oplenario/cadastros/components/repositorio.clj`
- Modify: `apps/backend/src/oplenario/cadastros/diplomat/http/in.clj`
- Modify: `apps/backend/src/oplenario/rotas.clj`
- Test: `apps/backend/test/integration/oplenario/cadastros/vereador_http_in_test.clj`

**Contexto:** `cadastros.vereador.identidade_id` já existe, sem FK (`-- GUARD ref ao modulo identidade (sem FK cross-schema)`). `cadastros` **não pode** importar `identidade` (§22.10) — a existência da identidade é checada por **guard de serviço injetado pelo host**, mesma forma do `info-ente`.

**Interfaces:**
- Produces: `PATCH /cadastros/vereadores/:id/identidade`. Consumido pela Task 12.

- [ ] **Step 1: Escrever o teste que falha**

Em `vereador_http_in_test.clj` (estender `fake-repo-cadastros` com `ligar-identidade!`):

```clojure
(deftest ligar-identidade-200
  (let [ente (random-uuid) vid (random-uuid) ident (random-uuid)
        r (pt/response-for (service-fn #{"admin_ente"} (fake-repo-cadastros [] nil)
                                       :identidade-existe? (constantly true))
                           :patch (str "/cadastros/vereadores/" vid "/identidade")
                           :headers (com-bearer (token ente (random-uuid)))
                           :body (json/write-value-as-string {:identidade-id (str ident)}))]
    (is (= 200 (:status r)))))

(deftest ligar-identidade-sem-admin-ente-403
  (let [r (pt/response-for (service-fn #{"secretario"} (fake-repo-cadastros [] nil)
                                       :identidade-existe? (constantly true))
                           :patch (str "/cadastros/vereadores/" (random-uuid) "/identidade")
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:identidade-id (str (random-uuid))}))]
    (is (= 403 (:status r)) "ligar identidade e' parte de conceder acesso — nao e' do secretario")))

(deftest ligar-identidade-inexistente-404
  (let [r (pt/response-for (service-fn #{"admin_ente"} (fake-repo-cadastros [] nil)
                                       :identidade-existe? (constantly false))
                           :patch (str "/cadastros/vereadores/" (random-uuid) "/identidade")
                           :headers (com-bearer (token (random-uuid) (random-uuid)))
                           :body (json/write-value-as-string {:identidade-id (str (random-uuid))}))]
    (is (= 404 (:status r))
        "guard de servico: sem FK cross-schema, a existencia da identidade e' checada por seam do host")))
```

- [ ] **Step 2: Rodar para ver falhar**

Expected: FAIL — rota não existe

- [ ] **Step 3: Migration + implementação**

Criar `20260714000060-vereador-identidade-unica.up.sql`:

```sql
-- Onda D Slice 5: uma identidade nao pode estar ligada a DOIS vereadores na mesma Casa (seria a mesma
-- pessoa com dois assentos). COALESCE-unique nao serve aqui: identidade_id NULL e' o estado normal de
-- quem ainda nao tem acesso, e varios NULL devem coexistir -> indice PARCIAL (WHERE NOT NULL).
CREATE UNIQUE INDEX IF NOT EXISTS idx_vereador_identidade_unica
  ON cadastros.vereador (ente_id, identidade_id)
  WHERE identidade_id IS NOT NULL;
```

`.down.sql`:

```sql
DROP INDEX IF EXISTS cadastros.idx_vereador_identidade_unica;
```

Em `cadastros/db/vereador.clj`:

```clojure
(defn ligar-identidade!
  "Liga o vereador a' identidade (GUARD ref — sem FK cross-schema, §22.10). ente_id no WHERE alem da RLS
  (defesa em profundidade, padrao do atualizar!). Idempotente. Devolve o count de linhas afetadas."
  [tx ente-id id identidade-id]
  (:next.jdbc/update-count
   (jdbc/execute-one! tx
     (sql/format {:update :cadastros.vereador
                  :set {:identidade_id identidade-id}
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))
```

Em `cadastros/components/repositorio.clj` — protocolo + impl:

```clojure
  (ligar-identidade! [this ente-id id identidade-id]
    "Liga vereador -> identidade. Passo (2) do provisionamento; NAO concede acesso (spec §4.2).")
```
```clojure
  (ligar-identidade! [this ente-id id identidade-id]
    (transacao this ente-id #(db-ver/ligar-identidade! % ente-id id identidade-id)))
```

Em `cadastros/diplomat/http/in.clj` — handler + rota (a `defn rotas` passa a receber `identidade-existe?`):

```clojure
(defn- ligar-identidade-handler
  "PATCH /cadastros/vereadores/:id/identidade. Gated `admin_ente` (nao `secretario`): ligar identidade e'
  parte de conceder acesso. `identidade-existe?` e' guard de SERVICO injetado pelo host — cadastros nunca
  importa identidade (§22.10), e nao ha' FK cross-schema p/ garantir a ref.
  Conflito (identidade ja' ligada a outro vereador nesta Casa) -> 409 LOCAL, nunca 500."
  [repo identidade-existe?]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (parse-uuid (get-in req [:path-params :id]))
          corpo (:json-params req)
          ident (some-> (get corpo "identidade-id") parse-uuid)]
      (cond
        (nil? id) (http/json-resposta 404 {:erro "vereador nao encontrado"})
        (nil? ident) (http/json-resposta 400 {:erro "identidade-id invalido"})
        (not (identidade-existe? ident)) (http/json-resposta 404 {:erro "identidade nao encontrada"})
        :else
        (try
          (if (pos? (repo-cad/ligar-identidade! repo ente-id id ident))
            (http/json-resposta 200 {:id (str id) :identidade-id (str ident)})
            (http/json-resposta 404 {:erro "vereador nao encontrado"}))
          (catch org.postgresql.util.PSQLException e
            (if (= "23505" (.getSQLState e))
              (http/json-resposta 409 {:erro "identidade ja vinculada a outro vereador nesta Casa"})
              (throw e))))))))
```

Rota (dentro da `defn rotas`, que agora desestrutura `identidade-existe?`):

```clojure
      ["/cadastros/vereadores/:id/identidade" :patch
       [auth (it/exige-papel "admin_ente") it/corpo-json
        (ligar-identidade-handler repo-cadastros identidade-existe?)]
       :route-name :cadastros/ligar-identidade]
```

Em `rotas.clj`, no `let` de `montar` (mesma forma do `info-ente`):

```clojure
        ;; Guard de servico: cadastros NUNCA importa identidade (§22.10). O host injeta a existencia.
        identidade-existe? (or identidade-existe?
                               (fn [ident-id] (some? (repo-identidade-comp/identidade-por-id repo-identidade ident-id))))
```
e passar `:identidade-existe? identidade-existe?` no `cadastros-http/rotas`. Adicionar `identidade-existe?` à desestruturação de `montar`.

- [ ] **Step 4: Rodar para ver passar**

Run: `docker run ... --focus oplenario.cadastros.vereador-http-in-test`
Expected: PASS (todos, incluindo os pré-existentes)

- [ ] **Step 5: Commit**

```bash
git add apps/backend/resources/migrations/20260714000060-* \
        apps/backend/src/oplenario/cadastros/ apps/backend/src/oplenario/rotas.clj \
        apps/backend/test/integration/oplenario/cadastros/vereador_http_in_test.clj
git commit -m "feat(cadastros): liga vereador->identidade (guard de servico, unique parcial)"
```

---

## Task 10: O teste do fail-closed escalonado — o coração da fatia

**Files:**
- Create: `apps/backend/test/integration/oplenario/identidade/fail_closed_test.clj`

**Contexto:** a spec §4.2 afirma que **todo estado intermediário é fail-closed**. Afirmação de segurança precisa de **teste**, não de comentário. Este teste percorre os 3 passos parando em cada um.

- [ ] **Step 1: Escrever o teste (que já deve passar — é caracterização de segurança)**

```clojure
(ns oplenario.identidade.fail-closed-test
  "A afirmacao de seguranca da spec §4.2: 'acesso por ultimo' — parar em QUALQUER ponto do provisionamento
  deixa o sistema FECHADO. Se este teste cair, o fluxo virou fail-OPEN e alguem entra antes da hora."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo]))

(deftest parar-apos-criar-identidade-nao-loga
  (let [ente (random-uuid) r (repo-identidade)
        ident (repo/criar-identidade! r {:id (random-uuid) :cpf "52998224725" :nome "Helena Matos"})]
    (is (nil? (auten/resolver-sessao r {:identidade-id ident :ente-id ente}))
        "PASSO 1 so': identidade existe, vinculo NAO -> resolver-sessao nil -> ninguem entra")))

(deftest parar-apos-ligar-vereador-nao-loga
  (let [ente (random-uuid) r (repo-identidade) rc (repo-cadastros)
        ident (repo/criar-identidade! r {:id (random-uuid) :cpf "52998224725" :nome "Helena Matos"})
        vid (:id (repo-cad/criar-vereador! rc ente {:id (random-uuid) :ente-id ente :nome "Helena Matos"}))]
    (repo-cad/ligar-identidade! rc ente vid ident)
    (is (nil? (auten/resolver-sessao r {:identidade-id ident :ente-id ente}))
        "PASSOS 1+2: cadastro ligado, acesso NAO concedido -> ainda nil -> ainda ninguem entra")))

(deftest so-apos-conceder-acesso-loga
  (let [ente (random-uuid) r (repo-identidade)
        ident (repo/criar-identidade! r {:id (random-uuid) :cpf "52998224725" :nome "Helena Matos"})]
    (repo/conceder-acesso! r ente {:id (random-uuid) :ente-id ente :identidade-id ident
                                   :tipo "vereador" :estado "ativo"} ["vereador"])
    (let [ator (auten/resolver-sessao r {:identidade-id ident :ente-id ente})]
      (is (some? ator) "PASSO 3: a porta abre — e SO' aqui")
      (is (= "vereador" (:tipo-vinculo ator)))
      (is (= #{"vereador"} (:papeis ator))))))

(deftest vinculo-suspenso-fecha-a-porta-na-hora
  (let [ente (random-uuid) r (repo-identidade)
        ident (repo/criar-identidade! r {:id (random-uuid) :cpf "52998224725" :nome "Helena Matos"})
        {:keys [vinculo-id]} (repo/conceder-acesso! r ente {:id (random-uuid) :ente-id ente
                                                            :identidade-id ident :tipo "vereador"
                                                            :estado "ativo"} ["vereador"])]
    (repo/mudar-estado-vinculo! r ente vinculo-id "suspenso")
    (is (nil? (auten/resolver-sessao r {:identidade-id ident :ente-id ente}))
        "authz viva: resolver-sessao roda a cada request — suspender derruba na hora, nao espera o cookie expirar")))

(deftest mesmo-cpf-duas-casas-nao-vaza-poder
  (let [casa-a (random-uuid) casa-b (random-uuid) r (repo-identidade)
        ident (repo/criar-identidade! r {:id (random-uuid) :cpf "52998224725" :nome "Helena Matos"})]
    ;; disc.1 (§22.5.3): mesmo CPF, uma identidade, dois vinculos. Vereadora em A, cidada em B.
    (repo/conceder-acesso! r casa-a {:id (random-uuid) :ente-id casa-a :identidade-id ident
                                     :tipo "vereador" :estado "ativo"} ["vereador"])
    (let [em-a (auten/resolver-sessao r {:identidade-id ident :ente-id casa-a})
          em-b (auten/resolver-sessao r {:identidade-id ident :ente-id casa-b})]
      (is (= #{"vereador"} (:papeis em-a)) "poderes em A")
      (is (nil? em-b)
          "MESMA identidade, ZERO poder em B — 'vereador acessando como cidadao no mesmo CPF nao traz
           consigo poderes de vereador' (§22.5.2 eixo D)"))))
```

**Nota:** reusar os helpers de fixture de `db_test.clj` (`repo-identidade`, e o de cadastros de `marco_m1_test.clj`). Se `criar-vereador!` tiver outra assinatura, ajustar — não inventar.

- [ ] **Step 2: Rodar**

Run: `docker run ... --focus oplenario.identidade.fail-closed-test`
Expected: PASS (5 testes). **Se qualquer um falhar, o fluxo é fail-open — parar e corrigir o desenho, não o teste.**

- [ ] **Step 3: Commit**

```bash
git add apps/backend/test/integration/oplenario/identidade/fail_closed_test.clj
git commit -m "test(identidade): prova o fail-closed escalonado + cross-tenant do provisionamento"
```

---

## Task 11: Frontend — conceder acesso pela tela

**Files:**
- Create: `apps/frontend/src/lib/use-conceder-acesso.ts`
- Create: `apps/frontend/src/app/(interno)/cadastros/vereadores/conceder-acesso-form.tsx`
- Modify: `apps/frontend/src/app/(interno)/cadastros/vereadores/page.tsx` (ou a ficha)
- Test: `apps/frontend/src/lib/use-conceder-acesso.test.ts`

**Contexto:** o front **orquestra** os 3 passos (§22.10:26). Espelhar `use-registrar-mandato.ts` (estados `"ocioso" | "enviando" | "erro"`).

- [ ] **Step 1: Escrever o teste que falha**

```typescript
import { describe, expect, it, vi } from "vitest";
import { concederAcesso } from "./use-conceder-acesso";

describe("concederAcesso — a ordem é a garantia de segurança", () => {
  it("chama os 3 passos na ordem, acesso por último", async () => {
    const chamadas: string[] = [];
    const fetchFake = vi.fn(async (url: string) => {
      chamadas.push(url);
      return { ok: true, json: async () => ({ "identidade-id": "id-1" }) } as Response;
    });
    await concederAcesso(
      { vereadorId: "v-1", cpf: "52998224725", nome: "Helena Matos", email: "h@c.local" },
      fetchFake,
    );
    expect(chamadas).toEqual([
      "/api/identidade/identidades",
      "/api/cadastros/vereadores/v-1/identidade",
      "/api/identidade/acessos",
    ]);
  });

  it("para no 1º erro e não concede acesso", async () => {
    const chamadas: string[] = [];
    const fetchFake = vi.fn(async (url: string) => {
      chamadas.push(url);
      if (url.includes("cadastros")) return { ok: false, status: 409, json: async () => ({}) } as Response;
      return { ok: true, json: async () => ({ "identidade-id": "id-1" }) } as Response;
    });
    await expect(
      concederAcesso({ vereadorId: "v-1", cpf: "52998224725", nome: "H", email: "h@c.local" }, fetchFake),
    ).rejects.toThrow();
    expect(chamadas).not.toContain("/api/identidade/acessos");
  });
});
```

- [ ] **Step 2: Rodar para ver falhar**

Run: `docker compose exec frontend npx vitest run src/lib/use-conceder-acesso.test.ts`
Expected: FAIL — módulo não existe

- [ ] **Step 3: Implementar**

```typescript
// A ORDEM é a garantia de segurança (spec §4.2 "acesso por último"): parar em qualquer ponto deixa o
// sistema fechado, porque o login exige vínculo ativo e ele nasce no passo 3. Não reordenar.
// O front orquestra porque §22.10:26 proíbe módulo backend orquestrador sem verdade própria.
export async function concederAcesso(
  entrada: { vereadorId: string; cpf: string; nome: string; email: string },
  fetchFn: typeof fetch = fetch,
): Promise<{ identidadeId: string }> {
  const post = async (url: string, corpo?: unknown) => {
    const r = await fetchFn(url, {
      method: url.includes("/cadastros/") ? "PATCH" : "POST",
      headers: { "content-type": "application/json" },
      body: corpo ? JSON.stringify(corpo) : undefined,
    });
    if (!r.ok) throw new Error(`falha em ${url} (${r.status})`);
    return r.json();
  };

  // 1. Identidade (idempotente por CPF; não concede nada).
  const { "identidade-id": identidadeId } = await post("/api/identidade/identidades", {
    cpf: entrada.cpf,
    nome: entrada.nome,
  });
  // 2. Liga o cadastro (ainda não concede).
  await post(`/api/cadastros/vereadores/${entrada.vereadorId}/identidade`, {
    "identidade-id": identidadeId,
  });
  // 3. Concede — a porta abre só aqui.
  await post("/api/identidade/acessos", {
    "identidade-id": identidadeId,
    tipo: "vereador",
    papeis: ["vereador"],
    email: entrada.email,
  });
  return { identidadeId };
}
```

O hook `useConcederAcesso` envolve isso com os estados `"ocioso" | "enviando" | "erro"`, espelhando `use-registrar-mandato.ts`. O form (`conceder-acesso-form.tsx`) pede **CPF** e **e-mail institucional**, e só aparece para quem tem `admin_ente`.

- [ ] **Step 4: Rodar para ver passar**

Run: `docker compose exec frontend npx vitest run src/lib/use-conceder-acesso.test.ts`
Expected: PASS

- [ ] **Step 5: Gates + AA**

Run: `docker compose exec frontend npm run lint && npx tsc --noEmit && npm test`
Medir AA nos 2 temas com a skill `independent-accessibility-verification` — **nunca** escrever ratio de memória.

- [ ] **Step 6: Commit**

```bash
git add apps/frontend/src/lib/use-conceder-acesso.ts apps/frontend/src/lib/use-conceder-acesso.test.ts \
        "apps/frontend/src/app/(interno)/cadastros/vereadores/"
git commit -m "feat(fe): conceder acesso ao vereador pela tela (3 passos, acesso por ultimo)"
```

---

## Task 12: Prova ao vivo + revisão de segurança

- [ ] **Step 1: Prova ao vivo**

```bash
cd apps/backend && OPLENARIO_APP_ENV=production docker compose --profile auth up -d
```
(Requer `/etc/hosts`: `127.0.0.1 keycloak` — setup documentado na Slice 2b.)

Percorrer: conceder acesso pela tela → abrir o Mailpit (`http://localhost:8025`) → clicar no convite → **o Keycloak exige o passkey antes de qualquer coisa** → cadastrar → cair logado → `GET /eu` devolve papel `vereador`.

Registrar o que **não** funcionar. A Slice 2 achou 4 gaps de wiring exatamente nesta prova — esperar o mesmo.

- [ ] **Step 2: Suíte inteira + lint**

```bash
docker run ... clojure -M:test --skip :e2e     # sem --skip :keycloak
docker run ... clojure -M:clj-kondo --lint src test
```
Expected: verde, 0/0.

- [ ] **Step 3: Revisão de segurança própria (pedido explícito do Daouda desde a abertura)**

Despachar em paralelo: `ecc:security-reviewer` · `ecc:database-reviewer` · `ecc:clojure-reviewer`.
Foco: **o fail-closed escalonado** (§4.2) · **a fronteira do split de privilégio** (§4.3 — nenhuma tx atravessa os dois níveis) · **a superfície nova sobre CPF** (§8.1 — CPF nunca em log/resposta de erro) · **a escalada `secretario`→`vereador`** (§4.5) · **`papeis-concediveis` não deixa conceder `admin_ente`**.

- [ ] **Step 4: Commit final + atualizar memória**

```bash
git commit -m "docs: fecha Onda D Slice 5 — carries e prova ao vivo"
```
Atualizar `oplenario-proxima-sessao` e `oplenario-fe-execucao`. **Merge → `main` só com aprovação do Daouda.**

---

## Auto-revisão do plano

**Cobertura da spec:** §3 escopo → Tasks 1-11 · §4.1 front orquestra → Task 11 · §4.2 acesso por último → Tasks 8, 10, 11 · §4.3 split de privilégio → Task 8 (supratenant vs tenant separados) · §4.4 adapter → Tasks 1-4 · §4.4 e-mail só no KC → Tasks 4, 8 · §4.5 `admin_ente` → Tasks 8, 9 · §5 bug do CPF → Task 6 · §6 erros → Tasks 6, 8, 9 · §7 verificação → Tasks 10, 12 · §7.1 CI → Task 5 · §8 carries → registrados, não implementados (correto).

**Consistência de tipos:** `convidar!` = `(idp ente-id identidade-id)` nas Tasks 1, 4, 8 ✓ · `conceder-acesso!` = `(this ente-id vinculo papeis)` → `{:vinculo-id}` nas Tasks 7, 8, 10 ✓ · `criar-identidade!` → uuid nas Tasks 7, 8, 10 ✓ · `ligar-identidade!` = `(this ente-id id identidade-id)` → count nas Tasks 9, 10 ✓.

**Riscos conhecidos, nomeados nas tasks:** `criar!` do vínculo muda de retorno (Task 7 manda rodar a suíte e ajustar call sites) · `admin-req!` pode não serializar vetor (Task 4) · `KEYCLOAK_SMTP_HOST` deve ser `mailpit`, não `localhost`, no CI (Task 5) · `buscar-usuario-por-identidade` pode devolver lista (Task 3).
