import { describe, expect, it } from "vitest";
import { avisoDoRascunho, paragrafosDoRascunho, rotuloDaCitacao, situacaoDoRascunho } from "./rascunho-ata-vista";
import type { AtaRascunhoOut, CitacaoRascunhoOut } from "./contrato-sessoes.gen";

const cit = (over: Partial<CitacaoRascunhoOut> = {}): CitacaoRascunhoOut => ({
  fonteId: "transcricao:t#1", trecho: "Senhor presidente", inicio: 0, fim: 0, status: "conferida", rotulo: "Ana, 0:10–0:40", ...over,
});
const ped = (over: Partial<AtaRascunhoOut> = {}): AtaRascunhoOut => ({
  solicitacaoId: "s", situacao: "pronto", solicitadoEm: "2026-09-26T21:00:00Z", ocorridoEm: "2026-09-26T21:02:00Z", ...over,
});

describe("rascunho-ata-vista", () => {
  it("numera as citações na ordem, destaca pontos a confirmar e parágrafos sem fonte", () => {
    const texto =
      "Reuniu-se a Câmara. [[sessao:1 | Sessão ordinária nº 12.]]\n\nAna falou: “Senhor presidente” [[transcricao:t#1 | Senhor presidente]]" +
      "\n\nEncerrada a sessão. [confirmar: horário de encerramento]";
    const ps = paragrafosDoRascunho(texto, [cit({ fonteId: "sessao:1" }), cit({ status: "trecho_nao_encontrado" })], [2]);
    expect(ps).toHaveLength(3);
    expect(ps[0].partes).toEqual([{ tipo: "texto", texto: "Reuniu-se a Câmara. " }, expect.objectContaining({ tipo: "citacao", n: 1 })]);
    expect(ps[1].partes[1]).toMatchObject({ tipo: "citacao", n: 2, citacao: { status: "trecho_nao_encontrado" } });
    expect(ps.map((p) => p.semFonte)).toEqual([false, false, true]);
    expect(ps[2].partes).toEqual([
      { tipo: "texto", texto: "Encerrada a sessão. " },
      { tipo: "confirmar", texto: "horário de encerramento" },
    ]);
  });

  it("título # não conta como parágrafo para o 'sem fonte' (igual ao satélite)", () => {
    const ps = paragrafosDoRascunho("# ATA\n\nSem fonte aqui.", [], [0]);
    expect(ps.map((p) => p.semFonte)).toEqual([false, true]);
  });

  it("aviso em linguagem de quem revisa", () => {
    expect(avisoDoRascunho("normal", [])).toBeNull();
    expect(avisoDoRascunho("revisar_com_atencao", ["conteudo_de_terceiro", "sem_fonte"])).toBe(
      "Revise com atenção: o texto nasceu da fala transcrita, que pode ter erros de reconhecimento; há parágrafos sem fonte na transcrição.",
    );
  });

  it("citação que não confere diz isso", () => {
    expect(rotuloDaCitacao(cit())).toBe("Ana, 0:10–0:40");
    expect(rotuloDaCitacao(cit({ status: "fonte_nao_lida", rotulo: null }))).toBe("transcricao:t#1 — não confere com a transcrição");
    expect(rotuloDaCitacao(null)).toBe("Fonte não identificada");
  });

  it("situação do pedido", () => {
    expect(situacaoDoRascunho(null)).toMatchObject({ podePedir: true, revisar: false });
    expect(situacaoDoRascunho(ped({ situacao: "solicitado" }))).toMatchObject({ podePedir: false, titulo: "A IA está redigindo o rascunho…" });
    expect(situacaoDoRascunho(ped({ situacao: "falhou", detalheErro: "a sessão ainda não tem transcrição." })).detalhe).toBe(
      "a sessão ainda não tem transcrição. Siga pela tela ou peça de novo.",
    );
    expect(situacaoDoRascunho(ped({ nPontosAConfirmar: 1, nParagrafosSemFonte: 2 })).detalhe).toBe(
      "Antes de usar: 1 ponto a confirmar e 2 parágrafos sem fonte.",
    );
  });
});
