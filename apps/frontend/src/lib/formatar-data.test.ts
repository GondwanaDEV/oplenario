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

// A suíte roda com TZ=America/Fortaleza (vitest.config.ts). Sem um fuso a OESTE de Greenwich estes
// testes não conseguem reprovar: em UTC o parser de `new Date("2026-08-15")` acerta o dia por acidente.
describe("formatarData", () => {
  it("preserva o dia de uma data SEM hora (o defeito recuava um dia em fuso a oeste)", () => {
    expect(formatarData("2026-08-15")).toBe("15/08/2026");
    expect(formatarData("2026-01-01")).toBe("01/01/2026");
    expect(formatarData("2026-12-31")).toBe("31/12/2026");
  });

  it("nunca recua o dia em NENHUMA data do ano", () => {
    for (let mes = 1; mes <= 12; mes++) {
      const iso = `2026-${String(mes).padStart(2, "0")}-01`;
      expect(formatarData(iso)).toBe(`01/${String(mes).padStart(2, "0")}/2026`);
    }
  });

  it("continua correto para timestamp com hora (o caminho que os 4 chamadores originais usam)", () => {
    // meio-dia local: longe da fronteira do dia em qualquer fuso brasileiro
    expect(formatarData("2026-08-15T12:00:00")).toBe("15/08/2026");
  });

  it("devolve a entrada quando não é data reconhecível, em vez de 'Invalid Date'", () => {
    expect(formatarData("")).toBe("");
    expect(formatarData("sem data")).toBe("sem data");
  });
});
