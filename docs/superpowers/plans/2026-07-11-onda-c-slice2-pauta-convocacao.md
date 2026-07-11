# Onda C · Slice C2 — Pauta/convocação (read puro) · Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: implementar via ultracode Workflow (padrão provado das
> fatias anteriores: impl TDD + revisores `ecc` adversariais + corretor; o orquestrador verifica
> independente), OU superpowers:subagent-driven-development. Steps usam checkbox (`- [ ]`) para tracking.

**Goal:** Entregar `pauta-convocacao` como rota web interna, **read puro**: visualizar a pauta (Expediente
+ Ordem do Dia) de uma sessão já agendada e o artefato de convocação **derivado** (não persistido) — sobre
backend inteiramente pronto, zero fan-out.

**Architecture:** Composição 100% client-side sobre 3 rotas já existentes: `GET /paineis/sli/sessoes`
(lista sessões, gated `secretario`) para escolher a sessão-alvo; `GET /sessoes/:id` + `GET
/sessoes/:id/pauta` para o detalhe; `GET /proposicoes` (já consumida na Onda B) para o rail "prontas fora
da pauta" e para resolver título de itens de pauta do tipo `proposicao`. View-models puros traduzem os 3
payloads em: sessão-alvo, grupos da pauta por fase, título de item, diff do rail, e a convocação derivada.
Nenhuma escrita: o backend não expõe `lock-version` em `GET /sessoes/:id/pauta`, e `PATCH`/`DELETE
.../itens/:item-id` exigem `lock-version` para o CAS otimista — sem o valor atual, o cliente não monta uma
mutação correta (ver `docs/superpowers/specs/2026-07-11-onda-c-slice2-pauta-convocacao-design.md`,
"Correção pós-investigação técnica"). O builder (add/reorder/remove) fica fora desta fatia.

**Tech Stack:** Frontend Next.js 16 (App Router, TS, React 19, vitest). Nenhum trabalho de backend.

## Global Constraints

- **Read puro, zero backend** — nenhuma rota nova, nenhuma migration, nenhum fan-out. As 4 chamadas já
  existem e já são consumidas em produção por outras fatias.
- **Correção de escopo (11/07)** — o builder (add/reorder/remove item da pauta) NÃO faz parte desta fatia:
  `GET /sessoes/:id/pauta` não expõe `lock-version`, exigido por `PATCH`/`DELETE .../itens/:item-id` para o
  CAS. Nenhuma UI de escrita de pauta aqui, nenhum botão que implique mutação.
- **Guard de papel** — esta é a primeira rota `(interno)/*` restrita a um papel específico (`secretario`);
  as demais (`proposicoes`/`tramitacao`/`parecer`) são abertas a qualquer servidor autenticado, sem guard
  client-side. O guard aqui é só UX (mesmo padrão de `GuardVereador` em `(vereador)/layout.tsx`) — a authz
  real já está no backend (403 nas 2 rotas gated).
- **Auth de produção = carry Onda D** — dev-token nesta fatia (mesmo padrão de todas as fatias FE
  anteriores). Não construir login real aqui.
- **Boundary kebab→camel** — todo fetch novo passa por `camelizarChaves` (`boundary.ts`); nunca acessar
  chave kebab-case direto de um `fetch().json()`.
- **`contrato-legislativo.gen.ts` é GERADO — não editar a mão.** Os tipos novos desta fatia (`SessaoOut`,
  `PautaOut`, `PautaItemOut`, camelCase) são hand-rolled em `use-sessao-pauta.ts`, mesmo padrão de
  `SliSessaoOut` em `use-mesa.ts` (a rota `/sessoes` ainda não tem codegen Malli→TS).
- **Verificação por fatia** — `vitest` (view-models + hooks) + `tsc`/`eslint`/`next build` limpos; paridade
  visual lado-a-lado com `pauta-convocacao.html` **recortada ao escopo IN** (sem builder/roster/barra de
  comando) nos 2 temas (`GUIDELINES-CHECKLIST.md`, contraste em pixel composto); revisão `ecc`
  **react-reviewer + security-reviewer**; branch `fe-15-pauta-convocacao`.

## Decisões assumidas (do spec, lean/reversíveis)

1. Sessão-alvo auto-selecionada pela mais próxima `situacao === "agendada"`; sem sessão agendada → estado
   vazio honesto. Troca manual via `<select>` quando há mais de uma agendada.
2. `POST /sessoes` (criar sessão) segue existindo via API/seed — sem UI nesta fatia.
3. Convocação é uma leitura derivada (contagens de itens + data/hora da sessão), não uma entidade
   persistida. Nota de antecedência regimental é texto estático rotulado `[Regimento]` — conteúdo real
   segue `[GAP]`.

---

## File Structure

- Modify: `apps/frontend/src/lib/formatar-data.ts` — adiciona `formatarDiaSemana`.
- Create: `apps/frontend/src/lib/formatar-data.test.ts` — cobre a função nova (o arquivo hoje não tem teste
  próprio).
- Create: `apps/frontend/src/lib/use-sessao-pauta.ts` (+`.test.ts`) — hook + tipos hand-rolled
  `SessaoOut`/`PautaItemOut`/`PautaOut` (camelCase).
- Create: `apps/frontend/src/lib/use-sli-sessoes.ts` (+`.test.ts`) — hook de `GET /paineis/sli/sessoes`.
- Create: `apps/frontend/src/lib/pauta-convocacao-vista.ts` (+`.test.ts`) — view-models puros.
- Create: `apps/frontend/src/app/(interno)/pauta-convocacao/page.tsx` — a página.
- Create: `apps/frontend/src/app/(interno)/pauta-convocacao/pauta-convocacao.css`.
- Modify: `apps/frontend/src/app/(interno)/topo.tsx` — adiciona "Pauta" a `DESTINOS_NAV`.

## Interfaces (contrato entre tasks)

- **`SessaoOut`** (`use-sessao-pauta.ts`): `{ id, sessaoLegislativaId, tipoSessao, numeroSequencial,
  estado, modalidade, delibera, transmitePublica, geraAtaRegimental, permiteVotoSecreto,
  permiteModalidadeRemota, agendadaPara: string|null, abertaEm: string|null, encerradaEm: string|null,
  motivoNaoRealizada: string|null }`.
- **`PautaItemOut`**: `{ id, fase, tipoItem, proposicaoId?, textoDescricao?, ordem }`.
- **`PautaOut`**: `{ sessaoId, itens: PautaItemOut[] }`.
- **`useSessaoPauta(token, sessaoId) -> { sessao: SessaoOut|null, pauta: PautaOut|null, estado }`**.
- **`useSliSessoes(token) -> { sessoes: SliSessaoOut[]|null, estado }`** (`SliSessaoOut` reexportado de
  `use-mesa.ts`).
- **`selecionarSessaoAlvo(sessoes, sessaoIdEscolhida) -> SliSessaoOut|null`**.
- **`agruparPautaPorFase(pauta) -> GrupoPauta[]`** onde `GrupoPauta = { chave, titulo, itens: PautaItemOut[] }`.
- **`resolverTituloItem(item, proposicoesPorId) -> { numero?, rotulo, indisponivel }`**.
- **`derivarProntasForaDaPauta(proposicoes, totalProposicoes, pauta) -> { itens, truncado }`**.
- **`derivarConvocacao(sessao, grupos) -> Convocacao|null`**.

---

## Tasks

### Task 1: `formatarDiaSemana` (util de data compartilhado)

**Files:**
- Modify: `apps/frontend/src/lib/formatar-data.ts`
- Create: `apps/frontend/src/lib/formatar-data.test.ts`

**Interfaces:**
- Produces: `formatarDiaSemana(iso: string) -> string` (ex.: `"2026-06-24T17:00:00Z"` → `"quarta-feira"`).

- [ ] **Step 1: Write failing test**

```ts
import { describe, expect, it } from "vitest";
import { formatarData, formatarDiaSemana, formatarHora } from "./formatar-data";

describe("formatarDiaSemana", () => {
  it("ISO -> nome do dia da semana em pt-BR", () => {
    expect(formatarDiaSemana("2026-06-24T17:00:00Z")).toBe("quarta-feira");
  });

  it("ISO inválido -> devolve a string original (fail-closed, não lança)", () => {
    expect(formatarDiaSemana("não-é-data")).toBe("não-é-data");
  });
});

describe("formatarData/formatarHora (regressão — já existiam)", () => {
  it("continuam formatando dd/mm/aaaa e HH:mm", () => {
    expect(formatarData("2026-06-24T17:00:00Z")).toMatch(/^\d{2}\/\d{2}\/\d{4}$/);
    expect(formatarHora("2026-06-24T17:00:00Z")).toMatch(/^\d{2}:\d{2}$/);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose exec frontend npx vitest run src/lib/formatar-data.test.ts`
Expected: FAIL — `formatarDiaSemana` não exportado por `./formatar-data`.

- [ ] **Step 3: Write minimal implementation**

Append to `apps/frontend/src/lib/formatar-data.ts`:

```ts

// formatarDiaSemana — Onda C Slice C2 (pauta-convocacao): nome do dia da semana por extenso, pt-BR.
const FORMATO_DIA_SEMANA_BR = new Intl.DateTimeFormat("pt-BR", { weekday: "long" });

export function formatarDiaSemana(iso: string): string {
  try {
    return FORMATO_DIA_SEMANA_BR.format(new Date(iso));
  } catch {
    return iso;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `docker compose exec frontend npx vitest run src/lib/formatar-data.test.ts`
Expected: PASS (3 testes).

- [ ] **Step 5: Commit**

```bash
git checkout -b fe-15-pauta-convocacao
git add apps/frontend/src/lib/formatar-data.ts apps/frontend/src/lib/formatar-data.test.ts
git commit -m "feat(fe): formatarDiaSemana em formatar-data.ts (Onda C2)"
```

---

### Task 2: View-model — seleção de sessão-alvo + formatação de tipo/título

**Files:**
- Create: `apps/frontend/src/lib/pauta-convocacao-vista.ts`
- Create: `apps/frontend/src/lib/pauta-convocacao-vista.test.ts`

**Interfaces:**
- Consumes: `SliSessaoOut` (de `./use-mesa`), `SessaoOut` (de `./use-sessao-pauta` — **ainda não existe**;
  para não travar este task no Task 4, declare o tipo `SessaoOut` **localmente neste arquivo** por ora —
  Task 5 fará `use-sessao-pauta.ts` reexportar o mesmo shape e o Task 6 importa de lá. Ver nota no Step 3.
- Produces: `sessoesAgendadas`, `selecionarSessaoAlvo`, `formatarTipoSessao`, `formatarTituloSessao`.

> Nota de sequenciamento: como `use-sessao-pauta.ts` só é criado no Task 5, este Task 2 (e o Task 3) definem
> `SessaoOut`/`PautaItemOut`/`PautaOut` **neste próprio arquivo** (`pauta-convocacao-vista.ts`). O Task 5
> cria os MESMOS shapes em `use-sessao-pauta.ts`; o Task 6 (página) importa `SessaoOut`/`PautaItemOut`/
> `PautaOut` de `use-sessao-pauta.ts` e `pauta-convocacao-vista.ts` os reexporta de lá (edit de 3 linhas no
> Task 5) para não ter 2 definições divergentes do mesmo shape. Isso é sinalizado explicitamente no Task 5.

- [ ] **Step 1: Write failing test**

```ts
import { describe, expect, it } from "vitest";
import { formatarTipoSessao, formatarTituloSessao, selecionarSessaoAlvo, sessoesAgendadas } from "./pauta-convocacao-vista";
import type { SliSessaoOut } from "./use-mesa";
import type { SessaoOut } from "./pauta-convocacao-vista";

function sliSessao(parcial: Partial<SliSessaoOut> & { sessaoId: string }): SliSessaoOut {
  return { estadoAtual: "agendada", situacao: "agendada", agendadaPara: "2026-06-24T17:00:00Z", ...parcial };
}

function sessao(parcial: Partial<SessaoOut> = {}): SessaoOut {
  return {
    id: "s1", sessaoLegislativaId: "sl1", tipoSessao: "ordinaria", numeroSequencial: 15,
    estado: "agendada", modalidade: "presencial", delibera: true, transmitePublica: true,
    geraAtaRegimental: true, permiteVotoSecreto: false, permiteModalidadeRemota: false,
    agendadaPara: "2026-06-24T17:00:00Z", abertaEm: null, encerradaEm: null, motivoNaoRealizada: null,
    ...parcial,
  };
}

describe("sessoesAgendadas / selecionarSessaoAlvo", () => {
  it("filtra só situacao 'agendada' com agendadaPara, ordena por data crescente", () => {
    const sessoes = [
      sliSessao({ sessaoId: "b", agendadaPara: "2026-07-01T00:00:00Z" }),
      sliSessao({ sessaoId: "a", agendadaPara: "2026-06-24T00:00:00Z" }),
      sliSessao({ sessaoId: "c", situacao: "aberta" }),
      sliSessao({ sessaoId: "d", agendadaPara: null }),
    ];
    expect(sessoesAgendadas(sessoes).map((s) => s.sessaoId)).toEqual(["a", "b"]);
  });

  it("sem override -> auto-seleciona a mais próxima", () => {
    const sessoes = [
      sliSessao({ sessaoId: "b", agendadaPara: "2026-07-01T00:00:00Z" }),
      sliSessao({ sessaoId: "a", agendadaPara: "2026-06-24T00:00:00Z" }),
    ];
    expect(selecionarSessaoAlvo(sessoes, null)?.sessaoId).toBe("a");
  });

  it("com override válido -> respeita a escolha manual", () => {
    const sessoes = [
      sliSessao({ sessaoId: "b", agendadaPara: "2026-07-01T00:00:00Z" }),
      sliSessao({ sessaoId: "a", agendadaPara: "2026-06-24T00:00:00Z" }),
    ];
    expect(selecionarSessaoAlvo(sessoes, "b")?.sessaoId).toBe("b");
  });

  it("override de id inexistente -> ignora, cai no auto", () => {
    const sessoes = [sliSessao({ sessaoId: "a" })];
    expect(selecionarSessaoAlvo(sessoes, "x")?.sessaoId).toBe("a");
  });

  it("nenhuma sessão agendada -> null", () => {
    expect(selecionarSessaoAlvo([], null)).toBeNull();
  });
});

describe("formatarTipoSessao / formatarTituloSessao", () => {
  it("mapeia os 5 tipos conhecidos (logic/tipos-sessao no backend)", () => {
    expect(formatarTipoSessao("ordinaria")).toBe("Ordinária");
    expect(formatarTipoSessao("extraordinaria")).toBe("Extraordinária");
    expect(formatarTipoSessao("solene")).toBe("Solene");
    expect(formatarTipoSessao("secreta")).toBe("Secreta");
    expect(formatarTipoSessao("especial")).toBe("Especial");
  });

  it("tipo desconhecido -> capitaliza o texto cru (fail-closed, não esconde)", () => {
    expect(formatarTipoSessao("nova_categoria")).toBe("Nova_categoria");
  });

  it("monta o título 'Nª Sessão Tipo'", () => {
    expect(formatarTituloSessao(sessao({ numeroSequencial: 15, tipoSessao: "ordinaria" }))).toBe("15ª Sessão Ordinária");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose exec frontend npx vitest run src/lib/pauta-convocacao-vista.test.ts`
Expected: FAIL — o módulo `./pauta-convocacao-vista` não existe.

- [ ] **Step 3: Write minimal implementation**

Create `apps/frontend/src/lib/pauta-convocacao-vista.ts`:

```ts
// View-model puro da rota pauta-convocacao (Onda C Slice C2 — leitura da pauta + convocação derivada de
// uma sessão agendada; escopo READ PURO, ver
// docs/superpowers/specs/2026-07-11-onda-c-slice2-pauta-convocacao-design.md). Nenhuma função aqui faz
// rede — tudo determinístico sobre os dados já buscados pelos hooks (use-sli-sessoes.ts,
// use-sessao-pauta.ts, use-proposicoes.ts).

import type { SliSessaoOut } from "./use-mesa";

// SessaoOut/PautaItemOut/PautaOut: hand-rolled (mesmo racional de SliSessaoOut em use-mesa.ts — a rota
// /sessoes ainda não tem codegen Malli→TS). Definidos aqui e reexportados por use-sessao-pauta.ts (Task 5)
// para não ter 2 declarações divergentes do mesmo shape.
export interface SessaoOut {
  id: string;
  sessaoLegislativaId: string;
  tipoSessao: string;
  numeroSequencial: number;
  estado: string;
  modalidade: string;
  delibera: boolean;
  transmitePublica: boolean;
  geraAtaRegimental: boolean;
  permiteVotoSecreto: boolean;
  permiteModalidadeRemota: boolean;
  agendadaPara: string | null;
  abertaEm: string | null;
  encerradaEm: string | null;
  motivoNaoRealizada: string | null;
}

export interface PautaItemOut {
  id: string;
  fase: string;
  tipoItem: string;
  proposicaoId?: string;
  textoDescricao?: string;
  ordem: number;
}

export interface PautaOut {
  sessaoId: string;
  itens: PautaItemOut[];
}

// ---------- seleção da sessão-alvo (decisão assumida 1: auto-seleciona a mais próxima "agendada"; troca
// manual se houver mais de uma) ----------

export function sessoesAgendadas(sessoes: SliSessaoOut[]): SliSessaoOut[] {
  return sessoes
    .filter((s) => s.situacao === "agendada" && s.agendadaPara)
    .slice()
    .sort((a, b) => (a.agendadaPara as string).localeCompare(b.agendadaPara as string));
}

export function selecionarSessaoAlvo(sessoes: SliSessaoOut[], sessaoIdEscolhida: string | null): SliSessaoOut | null {
  const agendadas = sessoesAgendadas(sessoes);
  if (agendadas.length === 0) return null;
  if (sessaoIdEscolhida) {
    const escolhida = agendadas.find((s) => s.sessaoId === sessaoIdEscolhida);
    if (escolhida) return escolhida;
  }
  return agendadas[0];
}

// ---------- rótulos de tipo de sessão (os 5 valores fechados de logic/tipos-sessao no backend;
// fail-closed — tipo fora do mapa cai no texto cru capitalizado, nunca é escondido) ----------

const TIPO_SESSAO_ROTULO: Record<string, string> = {
  ordinaria: "Ordinária",
  extraordinaria: "Extraordinária",
  solene: "Solene",
  secreta: "Secreta",
  especial: "Especial",
};

export function formatarTipoSessao(tipoSessao: string): string {
  return TIPO_SESSAO_ROTULO[tipoSessao] ?? tipoSessao.charAt(0).toUpperCase() + tipoSessao.slice(1);
}

export function formatarTituloSessao(sessao: SessaoOut): string {
  return `${sessao.numeroSequencial}ª Sessão ${formatarTipoSessao(sessao.tipoSessao)}`;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `docker compose exec frontend npx vitest run src/lib/pauta-convocacao-vista.test.ts`
Expected: PASS (9 testes).

- [ ] **Step 5: Commit**

```bash
git add apps/frontend/src/lib/pauta-convocacao-vista.ts apps/frontend/src/lib/pauta-convocacao-vista.test.ts
git commit -m "feat(fe): view-model puro — seleção de sessão-alvo + formatação de tipo/título (Onda C2)"
```

---

### Task 3: View-model — agrupamento de pauta + título de item + rail + convocação

**Files:**
- Modify: `apps/frontend/src/lib/pauta-convocacao-vista.ts`
- Modify: `apps/frontend/src/lib/pauta-convocacao-vista.test.ts`

**Interfaces:**
- Consumes: `ProposicaoResumoOut` (de `./contrato-legislativo.gen`), `formatarNumeroProposicao` (de
  `./proposicoes-vista`), `PautaOut`/`PautaItemOut`/`SessaoOut` (deste mesmo arquivo, Task 2).
- Produces: `GrupoPauta`, `agruparPautaPorFase`, `TituloItemPauta`, `resolverTituloItem`,
  `indexarProposicoesPorId`, `ProntasForaDaPauta`, `derivarProntasForaDaPauta`, `Convocacao`,
  `derivarConvocacao`.

- [ ] **Step 1: Write failing test**

Append to `apps/frontend/src/lib/pauta-convocacao-vista.test.ts`:

```ts
import {
  agruparPautaPorFase,
  derivarConvocacao,
  derivarProntasForaDaPauta,
  indexarProposicoesPorId,
  resolverTituloItem,
} from "./pauta-convocacao-vista";
import type { PautaItemOut, PautaOut } from "./pauta-convocacao-vista";
import type { ProposicaoResumoOut } from "./contrato-legislativo.gen";

function pautaItem(parcial: Partial<PautaItemOut> & { id: string; fase: string; tipoItem: string; ordem: number }): PautaItemOut {
  return parcial;
}

function proposicao(parcial: Partial<ProposicaoResumoOut> & { id: string }): ProposicaoResumoOut {
  return {
    tipo: "projeto_lei", ano: 2026, sequencial: 1, urnLex: "urn:x", ementa: "Ementa de teste",
    estado: "em_comissoes", atualizadoEm: "2026-01-01T00:00:00Z", ...parcial,
  };
}

describe("agruparPautaPorFase", () => {
  it("pauta nula -> grupos Expediente/Ordem do Dia vazios, sem 'Outras fases'", () => {
    const grupos = agruparPautaPorFase(null);
    expect(grupos.map((g) => g.titulo)).toEqual(["Expediente", "Ordem do Dia"]);
    expect(grupos.every((g) => g.itens.length === 0)).toBe(true);
  });

  it("separa por fase e ordena por 'ordem' dentro do grupo", () => {
    const pauta: PautaOut = {
      sessaoId: "s1",
      itens: [
        pautaItem({ id: "2", fase: "ordem_do_dia", tipoItem: "leitura", ordem: 2 }),
        pautaItem({ id: "1", fase: "expediente", tipoItem: "leitura", ordem: 1 }),
        pautaItem({ id: "3", fase: "ordem_do_dia", tipoItem: "leitura", ordem: 1 }),
      ],
    };
    const grupos = agruparPautaPorFase(pauta);
    expect(grupos.find((g) => g.chave === "ordem-do-dia")?.itens.map((i) => i.id)).toEqual(["3", "2"]);
    expect(grupos.find((g) => g.chave === "expediente")?.itens.map((i) => i.id)).toEqual(["1"]);
  });

  it("fase fora de expediente/ordem_do_dia cai em 'Outras fases' (fail-closed, nunca some em silêncio)", () => {
    const pauta: PautaOut = {
      sessaoId: "s1",
      itens: [pautaItem({ id: "1", fase: "tribuna_livre_cidadao", tipoItem: "leitura", ordem: 1 })],
    };
    const grupos = agruparPautaPorFase(pauta);
    expect(grupos.map((g) => g.titulo)).toContain("Outras fases");
    expect(grupos.find((g) => g.chave === "outras")?.itens[0].id).toBe("1");
  });
});

describe("resolverTituloItem", () => {
  it("tipo 'proposicao' resolvido no índice -> número + ementa", () => {
    const item = pautaItem({ id: "1", fase: "ordem_do_dia", tipoItem: "proposicao", proposicaoId: "p1", ordem: 1 });
    const indice = indexarProposicoesPorId([proposicao({ id: "p1", tipo: "projeto_lei", sequencial: 29, ano: 2026, ementa: "Cria o programa X" })]);
    expect(resolverTituloItem(item, indice)).toEqual({ numero: "PL 29/2026", rotulo: "Cria o programa X", indisponivel: false });
  });

  it("tipo 'proposicao' fora do índice -> rótulo honesto de indisponível, não quebra", () => {
    const item = pautaItem({ id: "1", fase: "ordem_do_dia", tipoItem: "proposicao", proposicaoId: "p-fora", ordem: 1 });
    expect(resolverTituloItem(item, new Map())).toEqual({
      rotulo: "Matéria fora da página carregada de proposições",
      indisponivel: true,
    });
  });

  it("tipo não-'proposicao' -> usa textoDescricao direto", () => {
    const item = pautaItem({ id: "1", fase: "expediente", tipoItem: "leitura", textoDescricao: "Leitura da ata", ordem: 1 });
    expect(resolverTituloItem(item, new Map())).toEqual({ rotulo: "Leitura da ata", indisponivel: false });
  });
});

describe("derivarProntasForaDaPauta", () => {
  it("filtra por estado 'pronta para pauta' e exclui quem já está na pauta atual", () => {
    const props = [
      proposicao({ id: "p1", estado: "aguardando_pauta" }),
      proposicao({ id: "p2", estado: "em_pauta" }),
      proposicao({ id: "p3", estado: "em_comissoes" }),
    ];
    const pauta: PautaOut = { sessaoId: "s1", itens: [pautaItem({ id: "i1", fase: "ordem_do_dia", tipoItem: "proposicao", proposicaoId: "p2", ordem: 1 })] };
    const r = derivarProntasForaDaPauta(props, props.length, pauta);
    expect(r.itens.map((p) => p.id)).toEqual(["p1"]);
    expect(r.truncado).toBe(false);
  });

  it("total maior que a página buscada -> truncado honesto", () => {
    const props = [proposicao({ id: "p1", estado: "aguardando_pauta" })];
    const r = derivarProntasForaDaPauta(props, 150, null);
    expect(r.truncado).toBe(true);
  });
});

describe("derivarConvocacao", () => {
  it("sessão sem agendadaPara -> null (nada a convocar)", () => {
    expect(derivarConvocacao(sessao({ agendadaPara: null }), [])).toBeNull();
  });

  it("conta itens por grupo e monta o título do edital", () => {
    const grupos = agruparPautaPorFase({
      sessaoId: "s1",
      itens: [
        pautaItem({ id: "1", fase: "expediente", tipoItem: "leitura", ordem: 1 }),
        pautaItem({ id: "2", fase: "ordem_do_dia", tipoItem: "leitura", ordem: 1 }),
        pautaItem({ id: "3", fase: "ordem_do_dia", tipoItem: "leitura", ordem: 2 }),
      ],
    });
    const conv = derivarConvocacao(sessao({ numeroSequencial: 15, tipoSessao: "ordinaria" }), grupos);
    expect(conv?.tituloEdital).toBe("Edital de convocação — 15ª Sessão Ordinária");
    expect(conv?.data).toBe("24/06/2026");
    expect(conv?.contagemExpediente).toBe(1);
    expect(conv?.contagemOrdemDoDia).toBe(2);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose exec frontend npx vitest run src/lib/pauta-convocacao-vista.test.ts`
Expected: FAIL — `agruparPautaPorFase`/`resolverTituloItem`/`indexarProposicoesPorId`/
`derivarProntasForaDaPauta`/`derivarConvocacao` não exportados ainda.

- [ ] **Step 3: Write minimal implementation**

Append to `apps/frontend/src/lib/pauta-convocacao-vista.ts` (topo do arquivo, junto aos outros imports):

```ts
import type { ProposicaoResumoOut } from "./contrato-legislativo.gen";
import { formatarData, formatarDiaSemana, formatarHora } from "./formatar-data";
import { formatarNumeroProposicao } from "./proposicoes-vista";
```

E ao final do arquivo:

```ts

// ---------- agrupamento da pauta por fase (os 5 valores fechados de logic/fases-pauta no backend;
// "Outras fases" é o catch-all honesto pras 3 fases fora de Expediente/Ordem do Dia — nunca descarta item
// em silêncio, mesmo princípio da coluna "Outros" de tramitacao-board-vista.ts) ----------

export interface GrupoPauta {
  chave: string;
  titulo: string;
  itens: PautaItemOut[];
}

const FASES_OUTRAS = new Set(["grande_expediente", "explicacoes_pessoais", "tribuna_livre_cidadao"]);

function porOrdem(itens: PautaItemOut[]): PautaItemOut[] {
  return itens.slice().sort((a, b) => a.ordem - b.ordem);
}

export function agruparPautaPorFase(pauta: PautaOut | null): GrupoPauta[] {
  const itens = pauta?.itens ?? [];
  const expediente = itens.filter((i) => i.fase === "expediente");
  const ordemDoDia = itens.filter((i) => i.fase === "ordem_do_dia");
  const outras = itens.filter((i) => FASES_OUTRAS.has(i.fase));
  const grupos: GrupoPauta[] = [
    { chave: "expediente", titulo: "Expediente", itens: porOrdem(expediente) },
    { chave: "ordem-do-dia", titulo: "Ordem do Dia", itens: porOrdem(ordemDoDia) },
  ];
  if (outras.length > 0) {
    grupos.push({ chave: "outras", titulo: "Outras fases", itens: porOrdem(outras) });
  }
  return grupos;
}

// ---------- título de exibição de um item de pauta (tipo "proposicao" resolve via lookup na lista de
// proposições já buscada pro rail — mesma rede, sem 2º round-trip; os demais tipos usam texto_descricao
// direto) ----------

export interface TituloItemPauta {
  numero?: string;
  rotulo: string;
  indisponivel: boolean;
}

export function resolverTituloItem(item: PautaItemOut, proposicoesPorId: Map<string, ProposicaoResumoOut>): TituloItemPauta {
  if (item.tipoItem === "proposicao") {
    const prop = item.proposicaoId ? proposicoesPorId.get(item.proposicaoId) : undefined;
    if (prop) {
      return {
        numero: formatarNumeroProposicao(prop.tipo, prop.sequencial, prop.ano),
        rotulo: prop.ementa,
        indisponivel: false,
      };
    }
    return { rotulo: "Matéria fora da página carregada de proposições", indisponivel: true };
  }
  return { rotulo: item.textoDescricao ?? "—", indisponivel: false };
}

export function indexarProposicoesPorId(proposicoes: ProposicaoResumoOut[]): Map<string, ProposicaoResumoOut> {
  return new Map(proposicoes.map((p) => [p.id, p]));
}

// ---------- rail "Prontas, fora da pauta" — proposições do tenant em estado aguardando pauta que ainda não
// estão em nenhum item ATIVO da pauta atual (diff client-side, sem query nova). `truncado` avisa quando a
// página de /proposicoes buscada (teto 100, o máximo do backend) não cobre o total do tenant — nunca finge
// cobertura completa em silêncio. ----------

const ESTADOS_PRONTAS_PARA_PAUTA = new Set(["em_pauta", "aguardando_pauta"]);

export interface ProntasForaDaPauta {
  itens: ProposicaoResumoOut[];
  truncado: boolean;
}

export function derivarProntasForaDaPauta(
  proposicoes: ProposicaoResumoOut[],
  totalProposicoes: number,
  pauta: PautaOut | null,
): ProntasForaDaPauta {
  const idsNaPauta = new Set(
    (pauta?.itens ?? [])
      .filter((i) => i.tipoItem === "proposicao" && i.proposicaoId)
      .map((i) => i.proposicaoId as string),
  );
  const itens = proposicoes.filter((p) => ESTADOS_PRONTAS_PARA_PAUTA.has(p.estado) && !idsNaPauta.has(p.id));
  return { itens, truncado: totalProposicoes > proposicoes.length };
}

// ---------- convocação — leitura derivada (decisão assumida 3: não é entidade persistida). Contagens por
// grupo + nota de antecedência regimental ESTÁTICA (`[Regimento]`, conteúdo real = [GAP] regulatório,
// mesma disciplina do resto do projeto — nunca crava prazo sem fonte). ----------

export interface Convocacao {
  tituloEdital: string;
  data: string;
  diaSemana: string;
  hora: string;
  contagemExpediente: number;
  contagemOrdemDoDia: number;
}

export function derivarConvocacao(sessao: SessaoOut, grupos: GrupoPauta[]): Convocacao | null {
  if (!sessao.agendadaPara) return null;
  const expediente = grupos.find((g) => g.chave === "expediente")?.itens.length ?? 0;
  const ordemDoDia = grupos.find((g) => g.chave === "ordem-do-dia")?.itens.length ?? 0;
  return {
    tituloEdital: `Edital de convocação — ${formatarTituloSessao(sessao)}`,
    data: formatarData(sessao.agendadaPara),
    diaSemana: formatarDiaSemana(sessao.agendadaPara),
    hora: formatarHora(sessao.agendadaPara),
    contagemExpediente: expediente,
    contagemOrdemDoDia: ordemDoDia,
  };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `docker compose exec frontend npx vitest run src/lib/pauta-convocacao-vista.test.ts`
Expected: PASS (9 + 11 = 20 testes).

- [ ] **Step 5: Run full frontend test suite + typecheck (sanity — nada quebrou em arquivos irmãos)**

Run: `docker compose exec frontend npm run test && docker compose exec frontend npx tsc --noEmit`
Expected: PASS, 0 erros de tipo.

- [ ] **Step 6: Commit**

```bash
git add apps/frontend/src/lib/pauta-convocacao-vista.ts apps/frontend/src/lib/pauta-convocacao-vista.test.ts
git commit -m "feat(fe): view-model puro — agrupamento de pauta + rail + convocação derivada (Onda C2)"
```

---

### Task 4: `use-sli-sessoes.ts`

**Files:**
- Create: `apps/frontend/src/lib/use-sli-sessoes.ts`
- Create: `apps/frontend/src/lib/use-sli-sessoes.test.ts`

**Interfaces:**
- Consumes: `SliSessaoOut` (reexportado de `./use-mesa`), `camelizarChaves` (de `./boundary`).
- Produces: `useSliSessoes(token: string | null) -> { sessoes: SliSessaoOut[] | null, estado }`.

- [ ] **Step 1: Write failing test**

```ts
import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useSliSessoes } from "./use-sli-sessoes";

const sessaoFake = {
  "sessao-id": "s1", "estado-atual": "agendada", situacao: "agendada",
  "agendada-para": "2026-06-24T17:00:00Z", "aberta-em": null, "encerrada-em": null, "duracao-segundos": null,
};

describe("useSliSessoes", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/paineis/sli/sessoes e cameliza -> 'pronto'", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ sessoes: [sessaoFake] }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useSliSessoes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.sessoes).toEqual([
      { sessaoId: "s1", estadoAtual: "agendada", situacao: "agendada", agendadaPara: "2026-06-24T17:00:00Z", abertaEm: null, encerradaEm: null, duracaoSegundos: null },
    ]);
  });

  it("chama a rota com o Bearer token", async () => {
    let headersCapturados: HeadersInit | undefined;
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      headersCapturados = init?.headers;
      return { ok: true, json: async () => ({ sessoes: [] }) } as Response;
    }) as unknown as typeof fetch;
    renderHook(() => useSliSessoes("tok-abc"));
    await waitFor(() => expect(headersCapturados).toBeDefined());
    expect((headersCapturados as Record<string, string>).Authorization).toBe("Bearer tok-abc");
  });

  it("falha de rede -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useSliSessoes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> 'erro' já na primeira renderização, sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useSliSessoes(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose exec frontend npx vitest run src/lib/use-sli-sessoes.test.ts`
Expected: FAIL — o módulo `./use-sli-sessoes` não existe.

- [ ] **Step 3: Write minimal implementation**

Create `apps/frontend/src/lib/use-sli-sessoes.ts`:

```ts
"use client";

// Hook de sessões do tenant (Onda C Slice C2, pauta-convocacao) — mesmo padrão de use-tramitacao-board.ts:
// fetch autenticado de UMA rota (GET /api/paineis/sli/sessoes, já gated ao papel "secretario" no backend),
// camelizarChaves do boundary, estados carregando/pronto/erro, cleanup por `vivo`. Reexporta SliSessaoOut
// de use-mesa.ts (mesmo tipo, evita 2ª definição divergente).

import { useEffect, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { SliSessaoOut } from "./use-mesa";

export type { SliSessaoOut };

type Estado = "carregando" | "pronto" | "erro";

export function useSliSessoes(token: string | null) {
  const [sessoes, setSessoes] = useState<SliSessaoOut[] | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (!token) return;
    let vivo = true;
    (async () => {
      try {
        const r = await fetch("/api/paineis/sli/sessoes", {
          headers: { Authorization: `Bearer ${token}` },
          cache: "no-store",
        });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        const corpo = camelizarChaves(await r.json()) as { sessoes: SliSessaoOut[] };
        if (!vivo) return;
        setSessoes(corpo.sessoes);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token]);

  if (!token) {
    return { sessoes: null, estado: "erro" as Estado };
  }
  return { sessoes, estado };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `docker compose exec frontend npx vitest run src/lib/use-sli-sessoes.test.ts`
Expected: PASS (4 testes).

- [ ] **Step 5: Commit**

```bash
git add apps/frontend/src/lib/use-sli-sessoes.ts apps/frontend/src/lib/use-sli-sessoes.test.ts
git commit -m "feat(fe): hook use-sli-sessoes (Onda C2)"
```

---

### Task 5: `use-sessao-pauta.ts`

**Files:**
- Create: `apps/frontend/src/lib/use-sessao-pauta.ts`
- Create: `apps/frontend/src/lib/use-sessao-pauta.test.ts`
- Modify: `apps/frontend/src/lib/pauta-convocacao-vista.ts` (reexporta os tipos deste hook em vez de manter
  a definição própria — colapsa a duplicação sinalizada no Task 2)

**Interfaces:**
- Produces: `SessaoOut`, `PautaItemOut`, `PautaOut` (fonte única a partir daqui),
  `useSessaoPauta(token, sessaoId) -> { sessao: SessaoOut|null, pauta: PautaOut|null, estado }`.

- [ ] **Step 1: Write failing test**

```ts
import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useSessaoPauta } from "./use-sessao-pauta";

const sessaoFake = {
  id: "s1", "sessao-legislativa-id": "sl1", "tipo-sessao": "ordinaria", "numero-sequencial": 15,
  estado: "agendada", modalidade: "presencial", delibera: true, "transmite-publica": true,
  "gera-ata-regimental": true, "permite-voto-secreto": false, "permite-modalidade-remota": false,
  "agendada-para": "2026-06-24T17:00:00Z", "aberta-em": null, "encerrada-em": null,
  "motivo-nao-realizada": null,
};
const pautaFake = {
  "sessao-id": "s1",
  itens: [{ id: "i1", fase: "expediente", "tipo-item": "leitura", "texto-descricao": "Leitura da ata", ordem: 1 }],
};

function mockFetchPorUrl(mapa: Record<string, unknown>) {
  return vi.fn(async (url: string) => {
    const chave = Object.keys(mapa).find((k) => url.includes(k));
    if (!chave) return { ok: false, status: 404 } as Response;
    return { ok: true, json: async () => mapa[chave] } as Response;
  }) as unknown as typeof fetch;
}

describe("useSessaoPauta", () => {
  afterEach(() => vi.restoreAllMocks());

  it("sessaoId nulo -> 'pronto' com sessao/pauta null, sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useSessaoPauta("tok", null));
    expect(result.current.estado).toBe("pronto");
    expect(result.current.sessao).toBeNull();
    expect(result.current.pauta).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("busca sessão + pauta em paralelo e cameliza -> 'pronto'", async () => {
    global.fetch = mockFetchPorUrl({
      "/api/sessoes/s1/pauta": pautaFake,
      "/api/sessoes/s1": sessaoFake,
    });
    const { result } = renderHook(() => useSessaoPauta("tok", "s1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.sessao?.tipoSessao).toBe("ordinaria");
    expect(result.current.pauta?.itens[0]).toEqual({
      id: "i1", fase: "expediente", tipoItem: "leitura", textoDescricao: "Leitura da ata", ordem: 1,
    });
  });

  it("uma das duas chamadas falha -> 'erro'", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url.includes("/pauta")) return { ok: false, status: 500 } as Response;
      return { ok: true, json: async () => sessaoFake } as Response;
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useSessaoPauta("tok", "s1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("troca de sessaoId volta pra 'carregando' e refaz a busca", async () => {
    global.fetch = mockFetchPorUrl({
      "/api/sessoes/s1/pauta": pautaFake,
      "/api/sessoes/s1": sessaoFake,
      "/api/sessoes/s2/pauta": { "sessao-id": "s2", itens: [] },
      "/api/sessoes/s2": { ...sessaoFake, id: "s2" },
    });
    const { result, rerender } = renderHook(({ id }: { id: string }) => useSessaoPauta("tok", id), {
      initialProps: { id: "s1" },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    rerender({ id: "s2" });
    await waitFor(() => expect(result.current.sessao?.id).toBe("s2"));
  });

  it("sem token -> 'erro' já na primeira renderização, sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useSessaoPauta(null, "s1"));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `docker compose exec frontend npx vitest run src/lib/use-sessao-pauta.test.ts`
Expected: FAIL — o módulo `./use-sessao-pauta` não existe.

- [ ] **Step 3: Write minimal implementation**

Create `apps/frontend/src/lib/use-sessao-pauta.ts`:

```ts
"use client";

// Hook de detalhe da sessão-alvo (Onda C Slice C2, pauta-convocacao) — dado um sessaoId, busca em paralelo
// GET /api/sessoes/:id (SessaoOut) e GET /api/sessoes/:id/pauta (PautaOut). Mesmo padrão de
// use-mesa.ts/use-tramitacao-board.ts: fetch autenticado, camelizarChaves do boundary, estados
// carregando/pronto/erro, cleanup por `vivo`. `sessaoId` nulo (nenhuma sessão agendada, ou ainda
// carregando a lista de sessões) -> estado "pronto" com sessao/pauta null: não é erro, é a ausência
// honesta de sessão-alvo, tratada pela página.

import { useEffect, useState } from "react";
import { camelizarChaves } from "./boundary";

export interface SessaoOut {
  id: string;
  sessaoLegislativaId: string;
  tipoSessao: string;
  numeroSequencial: number;
  estado: string;
  modalidade: string;
  delibera: boolean;
  transmitePublica: boolean;
  geraAtaRegimental: boolean;
  permiteVotoSecreto: boolean;
  permiteModalidadeRemota: boolean;
  agendadaPara: string | null;
  abertaEm: string | null;
  encerradaEm: string | null;
  motivoNaoRealizada: string | null;
}

export interface PautaItemOut {
  id: string;
  fase: string;
  tipoItem: string;
  proposicaoId?: string;
  textoDescricao?: string;
  ordem: number;
}

export interface PautaOut {
  sessaoId: string;
  itens: PautaItemOut[];
}

type Estado = "carregando" | "pronto" | "erro";

export function useSessaoPauta(token: string | null, sessaoId: string | null) {
  const [sessao, setSessao] = useState<SessaoOut | null>(null);
  const [pauta, setPauta] = useState<PautaOut | null>(null);
  const [estado, setEstado] = useState<Estado>(sessaoId ? "carregando" : "pronto");
  const [sessaoIdAnterior, setSessaoIdAnterior] = useState(sessaoId);

  // Reset ao trocar de sessaoId (mesmo padrão de use-proposicoes.ts): reset DURANTE O RENDER, não dentro do
  // useEffect — eslint-plugin-react-hooks v7 (set-state-in-effect) exige isso em vez de um setState
  // síncrono no topo do efeito.
  if (sessaoId !== sessaoIdAnterior) {
    setSessaoIdAnterior(sessaoId);
    setEstado(sessaoId ? "carregando" : "pronto");
    if (!sessaoId) {
      setSessao(null);
      setPauta(null);
    }
  }

  useEffect(() => {
    if (!token || !sessaoId) return;
    let vivo = true;
    (async () => {
      try {
        const [rSessao, rPauta] = await Promise.all([
          fetch(`/api/sessoes/${encodeURIComponent(sessaoId)}`, {
            headers: { Authorization: `Bearer ${token}` },
            cache: "no-store",
          }),
          fetch(`/api/sessoes/${encodeURIComponent(sessaoId)}/pauta`, {
            headers: { Authorization: `Bearer ${token}` },
            cache: "no-store",
          }),
        ]);
        if (!vivo) return;
        if (!rSessao.ok || !rPauta.ok) {
          setEstado("erro");
          return;
        }
        const [corpoSessao, corpoPauta] = await Promise.all([rSessao.json(), rPauta.json()]);
        if (!vivo) return;
        setSessao(camelizarChaves(corpoSessao) as SessaoOut);
        setPauta(camelizarChaves(corpoPauta) as PautaOut);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token, sessaoId]);

  if (!token) {
    return { sessao: null, pauta: null, estado: "erro" as Estado };
  }
  return { sessao, pauta, estado };
}
```

Now collapse the duplicate type definitions in `pauta-convocacao-vista.ts` (Task 2/3 had defined
`SessaoOut`/`PautaItemOut`/`PautaOut` locally as a sequencing workaround). In
`apps/frontend/src/lib/pauta-convocacao-vista.ts`, replace the 3 local `export interface` blocks
(`SessaoOut`, `PautaItemOut`, `PautaOut`) with a single reexport, and adjust the top-of-file import block
to pull them from `./use-sessao-pauta` instead:

```ts
import type { SliSessaoOut } from "./use-mesa";
import type { ProposicaoResumoOut } from "./contrato-legislativo.gen";
import { formatarData, formatarDiaSemana, formatarHora } from "./formatar-data";
import { formatarNumeroProposicao } from "./proposicoes-vista";
import type { PautaItemOut, PautaOut, SessaoOut } from "./use-sessao-pauta";

export type { PautaItemOut, PautaOut, SessaoOut };
```

(Delete the 3 `export interface SessaoOut {…}` / `PautaItemOut {…}` / `PautaOut {…}` blocks that Task 2
added — their fields move to `use-sessao-pauta.ts` verbatim, already written above.)

- [ ] **Step 4: Run tests to verify everything passes**

Run: `docker compose exec frontend npm run test && docker compose exec frontend npx tsc --noEmit`
Expected: PASS — `use-sessao-pauta.test.ts` (5 testes) + `pauta-convocacao-vista.test.ts` (20 testes,
unchanged, still passing against the reexported types) + 0 type errors.

- [ ] **Step 5: Commit**

```bash
git add apps/frontend/src/lib/use-sessao-pauta.ts apps/frontend/src/lib/use-sessao-pauta.test.ts apps/frontend/src/lib/pauta-convocacao-vista.ts
git commit -m "feat(fe): hook use-sessao-pauta + colapsa tipos duplicados em pauta-convocacao-vista (Onda C2)"
```

---

### Task 6: A página + CSS + guard de papel + verificação

**Files:**
- Create: `apps/frontend/src/app/(interno)/pauta-convocacao/page.tsx`
- Create: `apps/frontend/src/app/(interno)/pauta-convocacao/pauta-convocacao.css`
- Modify: `apps/frontend/src/app/(interno)/topo.tsx`

**Interfaces:**
- Consumes: `useAuth` (`@/lib/auth`), `useSliSessoes`, `useSessaoPauta`, `useProposicoes`,
  `selecionarSessaoAlvo`/`sessoesAgendadas`/`agruparPautaPorFase`/`resolverTituloItem`/
  `indexarProposicoesPorId`/`derivarProntasForaDaPauta`/`derivarConvocacao`/`formatarTipoSessao`/
  `formatarTituloSessao` (`@/lib/pauta-convocacao-vista`), `formatarData`/`formatarHora`
  (`@/lib/formatar-data`), `formatarNumeroProposicao` (`@/lib/proposicoes-vista`), `TopoInterno`
  (`../topo`).

- [ ] **Step 1: Modify `topo.tsx` — registra a área nova**

In `apps/frontend/src/app/(interno)/topo.tsx`, add one entry to `DESTINOS_NAV` (after "Expediente"):

```ts
const DESTINOS_NAV = [
  { rotulo: "Painéis da Mesa", href: "/paineis/mesa" },
  { rotulo: "Tramitação", href: "/tramitacao" },
  { rotulo: "Proposições", href: "/proposicoes" },
  { rotulo: "Expediente", href: "/expediente" },
  // Onda C Slice C2 — leitura da pauta de uma sessão agendada + convocação derivada (gated "secretario").
  { rotulo: "Pauta", href: "/pauta-convocacao" },
];
```

- [ ] **Step 2: Create `pauta-convocacao.css`**

Create `apps/frontend/src/app/(interno)/pauta-convocacao/pauta-convocacao.css`:

```css
/* Chrome específico da rota Pauta/convocação (Onda C Slice C2) — portado de
   produto/design-system/o-plenario/telas/pauta-convocacao.html (<style> local). Tokens + chassi vêm de
   globals.css; `.envelope`/`.eyebrow`/`.sr-only`/`.btn` já são globais.

   Escopo desta fatia (READ PURO — ver a correção de escopo no header do plano/spec): porta `.pg-cab`,
   `.faceta`, `.card`, `.grupo`(+`.grupo-cab`), `.pauta-item`(+`.ordem-n`/`.pi-mid`), `.disp`(+`.disp-item`/
   `.di-mid`), `.conv`(ilha-papel + `.edital-cab`/`.antec`/`.dest`), verbatim onde aplicável.

   NÃO PORTADO (fora de escopo — a fatia não tem NENHUMA mutação de pauta): `.tipo-sel` (seletor
   EDITÁVEL de tipo de sessão — vira label estático em `.pg-cab h1`), `.grade-form`/`.campo` (form de
   dados da sessão — vira `<dl>` read-only `.dados-sessao`), `.arrasta` (setas de reordenar — sem
   `lock-version` não há mutação possível), `.pi-rem`/`.disp-add` (botões de remover/adicionar — mutação),
   `.add-mat`/`.add-linha` (adicionar matéria — mutação), `.regs`/`.reg-*` (badges de votação/quórum/
   urgência/regime/adiado — `PautaItemOut` não carrega esse metadado, nada a mostrar sem inventar dado),
   `.roster` (ciência da convocação — sem tabela hoje, ver spec "Escopo OUT"), `.comando` (barra fixa de
   ações — todas as 3 ações da tela-fonte são mutação: pré-visualizar/salvar rascunho/publicar).

   NOVO (não existe na tela-fonte): `.dados-sessao` (substitui `.grade-form` por leitura), `.col-vazia`
   (nota textual de grupo/rail vazio — mesma convenção de tramitacao.css), `.rail-truncado` (aviso honesto
   quando a página de /proposicoes buscada não cobre o total do tenant), `.tela-estado`/`.acesso-restrito`
   (estados de carregando/erro/guard — mesma convenção das demais páginas (interno)). */

/* ---- cabeçalho de página ---- */
.pg-cab { padding: 1.4rem 0 0; display: grid; grid-template-columns: 1fr auto; gap: 0.8rem 1.4rem; align-items: end; }
.pg-cab h1 { font-family: var(--display); font-weight: 800; font-size: var(--t-28); letter-spacing: -0.02em; color: var(--texto); margin: 0.2rem 0 0; }
.pg-cab .leitura { font-size: var(--t-13); color: var(--texto-2); margin: 0.4rem 0 0; max-width: 60ch; }

.faceta { display: inline-flex; align-items: center; gap: 0.4rem; }
.faceta label { font-family: var(--mono); font-size: var(--t-12); letter-spacing: .04em; text-transform: uppercase; color: var(--texto-2); }
.faceta select { font-family: var(--corpo); font-size: var(--t-13); color: var(--texto); background: var(--surface-2); border: 1px solid color-mix(in srgb, var(--texto-2) 55%, var(--linha)); border-radius: var(--raio-sm); padding: 0.5rem 0.6rem; min-height: 40px; cursor: pointer; }

/* ---- balcão (pauta + rail) ---- */
.balcao { display: grid; grid-template-columns: minmax(0,1fr) 360px; gap: 1.5rem; align-items: start; margin: 1.4rem 0 0; }

.card { background: var(--surface); border: 1px solid var(--linha); border-radius: var(--raio); box-shadow: var(--sombra); padding: 1.2rem 1.3rem; }
.card + .card { margin-top: 1.1rem; }
.card > h2 { font-family: var(--display); font-weight: 700; font-size: var(--t-21); color: var(--texto); margin: 0 0 0.2rem; }
.card > .ajuda { font-size: var(--t-13); color: var(--texto-2); margin: 0 0 1.1rem; }

.dados-sessao { display: grid; grid-template-columns: auto 1fr; gap: 0.4rem 0.8rem; margin: 0; }
.dados-sessao dt { font-size: var(--t-12); color: var(--texto-2); }
.dados-sessao dd { font-size: var(--t-13); color: var(--texto); margin: 0; font-weight: 600; }

/* grupos da pauta */
.grupo { margin-top: 0.4rem; }
.grupo + .grupo { margin-top: 1.5rem; }
.grupo-cab { display: flex; align-items: baseline; gap: 0.6rem; border-bottom: 2px solid var(--linha); padding-bottom: 0.5rem; margin-bottom: 0.8rem; }
.grupo-cab h3 { font-family: var(--mono); font-size: var(--t-12); letter-spacing: .1em; text-transform: uppercase; color: var(--acento-texto); margin: 0; }
.grupo-cab .cont { font-family: var(--mono); font-size: var(--t-12); color: var(--texto-2); margin-left: auto; }

.pauta-item { display: grid; grid-template-columns: auto 1fr; gap: 0.6rem 0.8rem; align-items: center; background: var(--surface-2); border: 1px solid var(--linha); border-radius: var(--raio-sm); padding: 0.6rem 0.7rem; }
.pauta-item + .pauta-item { margin-top: 0.55rem; }
.ordem-n { font-family: var(--display); font-weight: 800; font-size: var(--t-16); color: var(--marca); width: 1.5rem; text-align: center; }
.pi-mid { min-width: 0; }
.pi-mid .num { font-family: var(--mono); font-size: var(--t-12); color: var(--acento-texto); }
.pi-mid h4 { font-family: var(--corpo); font-weight: 600; font-size: var(--t-13); color: var(--texto); margin: 0.1rem 0 0; line-height: 1.3; }

.col-vazia { font-size: var(--t-13); color: var(--texto-2); text-align: center; padding: 1rem 0.5rem; margin: 0; }

/* RAIL */
.rail { display: grid; gap: 1.1rem; position: sticky; top: 76px; }

.disp h3, .conv h3 { font-family: var(--mono); font-size: var(--t-12); letter-spacing: .08em; text-transform: uppercase; color: var(--acento-texto); margin: 0 0 0.7rem; }
.disp-item { display: flex; align-items: center; gap: 0.6rem; padding: 0.55rem 0; border-top: 1px solid var(--linha); }
.disp-item:first-of-type { border-top: none; }
.disp-item .di-mid { min-width: 0; flex: 1; }
.disp-item .di-mid .num { font-family: var(--mono); font-size: var(--t-12); color: var(--acento-texto); }
.disp-item .di-mid p { font-size: var(--t-13); color: var(--texto); margin: 0.1rem 0 0; line-height: 1.3; }
.rail-truncado { font-size: var(--t-12); color: var(--texto-2); margin: 0.6rem 0 0; }

/* convocação = ilha-PAPEL (o artefato) */
.conv { background: var(--papel); color: var(--papel-texto); border: 1px solid var(--papel-linha); border-radius: var(--raio); box-shadow: var(--sombra-papel); padding: 1.1rem 1.2rem; }
.conv h3 { color: var(--papel-marca); }
.conv .edital-cab { font-family: var(--display); font-weight: 700; font-size: var(--t-13); color: var(--papel-texto); text-transform: uppercase; letter-spacing: .02em; border-bottom: 1px solid var(--papel-linha); padding-bottom: 0.55rem; margin-bottom: 0.7rem; }
.conv dl { display: grid; grid-template-columns: auto 1fr; gap: 0.4rem 0.8rem; margin: 0; }
.conv dt { font-size: var(--t-12); color: var(--papel-texto-2); }
.conv dd { font-size: var(--t-13); color: var(--papel-texto); margin: 0; font-weight: 600; }
.conv dd .mono { font-family: var(--mono); }
.conv .antec { margin: 0.9rem 0 0; padding: 0.6rem 0.7rem; background: var(--papel-2); border-radius: var(--raio-sm); font-size: var(--t-12); color: color-mix(in srgb, var(--papel-texto) 55%, var(--papel-texto-2)); line-height: 1.45; }
.conv .antec b { color: var(--papel-texto); }
.conv .antec .tag { font-family: var(--mono); font-size: 0.6rem; letter-spacing: .04em; text-transform: uppercase; color: #6E5009; border: 1px solid #B07D14; border-radius: 4px; padding: 0.02rem 0.3rem; }
.conv .dest { margin: 0.8rem 0 0; font-size: var(--t-13); color: var(--papel-texto); }

/* ---- estados de carregando/erro/guard (mesma convenção de tramitacao.css / vereador-shell.css) ---- */
.tela-estado { max-width: 520px; margin: 4rem auto; text-align: center; padding: 2rem; }
.tela-estado h1 { font-family: var(--display); font-size: var(--t-21); color: var(--texto); margin: 0 0 0.5rem; }
.tela-estado p { color: var(--texto-2); }

.acesso-restrito { max-width: 460px; margin: 3rem auto; padding: 1.5rem; text-align: center; }
.acesso-restrito h1 { font-family: var(--display); font-size: var(--t-21); color: var(--texto); }
.acesso-restrito p { font-size: var(--t-13); color: var(--texto-2); margin-top: 0.5rem; }

@media (max-width: 980px) {
  .balcao { grid-template-columns: 1fr; }
  .rail { position: static; top: auto; }
}
@media (max-width: 720px) {
  .pg-cab { grid-template-columns: 1fr; }
}
```

- [ ] **Step 3: Create `page.tsx`**

Create `apps/frontend/src/app/(interno)/pauta-convocacao/page.tsx`:

```tsx
"use client";

// Pauta/convocação (Onda C Slice C2, servidor/secretário) — leitura da pauta (Expediente + Ordem do Dia)
// de uma sessão agendada + o artefato de convocação DERIVADO (não persistido). Escopo READ PURO: o backend
// não expõe `lock-version` em GET /sessoes/:id/pauta, então PATCH/DELETE de item (que exigem
// `lock-version` para o CAS) não podem ser montados corretamente pelo cliente nesta fatia — ver
// docs/superpowers/specs/2026-07-11-onda-c-slice2-pauta-convocacao-design.md, "Correção pós-investigação
// técnica". Nenhuma mutação de pauta aqui.
//
// GUARD DE PAPEL: as rotas consumidas (GET /paineis/sli/sessoes, GET /sessoes/:id[/pauta]) já são gated
// `exige-papel "secretario"` no backend (403 é a authz real). Este componente replica o guard client-side
// só por UX (mesmo padrão de GuardVereador em (vereador)/layout.tsx) — nenhuma outra rota (interno) hoje
// tem esse guard (proposições/tramitação/parecer são abertas a qualquer servidor autenticado); esta é a
// primeira, porque é a primeira rota (interno) restrita a um papel específico.

import { useState } from "react";
import { useAuth } from "@/lib/auth";
import { useSliSessoes } from "@/lib/use-sli-sessoes";
import { useSessaoPauta } from "@/lib/use-sessao-pauta";
import { useProposicoes } from "@/lib/use-proposicoes";
import {
  agruparPautaPorFase,
  derivarConvocacao,
  derivarProntasForaDaPauta,
  formatarTipoSessao,
  formatarTituloSessao,
  indexarProposicoesPorId,
  resolverTituloItem,
  selecionarSessaoAlvo,
  sessoesAgendadas,
} from "@/lib/pauta-convocacao-vista";
import { formatarData, formatarHora } from "@/lib/formatar-data";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { TopoInterno } from "../topo";
import "./pauta-convocacao.css";

export default function PaginaPautaConvocacao() {
  const { token, papeis } = useAuth();
  if (!papeis.includes("secretario")) {
    return (
      <main className="acesso-restrito">
        <h1>Acesso restrito</h1>
        <p>Esta área é exclusiva da secretaria legislativa.</p>
      </main>
    );
  }
  return <ConteudoPautaConvocacao token={token} />;
}

function ConteudoPautaConvocacao({ token }: { token: string | null }) {
  const { sessoes, estado: estadoSessoes } = useSliSessoes(token);
  const [escolhidaId, setEscolhidaId] = useState<string | null>(null);
  const alvo = selecionarSessaoAlvo(sessoes ?? [], escolhidaId);
  const { sessao, pauta, estado: estadoDetalhe } = useSessaoPauta(token, alvo?.sessaoId ?? null);
  const { dados: proposicoesDados, estado: estadoProposicoes } = useProposicoes(token, {
    pagina: 1,
    tamanho: 100,
    ordenarPor: "atualizado_em",
    ordenarDir: "desc",
  });

  if (estadoSessoes === "erro" || estadoDetalhe === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar a pauta</h1>
        <p>Tente novamente em instantes.</p>
      </main>
    );
  }

  const agendadas = sessoesAgendadas(sessoes ?? []);
  const grupos = agruparPautaPorFase(pauta);
  const proposicoesPorId = indexarProposicoesPorId(proposicoesDados?.itens ?? []);
  const rail = derivarProntasForaDaPauta(proposicoesDados?.itens ?? [], proposicoesDados?.total ?? 0, pauta);
  const convocacao = sessao ? derivarConvocacao(sessao, grupos) : null;

  return (
    <>
      <TopoInterno area="Pauta" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
      <main className="envelope">
        <div className="pg-cab">
          <div>
            <span className="eyebrow">Pauta</span>
            <h1>
              {sessao
                ? formatarTituloSessao(sessao)
                : estadoSessoes === "carregando"
                  ? "Carregando…"
                  : "Nenhuma sessão agendada"}
            </h1>
            <p className="leitura">
              Leitura da pauta e da convocação. Montar, reordenar e convocar não fazem parte desta versão.
            </p>
          </div>
          {agendadas.length > 1 && (
            <span className="faceta">
              <label htmlFor="sel-sessao">Sessão</label>
              <select id="sel-sessao" value={alvo?.sessaoId ?? ""} onChange={(e) => setEscolhidaId(e.target.value)}>
                {agendadas.map((s) => (
                  <option key={s.sessaoId} value={s.sessaoId}>
                    {s.agendadaPara ? formatarData(s.agendadaPara) : s.sessaoId}
                  </option>
                ))}
              </select>
            </span>
          )}
        </div>

        {estadoSessoes === "carregando" && <p role="status">Carregando…</p>}

        {estadoSessoes === "pronto" && !alvo && (
          <p className="col-vazia">Nenhuma sessão em estado &quot;agendada&quot; no momento.</p>
        )}

        {alvo && (
          <div className="balcao">
            <section aria-label="Pauta da sessão">
              {sessao && (
                <div className="card">
                  <h2>Dados da sessão</h2>
                  <dl className="dados-sessao">
                    <dt>Tipo</dt>
                    <dd>{formatarTipoSessao(sessao.tipoSessao)}</dd>
                    {sessao.agendadaPara && (
                      <>
                        <dt>Data</dt>
                        <dd>{formatarData(sessao.agendadaPara)}</dd>
                        <dt>Início</dt>
                        <dd>{formatarHora(sessao.agendadaPara)}</dd>
                      </>
                    )}
                  </dl>
                </div>
              )}

              <div className="card">
                <h2>Pauta</h2>
                <p className="ajuda">Leitura da ordem definida. Reordenar, adicionar e remover não fazem parte desta versão.</p>
                {estadoDetalhe === "carregando" && <p role="status">Carregando…</p>}
                {estadoDetalhe === "pronto" &&
                  grupos.map((grupo) => (
                    <div className="grupo" key={grupo.chave}>
                      <div className="grupo-cab">
                        <h3>{grupo.titulo}</h3>
                        <span className="cont">
                          {grupo.itens.length} {grupo.itens.length === 1 ? "item" : "itens"}
                        </span>
                      </div>
                      {grupo.itens.length === 0 && <p className="col-vazia">Nenhum item.</p>}
                      {grupo.itens.map((item) => {
                        const titulo = resolverTituloItem(item, proposicoesPorId);
                        return (
                          <div className="pauta-item" key={item.id}>
                            <span className="ordem-n" aria-hidden="true">
                              {item.ordem}
                            </span>
                            <div className="pi-mid">
                              {titulo.numero && <span className="num">{titulo.numero}</span>}
                              <h4>{titulo.rotulo}</h4>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  ))}
              </div>
            </section>

            <aside className="rail" aria-label="Disponíveis e convocação">
              <div className="card disp">
                <h3>Prontas, fora da pauta</h3>
                {estadoProposicoes === "erro" && <p className="col-vazia">Não foi possível carregar.</p>}
                {estadoProposicoes === "pronto" && rail.itens.length === 0 && (
                  <p className="col-vazia">Nenhuma matéria pronta fora da pauta.</p>
                )}
                {rail.itens.map((p) => (
                  <div className="disp-item" key={p.id}>
                    <div className="di-mid">
                      <span className="num">{formatarNumeroProposicao(p.tipo, p.sequencial, p.ano)}</span>
                      <p>{p.ementa}</p>
                    </div>
                  </div>
                ))}
                {rail.truncado && (
                  <p className="rail-truncado">
                    Mostrando as {proposicoesDados?.itens.length ?? 0} matérias mais recentes de{" "}
                    {proposicoesDados?.total ?? 0}.
                  </p>
                )}
              </div>

              {convocacao && (
                <div className="conv">
                  <h3>Convocação</h3>
                  <div className="edital-cab">{convocacao.tituloEdital}</div>
                  <dl>
                    <dt>Data</dt>
                    <dd>
                      <span className="mono">{convocacao.data}</span> · {convocacao.diaSemana}
                    </dd>
                    <dt>Início</dt>
                    <dd>
                      <span className="mono">{convocacao.hora}</span>
                    </dd>
                  </dl>
                  <p className="antec">
                    <span className="tag">[Regimento]</span> Deve ser publicada com a antecedência mínima
                    prevista no Regimento Interno.
                  </p>
                  <p className="dest">
                    {convocacao.contagemExpediente} no expediente · {convocacao.contagemOrdemDoDia} na ordem
                    do dia.
                  </p>
                </div>
              )}
            </aside>
          </div>
        )}
      </main>
    </>
  );
}
```

- [ ] **Step 4: `tsc`/`eslint`/`next build` limpos**

Run:
```bash
docker compose exec frontend npx tsc --noEmit
docker compose exec frontend npm run lint
docker compose exec frontend npm run build
```
Expected: 0 erros nos 3 comandos.

- [ ] **Step 5: Rodar contra o backend real (docker) com um dev-token de papel `secretario`**

1. `docker compose up -d --build` (raiz de `apps/backend/`, se ainda não estiver de pé).
2. Criar (ou confirmar via seed) ao menos uma sessão em estado `agendada` com `agendada-para` no futuro —
   `POST /sessoes` (API), já que não há UI de criação nesta fatia.
3. Gerar um dev-token com `"papeis":["secretario"]` (mesmo mecanismo de dev-token das fatias anteriores) e
   acessar `http://localhost:3000/pauta-convocacao?token=<...>`.
4. Conferir: a sessão mais próxima aparece selecionada; grupos Expediente/Ordem do Dia mostram os itens
   reais (semeados via `POST /sessoes/:id/pauta/itens` na fixture/seed, se necessário); o rail "Prontas,
   fora da pauta" lista proposições em `aguardando_pauta`/`em_pauta` que não estão na pauta atual; a
   convocação mostra data/hora/contagens corretas.
5. Acessar a mesma URL com um dev-token **sem** papel `secretario` → tela "Acesso restrito".

- [ ] **Step 6: Paridade visual nos 2 temas**

Comparar lado a lado com `produto/design-system/o-plenario/telas/pauta-convocacao.html` **recortado ao
escopo IN** (sem `.tipo-sel` editável, sem `.grade-form`, sem `.arrasta`/`.pi-rem`/`.disp-add`/`.add-mat`,
sem `.regs`, sem `.roster`, sem `.comando`) nos temas claro e escuro. Medir contraste em pixel composto
(`GUIDELINES-CHECKLIST.md`) nos textos sobre `.conv` (ilha-papel) e no chip `.tag` `[Regimento]`.

- [ ] **Step 7: Revisão `ecc` — react-reviewer + security-reviewer**

Rodar os 2 revisores sobre os arquivos desta fatia (`page.tsx`, os 2 hooks, `pauta-convocacao-vista.ts`,
`pauta-convocacao.css`, `topo.tsx`). Aplicar achados CRÍTICO/MAJOR antes do commit final; achados
MEDIUM/MINOR viram carry documentado se não bloqueantes.

- [ ] **Step 8: Commit**

```bash
git add apps/frontend/src/app/\(interno\)/pauta-convocacao/ apps/frontend/src/app/\(interno\)/topo.tsx
git commit -m "feat(fe): rota pauta-convocacao — leitura da pauta + convocação derivada (Onda C2)"
```

---

## Self-Review (cobertura vs. spec `2026-07-11-onda-c-slice2-pauta-convocacao-design.md`)

- ✅ Rota `(interno)/pauta-convocacao` gated papel `secretario` — Task 6 Step 3 (guard inline).
- ✅ Seleção da sessão-alvo (auto + troca manual) — Task 2 (`selecionarSessaoAlvo`) + Task 6 (`<select>`
  condicional a `agendadas.length > 1`).
- ✅ Visualização read-only da pauta (Expediente + Ordem do Dia, ordenada) — Task 3
  (`agruparPautaPorFase`) + Task 6.
- ✅ Rail "Prontas, fora da pauta" informativo (sem ação de adicionar) — Task 3
  (`derivarProntasForaDaPauta`) + Task 6 (nenhum botão `.disp-add` renderizado).
- ✅ Convocação derivada (client-composed, nota `[Regimento]` estática) — Task 3 (`derivarConvocacao`) +
  Task 6.
- ✅ Correção pós-investigação (builder fora de escopo) — nenhum código de mutação em nenhuma task; Global
  Constraints registra o motivo.
- ✅ Escopo OUT do spec (criar/editar sessão, campo "local", roster de ciência, antecedência calculada,
  drag-and-drop, builder) — nenhuma task implementa qualquer um desses; `pauta-convocacao.css` documenta
  cada um em "NÃO PORTADO".
- ⤳ **Achado durante a escrita do plano, fora do spec original mas fiel a ele**: `logic/fases-pauta` no
  backend tem **5** valores (`expediente`, `grande_expediente`, `ordem_do_dia`, `explicacoes_pessoais`,
  `tribuna_livre_cidadao`), não só os 2 que a tela-fonte mostra. `agruparPautaPorFase` (Task 3) cobre os 3
  restantes num grupo honesto "Outras fases" (mesmo princípio fail-closed da coluna "Outros" em
  `tramitacao-board-vista.ts`) — decisão registrada aqui, não no spec, por ser puramente uma questão de
  fidelidade de leitura (não muda escopo IN/OUT).
- ⤳ **Achado técnico adicional**: `PautaItemOut` do tipo `"proposicao"` só carrega `proposicaoId` (não
  número/ementa) — `resolverTituloItem` (Task 3) resolve via a MESMA lista de `/proposicoes` já buscada
  para o rail (sem 2º round-trip), com fallback honesto (`indisponivel: true`) se a proposição estiver fora
  da página de 100 itens buscada.

## Execução

Padrão provado das fatias anteriores: **ultracode Workflow** — impl TDD + revisores `ecc` adversariais
(`react` · `security`) + corretor; o orquestrador verifica independente antes do merge. Alternativa:
subagent-driven (um subagente fresco por task + review entre tasks). Merge `fe-15-pauta-convocacao`→`main`
quando aprovado (2/4 da Onda C; Marco MFE-3 fecha na C3, cockpit ao vivo de votação do vereador).
