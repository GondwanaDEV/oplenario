import { describe, expect, it } from "vitest";
import { formatarData } from "./formatar-data";

describe("formatarData", () => {
  it("formata ISO -> pt-BR dd/mm/aaaa", () => {
    expect(formatarData("2026-05-12T10:00:00Z")).toBe("12/05/2026");
  });

  it("entrada inválida -> devolve o valor cru, nunca lança", () => {
    expect(formatarData("não-é-data")).toBe("não-é-data");
  });
});
