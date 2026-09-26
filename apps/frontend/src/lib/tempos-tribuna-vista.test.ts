import { describe, expect, it } from "vitest";
import {
  FASES_TEMPO,
  TIPOS_FALA_TEMPO,
  alterado,
  itensDe,
  lerTempo,
  rascunhoDe,
  textoDoTempo,
} from "./tempos-tribuna-vista";

describe("lerTempo — o que a secretaria digita vira segundos", () => {
  it("vazio = sem limite", () => {
    expect(lerTempo("")).toEqual({ ok: true, segundos: null });
    expect(lerTempo("   ")).toEqual({ ok: true, segundos: null });
  });

  it("só minutos, ou minutos:segundos", () => {
    expect(lerTempo("3")).toEqual({ ok: true, segundos: 180 });
    expect(lerTempo("3:00")).toEqual({ ok: true, segundos: 180 });
    expect(lerTempo("1:30")).toEqual({ ok: true, segundos: 90 });
    expect(lerTempo("0:45")).toEqual({ ok: true, segundos: 45 });
    expect(lerTempo(" 10 ")).toEqual({ ok: true, segundos: 600 });
  });

  it("recusa o que não é tempo, com mensagem para a pessoa", () => {
    for (const t of ["abc", "3:7", "3:60", "0", "0:00", "-1", "1.5", "3:00:00"]) {
      const r = lerTempo(t);
      expect(r.ok, t).toBe(false);
      if (!r.ok) expect(r.erro.length).toBeGreaterThan(0);
    }
  });

  it("teto de 60 minutos (o mesmo do servidor)", () => {
    expect(lerTempo("60")).toEqual({ ok: true, segundos: 3600 });
    expect(lerTempo("60:01").ok).toBe(false);
    expect(lerTempo("61").ok).toBe(false);
  });
});

describe("textoDoTempo", () => {
  it("formata m:ss", () => {
    expect(textoDoTempo(180)).toBe("3:00");
    expect(textoDoTempo(90)).toBe("1:30");
    expect(textoDoTempo(45)).toBe("0:45");
    expect(textoDoTempo(null)).toBe("");
  });
});

describe("rascunho ↔ itens da API", () => {
  const itens = [
    { fase: null, tipoFala: "principal", segundos: 180, referenciaNormativa: "RI art. 98" },
    { fase: "ordem_do_dia", tipoFala: "principal", segundos: 600, referenciaNormativa: null },
    { fase: null, tipoFala: "aparte", segundos: 60, referenciaNormativa: null },
  ];

  it("todos os tipos de fala aparecem, configurados ou não", () => {
    const r = rascunhoDe(itens);
    expect(Object.keys(r).sort()).toEqual([...TIPOS_FALA_TEMPO].sort());
    expect(r.principal.padrao).toBe("3:00");
    expect(r.principal.referencia).toBe("RI art. 98");
    expect(r.principal.porFase.ordem_do_dia).toBe("10:00");
    expect(r.principal.porFase.expediente).toBe("");
    expect(r.comunicado.padrao).toBe("");
    expect(Object.keys(r.comunicado.porFase).sort()).toEqual([...FASES_TEMPO].sort());
  });

  it("ida e volta sem perda", () => {
    const volta = itensDe(rascunhoDe(itens));
    expect(volta.ok).toBe(true);
    if (volta.ok) {
      expect(new Set(volta.itens.map((i) => JSON.stringify(i)))).toEqual(
        new Set(itens.map((i) => JSON.stringify(i))),
      );
    }
  });

  it("campos vazios não viram linha; referência vale só para a linha do padrão", () => {
    const r = rascunhoDe([]);
    r.aparte.padrao = "1";
    r.aparte.referencia = "  RI art. 100  ";
    r.aparte.porFase.expediente = "0:30";
    const v = itensDe(r);
    expect(v).toEqual({
      ok: true,
      itens: [
        { fase: null, tipoFala: "aparte", segundos: 60, referenciaNormativa: "RI art. 100" },
        { fase: "expediente", tipoFala: "aparte", segundos: 30, referenciaNormativa: null },
      ],
    });
  });

  it("tempo inválido aponta o campo exato", () => {
    const r = rascunhoDe([]);
    r.principal.padrao = "3:99";
    r.aparte.porFase.ordem_do_dia = "abc";
    const v = itensDe(r);
    expect(v.ok).toBe(false);
    if (!v.ok) {
      expect(Object.keys(v.erros).sort()).toEqual(["aparte:ordem_do_dia", "principal:padrao"]);
    }
  });

  it("alterado compara com o que veio do servidor", () => {
    const original = rascunhoDe(itens);
    const copia = rascunhoDe(itens);
    expect(alterado(copia, original)).toBe(false);
    copia.aparte.padrao = "1:30";
    expect(alterado(copia, original)).toBe(true);
    const normalizado = rascunhoDe(itens);
    normalizado.principal.padrao = "3";
    expect(alterado(normalizado, original)).toBe(false);
  });
});
