import { describe, expect, it } from "vitest";
import { linhaDoTempo, opcoesDePresidencia, presidentePadrao } from "./atos-mesa-vista";
import type { AtosMesaOut, ComposicaoMembroOut } from "./contrato-sessoes.gen";

const membros: ComposicaoMembroOut[] = [
  { vereadorId: "v-ana", nomeParlamentar: "Ana Castro", cargoMesa: null },
  { vereadorId: "v-helena", nomeParlamentar: "Helena Past", cargoMesa: "Vice-presidente" },
  { vereadorId: "v-bruno", nomeParlamentar: "Bruno Lima", cargoMesa: "Presidente" },
  { vereadorId: "v-carla", nomeParlamentar: null, cargoMesa: null },
];

describe("presidentePadrao", () => {
  it("pré-seleciona o Presidente da Mesa, sem depender de caixa", () => {
    expect(presidentePadrao(membros)).toBe("v-bruno");
    expect(presidentePadrao([{ vereadorId: "x", nomeParlamentar: "X", cargoMesa: "presidente" }])).toBe("x");
  });

  it("vice-presidente não é confundido com presidente; sem Presidente → null (nunca adivinha)", () => {
    expect(presidentePadrao(membros.filter((m) => m.vereadorId !== "v-bruno"))).toBeNull();
  });
});

describe("opcoesDePresidencia", () => {
  it("Presidente primeiro, depois a Mesa, depois os demais em ordem alfabética; sem nome → rótulo neutro", () => {
    expect(opcoesDePresidencia(membros)).toEqual([
      { vereadorId: "v-bruno", rotulo: "Bruno Lima · Presidente" },
      { vereadorId: "v-helena", rotulo: "Helena Past · Vice-presidente" },
      { vereadorId: "v-ana", rotulo: "Ana Castro" },
      { vereadorId: "v-carla", rotulo: "Vereador(a) v-carla" },
    ]);
  });
});

describe("linhaDoTempo", () => {
  const atos: AtosMesaOut = {
    sessaoId: "s1",
    decisoes: [
      { id: "d1", presidenteId: "v-bruno", questao: "Cabe aparte?", decisao: "Indeferida", decididoEm: "2026-09-24T14:05:00Z", fundamentacao: "Art. 90" },
    ],
    incidentes: [
      { id: "i1", tipo: "pedido_vista", resultado: "deferido", descricao: "Vista do PL 22", ocorridoEm: "2026-09-24T14:10:00Z", requerenteId: "v-ana" },
      { id: "i2", tipo: "urgencia", resultado: "indeferido", descricao: "Urgência do PL 31", ocorridoEm: "2026-09-24T14:05:00Z" },
    ],
  };

  it("junta as duas listas, mais recente primeiro; empate: decisão antes de incidente", () => {
    expect(linhaDoTempo(atos, membros).map((a) => a.id)).toEqual(["i1", "d1", "i2"]);
  });

  it("decisão: questão, decisão, fundamentação e quem decidiu pelo nome", () => {
    const d = linhaDoTempo(atos, membros).find((a) => a.id === "d1");
    expect(d).toMatchObject({
      natureza: "decisao", titulo: "Questão de ordem", texto: "Cabe aparte?", desfecho: "Indeferida",
      nota: "Art. 90", pessoa: "Decidiu: Bruno Lima", resultado: null,
    });
  });

  it("incidente: rótulo do tipo, resultado em português, requerente quando houver", () => {
    const [i1, , i2] = linhaDoTempo(atos, membros);
    expect(i1).toMatchObject({ titulo: "Pedido de vista", desfecho: "Deferido", pessoa: "Requereu: Ana Castro", resultado: "deferido", nota: null });
    expect(i2).toMatchObject({ titulo: "Urgência", desfecho: "Indeferido", pessoa: null });
  });

  it("sem atos carregados → lista vazia", () => {
    expect(linhaDoTempo(null, membros)).toEqual([]);
  });
});
