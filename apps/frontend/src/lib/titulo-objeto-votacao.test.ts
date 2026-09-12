import { describe, expect, it } from "vitest";
import { tituloObjetoVotacao } from "./titulo-objeto-votacao";

describe("tituloObjetoVotacao", () => {
  it("proposição resolvida -> número real + ementa real (nunca 'Cockpit de votação' sem contexto)", () => {
    const t = tituloObjetoVotacao(
      { objetoTipo: "proposicao", proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 42, ementa: "Altera a Lei Orgânica quanto à Mesa" } },
      "pronto",
    );
    expect(t).toContain("42/2026");
    expect(t).toContain("Altera a Lei Orgânica quanto à Mesa");
  });

  it("redação final resolvida -> mesmo formato de proposição (o objeto-id É a matéria)", () => {
    const t = tituloObjetoVotacao(
      { objetoTipo: "redacao_final", proposicao: { tipo: "projeto_lei", ano: 2025, sequencial: 7, ementa: "Dispõe sobre X" } },
      "pronto",
    );
    expect(t).toContain("7/2025");
    expect(t).toContain("Dispõe sobre X");
  });

  it("emenda sem proposição resolvida -> rótulo honesto do TIPO, nunca vazio nem inventado", () => {
    const t = tituloObjetoVotacao({ objetoTipo: "emenda", proposicao: null }, "pronto");
    expect(t.toLowerCase()).toContain("emenda");
    expect(t).not.toBe("");
  });

  it("parecer sem proposição resolvida -> rótulo honesto do TIPO", () => {
    const t = tituloObjetoVotacao({ objetoTipo: "parecer", proposicao: null }, "pronto");
    expect(t.toLowerCase()).toContain("parecer");
  });

  it("requerimento sem proposição resolvida -> rótulo honesto do TIPO", () => {
    const t = tituloObjetoVotacao({ objetoTipo: "requerimento", proposicao: null }, "pronto");
    expect(t.toLowerCase()).toContain("requerimento");
  });

  it("tipo desconhecido (futuro) sem proposição -> ainda mostra o tipo cru, nunca quebra", () => {
    const t = tituloObjetoVotacao({ objetoTipo: "algo_novo", proposicao: null }, "pronto");
    expect(t).toContain("algo_novo");
  });

  it("carregando -> diz que está carregando, nunca um título em branco", () => {
    expect(tituloObjetoVotacao(null, "carregando")).not.toBe("");
    expect(tituloObjetoVotacao(null, "carregando").toLowerCase()).toContain("carregando");
  });

  it("erro -> diz que não conseguiu identificar, NUNCA finge (voto sem objeto identificado é pior que erro visível)", () => {
    const t = tituloObjetoVotacao(null, "erro");
    expect(t.toLowerCase()).toContain("não foi possível");
  });

  it("dados null com estado 'pronto' (inconsistência defensiva) -> ainda cai no honesto de erro, nunca undefined/crash", () => {
    expect(() => tituloObjetoVotacao(null, "pronto")).not.toThrow();
    expect(tituloObjetoVotacao(null, "pronto").length).toBeGreaterThan(0);
  });
});
