import { describe, expect, it } from "vitest";
import { destacar, resumoDaBusca, termosDaConsulta, vistaResultado } from "./busca-vista";

describe("vistaResultado", () => {
  it("proposição: número formatado, a ementa do core e o link para a ficha", () => {
    const v = vistaResultado(
      {
        tipo: "proposicao",
        trecho: "o que a IA indexou",
        proposicao: { id: "p/1", tipo: "projeto_lei", ano: 2026, sequencial: 7, ementa: "Dispõe sobre a merenda.", autorTexto: "Ver. Ana" },
      },
      0,
    );
    expect(v).toMatchObject({ rotuloTipo: "Proposição", detalhe: "Autoria: Ver. Ana", trecho: "Dispõe sobre a merenda." });
    expect(v.titulo).toMatch(/7\/2026$/);
    expect(v.href).toBe("/ficha-materia/p%2F1");
  });

  it("fala: a sessão, quem falou e quando, e o link para a transcrição", () => {
    const v = vistaResultado(
      {
        tipo: "transcricao",
        score: 0.5,
        trecho: "A merenda chegou atrasada.",
        sessao: { id: "s1", tipo: "ordinaria", numero: 12, data: "2026-09-20T18:00:00Z" },
        transcricaoId: "t1",
        inicio: 125.5,
        orador: "Ver. Ana",
      },
      3,
    );
    expect(v.titulo).toMatch(/^Sessão .+ nº 12 · \d{2}\/\d{2}\/2026$/);
    expect(v.detalhe).toBe("Ver. Ana · aos 2:05 da gravação");
    expect(v.trecho).toBe("A merenda chegou atrasada.");
    expect(v.href).toBe("/sessoes/s1/transcricao");
  });

  it("fala sem orador conhecido não inventa ninguém", () => {
    const v = vistaResultado(
      { tipo: "transcricao", score: 0.1, trecho: "x", sessao: { id: "s", tipo: "ordinaria", numero: 1 }, transcricaoId: "t" },
      0,
    );
    expect(v.detalhe).toBe("Orador não identificado");
  });
});

describe("destaque", () => {
  it("ignora acento e caixa, casa pelo radical e devolve o texto inteiro", () => {
    const termos = termosDaConsulta("Escola merenda de");
    expect(termos).toEqual(["escol", "meren"]);
    const pedacos = destacar("A MERENDA da Escolar chegou; ninguém comeu.", termos);
    expect(pedacos.filter((p) => p.destaque).map((p) => p.texto)).toEqual(["MERENDA", "Escolar"]);
    expect(pedacos.map((p) => p.texto).join("")).toBe("A MERENDA da Escolar chegou; ninguém comeu.");
  });

  it("acento na consulta também casa", () => {
    expect(destacar("a pavimentacao da rua", termosDaConsulta("pavimentação")).some((p) => p.destaque)).toBe(true);
  });

  it("sem termos, um pedaço só", () => {
    expect(destacar("abc", [])).toEqual([{ texto: "abc", destaque: false }]);
  });
});

it("resumo", () => {
  expect([resumoDaBusca(0), resumoDaBusca(1), resumoDaBusca(4)]).toEqual(["Nada encontrado.", "1 resultado", "4 resultados"]);
});
