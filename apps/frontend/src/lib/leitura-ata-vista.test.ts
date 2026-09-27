import { describe, expect, it } from "vitest";
import { linhaDoRegistro, tituloDaAta, trechosParaVoz } from "./leitura-ata-vista";

describe("leitura-ata-vista", () => {
  it("nome da ata", () => {
    expect(tituloDaAta({ id: "s", tipoSessao: "ordinaria", numeroSequencial: 11, abertaEm: "2026-09-12T18:00:00Z" }))
      .toBe("Ata da 11ª Sessão Ordinária (12/09/2026)");
    expect(tituloDaAta({ id: "s", tipoSessao: "extraordinaria", numeroSequencial: 2 })).toBe("Ata da 2ª Sessão Extraordinária");
  });

  it("linha do registro sem inventar nome", () => {
    expect(linhaDoRegistro({ modo: "dispensada", ataSessaoId: "s", ataVersao: 2, registradaEm: "2026-09-15T18:10:00Z", registradaPorNome: "João" }))
      .toMatch(/^Versão 2 · registrada por João às \d\d:10\.$/);
    expect(linhaDoRegistro({ modo: "dispensada", ataSessaoId: "s", ataVersao: 1, registradaEm: "2026-09-15T18:10:00Z" }))
      .toMatch(/^Versão 1 · registrada às/);
  });

  it("trechos por frase, com o parágrafo, e frase longa cortada na vírgula", () => {
    const t = trechosParaVoz("Aos doze dias. Reuniu-se a Câmara!\n\nNada mais.");
    expect(t).toEqual([
      { paragrafo: 0, texto: "Aos doze dias." },
      { paragrafo: 0, texto: "Reuniu-se a Câmara!" },
      { paragrafo: 1, texto: "Nada mais." },
    ]);
    const longa = Array.from({ length: 30 }, (_, i) => `vereador ${i}`).join(", ") + ".";
    const partes = trechosParaVoz(longa);
    expect(partes.length).toBeGreaterThan(1);
    expect(partes.every((p) => p.texto.length <= 220)).toBe(true);
    expect(partes.map((p) => p.texto).join(" ")).toBe(longa);
  });
});
