# Onda D Slice 5 — Identidade do vereador (Tier 2): acesso real ao sistema

**Data:** 14/07/2026 · **Branch:** `onda-d-slice5-identidade-tier2` · **Fecha:** Marco **MFE-4 "entrada real"**
**SSOT:** §22.5 (`arquitetura/22-5-auth.md`) · §22.9 Eixo 6 (`arquitetura/22-9-stack.md`, **v1.45**) · §22.10 (`arquitetura/22-10-monolito.md`)

---

## 1. Contexto

A Onda D Slice 4 entregou a escrita cadastral de vereadores (Tier 1) e **deferiu explicitamente** esta
fatia (`…slice4-cadastro-escrita-design.md:29-31`):

> **Tier 2 — vínculo de identidade + provisionamento de login:** ligar vereador → identidade (CPF, via o
> split-privilege `oplenario_id_resolver`), provisionar usuário Keycloak + papel, consentimento LGPD. É a
> vertical pesada de segurança; não entra aqui.

Hoje o vereador existe no cadastro com `identidade_id` **NULL** — pessoa cadastrada, mas não é usuário.
Esta fatia liga as pontas e fecha o MFE-4.

**Achado que reduz o tamanho da fatia:** o núcleo já existe e é testado (41 testes de integração em
`identidade`). `criar-identidade!`, `vincular-externa!` (com guarda anti-takeover), `criar-vinculo!`,
`adicionar-papel!` estão prontos; `criar-usuario!`/`provisionar-realm!` do adapter Keycloak são reais e
validados contra o KC 26. **Nenhuma rota HTTP os chama** — só testes e `demo/seed_demo.clj`. A fatia é
**borda + fio + enrollment**, não construção de núcleo.

### 1.1 Duas correções de rumo feitas na abertura (registradas aqui para não se perderem)

**(a) Reconciliação da SSOT — commit `beb6beb`, doc-mestre v1.45.** A materialização da Onda D construiu
**realm-por-Ente** enquanto o §22.9 Eixo 6 (v1.22) decidira **realm único**, e o spec da Slice 1 se
justificou citando *"§22.5.1 realm-por-tenant"* — **âncora fabricada** (a §22.5 não contém a palavra
"realm"). Ratificado realm-por-Ente pela razão de segurança (chave de assinatura por tenant ⇒ `ente_id`
derivado do issuer verificado, não de claim), com **gate de escala novo**: medir o Keycloak antes de
passar de ~100 entes. Esta fatia **depende** dessa ratificação — sem ela, `provisionar-realm!` não teria
lugar na arquitetura.

**(b) O "LGPD" do escopo original sai — a SSOT o proíbe** (`22-5-auth.md:144`):

> Servidor/vereador no exercício da função: base legal é "execução de contrato/exercício regular de função
> pública", **não** consentimento. Coletar consentimento aqui seria erro conceitual com efeito ruim
> (sugere que pode revogar e parar o tratamento, o que não é verdade).

A tabela `consentimento` existe e funciona, mas seus pontos de coleta são todos do **cidadão**. Não é
escolha de escopo — é seguir a SSOT.

---

## 2. Objetivo e critério de sucesso

**Objetivo:** o `admin_ente` concede acesso ao sistema a um vereador já cadastrado; o vereador recebe um
convite por e-mail, cadastra passkey e entra — sem dev-token.

**Sucesso quando:**

1. Vereador com `identidade_id` NULL recebe identidade (CPF), vínculo `vereador`, papel `vereador` e
   usuário no Keycloak, pela UI.
2. O convite chega, o Keycloak **obriga o cadastro do passkey antes de qualquer ação**, e o login
   resultante resolve um ator com papel `vereador` e `vereador-id`.
3. Toda falha parcial no meio do fluxo deixa o sistema **fechado** (ninguém entra).
4. Repetir qualquer passo converge (idempotente) em vez de duplicar.
5. **Os testes `:keycloak` rodam no CI** e falham o build.

---

## 3. Fronteira de escopo

**DENTRO:**
- Borda HTTP de escrita em `identidade` (não existe hoje — só as 3 rotas públicas de auth).
- Rota em `cadastros` para ligar `vereador.identidade_id` (guard ref).
- Ajustes no adapter Keycloak: realm com passkey + SMTP; `criar-usuario!` idempotente com required action;
  `convidar!` novo.
- Papel **`admin_ente`** fiado pela primeira vez.
- Mailpit no `docker-compose` (dev) + Keycloak/Mailpit no CI.
- Tela: "Dar acesso ao sistema" + estado do acesso + "reenviar convite".

**FORA (com motivo, não por conveniência):**
- **Consentimento LGPD** — proibido pela SSOT para vereador (§1.1b).
- **gov.br / cidadão** — outro ator; `vincular-externa!` já existe e não se toca aqui.
- **Cifra de CPF em repouso** — carry pré-prod conhecido (track cripto/segredos). Hoje `cpf text NOT NULL
  UNIQUE` em plaintext, protegido por split de privilégio + gating em app. **Esta fatia não piora nem
  conserta**; ver §8.
- **Servidor / `admin_ente` como sujeitos provisionáveis** — não têm tela de cadastro. Só vereador.
- **Step-up** — não existe mecanismo no sistema; ver §8.
- **Telas de enrollment do passkey** — são do Keycloak.
- **Promoção automática de suplente** — já deferida na Slice 4.

---

## 4. Arquitetura

### 4.1 Quem orquestra — a SSOT decide, e proíbe o óbvio

`arquitetura/22-10-monolito.md:26`:

> A administração do tenant (a câmara administrando a si mesma) é **área de UI** no app, gated pelo papel
> estático `admin_ente`, **compondo endpoints de `identidade`/`cadastros` via HTTP**. Um módulo backend
> orquestrador **sem verdade própria** seria anti-pattern (…) pelo Invariante 5 (core↔apresentação),
> orquestração de tela mora no front.

**Não se cria endpoint "criar vereador com acesso".** O front compõe. `cadastros` é dono do vereador,
`identidade` é dono da identidade e do acesso; nenhum escreve na casa do outro. O DDL já previa:
`identidade_id uuid, -- GUARD ref ao modulo identidade (sem FK cross-schema)`
(`20260620000010-cadastros.up.sql:114`).

### 4.2 O princípio de ordenação: **acesso por último**

Três chamadas ⇒ falha parcial é possível. A ordem garante que **todo estado intermediário é fail-closed**,
porque `resolver-sessao` exige **vínculo ativo** e ele é a última coisa criada:

| # | Chamada | Se parar aqui |
|---|---|---|
| 1 | `POST /identidade/identidades` `{cpf, nome}` → `{identidade-id}` | Identidade órfã. **Não loga** (sem vínculo). Inofensiva. |
| 2 | `PATCH /cadastros/vereadores/:id/identidade` `{identidade-id}` | Cadastro ligado, sem acesso. **Não loga.** |
| 3 | `POST /identidade/acessos` `{identidade-id, tipo, papeis, email}` | Acesso concedido. A porta abre **só aqui**. |

Dentro do passo 3: **banco primeiro, Keycloak depois**. Se o Keycloak falhar após o commit, existe vínculo
sem credencial → ninguém entra → repetir conserta. A inversão (KC primeiro) também seria fail-closed, mas
banco-primeiro mantém a nossa fonte de verdade à frente do sistema externo.

### 4.3 Identidade é supratenant — o que isso implica na borda

O passo 1 roda no caminho supratenant (conexão crua do pool, role efetivo herda `oplenario_id_resolver`);
o passo 3 roda em `com-tenant*` (`SET LOCAL ROLE oplenario_app`, que **perde** o acesso a CPF). São
**transações separadas por construção** — não se inventa transação que atravesse os dois níveis de
privilégio, sob pena de dissolver o split.

**Consequência semântica a nomear:** idempotência por CPF significa que o mesmo CPF vereador em Sobral e em
Fortaleza é **a mesma identidade, com dois vínculos**. É a disciplina 1 (`22-5-auth.md:152`), e é o que
permite papéis distintos por casa sem vazamento de poder — `22-5-auth.md:62`: *"vereador acessando como
cidadão no mesmo CPF não traz consigo poderes de vereador"*.

**Oráculo de existência de CPF (aceito, documentado):** o passo 1 devolve o id existente quando o CPF já
está no sistema, o que confirma a um `admin_ente` que aquele CPF existe *em algum lugar*. Não revela **onde**
(o id é UUID opaco, e vínculos são tenant sob RLS). É consequência direta da disciplina 1, não defeito; e o
atacante precisa **já conhecer o CPF**. Aceito.

### 4.4 Mudanças no `kernel/components/idp.clj` (port) e no adapter Keycloak

O port `IdentityProvider` ganha **`convidar!`**; as outras 3 operações mudam de comportamento:

- **`provisionar-realm!`** — passa a (a) **habilitar a required action de passkey**
  (`webauthn-register-passwordless`; vem **desabilitada** de fábrica no Keycloak) e (b) configurar o
  `smtpServer` do realm. Idempotente como já é.
- **`criar-usuario!`** — passa a ser **get-or-create** (hoje `throw` se `status != 201`) e cria com
  `requiredActions: ["webauthn-register-passwordless"]`. **Sai `emailVerified true`** — aquele `[GAP]`
  (`keycloak_idp.clj:274-277`) existia só porque não havia SMTP; agora há.
- **`convidar!`** — `PUT /admin/realms/{realm}/users/{id}/execute-actions-email` com a required action,
  `client_id`, `redirect_uri` e `lifespan`. Operação **própria** (não dobrada em `criar-usuario!`) porque
  "reenviar convite" é ação de produto separada.
- **`resetar-mfa!`** — inalterada.

**Onde mora o e-mail institucional (decisão, não omissão):** nem `cadastros.vereador` nem
`identidade.identidade` têm coluna de e-mail — verificado no DDL. **E não ganham.** O e-mail é digitado no
formulário de concessão, vai direto para o Keycloak e **mora só lá**; `convidar!` recebe `identidade-id`,
acha o usuário no realm (`?q=identidade-id:` — busca que já existe em `buscar-usuario-por-identidade`) e
manda o Keycloak reenviar. Motivo: o IdP já é dono do e-mail e do ciclo de verificação dele; duplicar do
nosso lado criaria **PII a mais para guardar e sincronizar**, sem ninguém consumindo. Se um dia o produto
precisar exibir/editar o e-mail, ele vem por leitura do IdP, não por cópia.

**O e-mail não sai da nossa aplicação.** Quem envia é o Keycloak; para nós é config de realm. Isto **não é**
o carry F6 (e-mail *da aplicação*: notificação de pauta/prazo) — a confusão está registrada no próprio
código (`keycloak_idp.clj:275`) e é corrigida aqui. Em produção o SMTP aponta para o **relay BR da Fundação
#3 (§22.9 Eixo 12)** — deploy-config, não código.

### 4.5 Papéis: `admin_ente` entra em cena

Hoje só `secretario` e `vereador` estão fiados em rotas; **`admin_ente` nunca foi ligado**, apesar de
decidido em `22-5-auth.md` eixo C. Esta fatia o liga:

- **criar/editar vereador** → segue `secretario` (inalterado, Slice 4).
- **conceder acesso / reenviar convite** → **`admin_ente`**.

SSOT (`22-5-auth.md:16`): *"Identidade institucional, **cadastrada pelo admin do ente** no início da
legislatura."*

**Motivo concreto da separação:** sem ela, um `secretario` mal-intencionado cria um "vereador" com o próprio
e-mail, recebe o convite, cadastra o próprio passkey e **vota**. A separação não elimina o risco (o
`admin_ente` também poderia) — ela **tira a escalada de quem só deveria mexer no cadastro**. A defesa
completa é step-up, que não existe (§8).

---

## 5. Correção de bug encontrada no caminho

`identidade/db/identidade.clj:17` valida CPF por **assertion**: `{:pre [(mod/valido-cpf? cpf)]}`.
Assertions **desaparecem** quando a JVM roda com `*assert*` false / `-da` — configuração de produção
comum. Hoje a única barreira contra CPF inválido no banco pode sumir sem sinal, e **não há CHECK no banco**
(a migration diz *"validacao em app/adapter"*, `…0011:15`).

**Correção:** validação real em `adapters/in` → CPF inválido = **400**, como as demais rotas. A assertion
pode ficar como rede interna, mas deixa de ser a única. Escopo mínimo — não se introduz CHECK de formato no
banco nesta fatia (mudança de DDL em tabela supratenant existente, sem requisito).

---

## 6. Tratamento de erro

| Situação | Resposta |
|---|---|
| CPF inválido (dígito verificador / 11 iguais) | **400** |
| E-mail malformado | **400** |
| Identidade já ligada a **outro** vereador no mesmo ente | **409** |
| Vereador inexistente / de outro tenant | **404** (fail-closed, não vaza existência) |
| Sem papel `admin_ente` | **403** |
| Keycloak fora do ar / erro de infra | **500 — nunca 401** |
| Repetição de passo já feito | **2xx idempotente** |

A linha do 500 é disciplina **já existente** e a fatia a preserva: `verificar-token*` propaga
`NetworkException`/`RateLimitReachedException` deliberadamente (`keycloak_idp.clj:128-135`) e
`mint-handler` tem teste para isso. Problema de infra **não pode se disfarçar de credencial inválida** —
mascarar vira incidente mudo.

---

## 7. Verificação

**TDD red→green por tarefa.** Além dos testes por unidade:

1. **Teste do fail-closed escalonado** (o coração da fatia): percorrer os 3 passos **parando em cada um** e
   afirmar que `resolver-sessao` devolve nil / o login não acontece. A afirmação de segurança da §4.2
   precisa de teste, não de comentário.
2. **Teste cross-tenant**: mesmo CPF, duas Casas — poderes não vazam (estende `marco-m1`).
3. **Idempotência**: repetir cada passo converge, não duplica.
4. **Borda**: 400/403/404/409/500 da §6.
5. **`:keycloak` ao vivo, no CI** (§7.1).
6. **Prova viva manual**: convite no Mailpit → cadastro de passkey → login → ator com papel `vereador`.
   Feita e mostrada; complementa o CI, não o substitui.

### 7.1 Keycloak no CI (decisão Daouda Traore, 14/07)

Hoje `.github/workflows/ci.yml:61` roda `--skip :e2e --skip :keycloak` — **o adapter Keycloak não tem rede
de proteção automática**. Esta fatia fecha isso; o carry não é registrado, é resolvido.

O CI já sobe dependências por `docker compose` (Postgres, MinIO) com espera de health. Somar:

- `docker compose --profile auth up -d postgres minio keycloak mailpit` (o Keycloak já está no compose sob
  o profile `auth`; Mailpit entra nesta fatia).
- Espera de health do Keycloak (`start-dev` leva ~30s; padrão de retry idêntico ao do Postgres/MinIO).
- Remover `--skip :keycloak` da linha de teste.
- `:e2e` **segue skipado** — é outra frente, fora do escopo.

**Custo assumido:** ~30-40s de wall-clock no CI. Aceito — o preço de não ter o keystone de auth sem rede.

---

## 8. Carries (nomeados, não varridos)

1. **CPF em repouso = plaintext.** Carry conhecido (cifra determinística, pré-prod, track cripto/segredos).
   Determinística é **forçada** pela SSOT: `UNIQUE(cpf)` e o match ICP-Brasil (`22-5-auth.md:90`) exigem
   igualdade sobre o ciphertext. Esta fatia **não muda o repouso** — só passa a escrever CPF por uma borda
   HTTP autenticada e gated por `admin_ente`, o que **aumenta a superfície** e reforça a urgência do carry.
2. **Step-up não existe.** `22-5-auth.md:86` lista step-up para "cassar/encerrar mandato", "mudar
   configuração do ente" — conceder acesso é de sensibilidade equivalente e **deveria** exigi-lo. Sem
   mecanismo no sistema, fica papel + auditoria. **Candidato a fatia própria.**
3. **Pool conecta como `oplenario` (o dono), não `oplenario_pool`** (`resources/config.edn:4`). O split de
   privilégio é provado por teste (`db_test.clj:68-74`, com `SET LOCAL ROLE` explícito), mas a **postura de
   runtime** depende de setar `DB_USER`. Carry pré-prod herdado de F1 — fora do escopo, registrado.
4. **Bootstrap do primeiro `admin_ente` — ovo-e-galinha, fora do escopo.** Esta fatia exige `admin_ente`
   para conceder acesso, mas **não há caminho para conceder o primeiro `admin_ente`**: hoje só o
   `demo/seed_demo.clj` insere papéis direto no banco (cobre dev/CI). Em produção o dono disso é o
   **operador SaaS** (`admin_sistema`, §22.10 — quem provisiona o ente também semeia o primeiro admin), e
   esse módulo é **stub de 2 linhas**. Não se resolve aqui — resolver exigiria abrir a área do operador,
   que é frente própria. **Consequência honesta: o MFE-4 fecha com o primeiro `admin_ente` semeado, não
   auto-provisionado.** Nomeado para não virar surpresa na primeira instalação real.
5. **Três decisões de identidade vivem só no código**, sem linha na SSOT: split de privilégio de CPF,
   guarda anti-takeover gov.br, `consentimento` como tabela tenant. Todas boas, nenhuma exigida por
   documento — se alguém as reescrever, nada acusa. **Candidatas a disciplinas 11–13 da §22.5.3.** Fora do
   escopo desta fatia (é trabalho de SSOT, não de código).

---

## 9. Revisão

Revisão de segurança **própria**, como pedido desde a abertura: `ecc:security-reviewer` +
`ecc:database-reviewer` + `ecc:clojure-reviewer`. Foco: o fail-closed escalonado (§4.2), a fronteira do
split de privilégio (§4.3), a superfície nova sobre CPF (§8.1) e a escalada `secretario`→`vereador` (§4.5).
