import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { TabelaProtocolo } from "./tabela-protocolo";

const itens = [
  {
    id: "p1",
    numero: 847,
    ano: 2026,
    objetoTipo: "documento",
    objetoId: "d1",
    sentido: "expedido",
    assunto: "Ofício 142/2026 – SL · Hortas comunitárias",
    protocoladoEm: "2026-05-21T14:02:00-03:00",
  },
  {
    id: "p2",
    numero: 845,
    ano: 2026,
    objetoTipo: "requerimento_cidadao",
    objetoId: "r1",
    sentido: "recebido",
    assunto: "Cidadão solicita poda de árvore — Bairro Cocó",
    protocoladoEm: "2026-05-21T11:37:00-03:00",
  },
];

describe("TabelaProtocolo", () => {
  afterEach(() => cleanup());

  it("lista vazia -> mensagem honesta, sem tabela fabricada", () => {
    render(<TabelaProtocolo itens={[]} />);
    expect(screen.getByText(/nenhum registro/i)).toBeTruthy();
  });

  it("renderiza uma linha por item com número, tipo, sentido, assunto e hora", () => {
    render(<TabelaProtocolo itens={itens} />);
    expect(screen.getByText("2026/00847")).toBeTruthy();
    expect(screen.getByText("2026/00845")).toBeTruthy();
    expect(screen.getByText("Documento")).toBeTruthy();
    expect(screen.getByText("Requerimento")).toBeTruthy();
    expect(screen.getByText("Saída")).toBeTruthy();
    expect(screen.getByText("Entrada")).toBeTruthy();
    expect(screen.getByText("Ofício 142/2026 – SL · Hortas comunitárias")).toBeTruthy();
    expect(screen.getByText("14:02")).toBeTruthy();
    expect(screen.getByText("11:37")).toBeTruthy();
  });
});
