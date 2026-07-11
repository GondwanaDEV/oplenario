import { describe, expect, it } from "vitest";
import {
  derivarDadosMateria,
  derivarTimelineTramitacao,
  derivarPareceres,
  derivarEmendas,
} from "./ficha-materia-vista";
import type { FichaMateriaOut } from "./contrato-legislativo.gen";

// Onda B Slice 3 (Ficha da Matéria) — view-model puro, mesma disciplina fail-closed de tramitacao-vista.ts
// e proposicoes-vista.ts: `estado`/`de-estado`/`para-estado` são :string LIVRE (template-driven por
// câmara, eixo C); emendas têm enum FIXO em código (logic.clj `estados-emenda`), mas o view-model trata
// qualquer valor fora do mapa conhecido do MESMO jeito honesto — degrada pro cru, nunca lança.

const fichaBase: FichaMateriaOut = {
  proposicao: {
    id: "1",
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 42,
    urnLex: "urn:lex:br;ceara;fortaleza:camara.municipal:projeto.lei:2026;042",
    ementa: "Cria o Programa Municipal de Hortas Comunitárias",
    estado: "em_comissoes",
    lockVersion: 3,
    atualizadoEm: "2026-05-12T10:00:00Z",
  },
  tramitacao: [
    { deEstado: "protocolada", paraEstado: "em_comissoes", gatilho: "distribuir", ocorridoEm: "2026-04-08T09:00:00Z" },
    { deEstado: "em_comissoes", paraEstado: "em_pauta", gatilho: "incluir_pauta", ocorridoEm: "2026-05-12T10:00:00Z" },
  ],
  apensadas: [
    { apensadaId: "9", apensadaEm: "2026-04-20T00:00:00Z", motivoApensacao: "mesmo tema" },
  ],
  emendas: [
    { id: "e1", numeroLocal: 1, tipoEmenda: "modificativa", momentoApresentacao: "no_prazo", autorTexto: "Ver.ª Carla Souza", autorTipo: "vereador", estado: "aprovada" },
  ],
  pareceres: [
    { id: "p1", comissaoId: "c1", relatorId: "r1", votoRelator: "favorável", estado: "aprovado" },
  ],
};

describe("derivarDadosMateria", () => {
  it("deriva situação (via derivarTramitacao), contagem de apensados e datas relevantes", () => {
    const r = derivarDadosMateria(fichaBase);
    expect(r.situacao).toBe("Em comissões");
    expect(r.apensadosTotal).toBe(1);
    expect(r.apresentadaEm).toBe("2026-04-08T09:00:00Z");
    expect(r.ultimaAcaoEm).toBe("2026-05-12T10:00:00Z");
  });

  it("sem histórico de tramitação -> datas caem pra atualizadoEm, sem lançar", () => {
    const r = derivarDadosMateria({ ...fichaBase, tramitacao: [] });
    expect(r.apresentadaEm).toBe(fichaBase.proposicao.atualizadoEm);
    expect(r.ultimaAcaoEm).toBe(fichaBase.proposicao.atualizadoEm);
  });

  it("sem apensadas -> contagem zero", () => {
    const r = derivarDadosMateria({ ...fichaBase, apensadas: [] });
    expect(r.apensadosTotal).toBe(0);
  });

  it("estado desconhecido -> fail-closed (situação = estado cru, não lança)", () => {
    const fichaXpto = { ...fichaBase, proposicao: { ...fichaBase.proposicao, estado: "xpto" } };
    expect(() => derivarDadosMateria(fichaXpto)).not.toThrow();
    expect(derivarDadosMateria(fichaXpto).situacao).toBe("xpto");
  });
});

describe("derivarTimelineTramitacao", () => {
  it("ordena do mais recente pro mais antigo, com rótulos amigáveis de/para (fail-closed via derivarTramitacao)", () => {
    const r = derivarTimelineTramitacao(fichaBase.tramitacao);
    expect(r.map((i) => i.ocorridoEm)).toEqual(["2026-05-12T10:00:00Z", "2026-04-08T09:00:00Z"]);
    expect(r[0]).toMatchObject({
      deEstado: "em_comissoes",
      paraEstado: "em_pauta",
      gatilho: "incluir_pauta",
      rotuloDe: "Em comissões",
      rotuloPara: "Em pauta",
    });
  });

  it("lista vazia -> array vazio, sem lançar", () => {
    expect(derivarTimelineTramitacao([])).toEqual([]);
  });

  it("de-estado/para-estado fora do vocabulário ilustrativo -> rótulo cru, sem lançar", () => {
    const r = derivarTimelineTramitacao([
      { deEstado: "xpto-de", paraEstado: "xpto-para", gatilho: "g", ocorridoEm: "2026-01-01T00:00:00Z" },
    ]);
    expect(r[0].rotuloDe).toBe("xpto-de");
    expect(r[0].rotuloPara).toBe("xpto-para");
  });
});

describe("derivarPareceres", () => {
  it("mapeia estado conhecido -> rótulo + categoria (mostra TODOS os estados, não só ativos)", () => {
    const r = derivarPareceres([
      { id: "p1", comissaoId: "c1", relatorId: "r1", votoRelator: "favorável", estado: "aprovado" },
      { id: "p2", comissaoId: "c2", relatorId: null, votoRelator: null, estado: "rejeitado" },
      { id: "p3", comissaoId: "c3", estado: "em_elaboracao" },
    ]);
    expect(r).toHaveLength(3);
    expect(r[0]).toMatchObject({ estado: "aprovado", rotuloEstado: "Aprovado", categoria: "aprovada" });
    expect(r[1]).toMatchObject({ estado: "rejeitado", rotuloEstado: "Rejeitado", categoria: "arquivada" });
    expect(r[2]).toMatchObject({ categoria: "tram" });
  });

  it("estado fora do mapa conhecido -> rótulo cru, categoria neutra 'tram', sem lançar", () => {
    expect(() => derivarPareceres([{ id: "p1", comissaoId: "c1", estado: "xpto" }])).not.toThrow();
    const r = derivarPareceres([{ id: "p1", comissaoId: "c1", estado: "xpto" }]);
    expect(r[0].rotuloEstado).toBe("xpto");
    expect(r[0].categoria).toBe("tram");
  });

  it("lista vazia -> array vazio", () => {
    expect(derivarPareceres([])).toEqual([]);
  });
});

describe("derivarEmendas", () => {
  it("mapeia tipo + estado conhecidos -> rótulos + categoria (reusa categorizarSituacao)", () => {
    const r = derivarEmendas([
      { id: "e1", numeroLocal: 1, tipoEmenda: "modificativa", momentoApresentacao: "no_prazo", autorTexto: "Ver.ª Carla Souza", estado: "aprovada" },
      { id: "e2", numeroLocal: 2, tipoEmenda: "supressiva", momentoApresentacao: "plenario", estado: "rejeitada" },
    ]);
    expect(r[0]).toMatchObject({ rotuloTipo: "Modificativa", rotuloEstado: "Aprovada", categoria: "aprovada" });
    expect(r[1]).toMatchObject({ rotuloTipo: "Supressiva", rotuloEstado: "Rejeitada", categoria: "arquivada" });
  });

  it("todos os estados aparecem (não só ativos) incluindo os não-terminais", () => {
    const r = derivarEmendas([
      { id: "e1", numeroLocal: 1, tipoEmenda: "aditiva", momentoApresentacao: "no_prazo", estado: "apresentada" },
    ]);
    expect(r[0]).toMatchObject({ rotuloEstado: "Apresentada", categoria: "tram" });
  });

  it("tipo/estado fora do vocabulário -> rótulo cru, sem lançar", () => {
    expect(() =>
      derivarEmendas([{ id: "e1", numeroLocal: 1, tipoEmenda: "xpto-tipo", momentoApresentacao: "no_prazo", estado: "xpto-estado" }]),
    ).not.toThrow();
    const r = derivarEmendas([
      { id: "e1", numeroLocal: 1, tipoEmenda: "xpto-tipo", momentoApresentacao: "no_prazo", estado: "xpto-estado" },
    ]);
    expect(r[0].rotuloTipo).toBe("xpto-tipo");
    expect(r[0].rotuloEstado).toBe("xpto-estado");
  });

  it("lista vazia -> array vazio", () => {
    expect(derivarEmendas([])).toEqual([]);
  });
});
