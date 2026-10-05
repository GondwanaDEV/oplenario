// "O que a Casa entregou": cada número que tem tela leva a ela; o que não tem (presença média, transmissão
// ao vivo) fica sem link.

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { OrgulhoInstitucional } from "./orgulho-institucional";
import type { MesaVista } from "@/lib/mesa-vista";

afterEach(() => cleanup());

function vista(over: Partial<MesaVista["orgulho"]> = {}): MesaVista["orgulho"] {
  return {
    estado: "disponivel",
    presencaMedia: 91,
    presencaSessoes: 2,
    esicPercentual: 96,
    esicEncerrados: 49,
    totalTramitacao: 31,
    transmissaoAoVivo: { estado: "em-breve" },
    ...over,
  };
}

describe("OrgulhoInstitucional — links", () => {
  it("proposições em tramitação levam à lista de proposições; e-SIC, à fila de e-SIC (com o token dev)", () => {
    render(<OrgulhoInstitucional vista={vista()} token="tk" />);
    expect(screen.getByRole("link", { name: /proposições/i }).getAttribute("href")).toBe("/proposicoes?token=tk");
    expect(screen.getByRole("link", { name: /e-SIC/i }).getAttribute("href")).toBe("/atendimento?aba=esic&token=tk");
  });

  it("presença média e transmissão ao vivo não têm tela e ficam sem link", () => {
    render(<OrgulhoInstitucional vista={vista()} />);
    expect(screen.getAllByRole("link")).toHaveLength(2);
  });

  it("sem dado (—), sem link para o que não existe", () => {
    render(<OrgulhoInstitucional vista={vista({ totalTramitacao: null, esicPercentual: null, esicEncerrados: null })} />);
    expect(screen.queryAllByRole("link")).toHaveLength(0);
  });
});
