import { describe, expect, it } from "vitest";
import { atuaisPorGravacao, avisoDeAtencao, blocosDeFala, relogio, situacao } from "./transcricao-vista";
import type { TranscricaoPonteiroOut } from "./contrato-sessoes.gen";

const p = (over: Partial<TranscricaoPonteiroOut> = {}): TranscricaoPonteiroOut => ({
  id: "p1",
  segmentoId: "g1",
  situacao: "concluida",
  transcricaoId: "t1",
  versao: 1,
  duracaoS: 3725,
  nTrechos: 412,
  coberturaAtribuida: 0.9,
  ocorridoEm: "2026-09-26T21:00:00Z",
  ...over,
});

describe("transcricao-vista", () => {
  it("relógio da gravação", () => {
    expect(relogio(0)).toBe("0:00");
    expect(relogio(37.9)).toBe("0:37");
    expect(relogio(725)).toBe("12:05");
    expect(relogio(3725)).toBe("1:02:05");
  });

  it("frases seguidas da mesma pessoa viram um bloco; sem nome é bloco próprio", () => {
    const b = blocosDeFala([
      { inicio: 0, fim: 4, texto: "Senhor presidente,", oradorNome: "Ana" },
      { inicio: 4, fim: 9, texto: "peço a palavra.", oradorNome: "Ana" },
      { inicio: 10, fim: 12, texto: "Aparte!", oradorNome: null },
      { inicio: 12, fim: 20, texto: "Concedo.", oradorNome: "Ana" },
    ]);
    expect(b).toEqual([
      { orador: "Ana", inicio: 0, fim: 9, texto: "Senhor presidente, peço a palavra." },
      { orador: null, inicio: 10, fim: 12, texto: "Aparte!" },
      { orador: "Ana", inicio: 12, fim: 20, texto: "Concedo." },
    ]);
  });

  it("aviso de atenção só quando falta orador ou a cobertura é baixa", () => {
    const tudoNomeado = [{ inicio: 0, fim: 1, texto: "a", oradorNome: "Ana" }];
    expect(avisoDeAtencao(p(), tudoNomeado)).toBeNull();
    expect(avisoDeAtencao(p({ coberturaAtribuida: 0.5 }), tudoNomeado)).toBe(
      "Revisar com atenção: 50% da fala tem orador identificado pela palavra concedida na Mesa.",
    );
    expect(avisoDeAtencao(p(), [...tudoNomeado, { inicio: 1, fim: 2, texto: "b", oradorNome: null }])).toContain(
      "1 trecho sem orador",
    );
  });

  it("situação: concluída com duração e trechos; falha com causa e o piso R-IA-1", () => {
    expect(situacao(p())).toMatch(/^Transcrita em 26\/09\/2026, \d\d:\d\d · 1:02:05 · 412 trechos$/);
    const f = situacao(p({ situacao: "falhou", categoriaErro: "entrada", transcricaoId: null }));
    expect(f).toContain("o áudio não pôde ser lido");
    expect(f).toContain("Siga pela tela");
  });

  it("uma situação por gravação: a mais recente", () => {
    const r = atuaisPorGravacao([p({ id: "novo" }), p({ id: "velho" }), p({ id: "outra", segmentoId: "g2" })]);
    expect(r.map((x) => x.id)).toEqual(["novo", "outra"]);
  });
});
