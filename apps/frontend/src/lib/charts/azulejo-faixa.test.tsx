import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { AzulejoFaixa } from "./azulejo-faixa";
import type { EstagioTramitacao } from "../tramitacao-vista";

// Task 0.5 (Fatia A2.0, Portal do Cidadão) — porte parametrizado do SVG de portal-cidadao.html:432-453
// (a faixa de azulejo da tramitação, a assinatura visual do design system).

const estagios: EstagioTramitacao[] = [
  { rotulo: "Protocolo", situacao: "concluido" },
  { rotulo: "Comissões", situacao: "concluido" },
  { rotulo: "1º turno", situacao: "concluido" },
  { rotulo: "2º turno", situacao: "ativo" },
  { rotulo: "Sanção", situacao: "pendente" },
];

describe("AzulejoFaixa", () => {
  // convenção do projeto (ver src/lib/auth.test.tsx): sem test.globals, cleanup manual entre casos.
  afterEach(() => {
    cleanup();
  });

  it("renderiza um <text> por estágio, com o rótulo correspondente", () => {
    render(<AzulejoFaixa estagios={estagios} rotuloAria="Tramitação do PL 042/2026" />);
    // getByText já lança se não encontrar — sem @testing-library/jest-dom no projeto (convenção:
    // asserções via classList/atributo cru, não toBeInTheDocument/toHaveClass).
    for (const e of estagios) {
      expect(screen.getByText(e.rotulo).textContent).toBe(e.rotulo);
    }
  });

  it("marca o estágio ativo com a classe .ativo", () => {
    render(<AzulejoFaixa estagios={estagios} rotuloAria="Tramitação" />);
    expect(screen.getByText("2º turno").getAttribute("class")).toBe("ativo");
  });

  it("marca estágios pendentes com a classe .pendente", () => {
    render(<AzulejoFaixa estagios={estagios} rotuloAria="Tramitação" />);
    expect(screen.getByText("Sanção").getAttribute("class")).toBe("pendente");
  });

  it("tem role=img com aria-label descritivo obrigatório", () => {
    render(<AzulejoFaixa estagios={estagios} rotuloAria="Tramitação do PL 042/2026: em 2º turno." />);
    expect(screen.getByRole("img", { name: /Tramitação do PL 042\/2026/ })).toBeTruthy();
  });
});
