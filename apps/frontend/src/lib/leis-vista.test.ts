import { describe, expect, it } from "vitest";
import { lerFiltro, lerPagina, rotuloDoTipo, tituloDaNorma, consultaDaLista, consultaDoFiltro, TIPOS_DE_NORMA } from "./leis-vista";

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

describe("lerPagina", () => {
  it("ausente, zero, negativa, texto ou grande demais: a primeira página — nunca uma lista em branco por endereço errado", () => {
    for (const p of [undefined, "", "0", "-2", "abc", "1.5", "9999999999"]) expect(lerPagina(p)).toBe(1);
  });

  it("inteiro de 1 em diante vale; repetido usa o primeiro", () => {
    expect(lerPagina("1")).toBe(1);
    expect(lerPagina("3")).toBe(3);
    expect(lerPagina(["2", "5"])).toBe(2);
  });
});

describe("consultaDaLista", () => {
  const f = { tipo: "lei", ano: "2026", numero: "", ignorados: [] };
  it("o filtro e a página, na mesma ordem; a primeira página não leva ?pagina=", () => {
    expect(Object.entries(consultaDaLista(f, 3))).toEqual([["tipo", "lei"], ["ano", "2026"], ["pagina", "3"]]);
    expect(consultaDaLista(f, 1)).toEqual({ tipo: "lei", ano: "2026" });
    expect(consultaDaLista({ tipo: "", ano: "", numero: "", ignorados: [] }, 1)).toEqual({});
  });
});

describe("consultaDoFiltro", () => {
  it("só envia o que está preenchido", () => {
    expect(consultaDoFiltro({ tipo: "lei", ano: "", numero: "7", ignorados: [] })).toEqual({ tipo: "lei", numero: "7" });
    expect(consultaDoFiltro({ tipo: "", ano: "", numero: "", ignorados: [] })).toEqual({});
  });
});
