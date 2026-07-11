import { describe, expect, it } from "vitest";
import { formatarTipoSessao, formatarTituloSessao, selecionarSessaoAlvo, sessoesAgendadas } from "./pauta-convocacao-vista";
import type { SliSessaoOut } from "./use-mesa";
import type { SessaoOut } from "./pauta-convocacao-vista";

function sliSessao(parcial: Partial<SliSessaoOut> & { sessaoId: string }): SliSessaoOut {
  return { estadoAtual: "agendada", situacao: "agendada", agendadaPara: "2026-06-24T17:00:00Z", ...parcial };
}

function sessao(parcial: Partial<SessaoOut> = {}): SessaoOut {
  return {
    id: "s1", sessaoLegislativaId: "sl1", tipoSessao: "ordinaria", numeroSequencial: 15,
    estado: "agendada", modalidade: "presencial", delibera: true, transmitePublica: true,
    geraAtaRegimental: true, permiteVotoSecreto: false, permiteModalidadeRemota: false,
    agendadaPara: "2026-06-24T17:00:00Z", abertaEm: null, encerradaEm: null, motivoNaoRealizada: null,
    ...parcial,
  };
}

describe("sessoesAgendadas / selecionarSessaoAlvo", () => {
  it("filtra só situacao 'agendada' com agendadaPara, ordena por data crescente", () => {
    const sessoes = [
      sliSessao({ sessaoId: "b", agendadaPara: "2026-07-01T00:00:00Z" }),
      sliSessao({ sessaoId: "a", agendadaPara: "2026-06-24T00:00:00Z" }),
      sliSessao({ sessaoId: "c", situacao: "aberta" }),
      sliSessao({ sessaoId: "d", agendadaPara: null }),
    ];
    expect(sessoesAgendadas(sessoes).map((s) => s.sessaoId)).toEqual(["a", "b"]);
  });

  it("sem override -> auto-seleciona a mais próxima", () => {
    const sessoes = [
      sliSessao({ sessaoId: "b", agendadaPara: "2026-07-01T00:00:00Z" }),
      sliSessao({ sessaoId: "a", agendadaPara: "2026-06-24T00:00:00Z" }),
    ];
    expect(selecionarSessaoAlvo(sessoes, null)?.sessaoId).toBe("a");
  });

  it("com override válido -> respeita a escolha manual", () => {
    const sessoes = [
      sliSessao({ sessaoId: "b", agendadaPara: "2026-07-01T00:00:00Z" }),
      sliSessao({ sessaoId: "a", agendadaPara: "2026-06-24T00:00:00Z" }),
    ];
    expect(selecionarSessaoAlvo(sessoes, "b")?.sessaoId).toBe("b");
  });

  it("override de id inexistente -> ignora, cai no auto", () => {
    const sessoes = [sliSessao({ sessaoId: "a" })];
    expect(selecionarSessaoAlvo(sessoes, "x")?.sessaoId).toBe("a");
  });

  it("nenhuma sessão agendada -> null", () => {
    expect(selecionarSessaoAlvo([], null)).toBeNull();
  });
});

describe("formatarTipoSessao / formatarTituloSessao", () => {
  it("mapeia os 5 tipos conhecidos (logic/tipos-sessao no backend)", () => {
    expect(formatarTipoSessao("ordinaria")).toBe("Ordinária");
    expect(formatarTipoSessao("extraordinaria")).toBe("Extraordinária");
    expect(formatarTipoSessao("solene")).toBe("Solene");
    expect(formatarTipoSessao("secreta")).toBe("Secreta");
    expect(formatarTipoSessao("especial")).toBe("Especial");
  });

  it("tipo desconhecido -> capitaliza o texto cru (fail-closed, não esconde)", () => {
    expect(formatarTipoSessao("nova_categoria")).toBe("Nova_categoria");
  });

  it("monta o título 'Nª Sessão Tipo'", () => {
    expect(formatarTituloSessao(sessao({ numeroSequencial: 15, tipoSessao: "ordinaria" }))).toBe("15ª Sessão Ordinária");
  });
});
