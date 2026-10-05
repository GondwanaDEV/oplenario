import { describe, expect, it } from "vitest";
import { lerFiltro, rotuloDoTipo, tituloDaNorma, consultaDoFiltro, TIPOS_DE_NORMA } from "./leis-vista";

describe("rotuloDoTipo / tituloDaNorma", () => {
  it("os 5 tipos do acervo saem em palavras, nunca o código", () => {
    expect(TIPOS_DE_NORMA.map((t) => t.valor)).toEqual(["lei", "lei_complementar", "resolucao", "decreto_legislativo", "emenda_lom"]);
    expect(rotuloDoTipo("lei")).toBe("Lei");
    expect(rotuloDoTipo("lei_complementar")).toBe("Lei complementar");
    expect(rotuloDoTipo("resolucao")).toBe("Resolução");
    expect(rotuloDoTipo("decreto_legislativo")).toBe("Decreto legislativo");
    expect(rotuloDoTipo("emenda_lom")).toBe("Emenda à Lei Orgânica");
  });

  it("tipo desconhecido não vaza o código cru", () => {
    expect(rotuloDoTipo("portaria_especial")).toBe("Portaria especial");
  });

  it("título: tipo, número e ano", () => {
    expect(tituloDaNorma({ tipoNorma: "lei", numero: 12, ano: 2026 })).toBe("Lei nº 12/2026");
    expect(tituloDaNorma({ tipoNorma: "emenda_lom", numero: 3, ano: 2025 })).toBe("Emenda à Lei Orgânica nº 3/2025");
  });
});

describe("lerFiltro", () => {
  it("sem parâmetros: filtro vazio, nada ignorado", () => {
    expect(lerFiltro({})).toEqual({ tipo: "", ano: "", numero: "", ignorados: [] });
  });

  it("aceita tipo conhecido e números inteiros", () => {
    expect(lerFiltro({ tipo: "lei", ano: "2026", numero: "12" })).toEqual({ tipo: "lei", ano: "2026", numero: "12", ignorados: [] });
  });

  it("ano/número não inteiro e tipo desconhecido são IGNORADOS e declarados, nunca mandados ao servidor", () => {
    const f = lerFiltro({ tipo: "xpto", ano: "20x6", numero: "-1" });
    expect(f).toEqual({ tipo: "", ano: "", numero: "", ignorados: ["tipo", "ano", "número"] });
  });

  it("número grande demais para o servidor (int4) é ignorado", () => {
    expect(lerFiltro({ ano: "99999999999" }).ignorados).toEqual(["ano"]);
  });

  it("parâmetro repetido (array) usa o primeiro", () => {
    expect(lerFiltro({ ano: ["2026", "2025"] }).ano).toBe("2026");
  });
});

describe("consultaDoFiltro", () => {
  it("só envia o que está preenchido", () => {
    expect(consultaDoFiltro({ tipo: "lei", ano: "", numero: "7", ignorados: [] })).toEqual({ tipo: "lei", numero: "7" });
    expect(consultaDoFiltro({ tipo: "", ano: "", numero: "", ignorados: [] })).toEqual({});
  });
});
