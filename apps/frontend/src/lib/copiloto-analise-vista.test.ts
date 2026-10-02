import { describe, expect, it } from "vitest";
import {
  SELO_RASCUNHO_IA,
  avisoDaAnalise,
  avisoDasNormas,
  juntarAnalise,
  lerResultadoAnalise,
  mensagemDeErroAnalise,
  rotuloDaCitacao,
} from "./copiloto-analise-vista";
import type { AnaliseCopilotoOut } from "./contrato-legislativo.gen";

const analise: AnaliseCopilotoOut = {
  texto: "A proposição tem por objeto hortas.",
  citacoes: [{ fonteId: "materia:p1", rotulo: "Projeto de Lei nº 12/2026", trecho: "hortas", status: "conferida" }],
  paragrafosSemFonte: [],
  pontosAConfirmar: [],
  incerteza: "normal",
  modelo: "fake-1",
};

describe("copiloto do relator — vista", () => {
  it("o selo nunca chama o texto de parecer como coisa pronta e diz quem assina", () => {
    expect(SELO_RASCUNHO_IA).toMatch(/Rascunho da IA/);
    expect(SELO_RASCUNHO_IA).toMatch(/Não é parecer/);
    expect(SELO_RASCUNHO_IA).toMatch(/revise antes de salvar/);
  });

  it("lerResultadoAnalise: rascunho, indisponível (R-IA-1) ou nada", () => {
    expect(lerResultadoAnalise({ analise, normas: "citadas", indisponivel: null })).toEqual({
      tipo: "rascunho",
      analise,
      normas: "citadas",
    });
    const fora = lerResultadoAnalise({ analise: null, normas: "citadas", indisponivel: "A IA da Casa atingiu o limite." });
    expect(fora).toEqual({ tipo: "nada", mensagem: "A IA da Casa atingiu o limite. Redija a análise no campo abaixo." });
    expect(lerResultadoAnalise({ analise: null, normas: "sem-dispositivo", indisponivel: null }).tipo).toBe("nada");
  });

  it("mensagens de erro: 503 manda seguir pelo editor; 404 não distingue", () => {
    expect(mensagemDeErroAnalise(503)).toMatch(/nada do parecer depende dele/);
    expect(mensagemDeErroAnalise(404)).toMatch(/não está com você como relator/);
    expect(mensagemDeErroAnalise(0)).toMatch(/Redija a análise/);
  });

  it("avisos: sem normas publicadas, sem dispositivo, citação que não conferiu, parágrafo sem fonte", () => {
    expect(avisoDasNormas("sem-normas")).toMatch(/ainda não publicou a Lei Orgânica nem o Regimento/);
    expect(avisoDasNormas("sem-dispositivo")).toMatch(/não achou nas normas da Casa/);
    expect(avisoDasNormas("citadas")).toBeNull();
    expect(avisoDaAnalise(analise)).toBeNull();
    expect(avisoDaAnalise({ ...analise, paragrafosSemFonte: [1] })).toMatch(/sem fonte|não tem fonte/);
    expect(
      avisoDaAnalise({ ...analise, citacoes: [{ ...analise.citacoes[0], status: "fonte_nao_lida" }] }),
    ).toMatch(/não conferiu/);
  });

  it("juntarAnalise: campo vazio recebe o rascunho; substituir troca; acrescentar vai ao fim", () => {
    expect(juntarAnalise("  ", "R", "acrescentar")).toBe("R");
    expect(juntarAnalise("Minha análise.", "R", "substituir")).toBe("R");
    expect(juntarAnalise("Minha análise.\n\n", "R", "acrescentar")).toBe("Minha análise.\n\nR");
  });

  it("rotuloDaCitacao: o rótulo do servidor, ou 'A matéria'", () => {
    expect(rotuloDaCitacao({ fonteId: "norma:n1#art11", rotulo: "Lei Orgânica do Município, art. 11" })).toBe(
      "Lei Orgânica do Município, art. 11",
    );
    expect(rotuloDaCitacao({ fonteId: "materia:p1", rotulo: null })).toBe("A matéria");
  });
});
