import { describe, expect, it } from "vitest";
import { corPorPosicao } from "./cor-por-posicao";

describe("corPorPosicao — a cor vem da posição, nunca do nome", () => {
  it("uma fatia só usa a primeira âncora", () => {
    expect(corPorPosicao(0, 1)).toBe("var(--seg-1)");
  });

  it("primeira e última fatias caem exatamente nas pontas da rampa", () => {
    expect(corPorPosicao(0, 5)).toBe("color-mix(in srgb, var(--seg-2) 0%, var(--seg-1))");
    expect(corPorPosicao(4, 5)).toBe("color-mix(in srgb, var(--seg-3) 100%, var(--seg-2))");
  });

  it("a fatia do meio é a âncora do meio (--seg-2 inteira)", () => {
    expect(corPorPosicao(2, 5)).toBe("color-mix(in srgb, var(--seg-2) 100%, var(--seg-1))");
  });

  it("de 2 a 12 estágios, todas as cores são distintas", () => {
    for (let n = 2; n <= 12; n++) {
      const cores = Array.from({ length: n }, (_, i) => corPorPosicao(i, n));
      expect(new Set(cores).size, `n=${n}`).toBe(n);
    }
  });

  it("nunca devolve cinza fixo nem hex: só a rampa do tema", () => {
    for (let i = 0; i < 8; i++) expect(corPorPosicao(i, 8)).not.toMatch(/#/);
  });
});
