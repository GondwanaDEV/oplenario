import { describe, expect, it } from "vitest";
import { comRotuloDoPasso, lerConversa, mensagemDeErroAssistente, rotuloDoPasso } from "./assistente-vista";

const SSE = [
  'event: passo\ndata: {"ferramenta":"situacao_da_materia","argumentos":{"tipo":"projeto_lei","sequencial":12,"ano":2026},"ok":true}',
  'event: resposta\ndata: {"texto":"Segundo o sistema. [[ferramenta:situacao_da_materia#1 | ementa: Merenda]]","citacoes":[{"fonte-id":"ferramenta:situacao_da_materia#1","trecho":"ementa: Merenda","status":"conferida"}],"paragrafos-sem-fonte":[],"incerteza":"normal","modelo":"fake-1","contaminado":false}',
  'event: fim\ndata: {"execucao-id":"e1"}',
  "",
].join("\n\n");

describe("lerConversa", () => {
  it("lê passos, resposta e fim, em camelCase", () => {
    const c = lerConversa(SSE);
    expect(c.passos).toHaveLength(1);
    expect(c.resposta?.citacoes[0].fonteId).toBe("ferramenta:situacao_da_materia#1");
    expect(c.resposta?.paragrafosSemFonte).toEqual([]);
    expect(c.execucaoId).toBe("e1");
    expect(c.indisponivel).toBeNull();
  });

  it("indisponível e lixo no meio não quebram", () => {
    const c = lerConversa('event: x\ndata: {torto\n\nevent: indisponivel\ndata: {"mensagem":"Siga pela tela."}\n\n');
    expect(c.indisponivel).toBe("Siga pela tela.");
    expect(c.resposta).toBeNull();
  });

  it("B.6: a proposta de ato criada na execução vira um cartão para confirmar", () => {
    const c = lerConversa(
      'event: proposta\ndata: {"id":"p1","titulo":"Protocolar o requerimento “X”","ritual":"assinatura"}\n\nevent: fim\ndata: {}\n\n',
    );
    expect(c.propostas).toEqual([{ id: "p1", titulo: "Protocolar o requerimento “X”", ritual: "assinatura" }]);
    expect(lerConversa(SSE).propostas).toEqual([]);
  });
});

describe("rótulos", () => {
  it("conta o que foi consultado em palavras da Casa", () => {
    const args = { tipo: "projeto_lei", sequencial: 12, ano: 2026 };
    expect(rotuloDoPasso({ ferramenta: "situacao_da_materia", argumentos: args, ok: true })).toBe("Consultou a situação do PL 12/2026");
    expect(rotuloDoPasso({ ferramenta: "tramitacao_da_materia", argumentos: args, ok: false })).toBe("Não encontrou a tramitação do PL 12/2026");
    expect(rotuloDoPasso({ ferramenta: "pauta_da_sessao", argumentos: {}, ok: true })).toBe("Consultou a pauta da próxima sessão");
  });

  it("a citação ganha o rótulo do passo que a sustenta", () => {
    const passos = [{ ferramenta: "pauta_da_sessao", argumentos: {}, ok: true }];
    const c = comRotuloDoPasso({ fonteId: "ferramenta:pauta_da_sessao#1", trecho: "x", status: "conferida" }, passos);
    expect(c.rotulo).toBe("A pauta da próxima sessão");
    expect(comRotuloDoPasso({ fonteId: "outra", trecho: null, status: "conferida" }, passos).rotulo).toBeUndefined();
  });

  it("B.5: conta a consulta às normas pelo artigo, e a citação de dispositivo mantém o rótulo que veio", () => {
    expect(rotuloDoPasso({ ferramenta: "buscar_dispositivos", argumentos: { consulta: "quórum do veto" }, ok: true })).toBe(
      "Consultou as normas da Casa sobre “quórum do veto”",
    );
    expect(
      rotuloDoPasso({ ferramenta: "ler_dispositivo", argumentos: { especie: "regimento_interno", endereco: "art45_par1" }, ok: true }),
    ).toBe("Consultou o art. 45 do Regimento Interno");
    expect(rotuloDoPasso({ ferramenta: "ler_dispositivo", argumentos: { especie: "lei_organica", endereco: "art9" }, ok: false })).toBe(
      "Não encontrou o art. 9 da Lei Orgânica",
    );
    expect(rotuloDoPasso({ ferramenta: "ler_dispositivo", argumentos: { "norma-id": "x", endereco: "art3" }, ok: true })).toBe(
      "Consultou o art. 3 da norma",
    );
    const c = { fonteId: "norma:abc#art45_par1", trecho: "t", status: "conferida" as const, rotulo: "Regimento Interno, art. 45, § 1º (consolidada até 30/06/2026)" };
    expect(comRotuloDoPasso(c, [{ ferramenta: "ler_dispositivo", argumentos: {}, ok: true }]).rotulo).toBe(c.rotulo);
  });

  it("B.6: o passo de ato diz que só preparou a proposta", () => {
    expect(rotuloDoPasso({ ferramenta: "modelos_de_requerimento", argumentos: {}, ok: true })).toBe(
      "Consultou os modelos de requerimento da Casa",
    );
    expect(rotuloDoPasso({ ferramenta: "protocolar_requerimento", argumentos: {}, ok: true })).toBe(
      "Preparou uma proposta de requerimento — nada foi protocolado",
    );
    expect(rotuloDoPasso({ ferramenta: "protocolar_requerimento", argumentos: {}, ok: false })).toBe(
      "Não conseguiu preparar a proposta de requerimento",
    );
  });

  it("erros em linguagem de quem usa", () => {
    expect(mensagemDeErroAssistente(403)).toMatch(/secretaria e dos vereadores/);
    expect(mensagemDeErroAssistente(500)).toMatch(/Siga pela tela/);
  });
});
