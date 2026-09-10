import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { AcoesCard } from "./acoes-card";
import type { ProposicaoDetalheOut } from "@/lib/contrato-legislativo.gen";

const proposicaoBase: ProposicaoDetalheOut = {
  id: "p1",
  tipo: "projeto_lei",
  ano: 2026,
  sequencial: 1,
  urnLex: "urn:x",
  ementa: "Ementa",
  estado: "em_comissoes",
  aprovada: false,
  lockVersion: 0,
  atualizadoEm: "2026-01-01T00:00:00Z",
};

describe("AcoesCard", () => {
  afterEach(() => cleanup());

  it("renderiza os 3 botões de ação, todos inertes (aria-disabled) + EmBreve honesto", () => {
    render(<AcoesCard proposicao={proposicaoBase} token="tok" />);
    expect(screen.getByText("Ações")).toBeTruthy();
    for (const rotulo of ["Incluir na pauta", "Gerar ficha PDF", "Distribuir a comissão"]) {
      const btn = screen.getByRole("button", { name: rotulo });
      expect(btn).toBeTruthy();
      expect(btn.getAttribute("aria-disabled")).toBe("true");
      expect((btn as HTMLButtonElement).disabled).toBe(true);
      // aria-describedby precisa estar no próprio botão (foco/leitura de AT) — não no <div> wrapper, que
      // não é exposto por leitores de tela (achado do review).
      expect(btn.getAttribute("aria-describedby")).toBe("ficha-acoes-embreve");
    }
    expect(screen.getByRole("status")).toBeTruthy();
  });

  it("aprovada === false -> sem link 'Ver pós-aprovação'", () => {
    render(<AcoesCard proposicao={proposicaoBase} token="tok" />);
    expect(screen.queryByRole("link", { name: /ver pós-aprovação/i })).toBeNull();
  });

  it("aprovada === true -> mostra o link 'Ver pós-aprovação' com o token preservado", () => {
    render(<AcoesCard proposicao={{ ...proposicaoBase, aprovada: true }} token="tok-de-teste" />);
    const link = screen.getByRole("link", { name: /ver pós-aprovação/i });
    expect(link.getAttribute("href")).toBe("/pos-aprovacao/p1?token=tok-de-teste");
  });

  it("aprovada === true mas estado ainda é texto livre não-'aprovada' -> link continua aparecendo (o gate é o booleano, não o rótulo)", () => {
    render(
      <AcoesCard
        proposicao={{ ...proposicaoBase, estado: "aguardando_promulgacao", aprovada: true }}
        token="tok"
      />,
    );
    expect(screen.getByRole("link", { name: /ver pós-aprovação/i })).toBeTruthy();
  });
});
