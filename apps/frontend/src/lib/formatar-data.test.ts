import { describe, expect, it } from "vitest";
import { formatarData, formatarDiaSemana, formatarHora } from "./formatar-data";

describe("formatarDiaSemana", () => {
  it("ISO -> nome do dia da semana em pt-BR", () => {
    expect(formatarDiaSemana("2026-06-24T17:00:00Z")).toBe("quarta-feira");
  });

  it("ISO inválido -> devolve a string original (fail-closed, não lança)", () => {
    expect(formatarDiaSemana("não-é-data")).toBe("não-é-data");
  });
});

describe("formatarData/formatarHora (regressão — já existiam)", () => {
  it("continuam formatando dd/mm/aaaa e HH:mm", () => {
    expect(formatarData("2026-06-24T17:00:00Z")).toMatch(/^\d{2}\/\d{2}\/\d{4}$/);
    expect(formatarHora("2026-06-24T17:00:00Z")).toMatch(/^\d{2}:\d{2}$/);
  });
});
