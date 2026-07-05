import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { EmBreve } from "./em-breve";

// Task 0.4 (Fatia A2.0, Portal do Cidadão) — extrai o espírito de LenteJuridico (A1, "em-breve honesto")
// para um componente reusável: sem dado falso, anuncia o estado indisponível para leitores de tela.

describe("EmBreve", () => {
  // sem `test.globals: true` no vitest.config.ts, o auto-cleanup do @testing-library/react não se
  // registra sozinho (convenção do projeto, ver src/lib/auth.test.tsx) — cleanup manual evita vazar
  // render entre casos (2º `it` veria os elementos do 1º ainda no DOM).
  afterEach(() => {
    cleanup();
  });

  it("mostra o título e o motivo", () => {
    render(<EmBreve titulo="Agenda pública" motivo="Sem backend ainda — [GAP] de datas." />);
    // getByText já lança se não encontrar — sem @testing-library/jest-dom no projeto (convenção:
    // asserções via textContent/atributo cru, não toBeInTheDocument/toHaveClass).
    expect(screen.getByText("Agenda pública").textContent).toBe("Agenda pública");
    expect(screen.getByText("Sem backend ainda — [GAP] de datas.").textContent).toBe(
      "Sem backend ainda — [GAP] de datas.",
    );
  });

  it("anuncia o estado indisponível via aria-label (leitor de tela)", () => {
    render(<EmBreve titulo="Sessões ao vivo" motivo="Rota pública ainda não existe." />);
    const regiao = screen.getByRole("status");
    expect(regiao.getAttribute("aria-label")).toMatch(/em breve/i);
  });
});
