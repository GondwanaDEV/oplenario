# Brief de abertura — Sessão: Plano de Execução da Engenharia

> **Tipo:** sessão fresca dedicada (não continuar numa janela carregada). **Output:** um plano de
> execução faseado, com dependências e marcos — **não** começar a implementar nesta sessão.
> **Idioma:** português. **Método:** §22.x fechado é lei; não relitigar decisão consolidada.

---

## 1. Objetivo único da sessão

Produzir o **plano de execução da materialização do produto** a partir de tudo que já está
especificado: design completo + arquitetura §22 fechada + esqueleto de backend. O plano deve dizer
**em que ordem construir, o que cada fase entrega, e quais dependências travam o quê** — pronto para
virar trilho de implementação nas sessões seguintes.

## 2. Modelo e effort

- **Modelo: Opus 4.8 (1M context).** O plano é síntese cruzada sobre a SSOT inteira (doc-mestre +
  `arquitetura/` + backend). O 1M permite carregar a fonte sem sumarização lossy.
- **Effort: `high`** para a sessão. Subir para `max` pontualmente nos nós mais espinhosos (contrato
  do resolvedor de fatos do runtime do motor; fronteiras de transação cross-módulo §22.10).
- **Execução depois (não nesta sessão):** mecânico (portar telas→React, codegen tokens→TS) =
  **Sonnet `low`–`medium`**; etapas de carga arquitetural (motor runtime, schema) = **Opus `high`**;
  revisões = agentes **`ecc`** (`clojure-reviewer`, `database-reviewer`, `security-reviewer`).

## 3. Estado de entrada (o que já está pronto)

- **Design: COMPLETO.** 48 telas em `produto/design-system/o-plenario/telas/` (3 públicos, 10
  arquétipos, 3 apostas materializadas), fundação SSOT em `…/sistema/` (`tokens.css` 99 props ·
  `chassi.css` · `tema.js`), guias + `README.md` de handoff. **Nada de design pendente como ausência.**
- **Arquitetura §22: FECHADA** (invariantes, tenancy, contrato core↔IA, dados legislativos, auth,
  sessão plenária, motor de compliance completo §22.7, stack §22.9, monólito §22.10). As 5 fundações
  de infra (cripto/segredos, staging de migração, e-mail in-region, failover de IA, RTO/RPO) fechadas.
- **Backend: esqueleto materializado.** `backend/` (Clojure/Pedestal): 7 contextos de domínio +
  projeções + `admin_sistema`; `compliance` materializado; `motor` dobrado (catálogo no schema
  `motor`); 6 migrations. **Persistência real de produção e orquestração de runtime do motor ainda
  são stub/deferidas** (§22.4.4) — é o coração do que o plano precisa sequenciar.

## 4. Arquivos a carregar (ordem de leitura)

1. `CLAUDE.md` — orientação + estado do cursor (§3).
2. `documento-mestre-camaras.md` — espinha estratégica + §24 (autoridade de versão).
3. `arquitetura/` (todos): `22-3-contrato-core-ia` · `22-4-dados-legislativo` · `22-5-auth` ·
   `22-6-sessao-plenaria` · `22-7-motor-compliance` · `22-9-stack` · `22-10-monolito`.
4. `backend/STRUCTURE.md` + `backend/deps.edn` + `backend/docker-compose.yml` + os READMEs de módulo
   (`cadastros`, `compliance`, `motor`, `identidade`, `sessoes`, `transparencia`, `participacao`) +
   `backend/resources/migrations/` (6 migrations existentes).
5. `produto/design-system/o-plenario/README.md` + `…/sistema/` (a SSOT do front).
6. Contexto de stack: memory `oplenario-stack-backend.md` (§22.9, 10/10 decisões).

## 5. O que o plano deve entregar (formato do output)

- **Fases ordenadas**, cada uma com: objetivo, entregáveis concretos, critério de "feito",
  dependências de entrada/saída, e modelo/effort sugerido para executá-la.
- **Caminho crítico explícito** — o que destrava o quê (ex.: `cadastros` + `jurisdicao_camara` antes
  do `motor/avaliar`; tokens→TS antes de portar telas).
- **Os "seams" já documentados a fechar:** persistência real `motor/db/`; resolvedor de fatos do
  runtime injetado (`motor/avaliar`, §22.5.3 disc.5); tabelas do `cadastros` incl. `jurisdicao_camara`.
- **Front:** geração de tokens/TS a partir de `sistema/` (Malli→TS, §22.9), camada de componentes a
  partir do `chassi.css`, ordem de portar telas por público/fluxo.
- **Riscos e `[GAP]` que continuam abertos** (layout físico do SIM; conteúdo regulatório por tribunal).
- **Marcos de valor demonstrável** (o que dá pra mostrar a um cliente/POC em cada marco).

## 6. Restrições inegociáveis (não driftar)

- Os **10 invariantes** da §22.1; em especial **Invariante 4** (regras de compliance = dados, não
  código) e a **disciplina 5** (DSL/motor declarativo compartilhado — não criar DSLs paralelas).
- **Stack provider-neutral, zero vendor cloud** (§22.9): Clojure/Pedestal, Postgres vanilla +
  outbox, Valkey, k8s self-managed, Keycloak, React/Next self-host, IA híbrida atrás de porta
  vendor-agnóstica. **Não introduzir dependência de vendor.**
- **§22.10:** ports&adapters por módulo, comunicação só HTTP/eventos, schema-por-módulo, **proibido
  cross-schema JOIN** — o plano respeita as fronteiras de bounded-context.
- **Escopo diferido por default** (régua das 4 perguntas, §15): não pré-construir o que não tem
  cliente validado. O plano sequencia o necessário, parqueia o resto.

---

## 7. Prompt de abertura (colar na sessão fresca)

```
Sessão dedicada: montar o PLANO DE EXECUÇÃO da engenharia do O Plenário. NÃO implementar nada
nesta sessão — só produzir o plano faseado.

Leia, nesta ordem: CLAUDE.md (esp. §3 estado do cursor); documento-mestre-camaras.md; todos os
arquivos de arquitetura/; backend/STRUCTURE.md + deps.edn + os READMEs de módulo em
backend/src/oplenario/*/README.md + backend/resources/migrations/; e
produto/design-system/o-plenario/README.md + …/sistema/. O brief completo está em
docs/10-proxima-sessao-plano-execucao.md — siga-o.

Entregue um plano de execução com: fases ordenadas (objetivo, entregáveis, critério de feito,
dependências, modelo/effort por fase), caminho crítico explícito, os seams a fechar (persistência
real do motor, resolvedor de fatos injetado, tabelas do cadastros incl. jurisdicao_camara),
o handoff do front (tokens→TS a partir de sistema/, componentes a partir do chassi.css, ordem de
portar telas), riscos/[GAP] abertos, e marcos de valor demonstrável.

Respeite, sem driftar: os 10 invariantes da §22.1 (esp. Inv. 4 e disciplina 5); stack
provider-neutral zero-vendor (§22.9); fronteiras do §22.10 (sem cross-schema JOIN); escopo diferido
por default. Em conflito com memória de chat antigo, o documento-mestre prevalece. Trabalhe em
português. Pare e pergunte só se algo não der para decidir.
```
