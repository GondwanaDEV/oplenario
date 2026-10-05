import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { AzulejoFaixa, quebrarRotulo } from "./azulejo-faixa";
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

// O rótulo é o nome que a Casa deu à etapa (rito da Casa) e pode ser longo: não pode invadir o tile vizinho.
describe("quebrarRotulo", () => {
  it("rótulo curto fica numa linha só, intacto", () => {
    expect(quebrarRotulo("Protocolo")).toEqual(["Protocolo"]);
    expect(quebrarRotulo("Em tramitação")).toEqual(["Em tramitação"]);
  });

  it("rótulo longo quebra por palavra em linhas de até 14 caracteres", () => {
    expect(quebrarRotulo("Aguardando designação de relator")).toEqual(["Aguardando", "designação de", "relator"]);
  });

  it("palavra maior que a linha, ou mais de 3 linhas, termina em reticências (o aria-label tem o rótulo inteiro)", () => {
    expect(quebrarRotulo("Anticonstitucionalissimamente")).toEqual(["Anticonstituc…"]);
    const linhas = quebrarRotulo("Uma duas tres quatro cinco seis sete oito nove dez onze doze");
    expect(linhas).toHaveLength(3);
    expect(linhas[2].endsWith("…")).toBe(true);
    for (const l of linhas) expect(l.length).toBeLessThanOrEqual(14);
  });
});

describe("AzulejoFaixa com rótulos da Casa", () => {
  afterEach(() => {
    cleanup();
  });

  it("rótulo longo vira linhas dentro do mesmo <text> e a faixa cresce em altura", () => {
    const { container } = render(
      <AzulejoFaixa
        estagios={[
          { chave: "a", rotulo: "Entrada", situacao: "concluido" },
          { chave: "b", rotulo: "Aguardando designação de relator", situacao: "ativo" },
        ]}
        rotuloAria="Tramitação"
      />,
    );
    const textos = Array.from(container.querySelectorAll("text"));
    expect(textos[1].querySelectorAll("tspan")).toHaveLength(3);
    expect(textos[0].querySelectorAll("tspan")).toHaveLength(0);
    expect(Number(container.querySelector("svg")?.getAttribute("height"))).toBeGreaterThan(132);
  });

  it("duas etapas com o mesmo rótulo coexistem (a chave, não o rótulo, identifica a etapa)", () => {
    const { container } = render(
      <AzulejoFaixa
        estagios={[
          { chave: "a", rotulo: "Análise", situacao: "concluido" },
          { chave: "b", rotulo: "Análise", situacao: "ativo" },
        ]}
        rotuloAria="Tramitação"
      />,
    );
    expect(container.querySelectorAll("text")).toHaveLength(2);
  });

  it("até 6 etapas a faixa encolhe para caber; com mais, ganha a classe que a deixa rolar em vez de ficar ilegível", () => {
    const faixa = (n: number) =>
      Array.from({ length: n }, (_, i) => ({ chave: `e${i}`, rotulo: `Etapa ${i}`, situacao: "pendente" as const }));
    const seis = render(<AzulejoFaixa estagios={faixa(6)} rotuloAria="T" />);
    expect(seis.container.querySelector("svg")?.getAttribute("class")).toBe("azulejo-faixa");
    cleanup();
    const sete = render(<AzulejoFaixa estagios={faixa(7)} rotuloAria="T" />);
    expect(sete.container.querySelector("svg")?.getAttribute("class")).toContain("azulejo-faixa--larga");
  });
});
