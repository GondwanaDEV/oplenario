// "Onde está cada proposição": cada proposição do quadro abre a ficha da matéria.

import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { PipelineLegislativo } from "./pipeline-legislativo";
import type { MesaVista } from "@/lib/mesa-vista";

afterEach(() => cleanup());

function vista(estados: string[], comItens = true): MesaVista["pipeline"] {
  return {
    estado: "disponivel",
    comItens,
    porEstado: estados.map((estado, i) => ({ estado, n: estados.length - i })),
    itens: comItens
      ? estados.map((estado, i) => ({
          proposicaoId: `p${i}`, tipo: "projeto_lei", ano: 2026, sequencial: i + 1, urnLex: `urn:${i}`,
          ementa: `Ementa ${i}`, estado, transicionouEm: "2026-09-01T00:00:00Z",
        }))
      : [],
  };
}

describe("PipelineLegislativo — cada proposição abre a ficha", () => {
  it("o item do quadro é link para a ficha da matéria (com o token dev)", () => {
    const { getAllByRole } = render(<PipelineLegislativo token="tk" vista={vista(["em_comissoes", "em_sancao"])} />);
    const hrefs = getAllByRole("link").map((a) => a.getAttribute("href"));
    expect(hrefs).toEqual(["/ficha-materia/p0?token=tk", "/ficha-materia/p1?token=tk"]);
  });

  it("o nome do link é a referência e a ementa, não 'clique aqui'", () => {
    const { getByRole } = render(<PipelineLegislativo vista={vista(["em_comissoes"])} />);
    expect(getByRole("link", { name: /PL 001\/2026.*Ementa 0/ }).getAttribute("href")).toBe("/ficha-materia/p0");
  });

  it("sem itens (degradado para só-contagem): nenhum link", () => {
    const { queryAllByRole } = render(<PipelineLegislativo vista={vista(["em_comissoes"], false)} />);
    expect(queryAllByRole("link")).toHaveLength(0);
  });
});
