import { describe, expect, it } from "vitest";
import {
  avisoDaNota,
  faltaParaAproveitar,
  linhaDaNota,
  linhaDoAgente,
  mensagemDeErroConferencias,
  numeroDaNota,
  textoAproveitado,
  vazioDaFila,
} from "./conferencias-vista";

describe("conferencias-vista", () => {
  it("identifica a matéria pela sigla", () => {
    expect(numeroDaNota({ tipo: "requerimento", sequencial: 5, ano: 2026 })).toBe("REQ 5/2026");
  });

  it("diz em que pé está a nota", () => {
    const criadaEm = "2026-09-27T14:03:00-03:00";
    expect(linhaDaNota({ estado: "pendente", criadaEm, decididaEm: null })).toMatch(/^Chegou em 27\/09\/2026, \d\d:\d\d$/);
    expect(linhaDaNota({ estado: "aproveitada", criadaEm, decididaEm: criadaEm })).toMatch(/^Aproveitada em 27\/09\/2026/);
    expect(linhaDaNota({ estado: "descartada", criadaEm, decididaEm: criadaEm })).toMatch(/^Descartada em/);
  });

  it("a fila vazia explica por quê — inclusive quando o agente está desligado", () => {
    expect(vazioDaFila("pendente", false)).toMatch(/desligada/);
    expect(vazioDaFila("pendente", true)).toMatch(/Quando uma proposição for protocolada/);
    expect(vazioDaFila("aproveitada", true)).toBe("Nenhuma nota aproveitada ainda.");
  });

  it("aviso de incerteza na linguagem de quem confere", () => {
    expect(avisoDaNota("normal", [])).toBeNull();
    expect(avisoDaNota("revisar_com_atencao", ["sem_fonte"])).toBe(
      "Leia com atenção: há parágrafos que nenhum dispositivo lido sustenta.",
    );
    expect(avisoDaNota("revisar_com_atencao", ["desconhecido"])).toBe("Leia com atenção antes de aproveitar.");
  });

  it("não aproveita texto vazio nem acima do teto", () => {
    expect(faltaParaAproveitar("  ")).toMatch(/Escreva/);
    expect(faltaParaAproveitar("a".repeat(20001))).toMatch(/até 20000/);
    expect(faltaParaAproveitar("Texto.")).toBeNull();
  });

  it("o agente ligado diz desde quando", () => {
    const base = { agente: "conferencia-normativa", titulo: "t", descricao: "d", classes: ["leitura", "rascunho"] };
    expect(linhaDoAgente({ ...base, ligado: true, ligadoEm: "2026-09-27T12:00:00Z" })).toBe("Ligada desde 27/09/2026");
    expect(linhaDoAgente({ ...base, ligado: false, ligadoEm: null })).toBe("Desligada");
  });

  it("o texto aproveitado é o editado, ou o da IA limpo", () => {
    expect(textoAproveitado({ textoFinal: null, textoLimpo: "limpo" })).toBe("limpo");
    expect(textoAproveitado({ textoFinal: "editado", textoLimpo: "limpo" })).toBe("editado");
  });

  it("erros", () => {
    expect(mensagemDeErroConferencias(409, "Esta nota já foi decidida.")).toBe("Esta nota já foi decidida.");
    expect(mensagemDeErroConferencias(403)).toMatch(/administrador/);
    expect(mensagemDeErroConferencias(0)).toMatch(/servidor/);
  });
});
