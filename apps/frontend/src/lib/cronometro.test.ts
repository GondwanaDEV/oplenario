import { describe, it, expect } from "vitest";
import { deveTocarCampainha, relogioDaFala, segundosDecorridos, tempoDaFala } from "./cronometro";
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

describe("tempoDaFala — tempo-limite, último minuto e esgotado (mig 0081)", () => {
  it("sem limite: só o decorrido, situação 'sem-limite'", () => {
    expect(tempoDaFala(null, [], 125)).toEqual({ limite: null, restante: null, excedido: 0, situacao: "sem-limite" });
    expect(tempoDaFala(undefined, [], 125).situacao).toBe("sem-limite");
  });

  it("correndo: restante = limite − decorrido", () => {
    expect(tempoDaFala(300, [], 100)).toEqual({ limite: 300, restante: 200, excedido: 0, situacao: "correndo" });
  });

  it("último minuto a partir de 60s restantes", () => {
    expect(tempoDaFala(300, [], 239).situacao).toBe("correndo");
    expect(tempoDaFala(300, [], 240).situacao).toBe("ultimo-minuto");
    expect(tempoDaFala(300, [], 299).situacao).toBe("ultimo-minuto");
  });

  it("esgotado ao chegar a zero; o excedido conta o que passou do limite", () => {
    expect(tempoDaFala(300, [], 300)).toEqual({ limite: 300, restante: 0, excedido: 0, situacao: "esgotado" });
    expect(tempoDaFala(300, [], 337)).toEqual({ limite: 300, restante: 0, excedido: 37, situacao: "esgotado" });
  });

  it("o tempo adicional concedido pela Mesa estende o limite (e tira do esgotado)", () => {
    const marcos: MarcoCronometro[] = [
      { tipo: "tempo_adicional_concedido", ocorridoEm: "2026-05-21T22:15:10Z", segundosAdicionais: 60 },
    ];
    expect(tempoDaFala(300, marcos, 310)).toEqual({ limite: 360, restante: 50, excedido: 0, situacao: "ultimo-minuto" });
    const dois: MarcoCronometro[] = [...marcos, { tipo: "tempo_adicional_concedido", ocorridoEm: "2026-05-21T22:16:20Z", segundosAdicionais: 60 }];
    expect(tempoDaFala(300, dois, 310).limite).toBe(420);
  });

  it("limite inválido (zero, negativo, NaN) é tratado como sem limite — nunca 'esgotado' inventado", () => {
    expect(tempoDaFala(0, [], 10).situacao).toBe("sem-limite");
    expect(tempoDaFala(-5, [], 10).situacao).toBe("sem-limite");
    expect(tempoDaFala(Number.NaN, [], 10).situacao).toBe("sem-limite");
  });
});

describe("deveTocarCampainha — só na TRANSIÇÃO para esgotado, vista ao vivo", () => {
  it("toca quando a MESMA fala passa de não-esgotada para esgotada", () => {
    expect(deveTocarCampainha({ falaId: "f1", esgotado: false }, { falaId: "f1", esgotado: true })).toBe(true);
  });

  it("não toca ao abrir a tela com a fala já esgotada (sem observação anterior)", () => {
    expect(deveTocarCampainha(null, { falaId: "f1", esgotado: true })).toBe(false);
  });

  it("não toca quando a observação anterior era de OUTRA fala", () => {
    expect(deveTocarCampainha({ falaId: "f0", esgotado: false }, { falaId: "f1", esgotado: true })).toBe(false);
  });

  it("não toca de novo enquanto segue esgotada; toca de novo depois do +1 min se esgotar outra vez", () => {
    expect(deveTocarCampainha({ falaId: "f1", esgotado: true }, { falaId: "f1", esgotado: true })).toBe(false);
    // +1 min: volta a correr…
    expect(deveTocarCampainha({ falaId: "f1", esgotado: true }, { falaId: "f1", esgotado: false })).toBe(false);
    // …e esgota de novo
    expect(deveTocarCampainha({ falaId: "f1", esgotado: false }, { falaId: "f1", esgotado: true })).toBe(true);
  });

  it("sem fala agora → não toca", () => {
    expect(deveTocarCampainha({ falaId: "f1", esgotado: false }, null)).toBe(false);
  });
});

describe("relogioDaFala — o número que TV e Mesa mostram", () => {
  it("sem limite: o decorrido", () => {
    expect(relogioDaFala(tempoDaFala(null, [], 125), 125)).toBe("02:05");
  });
  it("com limite: o restante", () => {
    expect(relogioDaFala(tempoDaFala(300, [], 125), 125)).toBe("02:55");
  });
  it("esgotado: +excedido", () => {
    expect(relogioDaFala(tempoDaFala(300, [], 342), 342)).toBe("+00:42");
  });
});
