import { describe, it, expect } from "vitest";
import {
  derivarConducaoSessao,
  rotuloEstadoSessao,
  type SessaoEstado,
} from "./conducao-sessao-vista";
import type { SessaoOut } from "./contrato-sessoes.gen";

function sessao(estado: SessaoEstado, over: Partial<SessaoOut> = {}): SessaoOut {
  return {
    id: "s1",
    sessaoLegislativaId: "sl1",
    tipoSessao: "ordinaria",
    numeroSequencial: 14,
    estado,
    modalidade: "presencial",
    delibera: true,
    transmitePublica: true,
    geraAtaRegimental: true,
    permiteVotoSecreto: false,
    permiteModalidadeRemota: false,
    lockVersion: 0,
    ...over,
  };
}

const alvos = (estado: SessaoEstado) => derivarConducaoSessao(sessao(estado)).atos.map((a) => a.para);

describe("derivarConducaoSessao — grafo de transições espelha o backend", () => {
  it("agendada → abrir | marcar não realizada", () => {
    expect(alvos("agendada")).toEqual(["aberta", "nao_realizada"]);
  });
  it("aberta → suspender | encerrar", () => {
    expect(alvos("aberta")).toEqual(["suspensa", "encerrada"]);
  });
  it("suspensa → reabrir | encerrar", () => {
    expect(alvos("suspensa")).toEqual(["aberta", "encerrada"]);
  });
  it("encerrada → arquivar", () => {
    expect(alvos("encerrada")).toEqual(["arquivada"]);
  });
  it("nao_realizada → arquivar", () => {
    expect(alvos("nao_realizada")).toEqual(["arquivada"]);
  });
  it("arquivada é terminal — nenhum ato", () => {
    const v = derivarConducaoSessao(sessao("arquivada"));
    expect(v.atos).toEqual([]);
    expect(v.terminal).toBe(true);
  });
});

describe("derivarConducaoSessao — situação e rótulo do estado", () => {
  it("classifica a situação macro por estado", () => {
    expect(derivarConducaoSessao(sessao("agendada")).situacao).toBe("agendada");
    expect(derivarConducaoSessao(sessao("aberta")).situacao).toBe("viva");
    expect(derivarConducaoSessao(sessao("suspensa")).situacao).toBe("suspensa");
    expect(derivarConducaoSessao(sessao("encerrada")).situacao).toBe("terminal");
    expect(derivarConducaoSessao(sessao("nao_realizada")).situacao).toBe("terminal");
    expect(derivarConducaoSessao(sessao("arquivada")).situacao).toBe("terminal");
  });
  it("rotula o estado em pt-BR humano", () => {
    expect(rotuloEstadoSessao("nao_realizada")).toBe("Não realizada");
    expect(derivarConducaoSessao(sessao("aberta")).rotuloEstado).toBe("Aberta");
  });
});

describe("derivarConducaoSessao — semântica dos atos", () => {
  it("encerrar é ato de perigo e pede confirmação, a partir de aberta e de suspensa", () => {
    for (const de of ["aberta", "suspensa"] as const) {
      const enc = derivarConducaoSessao(sessao(de)).atos.find((a) => a.para === "encerrada")!;
      expect(enc.tom).toBe("perigo");
      expect(enc.confirmacao).toBeTruthy();
      expect(enc.exigeMotivo).toBe(false);
    }
  });
  it("marcar não realizada exige motivo", () => {
    const nr = derivarConducaoSessao(sessao("agendada")).atos.find((a) => a.para === "nao_realizada")!;
    expect(nr.exigeMotivo).toBe(true);
    expect(nr.tom).toBe("perigo");
  });
  it("abrir e reabrir são atos primários sem motivo", () => {
    const abrir = derivarConducaoSessao(sessao("agendada")).atos.find((a) => a.para === "aberta")!;
    expect(abrir.tom).toBe("primaria");
    expect(abrir.exigeMotivo).toBe(false);
    const reabrir = derivarConducaoSessao(sessao("suspensa")).atos.find((a) => a.para === "aberta")!;
    expect(reabrir.tom).toBe("primaria");
    expect(reabrir.rotulo).toBe("Reabrir");
  });
});
