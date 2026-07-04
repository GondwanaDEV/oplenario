import { describe, expect, it } from "vitest";
import { arcoDashoffset } from "./anel-prazo";

describe("arcoDashoffset", () => {
  it("0 dias restantes de um total -> offset = 0 (arco cheio)", () => {
    expect(arcoDashoffset(0, 14, 40)).toBeCloseTo(0, 1);
  });
  it("todo o prazo restante -> offset = circunferencia (arco vazio)", () => {
    const circ = 2 * Math.PI * 40;
    expect(arcoDashoffset(14, 14, 40)).toBeCloseTo(circ, 1);
  });
  it("metade do prazo -> offset = metade da circunferencia", () => {
    const circ = 2 * Math.PI * 40;
    expect(arcoDashoffset(7, 14, 40)).toBeCloseTo(circ / 2, 1);
  });
  it("diasTotal 0 -> nao divide por zero (offset 0, trata como vencido/cheio)", () => {
    expect(arcoDashoffset(0, 0, 40)).toBe(0);
  });
});
