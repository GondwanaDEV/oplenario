import { describe, expect, it } from "vitest";
import { filtrarColegas, iniciais, pendentesAoProtocolar, resumoSubscricoes, rotuloSubscricao } from "./subscricao-vista";

const sub = (vereadorNome: string, estado: "pendente" | "confirmada" | "recusada" | "nao_consta") => ({
  vereadorNome,
  estado,
  respondidaEm: null,
});

describe("rotuloSubscricao", () => {
  it("cada estado do convite com o texto do desenho", () => {
    expect(rotuloSubscricao("pendente")).toEqual({ texto: "Aguarda confirmação", tom: "pend" });
    expect(rotuloSubscricao("confirmada")).toEqual({ texto: "Subscrição confirmada", tom: "conf" });
    expect(rotuloSubscricao("nao_consta").texto).toBe("Não consta");
  });
});

describe("resumoSubscricoes", () => {
  it("quantos confirmaram, com concordância", () => {
    expect(resumoSubscricoes([sub("A", "confirmada"), sub("B", "pendente")])).toBe("1 de 2 coautores confirmou");
    expect(resumoSubscricoes([sub("A", "pendente")])).toBe("O coautor ainda não confirmou");
    expect(resumoSubscricoes([sub("A", "pendente"), sub("B", "recusada")])).toBe("Nenhum dos 2 coautores confirmou ainda");
    expect(resumoSubscricoes([sub("A", "confirmada"), sub("B", "confirmada")])).toBe("2 de 2 coautores confirmaram");
  });
});

describe("pendentesAoProtocolar", () => {
  it("só quem ainda não respondeu fica de fora (recusa já é resposta)", () => {
    expect(pendentesAoProtocolar([sub("A", "confirmada"), sub("B", "pendente"), sub("C", "recusada")])).toEqual(["B"]);
  });
});

describe("filtrarColegas", () => {
  const colegas = [
    { id: "1", nome: "Bia Lima", partido: "PSB" },
    { id: "2", nome: "Caio Reis", partido: null },
    { id: "3", nome: "Érica Sá", partido: "PT" },
  ];
  it("por nome ou partido, sem acento nem caixa", () => {
    expect(filtrarColegas(colegas, "erica").map((c) => c.id)).toEqual(["3"]);
    expect(filtrarColegas(colegas, "psb").map((c) => c.id)).toEqual(["1"]);
    expect(filtrarColegas(colegas, "  ")).toHaveLength(3);
  });
});

describe("iniciais", () => {
  it("primeira e última", () => {
    expect(iniciais("Ana Maria Prado")).toBe("AP");
    expect(iniciais("Dora")).toBe("D");
  });
});
