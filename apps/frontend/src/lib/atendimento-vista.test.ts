import { describe, expect, it } from "vitest";
import {
  especieValida,
  faltaNoEncarregado,
  faltaNoTexto,
  hrefDoPrazo,
  linhaDoPrazo,
  mensagemDeErroAtendimento,
  rotuloEstado,
  seloDoPrazo,
  textoDoRecibo,
  tituloDoEvento,
  tituloDoItem,
  vazioDaFila,
} from "./atendimento-vista";

describe("seloDoPrazo", () => {
  it("vencido, vence hoje, amanhã e dias restantes", () => {
    expect(seloDoPrazo({ aberto: true, diasRestantes: -3 })).toEqual({ tom: "vencido", texto: "Venceu há 3 dias" });
    expect(seloDoPrazo({ aberto: true, diasRestantes: -1 })).toEqual({ tom: "vencido", texto: "Venceu ontem" });
    expect(seloDoPrazo({ aberto: true, diasRestantes: 0 })).toEqual({ tom: "hoje", texto: "Vence hoje" });
    expect(seloDoPrazo({ aberto: true, diasRestantes: 1 })).toEqual({ tom: "perto", texto: "Vence amanhã" });
    expect(seloDoPrazo({ aberto: true, diasRestantes: 4 })).toEqual({ tom: "perto", texto: "Faltam 4 dias" });
    expect(seloDoPrazo({ aberto: true, diasRestantes: 12 })).toEqual({ tom: "ok", texto: "Faltam 12 dias" });
  });

  it("encerrado não tem prazo correndo", () => {
    expect(seloDoPrazo({ aberto: false, diasRestantes: null })).toEqual({ tom: "encerrado", texto: "Encerrado" });
    expect(seloDoPrazo({ aberto: false, diasRestantes: -5 }).tom).toBe("encerrado");
  });
});

describe("textos", () => {
  it("o prazo em dd/mm/aaaa, sem mexer no dia (date-only, sem fuso)", () => {
    expect(linhaDoPrazo({ prazoVigente: "2026-07-15", prorrogado: false })).toBe("Prazo: 15/07/2026");
    expect(linhaDoPrazo({ prazoVigente: "2026-07-25", prorrogado: true })).toBe("Prazo: 25/07/2026 (prorrogado)");
    expect(linhaDoPrazo({ prazoVigente: null, prorrogado: false })).toBeNull();
  });

  it("o título por espécie", () => {
    expect(tituloDoItem("esic", { assunto: "Diárias" })).toBe("Diárias");
    expect(tituloDoItem("ouvidoria", { tipo: "reclamacao", assunto: "Fila" })).toBe("Reclamação: Fila");
    expect(tituloDoItem("lgpd", { tipo: "com_quem_compartilhado" })).toBe("Com quem os dados foram compartilhados");
  });

  it("o histórico em palavras", () => {
    expect(tituloDoEvento({ tipo: "prorrogacao", em: "2026-07-03T15:00:00Z", texto: "x", por: null, deData: "2026-07-15", paraData: "2026-07-25" }))
      .toBe("Prazo prorrogado de 15/07/2026 para 25/07/2026");
    expect(tituloDoEvento({ tipo: "recurso", em: "2026-07-03T15:00:00Z", texto: "x", por: null, protocolo: "REC-2026-000001" }))
      .toBe("Recurso do requerente (REC-2026-000001)");
    expect(tituloDoEvento({ tipo: "arquivamento", em: "2026-07-03T15:00:00Z", texto: "x", por: "Ana" })).toBe("Arquivada sem resposta de mérito");
  });

  it("o indeferimento tem título próprio, distinto da resposta, e o estado sai em palavras (nunca o enum cru)", () => {
    const e = { em: "2026-07-03T15:00:00Z", texto: "Dado pessoal de terceiro.", por: "Joana" } as const;
    expect(tituloDoEvento({ ...e, tipo: "indeferimento" })).toBe("Indeferimento (fundamentação da Casa)");
    expect(tituloDoEvento({ ...e, tipo: "indeferimento" })).not.toBe(tituloDoEvento({ ...e, tipo: "resposta" }));
    expect(rotuloEstado("indeferido")).toBe("Indeferido");
    expect(rotuloEstado("indeferida")).toBe("Indeferida");
  });

  it("o vazio de cada fila concorda em gênero", () => {
    expect(vazioDaFila("ouvidoria", "abertos")).toBe("Nenhuma manifestação esperando resposta. Tudo em dia.");
    expect(vazioDaFila("esic", "respondidos")).toBe("Nenhum pedido de informação encerrado ainda.");
  });

  it("o recibo diz o protocolo e a data", () => {
    expect(textoDoRecibo("prorrogar", "2026-07-25", "ESIC-2026-000001")).toBe(
      "Prazo do ESIC-2026-000001 prorrogado até 25/07/2026. A nova data já aparece para quem acompanha o protocolo.",
    );
    expect(textoDoRecibo("responder", "2026-07-03T15:00:00Z", "ESIC-2026-000001")).toMatch(/^Resposta ao ESIC-2026-000001 registrada em \d{2}\/\d{2}\/2026/);
    expect(textoDoRecibo("indeferir", "2026-07-03T15:00:00Z", "ESIC-2026-000001")).toMatch(
      /^ESIC-2026-000001 indeferido em \d{2}\/\d{2}\/2026, .*A fundamentação fica registrada no protocolo/,
    );
  });
});

describe("erros e validação", () => {
  it("cada status vira uma frase; 409 depende da ação", () => {
    expect(mensagemDeErroAtendimento(403, "listar")).toMatch(/secretaria/);
    expect(mensagemDeErroAtendimento(409, "prorrogar")).toMatch(/só cabe uma vez/);
    expect(mensagemDeErroAtendimento(409, "responder")).toMatch(/já foi respondido/);
    expect(mensagemDeErroAtendimento(409, "indeferir")).toMatch(/já foi respondido ou indeferido/);
    expect(mensagemDeErroAtendimento(400, "indeferir")).toMatch(/Confira o texto/);
    expect(mensagemDeErroAtendimento(0, "responder")).toMatch(/Falha de rede/);
  });

  it("texto obrigatório e teto", () => {
    expect(faltaNoTexto("   ", 10, "a resposta")).toBe("Escreva a resposta.");
    expect(faltaNoTexto("x".repeat(11), 10, "a resposta")).toMatch(/até 10 caracteres/);
    expect(faltaNoTexto("ok", 10, "a resposta")).toBeNull();
  });

  it("o encarregado precisa de nome, cargo e e-mail", () => {
    expect(faltaNoEncarregado({ nome: "", rotulo: "x", email: "a@b" })).toMatch(/nome/);
    expect(faltaNoEncarregado({ nome: "Ana", rotulo: "x", email: "ana" })).toMatch(/incompleto/);
    expect(faltaNoEncarregado({ nome: "Ana", rotulo: "Encarregada", email: "ana@camara.leg.br" })).toBeNull();
  });
});

describe("navegação", () => {
  it("só as três filas existem", () => {
    expect(especieValida("esic")).toBe(true);
    expect(especieValida("lgpd")).toBe(true);
    expect(especieValida("moderacao")).toBe(false);
    expect(especieValida(null)).toBe(false);
  });

  it("o prazo do painel de pendências leva ao balcão", () => {
    expect(hrefDoPrazo("pedido_esic", "p1")).toBe("/atendimento/esic/p1");
    expect(hrefDoPrazo("manifestacao_ouvidoria", "m1")).toBe("/atendimento/ouvidoria/m1");
    expect(hrefDoPrazo("solicitacao_titular", "s1")).toBe("/atendimento/lgpd/s1");
    expect(hrefDoPrazo("recurso_esic", "r1")).toBe("/atendimento?aba=esic");
    expect(hrefDoPrazo("obrigacao", "o1")).toBeNull();
  });
});
