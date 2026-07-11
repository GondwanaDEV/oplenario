import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { RailParecer } from "./rail-parecer";
import type { ParecerEditorOut } from "@/lib/contrato-legislativo.gen";

const base: ParecerEditorOut = {
  id: "p1",
  objetoTipo: "proposicao",
  objetoId: "obj1",
  comissaoId: "c-ccj",
  relatorId: null,
  votoRelator: null,
  estado: "em_elaboracao",
  templateId: "t1",
  lockVersion: 0,
  criadoEm: "2026-05-01T10:00:00Z",
  objeto: {
    id: "obj1",
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 42,
    urnLex: "urn:x",
    ementa: "Institui o Programa Municipal de Hortas Comunitárias.",
  },
  relatorio: "",
  analise: "",
  textoEstado: "vazio",
  textoNumeroVersao: null,
};

describe("RailParecer", () => {
  afterEach(() => cleanup());

  it("card 'Matéria': mostra número + ementa + link pra ficha", () => {
    render(<RailParecer parecer={base} token={null} />);
    expect(screen.getByText("PL 42/2026")).toBeTruthy();
    expect(screen.getByText(/Hortas Comunitárias/)).toBeTruthy();
    const link = screen.getByRole("link", { name: /abrir a ficha/i }) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/ficha-materia/obj1");
  });

  it("token presente -> link preserva ?token= (comToken)", () => {
    render(<RailParecer parecer={base} token="tok" />);
    const link = screen.getByRole("link", { name: /abrir a ficha/i }) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("/ficha-materia/obj1?token=tok");
  });

  it("objeto ausente -> fallback honesto, sem link (nunca inventa matéria)", () => {
    render(<RailParecer parecer={{ ...base, objeto: null }} token={null} />);
    expect(screen.queryByRole("link", { name: /abrir a ficha/i })).toBeNull();
    expect(screen.getByText(/matéria não disponível/i)).toBeTruthy();
  });

  it("card 'Relatoria': comissão crua (sem resolução id->nome), relator ausente omite a linha", () => {
    render(<RailParecer parecer={base} token={null} />);
    expect(screen.getByText("c-ccj")).toBeTruthy();
    expect(screen.queryByText(/^relator$/i)).toBeNull();
  });

  it("relatorId presente -> mostra a linha 'Relator' com rótulo honesto (sem nome inventado)", () => {
    render(<RailParecer parecer={{ ...base, relatorId: "r1" }} token={null} />);
    expect(screen.getByText("Relator")).toBeTruthy();
    expect(screen.getByText("Relator designado")).toBeTruthy();
  });

  it("card 'Antes de emitir': lista de dicas estática", () => {
    render(<RailParecer parecer={base} token={null} />);
    expect(screen.getByText(/relatório e análise preenchidos/i)).toBeTruthy();
  });
});
