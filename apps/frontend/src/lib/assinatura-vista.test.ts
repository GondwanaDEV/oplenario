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
  it("pronto-pra-revisar quando há rascunho ou vigente", () => {
    expect(deriveEstadoAssinatura({ ...base, textoEstado: "rascunho", relatorio: "X", analise: "Y" })).toBe(
      "pronto-pra-revisar"
    );
  });
  it("sem-texto quando dados é null", () => {
    expect(deriveEstadoAssinatura(null)).toBe("sem-texto");
  });
});
