import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { PreviewDocumento } from "./preview-documento";

const documento = {
  id: "d1",
  modeloId: "m1",
  tipoDocumento: "oficio",
  assunto: "Solicitação de informações sobre as hortas comunitárias",
  corpo: "Prezado Prefeito, solicito informações sobre hortas comunitárias.",
  estado: "rascunho",
  lockVersion: 0,
  criadoEm: "2026-05-21T10:00:00Z",
};

describe("PreviewDocumento", () => {
  afterEach(() => cleanup());

  it("sem documento ainda -> estado vazio honesto, sem inventar papel", () => {
    render(<PreviewDocumento documento={null} valoresMerge={[]} />);
    expect(screen.getByText(/aparecerá aqui/i)).toBeTruthy();
  });

  it("com documento gerado -> mostra assunto, carimbo 'a reservar' e o corpo", () => {
    render(<PreviewDocumento documento={documento} valoresMerge={["Prefeito"]} />);
    expect(screen.getByText("a reservar")).toBeTruthy();
    expect(screen.getByText(/solicitação de informações/i)).toBeTruthy();
    expect(screen.getByText("Prefeito")).toBeTruthy();
  });

  it("destaca os valores mesclados com a classe .merge", () => {
    render(<PreviewDocumento documento={documento} valoresMerge={["Prefeito"]} />);
    const destaque = screen.getByText("Prefeito");
    expect(destaque.className).toContain("merge");
  });

  it("protocolado -> mostra o número real no carimbo, não 'a reservar'", () => {
    render(
      <PreviewDocumento
        documento={{ ...documento, estado: "emitido", protocoloNumero: 847, protocoloAno: 2026 }}
        valoresMerge={[]}
      />,
    );
    expect(screen.getByText("2026/00847")).toBeTruthy();
    expect(screen.queryByText("a reservar")).toBeNull();
  });
});
