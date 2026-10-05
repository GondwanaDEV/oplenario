// Gate do rótulo de prazo do cartão "O que vence".
//
// POR QUE ESTE ARQUIVO EXISTE: este componente não tinha teste nenhum, e por isso um defeito
// visível na tela sobreviveu até ser achado numa checagem de prontidão manual, a olho: o helper
// `diasAte` fazia `Math.max(0, ...)`, achatando todo o passado em zero, e a linha imprimia
// "vence em 0 dia(s)" para uma obrigação vencida havia 16 dias — no MESMO cartão cuja manchete
// diz "1 obrigação venceu o prazo no TCE-CE". As duas metades do card de saúde institucional
// afirmavam coisas diferentes sobre o mesmo prazo.
//
// A asserção forte aqui não é "renderiza sem quebrar" — é que atrasado, hoje e futuro produzem
// TRÊS frases distintas. Um teste que só procurasse "dia(s)" passaria nos dois desenhos.

import { cleanup, render } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { OQueVence } from "./o-que-vence";
import type { MesaVista } from "@/lib/mesa-vista";

// 15/09/2026, 12:00 local — o mesmo "hoje" da checagem que achou o defeito.
const HOJE = new Date("2026-09-15T15:00:00.000Z");

// O item de origem `compliance` e' um ObrigacaoEmAberto + `origem` (ver mesa-vista.ts). O fixture
// divergia do contrato em DOIS pontos, invisiveis ate' o helper `vista` ganhar tipo de retorno: faltava
// `estado` (obrigatorio) e sobrava `protocolo`, que pertence a' OUTRA variante da uniao (`pendencia`).
// O teste passava porque o componente so' le' `venceEm`/`origem` — mas o fixture afirmava uma forma que
// a aplicacao nunca produz, que e' justamente o que um fixture nao pode fazer.
function item(venceEm: string, id: string) {
  return {
    origem: "compliance" as const,
    id,
    objetoId: id,
    objetoTipo: "competencia",
    templateChave: "remessa_mensal_sim",
    venceEm,
    estado: "pendente",
  };
}

// Tipo de retorno ANOTADO de proposito: sem ele, um campo que falte no fixture so' aparece como erro
// em cada `render(...)` la' embaixo (eram 4), longe da causa. Anotado, o compilador acusa AQUI, uma vez.
function vista(itens: ReturnType<typeof item>[]): MesaVista["oQueVence"] {
  return { estado: "disponivel" as const, itens, truncamentoCompliance: null, truncamentoPendencias: null };
}

describe("OQueVence — o prazo em palavras", () => {
  beforeEach(() => vi.useFakeTimers({ now: HOJE }));
  // `cleanup()` EXPLICITO: este projeto nao tem auto-cleanup do RTL, e as queries devolvidas por
  // `render` ligam-se a `document.body`, nao ao container — sem isto, o "nao existe X" de um teste
  // le' o X que o teste anterior montou. Foi exatamente o que aconteceu ao escrever este arquivo.
  afterEach(() => { cleanup(); vi.useRealTimers(); });

  // Queries ESCOPADAS ao container, nunca o `screen` global: este projeto nao tem auto-cleanup do
  // RTL, entao o `screen` enxerga o DOM acumulado de todos os `render` anteriores do arquivo — o
  // "nao existe X" de um teste passaria a ler o X que outro teste montou. Mesma convencao de
  // `despachos-da-mesa.test.tsx`.
  it("prazo JÁ VENCIDO diz há quantos dias venceu — nunca 'vence em 0 dias'", () => {
    // 30/08/2026 é o vencimento real da obrigação de 2026-07 na Casa da demo.
    const { getByText, queryByText } = render(<OQueVence vista={vista([item("2026-08-30", "a")])} />);
    expect(getByText("venceu há 16 dias")).toBeTruthy();
    expect(queryByText(/vence em 0 dia/)).toBeNull();
  });

  it("plural de verdade: ontem, amanhã e 2 dias não usam parênteses nem '1 dias'", () => {
    const { getByText, container } = render(
      <OQueVence vista={vista([item("2026-09-14", "a"), item("2026-09-16", "b"), item("2026-09-17", "c")])} />,
    );
    expect(getByText("venceu ontem")).toBeTruthy();
    expect(getByText("vence amanhã")).toBeTruthy();
    expect(getByText("vence em 2 dias")).toBeTruthy();
    expect(container.textContent ?? "").not.toMatch(/\(s\)|\(ns\)|\(ões\)|1 dias/);
  });

  it("o selo do cabeçalho conta no singular quando há um só item", () => {
    const { container } = render(<OQueVence vista={vista([item("2026-09-30", "a")])} />);
    expect(container.querySelector(".selo-n")?.textContent).toBe("1 item");
  });

  it("o selo do cabeçalho conta no plural quando há vários", () => {
    const { container } = render(<OQueVence vista={vista([item("2026-09-30", "a"), item("2026-10-01", "b")])} />);
    expect(container.querySelector(".selo-n")?.textContent).toBe("2 itens");
  });

  it("23h na Casa: o prazo de hoje continua 'vence hoje' (relógio UTC já virou o dia)", () => {
    vi.setSystemTime(new Date("2026-09-16T02:00:00Z")); // 15/09 23h em Fortaleza
    const { getByText } = render(<OQueVence vista={vista([item("2026-09-15", "n")])} />);
    expect(getByText("vence hoje")).toBeTruthy();
  });

  it("o número grande do anel de um prazo vencido é o atraso, não '0 dias'", () => {
    // 10/08/2026 -> 36 dias antes de 15/09/2026.
    const { container } = render(<OQueVence vista={vista([item("2026-08-10", "v")])} />);
    expect(container.querySelector(".anel-c .d")?.textContent).toBe("36");
    expect(container.querySelector(".anel-c .u")?.textContent).toBe("atraso");
    expect(container.querySelector(".prazo-obj .quando")?.textContent).toBe("venceu há 36 dias");
    expect(container.textContent ?? "").not.toMatch(/0\s*dias/);
  });

  it("o anel de um prazo vencido leva a cor de urgência; o de um prazo folgado, a de 'no prazo'", () => {
    const { container } = render(<OQueVence vista={vista([item("2026-08-10", "v"), item("2026-12-31", "f")])} />);
    const [vencido, folgado] = Array.from(container.querySelectorAll("li.prazo-item"));
    expect(vencido.className).toContain("prz-urgente");
    expect(folgado.className).toContain("prz-noprazo");
  });

  it("prazo de HOJE não se confunde com prazo vencido", () => {
    const { getByText, queryByText } = render(<OQueVence vista={vista([item("2026-09-15", "b")])} />);
    expect(getByText("vence hoje")).toBeTruthy();
    expect(queryByText(/venceu há/)).toBeNull();
  });

  it("prazo FUTURO segue dizendo em quantos dias vence", () => {
    const { getByText } = render(<OQueVence vista={vista([item("2026-09-30", "c")])} />);
    expect(getByText("vence em 15 dias")).toBeTruthy();
  });

  it("a obrigação aparece pelo NOME (ADR-0021), não pela chave do template", () => {
    const metas = { ...item("2026-09-30", "m"), templateChave: "audiencia_metas_fiscais" };
    const contas = { ...item("2026-10-31", "j"), objetoTipo: "prestacao_contas", templateChave: "julgamento_contas_prefeito" };
    const { getByText, queryByText } = render(<OQueVence vista={vista([metas, contas])} />);
    expect(getByText("Obrigação · Audiência de metas fiscais (LRF)")).toBeTruthy();
    expect(getByText("Obrigação · Julgamento das contas do Prefeito")).toBeTruthy();
    expect(queryByText(/audiencia_metas_fiscais|julgamento_contas_prefeito/)).toBeNull();
  });

  describe("cada prazo que tem tela leva a ela", () => {
    const pendencia = (objetoTipo: string, objetoId: string, protocolo: string) => ({
      origem: "pendencia" as const, objetoTipo, objetoId, protocolo, venceEm: "2026-09-30", estado: "pendente",
    });
    const vistaMista = (itens: Extract<MesaVista["oQueVence"], { estado: "disponivel" }>["itens"]): MesaVista["oQueVence"] => ({
      estado: "disponivel", itens, truncamentoCompliance: null, truncamentoPendencias: null,
    });

    it("pedido e-SIC, solicitação LGPD e manifestação abrem o protocolo no balcão (com o token dev)", () => {
      const { getByRole } = render(
        <OQueVence
          token="tk"
          vista={vistaMista([
            pendencia("pedido_esic", "e1", "ESIC-2026-000001"),
            pendencia("solicitacao_titular", "l1", "LGPD-2026-000001"),
            pendencia("manifestacao_ouvidoria", "o1", "OUV-2026-000001"),
          ])}
        />,
      );
      expect(getByRole("link", { name: /Pedido e-SIC · ESIC-2026-000001/ }).getAttribute("href")).toBe("/atendimento/esic/e1?token=tk");
      expect(getByRole("link", { name: /LGPD-2026-000001/ }).getAttribute("href")).toBe("/atendimento/lgpd/l1?token=tk");
      expect(getByRole("link", { name: /OUV-2026-000001/ }).getAttribute("href")).toBe("/atendimento/ouvidoria/o1?token=tk");
    });

    it("sem token dev, o href sai limpo", () => {
      const { getByRole } = render(<OQueVence vista={vistaMista([pendencia("pedido_esic", "e1", "ESIC-1")])} />);
      expect(getByRole("link", { name: /ESIC-1/ }).getAttribute("href")).toBe("/atendimento/esic/e1");
    });

    it("recurso e-SIC leva à fila de e-SIC (o balcão não abre o recurso pelo id dele)", () => {
      const { getByRole } = render(<OQueVence vista={vistaMista([pendencia("recurso_esic", "r1", "REC-1")])} />);
      expect(getByRole("link", { name: /REC-1/ }).getAttribute("href")).toBe("/atendimento?aba=esic");
    });

    it("obrigação do julgamento das contas abre a prestação; a remessa ao TCE não tem tela e fica sem link", () => {
      const contas = { ...item("2026-10-31", "j"), objetoTipo: "prestacao_contas", objetoId: "pc1", templateChave: "julgamento_contas_prefeito" };
      const remessa = item("2026-10-31", "s");
      const { getByRole, queryByRole } = render(<OQueVence vista={vista([contas, remessa])} />);
      expect(getByRole("link", { name: /Julgamento das contas do Prefeito/ }).getAttribute("href")).toBe("/contas/pc1");
      expect(queryByRole("link", { name: /Remessa mensal ao SIM/ })).toBeNull();
    });
  });

  it("as três frases são distintas entre si na mesma lista", () => {
    const { getAllByText } = render(
      <OQueVence
        vista={vista([item("2026-08-30", "a"), item("2026-09-15", "b"), item("2026-09-30", "c")])}
      />,
    );
    const frases = getAllByText(/venceu há|vence hoje|vence em/).map((n) => n.textContent);
    expect(new Set(frases).size).toBe(3);
  });
});
