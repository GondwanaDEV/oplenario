import { describe, expect, it } from "vitest";
import { campoLongo, faltando, podeVerPrevia, rotuloCampo, seloDaAssinatura } from "./requerimento-vista";

describe("rotuloCampo — o nome humano do placeholder do modelo", () => {
  it("campos conhecidos ganham rótulo com acento", () => {
    expect(rotuloCampo("destinatario")).toBe("Destinatário");
    expect(rotuloCampo("endereco_familia")).toBe("Endereço da família");
  });
  it("campo desconhecido: troca _ por espaço e capitaliza, sem inventar", () => {
    expect(rotuloCampo("numero_do_oficio")).toBe("Numero do oficio");
    expect(rotuloCampo("x")).toBe("X");
  });
});

describe("campoLongo — texto corrido vira área de texto", () => {
  it("justificativa e pedido são longos; o resto é linha única", () => {
    expect(campoLongo("justificativa")).toBe(true);
    expect(campoLongo("pedido")).toBe(true);
    expect(campoLongo("destinatario")).toBe(false);
  });
});

describe("faltando / podeVerPrevia", () => {
  const campos = ["destinatario", "assunto"];
  it("lista o que falta, na ordem do modelo; espaço em branco não conta como preenchido", () => {
    expect(faltando(campos, { destinatario: "  ", assunto: "x" })).toEqual(["destinatario"]);
    expect(faltando(campos, {})).toEqual(["destinatario", "assunto"]);
    expect(faltando(campos, { destinatario: "a", assunto: "b" })).toEqual([]);
  });
  it("prévia só com modelo, ementa e todos os campos", () => {
    expect(podeVerPrevia({ modeloId: "m", ementa: "E", campos, valores: { destinatario: "a", assunto: "b" } })).toBe(true);
    expect(podeVerPrevia({ modeloId: "m", ementa: "  ", campos, valores: { destinatario: "a", assunto: "b" } })).toBe(false);
    expect(podeVerPrevia({ modeloId: null, ementa: "E", campos, valores: { destinatario: "a", assunto: "b" } })).toBe(false);
    expect(podeVerPrevia({ modeloId: "m", ementa: "E", campos, valores: { destinatario: "a" } })).toBe(false);
  });
});

describe("seloDaAssinatura — honestidade sobre o que foi assinado", () => {
  it("o selo provisório diz que ainda não é ICP-Brasil", () => {
    expect(seloDaAssinatura("STUB-ICP-v0")).toEqual({
      provisorio: true,
      texto: "Assinatura eletrônica registrada no sistema (selo provisório — a certificação ICP-Brasil entra numa fase seguinte).",
    });
  });
  it("outro algoritmo: mostra o nome, sem prometer nada", () => {
    expect(seloDaAssinatura("ICP-Brasil-CAdES")).toEqual({ provisorio: false, texto: "Assinado digitalmente (ICP-Brasil-CAdES)." });
  });
});
