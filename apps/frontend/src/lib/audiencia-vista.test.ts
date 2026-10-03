import { describe, expect, it } from "vitest";
import {
  LIMITE_TEMA,
  cronometroDaFala,
  diaEMes,
  filaDaAudiencia,
  podeChamar,
  podeInscreverPresencial,
  quandoPorExtenso,
  quemFala,
  rotuloTempoFala,
  situacaoDaInscricao,
  tempoFalaEmSegundos,
  validarInscricaoCidada,
  validarInscricaoPresencial,
} from "./audiencia-vista";
import type { InscricaoOut } from "./contrato-audiencia";

const insc = (p: Partial<InscricaoOut>): InscricaoOut => ({
  id: "i",
  protocolo: "AUD-2026-000001",
  ordem: 1,
  nome: "Ana",
  falaComo: "individual",
  entidade: null,
  tema: "Ciclovias",
  origem: "portal_govbr",
  estado: "inscrita",
  chamadaEm: null,
  encerradaEm: null,
  tempoUsadoSegundos: null,
  ...p,
});

describe("inscrição da cidadã", () => {
  const base = { falaComo: "individual", entidade: "", tema: "Ciclovias na Beira-Mar", ciente: true };

  it("válida: o corpo leva o ciente e não leva entidade", () => {
    const r = validarInscricaoCidada(base);
    expect(r).toEqual({ ok: true, corpo: { "fala-como": "individual", tema: "Ciclovias na Beira-Mar", "ciente-publicidade": true } });
  });

  it("sem o ciente, não vai", () => {
    const r = validarInscricaoCidada({ ...base, ciente: false });
    expect(r).toMatchObject({ ok: false, campo: "ciente" });
  });

  it("fora de individual, a entidade é obrigatória", () => {
    expect(validarInscricaoCidada({ ...base, falaComo: "entidade" })).toMatchObject({ ok: false, campo: "entidade" });
    expect(validarInscricaoCidada({ ...base, falaComo: "conselho_movimento", entidade: "Conselho de Saúde" })).toMatchObject({
      ok: true,
      corpo: { entidade: "Conselho de Saúde" },
    });
  });

  it("o tema é obrigatório e cabe em uma frase", () => {
    expect(validarInscricaoCidada({ ...base, tema: "   " })).toMatchObject({ ok: false, campo: "tema" });
    expect(validarInscricaoCidada({ ...base, tema: "x".repeat(LIMITE_TEMA + 1) })).toMatchObject({ ok: false, campo: "tema" });
    expect(validarInscricaoCidada({ ...base, tema: "x".repeat(LIMITE_TEMA) }).ok).toBe(true);
  });

  it("um fala-como fora do vocabulário é recusado", () => {
    expect(validarInscricaoCidada({ ...base, falaComo: "vereador" })).toMatchObject({ ok: false, campo: "fala-como" });
  });
});

describe("inscrição presencial (Mesa)", () => {
  it("exige o nome, que aqui é digitado", () => {
    expect(validarInscricaoPresencial({ nome: " ", falaComo: "individual", entidade: "", tema: "t" })).toMatchObject({ ok: false, campo: "nome" });
    expect(validarInscricaoPresencial({ nome: "José", falaComo: "individual", entidade: "", tema: "Ônibus" })).toEqual({
      ok: true,
      corpo: { nome: "José", "fala-como": "individual", tema: "Ônibus" },
    });
  });
});

describe("tempo de fala", () => {
  it("minutos inteiros de 1 a 30", () => {
    expect(tempoFalaEmSegundos("5")).toEqual({ ok: true, segundos: 300 });
    expect(tempoFalaEmSegundos("30")).toEqual({ ok: true, segundos: 1800 });
    expect(tempoFalaEmSegundos("0").ok).toBe(false);
    expect(tempoFalaEmSegundos("31").ok).toBe(false);
    expect(tempoFalaEmSegundos("2,5").ok).toBe(false);
    expect(tempoFalaEmSegundos("").ok).toBe(false);
  });
  it("em palavras", () => {
    expect(rotuloTempoFala(300)).toBe("5 min");
    expect(rotuloTempoFala(90)).toBe("1 min 30 s");
    expect(rotuloTempoFala(45)).toBe("45 s");
  });
});

describe("a fila da Mesa", () => {
  it("ordena pela ordem de inscrição e acha quem fala e quem vem depois", () => {
    const f = filaDaAudiencia([
      insc({ id: "c", ordem: 3 }),
      insc({ id: "a", ordem: 1, estado: "falou" }),
      insc({ id: "b", ordem: 2, estado: "falando" }),
      insc({ id: "d", ordem: 4, estado: "desistiu" }),
    ]);
    expect(f.ordenadas.map((i) => i.id)).toEqual(["a", "b", "c", "d"]);
    expect(f.falando?.id).toBe("b");
    expect(f.proxima?.id).toBe("c");
    expect(f.aguardando).toBe(1);
  });

  it("só se chama com a sessão aberta e ninguém na palavra", () => {
    expect(podeChamar({ estado: "aberta", inscricoes: [insc({})] })).toBe(true);
    expect(podeChamar({ estado: "agendada", inscricoes: [insc({})] })).toBe(false);
    expect(podeChamar({ estado: "aberta", inscricoes: [insc({ estado: "falando" })] })).toBe(false);
    expect(podeInscreverPresencial({ estado: "suspensa" })).toBe(true);
    expect(podeInscreverPresencial({ estado: "encerrada" })).toBe(false);
  });

  it("o cronômetro é regressivo a partir do tempo da audiência", () => {
    const chamadaEm = "2026-06-11T12:00:00.000Z";
    const t0 = Date.parse(chamadaEm);
    expect(cronometroDaFala({ chamadaEm }, 300, t0 + 10_000)).toMatchObject({ decorrido: 10, relogio: "04:50" });
    expect(cronometroDaFala({ chamadaEm }, 300, t0 + 250_000).tempo.situacao).toBe("ultimo-minuto");
    const esgotado = cronometroDaFala({ chamadaEm }, 300, t0 + 312_000);
    expect(esgotado.tempo.situacao).toBe("esgotado");
    expect(esgotado.relogio).toBe("+00:12");
  });

  it("quem fala: o nome e, se representa alguém, a entidade", () => {
    expect(quemFala({ nome: "Ana", falaComo: "individual", entidade: null })).toBe("Ana");
    expect(quemFala({ nome: "Ana", falaComo: "entidade", entidade: "ACB" })).toBe("Ana · ACB");
  });
});

describe("portal", () => {
  it("a situação do cartão 'Quero falar'", () => {
    expect(situacaoDaInscricao({ estado: "agendada", inscricoesAbertas: true })).toBe("aberta");
    expect(situacaoDaInscricao({ estado: "agendada", inscricoesAbertas: false })).toBe("fechada");
    expect(situacaoDaInscricao({ estado: "encerrada", inscricoesAbertas: false })).toBe("realizada");
    expect(situacaoDaInscricao({ estado: "nao_realizada", inscricoesAbertas: false })).toBe("nao_realizada");
  });

  it("as datas como o design escreve", () => {
    const iso = new Date(2026, 5, 11, 9, 0).toISOString();
    expect(quandoPorExtenso(iso)).toBe("Quinta, 11/06/2026 · 9h");
    expect(diaEMes(iso)).toEqual({ dia: "11", mes: "jun" });
    expect(quandoPorExtenso(null)).toBeNull();
    expect(quandoPorExtenso("lixo")).toBeNull();
  });
});
