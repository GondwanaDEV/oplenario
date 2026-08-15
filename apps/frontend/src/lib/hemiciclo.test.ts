import { describe, expect, it } from "vitest";
import { assentosHemiciclo, distribuirFileiras } from "./hemiciclo";

describe("distribuirFileiras", () => {
  it("usa UMA fileira para Casa pequena — o defeito era virar [1,1,2] com 4 membros", () => {
    expect(distribuirFileiras(1)).toEqual([1]);
    expect(distribuirFileiras(3)).toEqual([3]);
    expect(distribuirFileiras(4)).toEqual([4]);
    expect(distribuirFileiras(6)).toEqual([6]);
  });

  it("usa duas fileiras no porte médio, a de dentro menor", () => {
    for (const n of [7, 9, 11, 14]) {
      const f = distribuirFileiras(n);
      expect(f).toHaveLength(2);
      expect(f[0]).toBeLessThanOrEqual(f[1]);
    }
  });

  it("preserva a geometria original do desenho no porte grande (43 -> 11/15/17)", () => {
    expect(distribuirFileiras(43)).toEqual([11, 15, 17]);
  });

  it("nunca perde nem inventa assento, e nunca devolve fileira vazia", () => {
    for (let n = 1; n <= 120; n++) {
      const f = distribuirFileiras(n);
      expect(f.reduce((a, b) => a + b, 0)).toBe(n);
      expect(f.every((q) => q >= 1)).toBe(true);
    }
  });

  it("nenhuma fileira fica com menos de 3 assentos quando há mais de uma fileira", () => {
    // Esta é a asserção que reprova o defeito: fileira de 1 ou 2 é o que produzia o empilhamento.
    for (let n = 1; n <= 120; n++) {
      const f = distribuirFileiras(n);
      if (f.length > 1) expect(Math.min(...f)).toBeGreaterThanOrEqual(3);
    }
  });
});

describe("assentosHemiciclo", () => {
  it("com 4 membros devolve 4 pontos DISTINTOS no eixo x — o sintoma visível do defeito", () => {
    const a = assentosHemiciclo(4);
    expect(a).toHaveLength(4);
    const xs = a.map((p) => Math.round(p.x));
    expect(new Set(xs).size).toBe(4);
  });

  it("nunca empilha dois assentos na mesma coordenada", () => {
    for (const n of [1, 2, 4, 5, 7, 13, 21, 43, 55]) {
      const a = assentosHemiciclo(n);
      const chaves = new Set(a.map((p) => `${p.x.toFixed(2)},${p.y.toFixed(2)}`));
      expect(chaves.size).toBe(n);
    }
  });

  it("mantém todo assento dentro do viewBox 240x130 das duas telas", () => {
    for (const n of [1, 4, 9, 21, 43, 55]) {
      for (const p of assentosHemiciclo(n)) {
        expect(p.x).toBeGreaterThanOrEqual(0);
        expect(p.x).toBeLessThanOrEqual(240);
        expect(p.y).toBeGreaterThanOrEqual(0);
        expect(p.y).toBeLessThanOrEqual(130);
      }
    }
  });

  it("a fileira única fica no arco EXTERNO (não no miolo)", () => {
    const a = assentosHemiciclo(5);
    // extremos do arco de raio 90 a partir do centro x=120
    expect(Math.round(a[0].x)).toBe(30);
    expect(Math.round(a[4].x)).toBe(210);
  });
});
