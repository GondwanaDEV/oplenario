import { describe, expect, it } from "vitest";
import { agruparPorSituacao } from "./atuacao-vista";

const m = (id: string, estado: string) => ({
  proposicaoId: id, tipo: "projeto_lei", ano: 2026, sequencial: 1, ementa: id, estado,
});

describe("agruparPorSituacao", () => {
  it("conta por estado do rito, com a largura proporcional ao que foi listado", () => {
    const r = agruparPorSituacao([m("a", "em_comissoes"), m("b", "em_comissoes"), m("c", "arquivada"), m("d", "x_y")]);
    expect(r).toEqual([
      { estado: "em_comissoes", rotulo: "Em comissões", quantidade: 2, largura: 50 },
      { estado: "arquivada", rotulo: "Arquivada", quantidade: 1, largura: 25 },
      { estado: "x_y", rotulo: "X y", quantidade: 1, largura: 25 },
    ]);
  });

  it("estado desconhecido nunca sai como chave crua de banco", () => {
    const [g] = agruparPorSituacao([m("a", "aguardando_leitura_em_plenario")]);
    expect(g.rotulo).toBe("Aguardando leitura em plenario");
  });

  it("lista vazia → nenhum grupo", () => {
    expect(agruparPorSituacao([])).toEqual([]);
  });
});
