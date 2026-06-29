import { describe, it, expect } from "vitest";
import { segundosDecorridos } from "./cronometro";
import type { MarcoCronometro } from "./plenario-reducer";

const t = (s: string) => Date.parse(s);
const ini = "2026-05-21T22:10:00Z";

describe("segundosDecorridos — cronômetro client-side com pausa/retomada", () => {
  it("sem marcos: decorrido = agora - início", () => {
    expect(segundosDecorridos(ini, [], t("2026-05-21T22:10:30Z"))).toBe(30);
  });

  it("desconta o intervalo pausada→retomada", () => {
    const marcos: MarcoCronometro[] = [
      { tipo: "pausada", ocorridoEm: "2026-05-21T22:10:10Z" },
      { tipo: "retomada", ocorridoEm: "2026-05-21T22:10:25Z" }, // 15s pausado
    ];
    expect(segundosDecorridos(ini, marcos, t("2026-05-21T22:10:40Z"))).toBe(40 - 15);
  });

  it("se está pausada agora (sem retomada), congela no instante da pausa", () => {
    const marcos: MarcoCronometro[] = [{ tipo: "pausada", ocorridoEm: "2026-05-21T22:10:18Z" }];
    expect(segundosDecorridos(ini, marcos, t("2026-05-21T22:11:00Z"))).toBe(18);
  });

  it("nunca devolve negativo", () => {
    expect(segundosDecorridos(ini, [], t("2026-05-21T22:09:50Z"))).toBe(0);
  });

  it("ignora marcos não-pausa (aparte/tempo_adicional não mexem no decorrido)", () => {
    const marcos: MarcoCronometro[] = [{ tipo: "tempo_adicional_concedido", ocorridoEm: "2026-05-21T22:10:05Z", segundosAdicionais: 60 }];
    expect(segundosDecorridos(ini, marcos, t("2026-05-21T22:10:20Z"))).toBe(20);
  });
});
