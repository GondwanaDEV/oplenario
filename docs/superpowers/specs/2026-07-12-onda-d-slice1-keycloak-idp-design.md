# Onda D · Slice 1 — Adapter Keycloak real do IdP (login de verdade)

> **Spec de design.** Fecha o buraco de "login real" da Onda D (`docs/13` §4): troca o `idp-dev`
> (confia em qualquer blob JSON como claims) por um `KeycloakIdp` que verifica tokens de verdade.
> Referência canônica de auth: `arquitetura/22-5-auth.md` (§22.5). Em conflito, o SSOT prevalece.
>
> **Data:** 2026-07-12 · **Fase:** Onda D (FE) / F1.4-carry (backend auth vivo) · **Modelo:** Opus high (design);
> implementação = Sonnet medium (piso Sonnet — toca auth/PII/invariante §22.1), Opus só nos nós load-bearing.

---

## 1. Problema

Hoje o seam de autenticação está **construído mas cego**:

- `kernel/components/idp.clj` — protocolo `IdentityProvider` (`verificar-token`, `provisionar-realm!`,
  `criar-usuario!`, `resetar-mfa!`) + schema `Claims`. **Pronto.**
- `kernel/components/idp_dev.clj` — única impl: `verificar-token` decodifica o token como JSON e **confia
  totalmente, sem verificar assinatura**. Provisionamento lança (indisponível em dev). "NUNCA usar em produção."
- `identidade/autenticacao.clj` — `resolver-sessao` (claims verificadas → `ator`). **Pronto, testado contra PG real.**
- `interceptors.clj` — cadeia Pedestal `bearer → idp/verificar-token → resolver-sessao → ator`, fail-closed 401.
  **Pronto.**
- `sistema.clj` — `idp-para` **LANÇA em `production`** ("idp-dev proibido em produção e a impl Keycloak é
  carry F1.4"). O boot de prod está deliberadamente bloqueado.
- `docker-compose.yml` — serviço `keycloak` (quay.io/keycloak/keycloak:26.0.0, `start-dev`) atrás do
  profile `auth`. **Ninguém fala com ele.**

O único componente ausente é a **impl real do `IdentityProvider`**. Tudo a jusante já se apoia nela.

**Fora de escopo (subsistemas distintos, cada um sua fatia futura):**
- **Passkey/WebAuthn** (fator primário do §22.5.1, mas subsistema próprio de enrollment/verificação).
- **Broker gov.br OIDC** (caminho do cidadão; exige credencial real inexistente).
- **IdP do operador** (`admin_sistema`, Keycloak fisicamente separado, hardware-key; `idp_admin.clj` é stub
  vazio, sem endpoints de operador). **Só o IdP de tenant aqui.**
- **Assinatura ICP-Brasil real** (`legislativo/components/assinador_icp.clj` — porta diferente, módulo
  diferente, zero dependência de Keycloak; track próprio).

---

## 2. Objetivo e critério de sucesso

**Objetivo:** um `KeycloakIdp` que implementa os 4 métodos do `IdentityProvider`, injetável no boot
não-dev no lugar do `idp-dev`, com `verificar-token` fazendo verificação criptográfica real por JWKS
sob o modelo realm-por-tenant.

**Sucesso quando:**
1. `verificar-token` aceita **apenas** um JWT RS256 assinado pela chave privada do realm cujo issuer está
   na allowlist, não-expirado, com `aud`/`azp` esperados; qualquer desvio → `nil` (fail-closed 401) — exceto
   erro de INFRA (JWKS/rede indisponível), que **lança** (borda responde 500), conforme o contrato do port.
2. `provisionar-realm!` + `criar-usuario!` criam realm-de-tenant e usuário via admin-API do Keycloak,
   idempotentes; o usuário recebe o claim `identidade-id` (protocol mapper) que o token carrega.
3. Boot em `production`/`staging` injeta `KeycloakIdp` (não lança mais); `dev`/`test` seguem no `idp-dev`.
4. Um token emitido por um realm provisionado por nós, verificado por `verificar-token`, produz claims que
   `resolver-sessao` transforma em `ator` — o caminho ponta-a-ponta fecha contra Keycloak real.
5. Suíte verde: verificação com cobertura exaustiva de borda (unit, keypair sintético); provisionamento
   provado contra o Keycloak do docker (integração gated).

**Invariantes que não podem driftar:** §22.5.3 disciplina 6 (auth ≠ assinatura — não tocar o `assinador`);
disciplina 1 (identidade supratenant ↔ vínculo por tenant — `ente-id` vem do realm verificado, `identidade-id`
do claim); §22.10 (o `kernel` não importa módulo — o `KeycloakIdp` vive no kernel e não conhece `identidade`).

---

## 3. Arquitetura

### 3.1 `KeycloakIdp` como Component com Lifecycle

Diferente do `idp-dev` (defrecord sem estado), o `KeycloakIdp` guarda estado e ganha `component/Lifecycle`:

- **start:** valida config; constrói o cliente admin (obtém/renova o token de service-account do realm
  `master` ou de um realm-admin dedicado); inicializa o cache de JWKS por-issuer (jwks-rsa `JwkProvider`
  com cache + rate-limit).
- **stop:** fecha clientes HTTP; limpa caches.

Vive em `kernel/components/keycloak_idp.clj`. **Não importa nenhum módulo** (§22.10) — só o protocolo do
kernel e libs. `identidade` continua o dono da resolução de sessão; este port só entrega claims + provisiona.

### 3.2 Config (12-factor, padrão de `config.clj`)

Bloco novo em `resources/config.edn`:

```edn
:keycloak {:base-url        "http://localhost:8080"   ; issuer raiz; realms sob <base>/realms/<realm>
           :realm-prefixo   "ente-"                    ; realm = "ente-<ente-id>"
           :audiencia       "oplenario-backend"        ; aud/azp esperado no token
           :admin-realm     "master"                   ; realm do service-account admin
           :admin-client-id "oplenario-provisioner"
           :admin-secret    "dev-provisioner-secret"   ; DEV — rotacionar via secrets-manager em prod
           :jwks-cache-ttl-s 600
           :jwks-rate-limit-por-min 10}
```

Overrides de ambiente em `config/carregar` (mesma mecânica dos blocos `:db`/`:valkey`/`:objeto-store`):
`KEYCLOAK_BASE_URL`, `KEYCLOAK_AUDIENCIA`, `KEYCLOAK_ADMIN_REALM`, `KEYCLOAK_ADMIN_CLIENT_ID`,
`KEYCLOAK_ADMIN_SECRET`. **O secret nunca no EDN de prod** — carry de secrets-manager (§22.9, alinhado ao
carry de CPF-cifra/senha-de-pool da F1).

### 3.3 Boot: `idp-para` ganha o braço real

`sistema.clj` `idp-para` hoje:

```clojure
(if (= "production" (:env config))
  (throw (ex-info "idp-dev proibido em producao e a impl Keycloak e' carry F1.4 ..." ...))
  (idp-dev/idp-dev))
```

Vira (a impl Keycloak deixa de ser carry):

```clojure
(case (:env config)
  ("production" "staging") (keycloak-idp/keycloak-idp config)  ; Component; entra no system-map com Lifecycle
  (idp-dev/idp-dev))                                            ; dev/test
```

**Nuance de wiring:** hoje `idp-para` devolve um record pronto e `sistema-serve` o põe no system-map como
`:idp`. Como o `KeycloakIdp` agora tem Lifecycle, `sistema-serve` precisa iniciá-lo via Component. Decisão:
`idp-para` devolve o **componente não-iniciado** (record com config), e o system-map o inclui como
`:idp` normalmente — o `component/start` do sistema o inicia junto. O `idp-dev` (sem Lifecycle) é inerte ao
start, então o mesmo caminho serve os dois. Sem `using` (o `KeycloakIdp` não depende de outros componentes).

### 3.4 `verificar-token` — o keystone de segurança

Realm-por-tenant ⇒ cada tenant emite tokens com issuer + JWKS próprios. Não há uma única chave para
verificar. Fluxo seguro (a ordem importa):

1. **Decode sem verificar** só para ler `iss` (java-jwt `JWT.decode`, sem `.verify`).
2. **Allowlist de issuer:** rejeita salvo `iss` = `<base-url>/realms/<realm-prefixo><uuid>` (regex + UUID
   válido). Um issuer forjado morre aqui, antes de qualquer confiança.
3. **Verifica contra a chave DAQUELE realm:** JWKS do issuer (cache jwks-rsa, rotação-aware) → verifica
   **RS256** (`Algorithm.RSA256(publicKey, null)` — rejeita estruturalmente `alg:none` e confusão HS256↔RS256)
   + `exp` + `aud`/`azp` = `:audiencia`. Um token que *diz* ser do realm X ainda precisa ser assinado pela
   chave privada de X — é isto que torna o passo 1 seguro.
4. **Deriva `ente-id` do realm VERIFICADO** (`iss` → strip prefixo → UUID), **não** de um claim
   auto-declarado (mais seguro que o `idp-dev`, que confia no claim). `identidade-id` vem do claim
   homônimo (protocol mapper setado em `criar-usuario!`).
5. Devolve as claims na forma que o schema `Claims` + `resolver-sessao` já consomem
   (`{:sub :identidade-id :ente-id :exp}`).

**Contrato de falha (do port, review W2):** token malformado/expirado/assinatura ruim/issuer fora da
allowlist → `nil` (401). Erro de INFRA (JWKS 5xx, timeout, rede) → **lança** (500) — nunca mascarar
degradação como "token inválido".

### 3.5 Provisionamento (admin-API)

- `provisionar-realm! [ente-id]` — cria o realm `ente-<ente-id>` se ausente (idempotente); configura o
  client `:audiencia` (confidential/public conforme o fluxo do FE) + o **protocol mapper** que injeta
  `identidade-id` no access token; política de MFA obrigatório no 1º login (§22.5.2 eixo F) fica declarada
  no realm mas o *enrollment* de passkey é fatia futura.
- `criar-usuario! [ente-id usuario]` — cria o usuário no realm do tenant com o atributo `identidade-id` =
  o id canônico (supratenant, CPF-anchored) da nossa `identidade`; enrollment por e-mail de uso único
  (§22.5.2 eixo F) é o bootstrap — a mecânica de e-mail concreta é carry (sem SMTP, alinhado ao carry F6).
- `resetar-mfa! [ente-id identidade-id]` — remove as credenciais OTP/WebAuthn do usuário via admin-API
  (ato auditado; o audit-log de produto é responsabilidade do chamador, não deste port).

Idempotência em todos: re-provisionar realm/usuário existente não falha nem duplica.

### 3.6 Convenção de realm e claims (decisão a cravar)

- **Nome do realm:** `ente-<ente-id-uuid>`. Um realm por Ente (§22.9 Eixo 6 ponto 2, revisto na v1.45 —
  a citação original desta linha, "§22.5.1 realm-por-tenant", era **âncora fabricada**: a §22.5 não
  menciona realm em nenhum ponto, e a v1.22 do Eixo 6 decidira o oposto. Reconciliado na v1.45, que
  ratifica realm-por-ente pela razão de segurança abaixo e crava o gate de escala). Simples,
  determinístico, sem tabela de mapeamento extra — o issuer já codifica o tenant.
- **`identidade-id` no token:** protocol mapper de atributo de usuário → claim `identidade-id` (nossa
  identidade supratenant). `sub` do Keycloak é o id do usuário no realm (por-realm, não é o nosso).
- **`ente-id`:** **derivado do issuer verificado**, nunca de claim. Fecha o risco de um claim `ente-id`
  auto-declarado atravessar realms.

---

## 4. Biblioteca (Fork A — decidido)

`com.auth0/java-jwt` + `com.auth0/jwks-rsa` (adicionar ao `deps.edn`). Padrão JVM, auditado; jwks-rsa
resolve cache + rotação de chave; `Algorithm.RSA256(publicKey, null)` fecha `alg:none` e confusão de
algoritmo por construção. Alternativa Clojure-nativa (`buddy-sign`) deixaria fetch/cache de JWKS como
DIY — exatamente a parte que não se hand-rola em auth. Admin-API: `clj-http`/o cliente HTTP já em uso, ou
o SDK `org.keycloak/keycloak-admin-client` (avaliar peso na implementação; preferência por HTTP direto +
jsonista para não arrastar o stack Keycloak inteiro).

Versões a fixar no setup (ilustrativas): `com.auth0/java-jwt {:mvn/version "4.4.0"}`,
`com.auth0/jwks-rsa {:mvn/version "0.22.1"}`.

---

## 5. Estratégia de teste (Fork B — decidido: split)

**Verificação (crítico) → unit rápido com keypair RSA sintético, sem rede:**
- Gera um par RSA no teste; expõe a chave pública como um `JwkProvider` injetado (o `KeycloakIdp` recebe o
  provider por inversão de dependência, para o teste não precisar de HTTP). Assina tokens com a privada.
- Casos de borda exaustivos: assinatura válida → claims; assinatura de OUTRA chave → nil; `exp` no passado
  → nil; `alg:none` → nil; token HS256 assinado com a pública como segredo (confusão) → nil; issuer fora
  da allowlist → nil; issuer bem-formado mas realm não-provisionado → nil; `aud` errado → nil; JWKS que
  lança (infra) → **propaga exceção** (não nil).

**Provisionamento → integração gated contra o Keycloak do docker:**
- Novo seletor kaocha `:keycloak` (test-path `test/integration/.../keycloak/`), pulado quando o container
  não está de pé (mesma disciplina de `--skip :e2e`; os testes de integração já exigem PG docker).
- `provisionar-realm!` cria realm → assert existe (idempotente ao rodar 2×); `criar-usuario!` cria usuário
  com atributo → obtém token via password/client-credentials grant → `verificar-token` real → claims
  batem → `resolver-sessao` (com um vínculo seedado no PG) → `ator`. É o teste ponta-a-ponta do sucesso #4.
- `docker-compose.yml`: documentar que o profile `auth` precisa estar up (`docker compose --profile auth up`);
  seed de um client `oplenario-provisioner` no realm `master` para o service-account admin (script/realm-import).

**Regressão:** `http_test.clj` e `autenticacao_test.clj` seguem no `idp-dev` (o caminho dev não muda).
Nenhuma rota existente muda de comportamento.

---

## 6. Arquivos (silhueta)

| Arquivo | Ação |
|---|---|
| `deps.edn` | +`com.auth0/java-jwt`, +`com.auth0/jwks-rsa` (e cliente admin, se não-HTTP-direto) |
| `resources/config.edn` | +bloco `:keycloak` |
| `src/oplenario/config.clj` | +overrides de ambiente `KEYCLOAK_*` |
| `src/oplenario/kernel/components/keycloak_idp.clj` | **novo** — `KeycloakIdp` (Lifecycle + 4 métodos do port) |
| `src/oplenario/sistema.clj` | `idp-para` ganha o braço `production`/`staging` → `keycloak-idp` |
| `test/unit/oplenario/kernel/keycloak_idp_test.clj` | **novo** — verificação, keypair sintético |
| `test/integration/oplenario/keycloak/provisionamento_test.clj` | **novo** — gated, contra docker Keycloak |
| `tests.edn` | +seletor `:keycloak` |
| `docker-compose.yml` / realm-import | seed do client provisioner (comentário/script) |

Nada em `identidade/`, `interceptors.clj`, `rotas.clj` muda — o contrato do port é o mesmo.

---

## 7. Riscos e mitigação

- **Confusão de algoritmo / `alg:none`** — a armadilha clássica de JWT. Mitigado por `Algorithm.RSA256`
  com chave pública (java-jwt recusa qualquer outro alg) + teste dedicado de cada variante.
- **Trust-before-verify no passo 1** — ler `iss` sem verificar é seguro SÓ porque o passo 3 verifica a
  assinatura contra a chave daquele issuer + a allowlist barra issuer forjado. Documentar o porquê no código
  (comentário de invariante) para não ser "simplificado" depois.
- **Infra-como-token-inválido** — o contrato do port exige LANÇAR em erro de infra, não devolver nil.
  Teste explícito.
- **Realm/JWKS por-tenant × cache** — cache com TTL + rate-limit (jwks-rsa) evita martelar o Keycloak a
  cada request e cobre rotação de chave. TTL configurável.
- **Secret do admin no EDN** — DEV só; prod = secrets-manager (carry §22.9, mesmo track de CPF-cifra/senha
  de pool da F1). Marcado `[GAP]` no config.
- **Peso do SDK admin** — preferir HTTP direto + jsonista à dependência `keycloak-admin-client` se o custo
  de árvore de deps for alto; decisão na implementação.

---

## 8. Fora de escopo (recapitulado, não relitigar nesta fatia)

Passkey/WebAuthn enrollment; broker gov.br; IdP do operador (`admin_sistema`); revogação imediata de token
(§22.5.2 eixo D — lista de revogados; carry, o TTL curto cobre a V1); step-up/ICP-Brasil; e-mail real de
enrollment (sem SMTP, carry F6); tela de login do FE (`login`/`entrar-govbr` — fatia FE da Onda D, consome
este backend). Este slice entrega o **substrato de verificação + provisionamento**; as telas e os fatores
adicionais assentam sobre ele.
