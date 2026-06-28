# docs/12 — Contrato do Resolvedor de Fatos (F2, KEYSTONE)

> **Status:** desenho cravado · 2026-06-27 · branch `f2-resolvedor-fatos`
> **Autoridade de origem:** §22.5.3 (disc.3/5 — funções de relação, resolvedor injetado),
> §22.7.5/§22.7.6 (catálogo/registry do motor), §22.10 (monólito modular — a regra "só HTTP/eventos").
> **Risco:** 🔴 CRÍTICO (`docs/11` §riscos) — o erro propaga p/ motor + auth + tramitação juntos.
> Por isso este contrato é desenhado e **validado end-to-end antes de qualquer fanout** (F3+).

---

## 0. O problema que F2 resolve

O motor de regras (§22.7) está **construído e verde** (`motor/`, 12 testes), mas avalia contra
**fixtures em memória**: `runtime/a-chamada` resolve `populacao`, `membros_da_casa`,
`tribunal_competente`, `remessa_enviada`… lendo mapas de `:estado`/`amb` montados à mão no teste.

As **funções de relação reais** (`cadastros/relacoes/`, `identidade/relacoes/`) já existem (F1) e
leem Postgres pela `tx` do tenant, com a assinatura `(tx & args)` — `data` explícito por último
quando temporal. Cada módulo expõe um mapa `relacoes {nome → fn}`.

**F2 = trocar a resolução-de-fixture pela resolução-real**, sem que o motor importe módulo nenhum
(§22.10). Este documento crava **como**.

---

## 1. As três faces do registry (e por que são três)

| Face | Onde vive | Quem lê | O que garante |
|---|---|---|---|
| **Catálogo de assinaturas** (`motor/catalogo`) | código do motor | type-checker do *save time* | a regra é bem-tipada **antes** de virar `vigente` (Inv. 4) |
| **Registry de fns** (`RegistroFatos`, Component) | host injeta no boot | avaliador em *runtime* | a regra avalia contra o fato **real** do domínio |
| **Builtins** (`motor/runtime`) | código do motor | avaliador em runtime | calendário/aritmética/own-db: puros ou tabelas do schema `motor` |

O **invariante de costura** (a rede de segurança contra o risco CRÍTICO) é **uni-direcional**:
**toda função de relação registrada tem assinatura `:categoria "relacao"` no catálogo, com aridade
de domínio compatível.** Verificado por **assert no boot** do Component — divergência = o sistema
**não sobe** (fail-closed; melhor não-bootar que avaliar errado).

Por que **uni-direcional** (revisão `ecc:architect`, C1): o catálogo é a superfície *declarada* que
o type-check conhece — pode legitimamente conter assinaturas de fatos cujo módulo dono ainda **não
existe** (`votos_favoraveis` é do Plenário/F4; `remessa_enviada` do compliance/F5). Uma regra que os
referencia **tipa** (save-time), mas só **avalia** quando o módulo registra a fn. Exigir bijeção
estrita faria F2 **não bootar** (8 assinaturas, 2 fns). Logo: **fns ⊆ assinaturas** no boot;
assinatura-sem-fn é legal. O caso "regra `vigente` referencia fato sem fn neste deploy" é pego em
**runtime fail-closed** — o resolvedor lança quando o nome não tem fn (§4), nunca avalia errado.

O assert compara **só `:categoria "relacao"`** — os builtins (`hoje`, `prazo_vigente`, … ) e
`parametro_tenant` **nunca** entram no registry de fns (M3) — **e checa a aridade de domínio**
(`count params` vs. a aridade real da fn menos a `tx` injetada), não só o nome (M5).

---

## 2. O split: builtin vs. fato resolvido

`runtime/a-chamada` hoje mistura duas naturezas. F2 separa:

- **Builtin** (fica no motor, in-engine): `hoje`/`agora`, `fim_de`, `proximo_dia_util`,
  `soma_dias_uteis`, `arredonda_cima`, `fracao`, `dias`, `prazo_vigente`, `parametro_tenant`.
  São puros (calendário/aritmética exata) **ou** leem o **próprio schema `motor`**
  (`prazo_vigente` → `motor.prazo_dominio_vigente`; `parametro_tenant` →
  `motor.compliance_regra_tenant`). O motor é dono dessas tabelas — não é cross-módulo.
- **Fato resolvido** (sai do motor, via `RegistroFatos`): tudo com forma de **domínio** —
  `populacao`, `membros_da_casa`, `tribunal_competente`, `remessa_enviada`, `publicado`,
  `é_o_próprio`, `tem_mandato_vigente`, … Resolvido **por nome** pelo registry injetado.

`a-chamada`: se o nome é builtin → resolve in-engine; **senão** → delega ao `:resolver` do `ctx`.

---

## 3. A inversão de dependência (a exceção nomeada ao §22.10)

```
  cadastros/relacoes  ─┐
  identidade/relacoes ─┤   (cada módulo expõe `relacoes {nome→fn}`)
  compliance/...      ─┘
          │  (o HOST — sistema.clj — PODE importar módulos: é a raiz de composição)
          ▼
   RegistroFatos (Component do motor)   ←── injeção no boot, via `using`/merge no host
          │  motor chama `(get fns nome)` — NUNCA importa o módulo
          ▼
   motor/avaliar  →  ctx{:resolver …}  →  a-chamada delega fato por nome
```

**Enquadramento (revisão `ecc:architect`, N8):** isto **não** é uma exceção nova ao §22.10 — é a
materialização da camada `relacoes` que o §22.10 já prevê ("registradas no catálogo do motor"), com
`motor` como **core compartilhado**, não módulo. A regra "só HTTP/eventos" governa **módulo↔módulo**;
`motor → fn-injetada-pelo-host` é outro eixo. O **único** ponto genuinamente novo: a resolução é
**in-process síncrona** (não um hop). Justificativa (a mesma de §22.5.3
disc.5): a avaliação de regra/autorização está no **caminho quente síncrono** de toda requisição —
um hop HTTP por fato seria latência inaceitável e uma falha de rede viraria fail-open de
autorização. O acoplamento é **invertido** (o módulo entrega a fn; o motor não conhece o módulo)
e **mediado pelo host**, então não reintroduz a "bola de pelo": nenhum módulo importa outro,
`kernel`/`motor` não importam módulo. A fronteira é o `RegistroFatos`.

---

## 4. Convenção de invocação (resolve a heterogeneidade de aridade)

Toda fn de relação tem a forma **`(fn tx & args)`** — o resolvedor injeta a `tx` do tenant como
1º argumento; o chamador (motor) passa os `args` de domínio. `data` é um **arg comum do DSL** que o
autor escreve via os builtins `hoje()`/`agora()` (ex.: `tem_mandato_vigente(ator.identidade,
hoje())`, `membros_da_casa(hoje())`) — **não** há injeção implícita de default pela camada-ctx
(M4: §4 e §5 não podem divergir; o explícito vence — casa com a assinatura real `(tx … data)` e
mantém a fn pura/testável). Relações **puras** (`é_o_próprio`) aceitam a `tx` por uniformidade e a
ignoram.

### 4-bis. Reconciliação de aridade: o arg `ente` era artefato de fixture (C2)

O protótipo modelava `ente` como **Registro com campos** (`:populacao`/`:membros`) e os templates
escreviam `populacao(ente)`, `membros_da_casa(ente)`. Na realidade **a Casa é 1:1 com o tenant** —
implícita na `tx` (RLS isola; a query `FROM cadastros.ente` devolve a única linha). As fns reais
**não tomam `ente`**: `populacao [tx]`, `membros_da_casa [tx data]`. Reconciliação cravada:

- **`ente` deixa de ser parâmetro/arg do DSL.** O Registro `Ente` e o seeding implícito
  `amb {"ente" …}` saem do `verificador`/`runtime`.
- As assinaturas do catálogo passam a **espelhar os args de domínio reais**:
  `populacao() → Inteiro`, `membros_da_casa(Data) → Inteiro`,
  `remessa_enviada(Texto, Competencia) → Booleano`, `tribunal_competente() → Texto` (M7).
- Os **templates T1/T2/T4 + negativos N1/N3/N4** são reescritos para a forma sem-`ente`
  (preservando a *intenção* validada no Eixo C — é reconciliação de forma, não de conteúdo).
- **Args opacos** (identidade-id, comissao-id = uuid) ganham um tipo escalar opaco no DSL
  (comparável só por `==`, nunca aritmética/ordem) — cravado na implementação de F2.2.

O resolvedor é **construído por contexto de avaliação**, fechando sobre a `tx` do tenant:

```clojure
;; pseudo — o motor recebe (registro, tx) e devolve o resolver que a-chamada chama
(defn resolver-para [registro tx]
  (fn [nome args]
    (if-let [f (get (:fns registro) nome)]
      (apply f tx args)
      (throw (ex-info "fato sem fn registrada" {:nome nome})))))
```

A **assinatura no catálogo** (`:params`/`:retorno`) é o contrato de tipo (save-time); a **aridade
de runtime** = `(count params)` + a `tx` injetada. O assert de boot (§1) casa os dois conjuntos
de nomes — divergência de aridade/nome é erro de boot, não de runtime.

---

## 5. `policy.check` no MESMO avaliador (disc. 5)

`kernel/autorizacao/check!` já é o seam: recebe `politica (ator recurso → bool)`. Em F2 a política
de um recurso passa a ser **expressão da DSL** avaliada pelo **mesmo** `motor/avaliar`, com o
`ator` e o `recurso` no ambiente e o resolvedor injetado (ex.: `é_autor_de(recurso, ator)` ou
`tem_mandato_vigente(ator.identidade, hoje())`). A mecânica two-layer (grossa no middleware, fina
no domínio) já está em `kernel/autorizacao`; F2 liga a camada fina ao avaliador. **A política
declarativa mora no módulo dono do recurso** (§22.5 eixo E) — o kernel só roda o mecanismo.

> Os **interceptors Pedestal** (middleware grosso na borda HTTP) são **F3** (infra-gated, rotas) —
> F2 entrega a mecânica + a fina ligada ao avaliador, testada direto (sem HTTP).

### 5-bis. Invariante de tenancy do registry (M6)

**Todo fato registrado é leitura tenant-scoped sobre a `tx` do tenant.** Decorre disso:

- **Supratenant é proibido no registry.** O resolvedor fecha sobre **uma** `tx` (RLS/GUC do
  tenant). Fatos supratenant (`identidade`/CPF exigem o role `oplenario_id_resolver`, não o pool
  do tenant) **não entram** como fato resolvido. A única relação transversal de `identidade` hoje
  — `é_o_próprio` — é **pura** (ignora a `tx`), então passa sem violar o invariante.
- **Autorização supratenant** (operador SaaS, token sem `ente_id`) **não** roteia pelo avaliador
  tenant-tx — usa a camada **grossa** (`checar-esfera!`/`exige-papel!` de `kernel/autorizacao`),
  que independe de recurso e de `tx`. `policy.check` fino via motor só roda quando há `tx` de
  tenant (toda ação `:tenant`).
- **Fatos são read-only** (N9): nenhuma fn de relação escreve / emite evento — roda no caminho
  quente síncrono de `avaliar`/`policy.check`. Invariante + lint (a fn de relação só faz `SELECT`).

---

## 6. Fatias de F2 (TDD red→green, review `ecc` por fatia)

| Fatia | Entrega | Modelo/effort |
|---|---|---|
| **F2.1** | `motor/db/` real (Repo-Component, ADR-0001 §3-bis) sobre as 5 tabelas do schema `motor`: `template_compliance`, `compliance_regra_tenant`, `prazo_dominio_vigente`, `calendario_feriado`, `registry_catalogo_versao`. `regras-aplicaveis`, `prazo-vigente`, `parametro-tenant` db-backed. | Opus high |
| **F2.2** | **RegistroFatos** (Component) + `resolver-para` + **assert de costura** catálogo⋈fns no boot + catálogo ganha as assinaturas reais das relações de F1 (ente-less, tipos opacos `IdentidadeId`/`ComissaoId`) + **drop `ente` do DSL** + **rewrite T1/T2/T4/N1/N3/N4** + **runtime split builtin/fato** (forçado: o arity-check da costura exige `populacao()`/`membros_da_casa(Data)` sem `ente`, o que arrasta o split do `a-chamada` — `(:resolver ctx)` p/ fatos, builtins in-engine). **(o contrato — Opus max)** | **Opus max** |
| **F2.3** | `motor/avaliar` exposto no `api.clj` (recebe ds/tx + ente + regra; resolve fatos pelo registry via `resolver-para`) + **builtins db-backed** (`prazo_vigente` → `motor.prazo_dominio_vigente`; `parametro_tenant` → `motor.compliance_regra_tenant`, via RepoMotor — hoje fixture no `:estado`) + **persistência** "sair do atom" = obrigação/avaliação nas tabelas **de runtime** do schema `compliance` (`prazo_dominio_ativo`/`compliance_avaliacao`, migration 0005). **Carry do review F2.2 (N3):** isolar erro por-regra no re-sweep (try/catch que audita `erro_resolucao` p/ a obrigação e segue o lote) é orquestração de runtime = aqui. | Opus high |
| **F2.4** | `policy.check` ligado ao avaliador (política = expressão DSL) — **`motor/politica-dsl`** compila expressão booleana em predicado `(fn [ator recurso]→bool)` p/ `kernel/check!` (mesmo avaliador + registry, disciplina 5). **Lints diferidos p/ F3** (quando houver ação de domínio com `ator` p/ lintar): `dominio.acao(args, ator)` exige `policy.check`; relação só-`SELECT`/tenant-scoped/não-supratenant (carry N2 de F2.2). | Opus high |
| **F2.5** | **E2E (M2 "compliance vivo")**: T1 do TCE-CE avalia com `tribunal_competente`/`populacao` reais do `cadastros` (PG); política de auth com `é_autor_de`/`tem_mandato_vigente`. | Opus high |

**Critério de feito (M2):** o motor sai do atom e avalia contra Postgres; obrigação
materializa→avalia→audita com fatos reais; `policy.check` nega por papel, por relação dinâmica e
por estado.

---

## 7. Decisões cravadas (não relitigar)

1. **Resolvedor in-process síncrono por inversão** — não HTTP, não evento. Exceção **nomeada e
   única** ao §22.10, mediada pelo host. (§3)
2. **Split builtin/fato** — calendário/aritmética/own-`motor`-db ficam no motor; tudo com forma de
   domínio sai pelo `RegistroFatos`. (§2)
3. **`(fn tx & args)`** — `tx` injetada pelo resolvedor; `data` é arg comum; default temporal na
   camada que monta o `ctx`. (§4)
4. **Assert de costura no boot** — catálogo de assinaturas ⋈ registry de fns; divergência =
   fail-closed (não sobe). É a rede contra o risco CRÍTICO. (§1)
5. **`policy.check` reusa `motor/avaliar`** — disciplina 5; política declarativa no módulo dono;
   interceptors HTTP ficam p/ F3. (§5)
6. **Bijeção uni-direcional** — fns ⊆ assinaturas `:relacao` no boot; assinatura-sem-fn é legal
   (fato de módulo futuro); fato-sem-fn em runtime = fail-closed. (§1, C1)
7. **`ente` sai do DSL** — era fixture; a Casa é a `tx`. Catálogo espelha args reais; templates
   reescritos. (§4-bis, C2/M7)
8. **Registry é tenant-scoped + read-only** — supratenant/CPF e escrita proibidos; supratenant
   authz via camada grossa. (§5-bis, M6/N9)

---

## 8. Procedência da revisão

**Contrato (pré-implementação):** revisado por `ecc:architect` (read-only, contra §22.5.3/§22.7/§22.10)
— o gate Opus-max que `docs/11` exige. Achados **C1** (boot não bootava por bijeção estrita) e **C2**
(ArityException no E2E pelo arg `ente`) eram CRÍTICOS reais; reconciliados acima (§1, §4-bis). MAIORES
M3–M7 e MENORES N8–N10 incorporados. Sólidos: import preservado; `prazo_vigente`/`parametro_tenant`
builtins; reuso de `check!` correto.

**F2.2 (pós-implementação):** revisado por `ecc:architect` + `ecc:clojure-reviewer` (suíte verde,
112 testes). **Sem CRÍTICOS** — §22.10 preservado (só o host importa módulo), costura genuinamente
fail-closed e uni-direcional, split builtin/fato e ente-drop completos. **Aplicados:** colisão de nome
entre módulos no host agora **falha** (`fundir-relacoes`, não `merge` silencioso — fail-closed na borda
do registry); fn de relação **variádica** rejeitada com erro explícito (relações = aridade fixa,
crava-se aqui); `resolver-para` captura `:fns` uma vez; snapshot único no re-sweep; teste de **boot do
Component real**. **Diferidos (com dono):** conformidade de **tipo de retorno** das fns → golden no E2E
**F2.5** (N1); enforcement de **tenant-scoped/read-only/supratenant-proibido** (M6/N9) → lint de
**F2.4** (N2); isolamento de erro por-regra no re-sweep → **F2.3** (N3, orquestração de runtime).
