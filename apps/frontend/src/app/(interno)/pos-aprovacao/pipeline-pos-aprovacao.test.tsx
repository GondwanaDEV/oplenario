import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { PipelinePosAprovacao } from "./pipeline-pos-aprovacao";
import type { EtapaPipeline } from "@/lib/pos-aprovacao-vista";

const etapas: EtapaPipeline[] = [
  { rotulo: "Autógrafo", detalhe: "18/06/2026 · gerado", situacao: "feita" },
  { rotulo: "No Executivo", detalhe: "desde 18/06/2026", situacao: "atual" },
  { rotulo: "Sanção ou veto", detalhe: "aguarda", situacao: "futura" },
  { rotulo: "Promulgação", detalhe: "—", situacao: "futura" },
  { rotulo: "Publicação", detalhe: "vira lei", situacao: "futura" },
];

describe("PipelinePosAprovacao", () => {
  afterEach(() => cleanup());

  it("renderiza as 5 etapas como itens de lista, na ordem", () => {
    render(<PipelinePosAprovacao etapas={etapas} />);
    const lista = screen.getByRole("list", { name: /etapas da sanção/i });
    const itens = screen.getAllByRole("listitem");
    expect(itens).toHaveLength(5);
    expect(lista).toBeTruthy();
    expect(screen.getByText("Autógrafo")).toBeTruthy();
    expect(screen.getByText("No Executivo")).toBeTruthy();
    expect(screen.getByText("Sanção ou veto")).toBeTruthy();
    expect(screen.getByText("Promulgação")).toBeTruthy();
    expect(screen.getByText("Publicação")).toBeTruthy();
  });

  it("etapa 'feita' mostra ✓ e classe 'et feita'; 'atual' tem aria-current=step; 'futura' mostra o índice numérico", () => {
    render(<PipelinePosAprovacao etapas={etapas} />);
    const itens = screen.getAllByRole("listitem");
    expect(itens[0].className).toContain("feita");
    expect(itens[0].textContent).toContain("✓");
    expect(itens[1].getAttribute("aria-current")).toBe("step");
    expect(itens[1].className).toContain("atual");
    expect(itens[2].className).toContain("futura");
    expect(itens[2].textContent).toContain("3"); // índice 1-based da etapa futura
    expect(itens[2].getAttribute("aria-current")).toBeNull();
  });

  it("mostra o detalhe textual de cada etapa", () => {
    render(<PipelinePosAprovacao etapas={etapas} />);
    expect(screen.getByText("18/06/2026 · gerado")).toBeTruthy();
    expect(screen.getByText("aguarda")).toBeTruthy();
    expect(screen.getByText("vira lei")).toBeTruthy();
  });
});
