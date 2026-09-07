import { describe, expect, it } from "vitest";
import { COMISSAO_SEM_NOME, nomeDeComissao, rotularComissao } from "./comissao-vista";

// Defeito #11 do ledger de prontidão (`MATA`): a tela do parecer imprimia o `comissaoId` cru e o
// público via `9119889e-…`. O detector aqui é ESTRUTURAL — não basta o caso feliz: nenhum rótulo de
// comissão pode ter forma de UUID, venha o valor de onde vier.
const FORMA_DE_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

describe("rotularComissao", () => {
  it("sem nome (o caso REAL de hoje: o backend não resolve id->nome) -> rótulo honesto", () => {
    expect(rotularComissao(null)).toBe(COMISSAO_SEM_NOME);
    expect(rotularComissao(undefined)).toBe(COMISSAO_SEM_NOME);
    expect(rotularComissao("")).toBe(COMISSAO_SEM_NOME);
    expect(rotularComissao("   ")).toBe(COMISSAO_SEM_NOME);
  });

  it("com nome de verdade -> o nome, sem enfeite", () => {
    expect(rotularComissao("Comissão de Constituição e Justiça")).toBe(
      "Comissão de Constituição e Justiça",
    );
    expect(rotularComissao("  Mesa Diretora  ")).toBe("Mesa Diretora");
  });

  it("um UUID NUNCA vira rótulo — é o defeito #11 voltando", () => {
    expect(rotularComissao("9119889e-1111-4222-8333-444444444444")).toBe(COMISSAO_SEM_NOME);
    expect(rotularComissao("9119889E-1111-4222-8333-444444444444")).toBe(COMISSAO_SEM_NOME);
  });

  it("nenhuma entrada plausível produz rótulo com forma de UUID", () => {
    for (const entrada of [
      null,
      undefined,
      "",
      "9119889e-1111-4222-8333-444444444444",
      "10000000-0000-0000-0000-000000000001",
      "Comissão de Finanças e Orçamento",
    ]) {
      expect(rotularComissao(entrada)).not.toMatch(FORMA_DE_UUID);
    }
  });
});

describe("nomeDeComissao", () => {
  it("sem nome ou com id -> null, pra quem prefere OMITIR a linha (o rail do editor)", () => {
    expect(nomeDeComissao(undefined)).toBeNull();
    expect(nomeDeComissao("")).toBeNull();
    expect(nomeDeComissao("9119889e-1111-4222-8333-444444444444")).toBeNull();
  });

  it("com nome -> o nome aparado", () => {
    expect(nomeDeComissao("  Comissão de Finanças  ")).toBe("Comissão de Finanças");
  });
});
