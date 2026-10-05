import { describe, expect, it } from "vitest";
import { humanizarChave } from "./humanizar-chave";

describe("humanizarChave — a chave desconhecida vira palavras, nunca a chave", () => {
  it("troca _ e - por espaço e põe a maiúscula inicial", () => {
    expect(humanizarChave("aguardando_orgao")).toBe("Aguardando orgao");
    expect(humanizarChave("xpto-desconhecido")).toBe("Xpto desconhecido");
  });

  it("CAIXA ALTA de enum vira frase normal", () => {
    expect(humanizarChave("EM_ANALISE_JURIDICA")).toBe("Em analise juridica");
  });

  it("colapsa separadores repetidos e apara as pontas", () => {
    expect(humanizarChave("__em__analise--")).toBe("Em analise");
  });

  it("vazio, só separador ou ausente cai no texto neutro dado (ou no padrão)", () => {
    expect(humanizarChave("")).toBe("Não informado");
    expect(humanizarChave("___")).toBe("Não informado");
    expect(humanizarChave(null)).toBe("Não informado");
    expect(humanizarChave(undefined, "Situação desconhecida")).toBe("Situação desconhecida");
  });
});
