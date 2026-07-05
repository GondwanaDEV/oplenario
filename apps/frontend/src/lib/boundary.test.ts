import { describe, expect, it } from "vitest";
import { camelizarChaves, paraCamel } from "./boundary";

// jsonista (backend) serializa keywords Clojure kebab-case VERBATIM — o boundary kebab->camel é o único
// ponto em que o payload vira o formato camelCase que todo o código downstream espera (bug B7b). Estes
// casos já viviam implícitos em use-mesa.ts; extraídos aqui para src/lib/boundary.ts compartilhado
// (Task 0.1, Fatia A2.0).

describe("paraCamel", () => {
  it("converte kebab-case simples para camelCase", () => {
    expect(paraCamel("por-estado")).toBe("porEstado");
  });

  it("trata hífens consecutivos sem deixar maiúscula solta (fix b3f69c6)", () => {
    expect(paraCamel("a--b")).toBe("aB");
  });

  it("chave sem hífen permanece igual", () => {
    expect(paraCamel("estado")).toBe("estado");
  });
});

describe("camelizarChaves", () => {
  it("cameliza chaves de objeto de forma profunda, incluindo dentro de arrays", () => {
    expect(camelizarChaves({ "vence-em": 1, itens: [{ "objeto-id": 2 }] })).toEqual({
      venceEm: 1,
      itens: [{ objetoId: 2 }],
    });
  });

  it("preserva arrays de escalares e valores primitivos sem tentar camelizá-los", () => {
    expect(camelizarChaves(["a-b", 1, true, null])).toEqual(["a-b", 1, true, null]);
  });

  it("preserva escalares no topo (não são objeto nem array)", () => {
    expect(camelizarChaves("um-texto")).toBe("um-texto");
    expect(camelizarChaves(42)).toBe(42);
    expect(camelizarChaves(null)).toBeNull();
  });
});
