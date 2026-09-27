import { describe, expect, it } from "vitest";
import { avisoDaJustificativa, lerResultadoCopiloto, mensagemDeErroCopiloto } from "./copiloto-requerimento-vista";

const j = {
  campo: "justificativa",
  citacoes: [{ fonteId: "norma:n1#art25", rotulo: "LOM, art. 25", trecho: "Compete", status: "conferida" }],
  paragrafosSemFonte: [],
  incerteza: "normal",
  modelo: "fake-1",
};

describe("copiloto do requerimento", () => {
  it("preenchido, ou uma mensagem que manda seguir pelo formulário", () => {
    const p = { modeloId: "m", ementa: "E", campos: { assunto: "a praça" } };
    expect(lerResultadoCopiloto({ preenchimento: p, justificativa: null, indisponivel: null }).tipo).toBe("preenchido");
    expect(lerResultadoCopiloto({ preenchimento: null, justificativa: null, indisponivel: "Fora." })).toEqual({
      tipo: "nada",
      mensagem: "Fora. Preencha o formulário abaixo.",
    });
    expect(lerResultadoCopiloto({ preenchimento: null, justificativa: null, indisponivel: null })).toMatchObject({
      tipo: "nada",
    });
  });

  it("avisa quando a justificativa não tem base conferida", () => {
    expect(avisoDaJustificativa(j)).toBeNull();
    expect(avisoDaJustificativa({ ...j, citacoes: [] })).toMatch(/não achou nas normas/);
    expect(avisoDaJustificativa({ ...j, paragrafosSemFonte: [1] })).toMatch(/sem fonte|não tem fonte/);
    expect(avisoDaJustificativa(null)).toBeNull();
  });

  it("erros em linguagem de quem usa", () => {
    expect(mensagemDeErroCopiloto(503)).toMatch(/Preencha o formulário/);
    expect(mensagemDeErroCopiloto(400)).toMatch(/5 caracteres/);
  });
});
