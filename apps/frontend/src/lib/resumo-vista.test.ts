import { describe, expect, it } from "vitest";
import { avisoDoResumo, faltaParaPublicar, linhaDaVersao, mensagemDeErroResumo, situacaoDoResumo } from "./resumo-vista";
import type { RascunhoResumoPonteiroOut } from "./contrato-legislativo.gen";

const pronto: RascunhoResumoPonteiroOut = {
  situacao: "pronto",
  rascunhoId: "r1",
  desatualizado: false,
  modeloLlmId: "fake:fake-1",
  promptVersao: "resumo-v1",
  incerteza: "revisar_com_atencao",
  nCitacoes: 2,
  nCitacoesConferidas: 2,
  nParagrafosSemFonte: 1,
  categoriaErro: null,
  retentavel: null,
  ocorridoEm: "2026-09-27T01:00:00Z",
};

describe("situacaoDoResumo", () => {
  it("sem rascunho: a IA redige sozinha, e dá para escrever à mão", () => {
    expect(situacaoDoResumo(null)).toMatchObject({ revisar: false, titulo: "A IA ainda não redigiu o resumo" });
  });

  it("pronto: os sinais da conferência, em linguagem de quem revisa", () => {
    expect(situacaoDoResumo(pronto)).toEqual({
      titulo: "Rascunho da IA pronto para revisar",
      detalhe: "2 de 2 citações conferidas · 1 parágrafo sem fonte.",
      revisar: true,
    });
  });

  it("de uma versão anterior do texto: avisa, e ainda deixa revisar", () => {
    const s = situacaoDoResumo({ ...pronto, desatualizado: true });
    expect(s.titulo).toMatch(/versão anterior/);
    expect(s.revisar).toBe(true);
  });

  it("falhou: diz o motivo sem jargão e não oferece revisão", () => {
    const s = situacaoDoResumo({ ...pronto, situacao: "falhou", rascunhoId: null, categoriaErro: "infraestrutura" });
    expect(s.detalhe).toMatch(/a IA ficou fora do ar/);
    expect(s.revisar).toBe(false);
  });
});

it("aviso de incerteza", () => {
  expect(avisoDoResumo("normal", [])).toBeNull();
  expect(avisoDoResumo("revisar_com_atencao", ["sem_fonte"])).toBe(
    "Revise com atenção: há parágrafos sem fonte no texto da proposição.",
  );
});

it("linha da versão, falta para publicar e erros", () => {
  expect(linhaDaVersao({ versao: 2, publicadoEm: "2026-09-27T12:00:00Z", origemRedacao: "redigida_pela_casa" })).toMatch(
    /^Versão 2 · publicada em \d{2}\/\d{2}\/2026 · escrito pela Casa$/,
  );
  expect(faltaParaPublicar("   ")).toBe("Escreva o resumo.");
  expect(faltaParaPublicar("a".repeat(4001))).toMatch(/passou de 4000/);
  expect(faltaParaPublicar("Cria hortas.")).toBeNull();
  expect(mensagemDeErroResumo(409, "recarregue")).toBe("recarregue");
  expect(mensagemDeErroResumo(500, undefined)).toBe("Não foi possível publicar agora. Tente de novo.");
});
