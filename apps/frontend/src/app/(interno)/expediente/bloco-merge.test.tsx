import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { BlocoMerge } from "./bloco-merge";

describe("BlocoMerge", () => {
  afterEach(() => cleanup());

  it("lista os pares chave/valor enviados nesta geração", () => {
    render(<BlocoMerge dados={{ destinatario: "Prefeito", assunto_extra: "Hortas comunitárias" }} carimbo="a reservar" />);
    expect(screen.getByText("destinatario")).toBeTruthy();
    expect(screen.getByText("Prefeito")).toBeTruthy();
    expect(screen.getByText("assunto_extra")).toBeTruthy();
    expect(screen.getByText("Hortas comunitárias")).toBeTruthy();
    expect(screen.getByText("Protocolo Geral")).toBeTruthy();
    expect(screen.getByText("a reservar")).toBeTruthy();
  });

  it("sem campos digitados -> mensagem honesta (nenhum dado inventado)", () => {
    render(<BlocoMerge dados={{}} carimbo="a reservar" />);
    expect(screen.getByText(/nenhum campo/i)).toBeTruthy();
  });

  it("mostra o número real do protocolo quando já protocolado", () => {
    render(<BlocoMerge dados={{ destinatario: "Prefeito" }} carimbo="2026/00847" />);
    expect(screen.getByText("2026/00847")).toBeTruthy();
  });
});
