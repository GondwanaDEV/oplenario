import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { AcoesCard } from "./acoes-card";

describe("AcoesCard", () => {
  afterEach(() => cleanup());

  it("renderiza os 3 botões de ação, todos inertes (aria-disabled) + EmBreve honesto", () => {
    render(<AcoesCard />);
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
});
