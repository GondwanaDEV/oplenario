import { describe, expect, it } from "vitest";
import { emBlocos, especieUnica, linhaDaEmConferencia, linhaDaVigente, marcaDoDispositivo, type DispositivoOut } from "./normas-vista";

const d = (endereco: string, rotulo: string, tipo: DispositivoOut["tipo"], agrupador: string | null = null): DispositivoOut => ({
  endereco, rotulo, tipo, pai: null, ordem: 0, texto: "x", agrupador,
});

describe("normas-vista", () => {
  it("a marca que abre cada dispositivo no texto", () => {
    expect(marcaDoDispositivo(d("art12", "art. 12", "artigo"))).toBe("Art. 12");
    expect(marcaDoDispositivo(d("art1", "art. 1º", "artigo"))).toBe("Art. 1º");
    expect(marcaDoDispositivo(d("art1_par1", "art. 1º, § 1º", "paragrafo"))).toBe("§ 1º");
    expect(marcaDoDispositivo(d("art1_par1u", "art. 1º, parágrafo único", "paragrafo"))).toBe("Parágrafo único.");
    expect(marcaDoDispositivo(d("art1_cpt_inc2", "art. 1º, II", "inciso"))).toBe("II –");
    expect(marcaDoDispositivo(d("x", "art. 1º, II, a", "alinea"))).toBe("a)");
    expect(marcaDoDispositivo(d("x", "art. 1º, II, a, item 3", "item"))).toBe("3.");
  });

  it("agrupa pelo título/capítulo sem reordenar", () => {
    const b = emBlocos([d("a", "a", "artigo", "T I"), d("b", "b", "artigo", "T I"), d("c", "c", "artigo", "T II")]);
    expect(b.map((x) => [x.agrupador, x.dispositivos.length])).toEqual([["T I", 2], ["T II", 1]]);
  });

  it("linhas de estado em palavras da Casa", () => {
    const v = { id: "v", estado: "vigente" as const, consolidadaAte: "2026-06-30", fonte: "f", nDispositivos: 214, nAlertas: 0,
      enviadaEm: "2026-09-27T10:00:00Z", decididaEm: "2026-09-27T11:00:00Z" };
    expect(linhaDaVigente(v)).toBe("conferida em 27/09/2026 · consolidada até 30/06/2026 · 214 dispositivos");
    expect(linhaDaEmConferencia({ ...v, nAlertas: 2 })).toBe("Enviada em 27/09/2026 · 214 dispositivos · 2 pontos a conferir");
    expect(especieUnica("lei_organica")).toBe(true);
    expect(especieUnica("lei")).toBe(false);
  });
});
