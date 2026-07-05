import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MaisTramitacao } from "./mais-tramitacao";
import type { MateriaVista } from "@/lib/materia-vista";

// Task 1.3 (Fatia A2.1, Portal do Cidadão) — porte de portal-cidadao.html:492-508 (a lista "mais em
// tramitação").

function vista(parcial: Partial<MateriaVista>): MateriaVista {
  return {
    ref: "PL 051/2026",
    titulo: "Arborização viária do entorno da Av. Bezerra de Menezes",
    situacao: "Em 1º turno",
    permalink: "urn:lex:x",
    proposicaoId: "1",
    autorTexto: null,
    estagios: [],
    ...parcial,
  };
}

describe("MaisTramitacao", () => {
  afterEach(() => cleanup());

  it("lista vazia -> não lança, não renderiza itens", () => {
    render(<MaisTramitacao itens={[]} ente="fortaleza" />);
    expect(screen.queryAllByRole("listitem").length).toBe(0);
  });

  it("renderiza um item por matéria, com ref/título/situação, linkando à ficha", () => {
    const itens = [vista({ proposicaoId: "1", ref: "PL 051/2026" }), vista({ proposicaoId: "2", ref: "PLC 004/2026" })];
    render(<MaisTramitacao itens={itens} ente="fortaleza" />);
    expect(screen.getAllByRole("listitem").length).toBe(2);
    const link1 = screen.getByRole("link", { name: /PL 051\/2026/ });
    expect(link1.getAttribute("href")).toBe("/portal/casa/fortaleza/materias/1");
  });
});
