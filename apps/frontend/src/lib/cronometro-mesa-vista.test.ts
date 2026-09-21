import { describe, it, expect } from "vitest";
import {
  apartesConcedidos,
  estaPausado,
  segundosAdicionaisConcedidos,
  TIPOS_FALA,
} from "./cronometro-mesa-vista";
import type { MarcoCronometroOut } from "./contrato-sessoes.gen";

const m = (tipo: MarcoCronometroOut["tipo"], ocorridoEm: string, segundosAdicionais: number | null = null): MarcoCronometroOut => ({
  tipo,
  ocorridoEm,
  segundosAdicionais,
});

describe("estaPausado", () => {
  it("false sem marcos", () => {
    expect(estaPausado([])).toBe(false);
  });
  it("true quando o último marco de pausa/retomada é 'pausada'", () => {
    expect(
      estaPausado([m("pausada", "2026-05-21T14:01:00Z"), m("retomada", "2026-05-21T14:02:00Z"), m("pausada", "2026-05-21T14:03:00Z")]),
    ).toBe(true);
  });
  it("false quando a última é 'retomada' (ignora aparte/tempo entre elas)", () => {
    expect(
      estaPausado([m("pausada", "2026-05-21T14:01:00Z"), m("aparte_concedido", "2026-05-21T14:01:30Z"), m("retomada", "2026-05-21T14:02:00Z")]),
    ).toBe(false);
  });
});

describe("segundosAdicionaisConcedidos", () => {
  it("soma só os tempo_adicional_concedido", () => {
    expect(
      segundosAdicionaisConcedidos([m("tempo_adicional_concedido", "t1", 60), m("pausada", "t2"), m("tempo_adicional_concedido", "t3", 30)]),
    ).toBe(90);
  });
  it("0 sem concessões", () => {
    expect(segundosAdicionaisConcedidos([m("pausada", "t")])).toBe(0);
  });
});

describe("apartesConcedidos / TIPOS_FALA", () => {
  it("conta os apartes", () => {
    expect(apartesConcedidos([m("aparte_concedido", "t1"), m("aparte_concedido", "t2"), m("pausada", "t3")])).toBe(2);
  });
  it("TIPOS_FALA cobre o enum e começa por principal", () => {
    expect(TIPOS_FALA.map((t) => t.valor)).toEqual([
      "principal",
      "aparte",
      "pela_ordem",
      "questao_de_ordem",
      "explicacao_pessoal",
      "comunicado",
    ]);
  });
});
