import { describe, expect, it } from "vitest";
import { formatarData, formatarHora } from "./formatar-data";

describe("formatarData", () => {
  it("formata ISO -> pt-BR dd/mm/aaaa", () => {
    expect(formatarData("2026-05-12T10:00:00Z")).toBe("12/05/2026");
  });

  it("entrada inválida -> devolve o valor cru, nunca lança", () => {
    expect(formatarData("não-é-data")).toBe("não-é-data");
  });
});

describe("formatarHora", () => {
  it("formata ISO -> pt-BR HH:mm (24h)", () => {
    expect(formatarHora("2026-05-21T14:02:00Z")).toBe("14:02");
  });

  it("entrada inválida -> devolve o valor cru, nunca lança", () => {
    expect(formatarHora("não-é-data")).toBe("não-é-data");
  });
});
