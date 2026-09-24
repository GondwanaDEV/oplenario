import { describe, it, expect } from "vitest";
import {
  candidatosObjeto,
  derivarPainelVotacao,
  exigeResultadoAoEncerrar,
  rotuloModalidade,
  rotuloObjetoTipo,
  rotuloQuorum,
  MODALIDADES,
  QUORUNS,
} from "./votacao-mesa-vista";
import type { VotacaoAbertaResumo } from "./use-votacao-mesa";

const votacao = (over: Partial<VotacaoAbertaResumo> = {}): VotacaoAbertaResumo => ({
  votacaoId: "vt1",
  modalidade: "nominal",
  objetoTipo: "proposicao",
  objetoId: "p1",
  proposicao: null,
  ...over,
});

describe("candidatosObjeto", () => {
  it("mantém só proposições com proposicaoId, ordenadas por ordem", () => {
    const c = candidatosObjeto([
      { tipoItem: "comunicado", proposicaoId: null, fase: "expediente", ordem: 1 },
      { tipoItem: "proposicao", proposicaoId: "pB", fase: "ordem_do_dia", ordem: 3 },
      { tipoItem: "proposicao", proposicaoId: "pA", fase: "ordem_do_dia", ordem: 2 },
      { tipoItem: "proposicao", proposicaoId: null, fase: "ordem_do_dia", ordem: 4 }, // sem id -> fora
    ]);
    expect(c.map((x) => x.objetoId)).toEqual(["pA", "pB"]);
    expect(c[0]).toMatchObject({ objetoId: "pA", fase: "ordem_do_dia", ordem: 2 });
  });
  it("carrega a sigla da matéria quando a pauta traz o resumo (null sem ele)", () => {
    const c = candidatosObjeto([
      { tipoItem: "proposicao", proposicaoId: "p22", fase: "ordem_do_dia", ordem: 3, proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 22 } },
      { tipoItem: "proposicao", proposicaoId: "p9", fase: "ordem_do_dia", ordem: 4 },
    ]);
    expect(c.map((x) => x.sigla)).toEqual(["PL 22/2026", null]);
  });
  it("pauta sem proposições -> lista vazia", () => {
    expect(candidatosObjeto([{ tipoItem: "homenagem", fase: "expediente", ordem: 1 }])).toEqual([]);
  });
});

describe("derivarPainelVotacao", () => {
  it("sessão não aberta -> indisponível", () => {
    for (const estado of ["agendada", "suspensa", "encerrada"]) {
      const p = derivarPainelVotacao({ sessaoEstado: estado, votacaoAberta: null });
      expect(p.tipo).toBe("indisponivel");
    }
  });
  it("sessão aberta e nenhuma votação -> abrir", () => {
    expect(derivarPainelVotacao({ sessaoEstado: "aberta", votacaoAberta: null }).tipo).toBe("abrir");
  });
  it("sessão aberta com votação em curso -> em-curso, exigeResultado só na simbólica", () => {
    const nominal = derivarPainelVotacao({ sessaoEstado: "aberta", votacaoAberta: votacao({ modalidade: "nominal" }) });
    expect(nominal.tipo).toBe("em-curso");
    if (nominal.tipo === "em-curso") expect(nominal.exigeResultado).toBe(false);

    const simb = derivarPainelVotacao({ sessaoEstado: "aberta", votacaoAberta: votacao({ modalidade: "simbolica" }) });
    if (simb.tipo !== "em-curso") throw new Error("esperava em-curso");
    expect(simb.exigeResultado).toBe(true);
  });
});

describe("rótulos e regras", () => {
  it("exigeResultadoAoEncerrar só para simbólica", () => {
    expect(exigeResultadoAoEncerrar("simbolica")).toBe(true);
    expect(exigeResultadoAoEncerrar("nominal")).toBe(false);
    expect(exigeResultadoAoEncerrar("secreta")).toBe(false);
  });
  it("mapeia rótulos humanos, com fallback para a chave crua", () => {
    expect(rotuloObjetoTipo("redacao_final")).toBe("Redação final");
    expect(rotuloModalidade("secreta")).toBe("Secreta");
    expect(rotuloQuorum("maioria_qualificada_2_3")).toBe("Qualificada (2/3)");
    expect(rotuloObjetoTipo("desconhecido")).toBe("desconhecido");
  });
  it("as opções cobrem os enums do backend", () => {
    expect(MODALIDADES.map((m) => m.valor)).toEqual(["nominal", "simbolica", "secreta"]);
    expect(QUORUNS.map((q) => q.valor)).toEqual([
      "maioria_simples",
      "maioria_absoluta",
      "maioria_qualificada_2_3",
      "maioria_qualificada_3_5",
    ]);
  });
});
