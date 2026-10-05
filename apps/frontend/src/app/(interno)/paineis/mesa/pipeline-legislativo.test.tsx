// "Onde está cada proposição": cada proposição do quadro abre a ficha da matéria, e o gráfico "Carga por
// estágio" tem uma cor por POSIÇÃO do estágio — o estado é texto livre por Casa (template de tramitação), então a
// cor não pode depender de adivinhar o nome. O gráfico saía cinza porque 5 chaves fixas cobriam só 2 estados reais.

import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { PipelineLegislativo } from "./pipeline-legislativo";
import type { MesaVista } from "@/lib/mesa-vista";

afterEach(() => cleanup());

const ESTADOS_DE_UMA_CASA = ["aguardando_pauta", "em_comissoes", "parecer_juridico", "redacao_final", "primeiro_turno", "em_sancao"];

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

describe("PipelineLegislativo — carga por estágio", () => {
  it("nenhuma fatia sai cinza, qualquer que seja o nome do estado da Casa", () => {
    const { container } = render(<PipelineLegislativo vista={vista(ESTADOS_DE_UMA_CASA)} />);
    const fatias = Array.from(container.querySelectorAll<HTMLElement>(".barra-dist span"));
    expect(fatias).toHaveLength(ESTADOS_DE_UMA_CASA.length);
    for (const f of fatias) {
      expect(f.getAttribute("style") ?? "").toMatch(/background/);
      expect(f.getAttribute("style") ?? "").not.toMatch(/#888/);
    }
  });

  it("cada estágio tem a sua cor: a fatia e o quadradinho da legenda combinam, e não se repetem", () => {
    const { container } = render(<PipelineLegislativo vista={vista(ESTADOS_DE_UMA_CASA)} />);
    // só o `background`: a fatia leva também a largura proporcional, a legenda não.
    const cor = (el: Element) => /background:\s*([^;]+)/.exec((el as HTMLElement).getAttribute("style") ?? "")?.[1] ?? "";
    const fatias = Array.from(container.querySelectorAll(".barra-dist span")).map(cor);
    const legenda = Array.from(container.querySelectorAll(".dist-legenda i")).map(cor);
    expect(new Set(fatias).size).toBe(ESTADOS_DE_UMA_CASA.length);
    expect(legenda).toEqual(fatias);
  });

  it("a cor vem da POSIÇÃO: trocar o nome dos estados não muda as cores", () => {
    const a = render(<PipelineLegislativo vista={vista(["um", "dois", "tres"])} />);
    const coresA = Array.from(a.container.querySelectorAll(".barra-dist span")).map((e) => e.getAttribute("style"));
    cleanup();
    const b = render(<PipelineLegislativo vista={vista(["protocolada", "em_comissao", "sancao"])} />);
    const coresB = Array.from(b.container.querySelectorAll(".barra-dist span")).map((e) => e.getAttribute("style"));
    expect(coresB).toEqual(coresA);
  });

  it("o rótulo do estágio sai em palavras, sem underscore nem chave crua", () => {
    const { container } = render(<PipelineLegislativo vista={vista(ESTADOS_DE_UMA_CASA)} />);
    const texto = container.textContent ?? "";
    expect(texto).not.toMatch(/aguardando_pauta|parecer_juridico|redacao_final/);
    expect(texto).toMatch(/Aguardando pauta/);
    expect(texto).toMatch(/Parecer juridico/i);
  });

  it("estado desconhecido sem proposição ainda assim entra no gráfico e na legenda", () => {
    const { container } = render(<PipelineLegislativo vista={vista(["so_um_estado"], false)} />);
    expect(container.querySelectorAll(".dist-legenda i")).toHaveLength(1);
  });
});
