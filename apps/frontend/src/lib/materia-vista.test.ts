import { describe, expect, it } from "vitest";
import { derivarRef, escolherDestaque } from "./materia-vista";
import type { MateriaOut } from "./contrato-portal.gen";

// Task 1.1 (Fatia A2.1, Portal do Cidadão) — view-model puro da matéria: a referência curta ("PL
// 042/2026"), e a escolha de destaque + "mais em tramitação" a partir da listagem pública real
// (GET /portal/casa/{ente}/materias, já ordenada desc por ano/sequencial — confirmado em
// transparencia/db/materia.clj:listar-em-tramitacao).

function materia(parcial: Partial<MateriaOut>): MateriaOut {
  return {
    proposicaoId: "id-1",
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 42,
    urnLex: "urn:lex:br;ce;fortaleza:camara.municipal:projeto.lei:2026;042",
    ementa: "Cria o Programa Municipal de Hortas Comunitárias.",
    autorTipo: "vereador",
    autorTexto: "Ver.ª Helena Matos",
    estado: "protocolada",
    ...parcial,
  };
}

describe("derivarRef", () => {
  // mapa tipo->sigla confirmado contra o vocabulário REAL de legislativo/logic.clj (`def tipos`, grep
  // 04/07/2026): projeto_lei, projeto_lei_complementar, projeto_resolucao, projeto_decreto_legislativo,
  // proposta_emenda_lom, indicacao, requerimento, mocao. PL/PLC confirmados contra o design-system
  // (portal-cidadao.html usa "PL 042/2026" e "PLC 004/2026" lado a lado); os demais seguem a convenção
  // de sigla legislativa brasileira padrão (sem fonte de design para confirmar — best effort, documentado).
  it.each([
    ["projeto_lei", "PL"],
    ["projeto_lei_complementar", "PLC"],
    ["projeto_resolucao", "PR"],
    ["projeto_decreto_legislativo", "PDL"],
    ["proposta_emenda_lom", "PELOM"],
    ["indicacao", "IND"],
    ["requerimento", "REQ"],
    ["mocao", "MOC"],
  ])("tipo %s -> sigla %s", (tipo, sigla) => {
    expect(derivarRef(materia({ tipo, sequencial: 42, ano: 2026 }))).toBe(`${sigla} 042/2026`);
  });

  it("zero-pad do sequencial em 3 dígitos", () => {
    expect(derivarRef(materia({ sequencial: 4 }))).toBe("PL 004/2026");
    expect(derivarRef(materia({ sequencial: 1234 }))).toBe("PL 1234/2026");
  });

  it("fail-closed: tipo desconhecido -> sigla = o tipo cru em maiúsculas (nunca lança)", () => {
    expect(derivarRef(materia({ tipo: "campanha_estranha", sequencial: 5, ano: 2026 }))).toBe(
      "CAMPANHA_ESTRANHA 005/2026",
    );
  });
});

describe("escolherDestaque", () => {
  it("lista vazia -> destaque null, mais-tramitação vazia", () => {
    expect(escolherDestaque([])).toEqual({ destaque: null, maisTramitacao: [] });
  });

  it("destaque = o 1º item (a lista já vem ordenada desc do backend); mais-tramitação = os próximos 3", () => {
    const itens = [
      materia({ proposicaoId: "1", sequencial: 51 }),
      materia({ proposicaoId: "2", sequencial: 42 }),
      materia({ proposicaoId: "3", sequencial: 38 }),
      materia({ proposicaoId: "4", sequencial: 22 }),
      materia({ proposicaoId: "5", sequencial: 4 }),
    ];
    const { destaque, maisTramitacao } = escolherDestaque(itens);
    expect(destaque?.proposicaoId).toBe("1");
    expect(maisTramitacao.map((m) => m.proposicaoId)).toEqual(["2", "3", "4"]);
  });

  it("com menos de 4 itens, mais-tramitação tem só o que sobrar (sem lançar)", () => {
    const itens = [materia({ proposicaoId: "1" }), materia({ proposicaoId: "2" })];
    const { destaque, maisTramitacao } = escolherDestaque(itens);
    expect(destaque?.proposicaoId).toBe("1");
    expect(maisTramitacao.map((m) => m.proposicaoId)).toEqual(["2"]);
  });

  it("a vista compõe ref/título/situação/permalink/estágios a partir da matéria", () => {
    const { destaque } = escolherDestaque([
      materia({
        proposicaoId: "p1",
        tipo: "projeto_lei",
        sequencial: 42,
        ano: 2026,
        ementa: "Cria o Programa Municipal de Hortas Comunitárias.",
        urnLex: "urn:lex:br;ce;fortaleza:camara.municipal:projeto.lei:2026;042",
        estado: "segundo_turno",
      }),
    ]);
    expect(destaque).toEqual({
      ref: "PL 042/2026",
      titulo: "Cria o Programa Municipal de Hortas Comunitárias.",
      situacao: "Em 2º turno",
      permalink: "urn:lex:br;ce;fortaleza:camara.municipal:projeto.lei:2026;042",
      proposicaoId: "p1",
      autorTexto: "Ver.ª Helena Matos",
      estagios: [
        { rotulo: "Protocolo", situacao: "concluido" },
        { rotulo: "Comissões", situacao: "concluido" },
        { rotulo: "1º turno", situacao: "concluido" },
        { rotulo: "2º turno", situacao: "ativo" },
        { rotulo: "Sanção", situacao: "pendente" },
      ],
    });
  });

  it("autorTexto ausente (null/undefined na origem) -> null honesto, nunca undefined/inventado", () => {
    const { destaque } = escolherDestaque([materia({ autorTexto: undefined, autorTipo: undefined })]);
    expect(destaque?.autorTexto).toBeNull();
  });
});
