import { describe, it, expect } from "vitest";
import { ordenarFila, resumirFila } from "./moderacao-vista";
import type { ItemFilaModeracao } from "./use-fila-moderacao";

function item(id: string, denunciado: boolean): ItemFilaModeracao {
  return { id, proposicaoId: "p1", autorIdentidadeId: "a1", corpo: "c " + id, denunciado, criadoEm: "2026-01-01T00:00:00Z" };
}

describe("ordenarFila", () => {
  it("põe denunciados primeiro, preservando a ordem relativa dentro de cada grupo (estável)", () => {
    const entrada = [item("a", false), item("b", true), item("c", false), item("d", true)];
    const ids = ordenarFila(entrada).map((i) => i.id);
    expect(ids).toEqual(["b", "d", "a", "c"]);
  });

  it("não muta a lista de entrada", () => {
    const entrada = [item("a", false), item("b", true)];
    ordenarFila(entrada);
    expect(entrada.map((i) => i.id)).toEqual(["a", "b"]);
  });
});

describe("resumirFila", () => {
  it("conta total e denunciados", () => {
    expect(resumirFila([item("a", true), item("b", false), item("c", true)])).toEqual({ total: 3, denunciados: 2 });
    expect(resumirFila([])).toEqual({ total: 0, denunciados: 0 });
  });
});
