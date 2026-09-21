import { describe, it, expect } from "vitest";
import {
  agendadaParaIso,
  sessoesLegislativasDisponiveis,
  validarAgendar,
  TIPOS_SESSAO,
  MODALIDADES_SESSAO,
} from "./agendar-sessao-vista";
import type { SessaoOut } from "./contrato-sessoes.gen";

function sessao(sessaoLegislativaId: string, numeroSequencial: number): SessaoOut {
  return {
    id: `s-${numeroSequencial}`,
    sessaoLegislativaId,
    tipoSessao: "ordinaria",
    numeroSequencial,
    estado: "encerrada",
    modalidade: "presencial",
    delibera: true,
    transmitePublica: true,
    geraAtaRegimental: true,
    permiteVotoSecreto: false,
    permiteModalidadeRemota: false,
    lockVersion: 0,
  };
}

describe("sessoesLegislativasDisponiveis", () => {
  it("agrupa por sessao-legislativa-id, conta e pega o maior número; ordena pela mais cheia", () => {
    const ops = sessoesLegislativasDisponiveis([
      sessao("sl-A", 1),
      sessao("sl-A", 3),
      sessao("sl-B", 2),
    ]);
    expect(ops.map((o) => o.id)).toEqual(["sl-A", "sl-B"]);
    expect(ops[0]).toMatchObject({ id: "sl-A", sessoesCount: 2, ultimoNumero: 3 });
    expect(ops[1]).toMatchObject({ id: "sl-B", sessoesCount: 1, ultimoNumero: 2 });
  });
  it("lista vazia -> nenhuma opção", () => {
    expect(sessoesLegislativasDisponiveis([])).toEqual([]);
  });
});

describe("validarAgendar", () => {
  it("exige sessão legislativa e tipo", () => {
    expect(validarAgendar({ sessaoLegislativaId: "", tipoSessao: "ordinaria" }).ok).toBe(false);
    expect(validarAgendar({ sessaoLegislativaId: "sl", tipoSessao: "" }).ok).toBe(false);
  });
  it("recusa tipo fora do enum", () => {
    expect(validarAgendar({ sessaoLegislativaId: "sl", tipoSessao: "xpto" }).ok).toBe(false);
  });
  it("aceita um formulário válido", () => {
    expect(validarAgendar({ sessaoLegislativaId: "sl", tipoSessao: "ordinaria" })).toEqual({ ok: true });
  });
});

describe("agendadaParaIso", () => {
  it("vazio -> null (campo opcional)", () => {
    expect(agendadaParaIso("")).toBeNull();
    expect(agendadaParaIso("   ")).toBeNull();
  });
  it("datetime-local válido -> ISO-8601", () => {
    const iso = agendadaParaIso("2026-05-21T14:00");
    expect(iso).toMatch(/^2026-05-21T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
  });
  it("valor inválido -> null (nunca uma data inventada)", () => {
    expect(agendadaParaIso("não é data")).toBeNull();
  });
});

describe("opções cobrem os enums do backend", () => {
  it("tipos e modalidades", () => {
    expect(TIPOS_SESSAO.map((t) => t.valor)).toEqual(["ordinaria", "extraordinaria", "solene", "secreta", "especial"]);
    expect(MODALIDADES_SESSAO.map((m) => m.valor)).toEqual(["presencial", "remota", "hibrida"]);
  });
});
