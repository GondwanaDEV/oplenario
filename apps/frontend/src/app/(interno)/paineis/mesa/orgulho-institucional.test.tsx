// "O que a Casa entregou": um percentual sem denominador engana. "91% de presença" vinha de 2 sessões, e a
// vitrine o apresentava ao lado de "31 proposições" como se tivesse o mesmo peso. O denominador (quantas
// sessões, quantos pedidos) sai da própria rota (`sessoesConsideradas`, `totalEncerrados`) e vai junto.

import { cleanup, render } from "@testing-library/react";
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

describe("OrgulhoInstitucional — o denominador de cada percentual", () => {
  it("presença: '91%' vem com 'em 2 sessões'", () => {
    const { container } = render(<OrgulhoInstitucional vista={vista()} />);
    const texto = container.textContent ?? "";
    expect(texto).toMatch(/91%/);
    expect(texto).toMatch(/em 2 sessões/);
  });

  it("presença: uma sessão só, no singular", () => {
    const { container } = render(<OrgulhoInstitucional vista={vista({ presencaSessoes: 1 })} />);
    expect(container.textContent ?? "").toMatch(/em 1 sessão(?!s)/);
  });

  it("e-SIC: '96%' vem com 'de 49 pedidos encerrados'", () => {
    const { container } = render(<OrgulhoInstitucional vista={vista()} />);
    expect(container.textContent ?? "").toMatch(/de 49 pedidos encerrados/);
  });

  it("e-SIC: um pedido só, no singular", () => {
    const { container } = render(<OrgulhoInstitucional vista={vista({ esicEncerrados: 1 })} />);
    expect(container.textContent ?? "").toMatch(/de 1 pedido encerrado(?!s)/);
  });

  it("sem percentual (—) não se afirma denominador nenhum", () => {
    const { container } = render(
      <OrgulhoInstitucional vista={vista({ presencaMedia: null, presencaSessoes: null, esicPercentual: null, esicEncerrados: null })} />,
    );
    const texto = container.textContent ?? "";
    expect(texto).not.toMatch(/em \d+ sess|de \d+ pedido/);
  });

  it("percentual sem denominador na rota -> não inventa: mostra só o percentual", () => {
    const { container } = render(<OrgulhoInstitucional vista={vista({ presencaSessoes: null, esicEncerrados: null })} />);
    const texto = container.textContent ?? "";
    expect(texto).toMatch(/91%/);
    expect(texto).not.toMatch(/em \d+ sess|de \d+ pedido/);
  });
});
