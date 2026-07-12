import { describe, expect, it } from "vitest";
import { deriveEstadoAssinatura } from "./assinatura-vista";
import type { ParecerEditorOut } from "./contrato-legislativo.gen";

const base: ParecerEditorOut = {
  id: "p1", objetoTipo: "proposicao", objetoId: "obj1", comissaoId: "c1",
  estado: "com_relator", templateId: "t1", lockVersion: 0, criadoEm: "2026-07-11T00:00:00Z",
  textoEstado: "vazio",
};

describe("deriveEstadoAssinatura", () => {
  it("sem-texto quando textoEstado é vazio", () => {
    expect(deriveEstadoAssinatura(base)).toBe("sem-texto");
  });
  it("pronto-pra-revisar quando há rascunho ou vigente E o relator já registrou a conclusão (votoRelator)", () => {
    expect(
      deriveEstadoAssinatura({ ...base, textoEstado: "rascunho", relatorio: "X", analise: "Y", votoRelator: "favoravel" })
    ).toBe("pronto-pra-revisar");
  });
  it("sem-texto quando dados é null", () => {
    expect(deriveEstadoAssinatura(null)).toBe("sem-texto");
  });
  it("sem-voto quando há texto mas o relator ainda não registrou a conclusão (votoRelator null) — NUNCA pronto-pra-revisar/fabricar voto", () => {
    expect(
      deriveEstadoAssinatura({ ...base, textoEstado: "rascunho", relatorio: "X", analise: "Y", votoRelator: null })
    ).toBe("sem-voto");
  });
});
