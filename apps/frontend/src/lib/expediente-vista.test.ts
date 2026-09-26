import { describe, expect, it } from "vitest";
import {
  rotularObjetoTipo,
  corObjetoTipo,
  rotularSentido,
  formatarNumeroProtocolo,
  rotularTipoDocumento,
  rotularEstadoDocumento,
  documentoEhTerminal,
  textoCarimbo,
  destacarMerge,
  paresParaMapaDados,
  modelosParaGerar,
  TIPOS_DOCUMENTO,
} from "./expediente-vista";

describe("rotularObjetoTipo / corObjetoTipo", () => {
  it("mapeia os 6 objeto-tipo conhecidos do protocolo geral", () => {
    expect(rotularObjetoTipo("proposicao")).toBe("Proposição");
    expect(corObjetoTipo("proposicao")).toBe("jade");
    expect(rotularObjetoTipo("documento")).toBe("Documento");
    expect(corObjetoTipo("documento")).toBe("cobalto");
    expect(rotularObjetoTipo("oficio_recebido")).toBe("Ofício recebido");
    expect(corObjetoTipo("oficio_recebido")).toBe("cobalto");
    expect(rotularObjetoTipo("requerimento_cidadao")).toBe("Requerimento");
    expect(corObjetoTipo("requerimento_cidadao")).toBe("amarelo");
    expect(rotularObjetoTipo("processo_administrativo")).toBe("Processo administrativo");
    expect(corObjetoTipo("processo_administrativo")).toBe("telha");
    expect(rotularObjetoTipo("outro")).toBe("Outro");
    expect(corObjetoTipo("outro")).toBe("neutro");
  });

  it("fail-closed: objeto-tipo fora do vocabulário -> valor cru / cor neutra", () => {
    expect(rotularObjetoTipo("desconhecido")).toBe("desconhecido");
    expect(corObjetoTipo("desconhecido")).toBe("neutro");
  });
});

describe("rotularSentido", () => {
  it("recebido -> Entrada", () => {
    expect(rotularSentido("recebido")).toEqual({ rotulo: "Entrada", direcao: "entrada" });
  });
  it("expedido -> Saída", () => {
    expect(rotularSentido("expedido")).toEqual({ rotulo: "Saída", direcao: "saida" });
  });
  it("interno -> Interno", () => {
    expect(rotularSentido("interno")).toEqual({ rotulo: "Interno", direcao: "interno" });
  });
  it("fail-closed: fora do vocabulário -> valor cru, direção interno", () => {
    expect(rotularSentido("x")).toEqual({ rotulo: "x", direcao: "interno" });
  });
});

describe("formatarNumeroProtocolo", () => {
  it("formata ano/numero com 5 dígitos", () => {
    expect(formatarNumeroProtocolo(847, 2026)).toBe("2026/00847");
    expect(formatarNumeroProtocolo(1, 2026)).toBe("2026/00001");
    expect(formatarNumeroProtocolo(123456, 2026)).toBe("2026/123456");
  });
});

describe("rotularTipoDocumento", () => {
  it("mapeia os 6 tipos conhecidos", () => {
    expect(rotularTipoDocumento("oficio")).toBe("Ofício");
    expect(rotularTipoDocumento("certidao")).toBe("Certidão");
    expect(rotularTipoDocumento("requerimento_administrativo")).toBe("Requerimento");
    expect(rotularTipoDocumento("convite")).toBe("Convite");
    expect(rotularTipoDocumento("mala_direta")).toBe("Mala-direta");
    expect(rotularTipoDocumento("outro")).toBe("Outro");
  });
  it("fail-closed: fora do vocabulário -> valor cru", () => {
    expect(rotularTipoDocumento("x")).toBe("x");
  });
});

describe("rotularEstadoDocumento / documentoEhTerminal", () => {
  it("rascunho/emitido", () => {
    expect(rotularEstadoDocumento("rascunho")).toBe("Rascunho");
    expect(rotularEstadoDocumento("emitido")).toBe("Emitido");
    expect(rotularEstadoDocumento("x")).toBe("x");
  });
  it("só emitido é terminal", () => {
    expect(documentoEhTerminal("rascunho")).toBe(false);
    expect(documentoEhTerminal("emitido")).toBe(true);
  });
});

describe("textoCarimbo", () => {
  it("sem protocolo ainda -> 'a reservar'", () => {
    expect(textoCarimbo({ protocoloNumero: null, protocoloAno: null })).toBe("a reservar");
    expect(textoCarimbo({})).toBe("a reservar");
  });
  it("com protocolo -> numero real formatado", () => {
    expect(textoCarimbo({ protocoloNumero: 847, protocoloAno: 2026 })).toBe("2026/00847");
  });
});

describe("destacarMerge", () => {
  it("corpo vazio -> nenhum segmento", () => {
    expect(destacarMerge("", ["x"])).toEqual([]);
  });

  it("sem valores de merge -> um único segmento não-merge", () => {
    expect(destacarMerge("Prezado senhor.", [])).toEqual([{ texto: "Prezado senhor.", merge: false }]);
  });

  it("destaca uma ocorrência exata do valor mesclado", () => {
    expect(destacarMerge("Ao Sr. Lúcia Andrade, atenciosamente.", ["Lúcia Andrade"])).toEqual([
      { texto: "Ao Sr. ", merge: false },
      { texto: "Lúcia Andrade", merge: true },
      { texto: ", atenciosamente.", merge: false },
    ]);
  });

  it("destaca múltiplos valores diferentes, cada um no seu segmento", () => {
    const corpo = "Ofício 142/2026 – SL, Fortaleza, 21 de maio de 2026.";
    const segmentos = destacarMerge(corpo, ["142/2026 – SL", "21 de maio de 2026"]);
    expect(segmentos.filter((s) => s.merge).map((s) => s.texto)).toEqual(["142/2026 – SL", "21 de maio de 2026"]);
    expect(segmentos.map((s) => s.texto).join("")).toBe(corpo);
  });

  it("valor mais longo que contém um mais curto não é fatiado pelo curto", () => {
    // "Ana" é substring de "Ana Ribeiro" — o valor mais longo deve vencer (ordenação por tamanho).
    const segmentos = destacarMerge("Assinado por Ana Ribeiro.", ["Ana", "Ana Ribeiro"]);
    expect(segmentos.filter((s) => s.merge).map((s) => s.texto)).toEqual(["Ana Ribeiro"]);
  });

  it("valor de merge ausente no corpo -> não aparece nenhum segmento marcado", () => {
    const segmentos = destacarMerge("Texto qualquer.", ["não está aqui"]);
    expect(segmentos.every((s) => !s.merge)).toBe(true);
    expect(segmentos.map((s) => s.texto).join("")).toBe("Texto qualquer.");
  });

  it("escapa caracteres especiais de regex no valor de merge", () => {
    const segmentos = destacarMerge("Valor: R$ 1.000,00 (aprox.).", ["R$ 1.000,00 (aprox.)"]);
    expect(segmentos.filter((s) => s.merge).map((s) => s.texto)).toEqual(["R$ 1.000,00 (aprox.)"]);
  });
});

describe("paresParaMapaDados", () => {
  it("converte pares chave/valor em mapa", () => {
    expect(paresParaMapaDados([{ chave: "destinatario", valor: "Prefeito" }])).toEqual({
      destinatario: "Prefeito",
    });
  });
  it("descarta linhas com chave em branco (não vira placeholder vazio)", () => {
    expect(
      paresParaMapaDados([
        { chave: "destinatario", valor: "Prefeito" },
        { chave: "  ", valor: "ignorado" },
        { chave: "", valor: "ignorado" },
      ]),
    ).toEqual({ destinatario: "Prefeito" });
  });
  it("lista vazia -> mapa vazio", () => {
    expect(paresParaMapaDados([])).toEqual({});
  });
});

describe("modelosParaGerar — o modelo de requerimento de vereador não gera documento do Expediente", () => {
  it("tira só os de requerimento_proposicao", () => {
    const ms = [
      { id: "a", tipoDocumento: "oficio" },
      { id: "b", tipoDocumento: "requerimento_proposicao" },
      { id: "c", tipoDocumento: "requerimento_administrativo" },
    ];
    expect(modelosParaGerar(ms).map((m) => m.id)).toEqual(["a", "c"]);
  });
  it("o tipo novo tem rótulo próprio na aba Modelos", () => {
    expect(rotularTipoDocumento("requerimento_proposicao")).toBe("Requerimento de vereador");
    expect(TIPOS_DOCUMENTO).toContain("requerimento_proposicao");
  });
});
