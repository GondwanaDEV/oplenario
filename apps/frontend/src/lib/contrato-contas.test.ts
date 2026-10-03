import { describe, expect, it } from "vitest";
import { camelizarChaves } from "./boundary";
import {
  ROTAS_CONTAS,
  ROTULO_ESTADO,
  ROTULO_PARECER,
  corpoDaNotificacao,
  corpoDaNovaPrestacao,
  corpoDosParametros,
  doFio,
  formaValida,
  rotulo,
  type NovaPrestacaoIn,
} from "./contrato-contas";

// ADR-0021 Parte B: o contrato à mão. Os corpos saem em kebab-case com só o que o tipo usa; as respostas do fio
// (camelizadas) passam pela forma mínima, e o que falta vira erro — nunca meio-dado.

const prestacaoFio = {
  id: "pc1", tipo: "governo_prefeito", exercicio: 2024, responsavel: "José Sarto", "parecer-previo": "favoravel_com_ressalvas",
  estado: "prazo_de_defesa", resultado: null, "prazo-julgamento-ate": "2026-12-01", "recebida-em": "2026-10-02",
  "processo-tce": "12345/2025", proposicao: { id: "p9", rotulo: "PDL 3/2026", estado: "em_comissao" },
  "notificado-em": "2026-10-03", "notificacao-meio": "AR", "prazo-defesa-ate": "2026-10-18", quorum: { "base-membros": 21, "necessarios-para-rejeitar": 14 },
  pautavel: false, "motivo-nao-pautavel": "o prazo de defesa vai até 18/10/2026",
  documentos: [{ id: "d1", tipo: "parecer_previo", nome: "parecer.pdf", "tamanho-bytes": 1024, "criado-em": "2026-10-02T12:00:00Z" }],
};

describe("os corpos das escritas (kebab-case)", () => {
  const base: NovaPrestacaoIn = {
    tipo: "governo_prefeito", exercicio: 2024, responsavel: "  José Sarto ", recebidaEm: "2026-10-02", processoTce: " ",
    parecerPrevio: "favoravel", comissaoAutoraId: "c1", situacaoTce: "ignorada",
  };

  it("governo: parecer e comissão autora; opcional vazio vai como null; sem situação no TCE", () => {
    expect(corpoDaNovaPrestacao(base)).toEqual({
      tipo: "governo_prefeito", exercicio: 2024, responsavel: "José Sarto", "recebida-em": "2026-10-02", "processo-tce": null,
      "parecer-previo": "favoravel", "comissao-autora-id": "c1",
    });
  });

  it("Mesa: situação no TCE; sem parecer nem comissão (não há PDL)", () => {
    const c = corpoDaNovaPrestacao({ ...base, tipo: "gestao_camara", processoTce: "999/2025", situacaoTce: "Em instrução" });
    expect(c).toEqual({
      tipo: "gestao_camara", exercicio: 2024, responsavel: "José Sarto", "recebida-em": "2026-10-02", "processo-tce": "999/2025",
      "situacao-tce": "Em instrução",
    });
    expect(c).not.toHaveProperty("parecer-previo");
  });

  it("notificação e parâmetros", () => {
    expect(corpoDaNotificacao("2026-10-03", " AR (Correios) ")).toEqual({ "notificado-em": "2026-10-03", meio: "AR (Correios)" });
    expect(corpoDosParametros({ prazoDefesaDias: 15, prazoJulgamentoDias: 60 })).toEqual({ "prazo-defesa-dias": 15, "prazo-julgamento-dias": 60 });
  });
});

describe("as rotas", () => {
  it("são as da ADR, com cada segmento codificado", () => {
    expect(ROTAS_CONTAS.lista).toBe("/api/contas");
    expect(ROTAS_CONTAS.prestacao("a/b")).toBe("/api/contas/a%2Fb");
    expect(ROTAS_CONTAS.documentos("pc1", "defesa")).toBe("/api/contas/pc1/documentos?tipo=defesa");
    expect(ROTAS_CONTAS.documento("pc1", "d1")).toBe("/api/contas/pc1/documentos/d1");
    expect(ROTAS_CONTAS.daProposicao("p9")).toBe("/api/contas-da-proposicao/p9");
    expect(ROTAS_CONTAS.parametros).toBe("/api/parametros-de-contas");
    expect(ROTAS_CONTAS.portalDocumento("ce", "pc1", "d1")).toBe("/api/portal/casa/ce/contas/pc1/documentos/d1");
  });
});

describe("doFio + formaValida", () => {
  it("a prestação camelizada passa", () => {
    const p = doFio.prestacao(camelizarChaves(prestacaoFio));
    expect(formaValida.prestacao(p)).toBe(true);
    expect(p).toMatchObject({ quorum: { baseMembros: 21, necessariosParaRejeitar: 14 }, motivoNaoPautavel: "o prazo de defesa vai até 18/10/2026" });
  });

  it("envelope aberto; documentos ausentes viram lista vazia; pautável ausente é false (fail-closed)", () => {
    const semNada: Record<string, unknown> = { ...prestacaoFio };
    delete semNada.documentos;
    delete semNada.pautavel;
    const p = doFio.prestacao({ prestacao: camelizarChaves(semNada) }) as { documentos: unknown[]; pautavel: boolean };
    expect(p.documentos).toEqual([]);
    expect(p.pautavel).toBe(false);
    expect(formaValida.prestacao(p)).toBe(true);
  });

  it("forma que não bate é recusada", () => {
    expect(formaValida.prestacao({ id: "x" })).toBe(false);
    expect(formaValida.lista({ prestacoes: [{ id: "x" }] })).toBe(false);
    expect(formaValida.lista(doFio.lista([]))).toBe(true);
    expect(formaValida.parametros({ prazoDefesaDias: 15 })).toBe(false);
    expect(formaValida.parametros({ prazoDefesaDias: 15, prazoJulgamentoDias: 60, padrao: true })).toBe(true);
  });

  it("portal: documentos ausentes viram lista vazia", () => {
    const d = doFio.publica({ prestacoes: [{ id: "pc1", tipo: "gestao_camara", exercicio: 2024, estado: "acompanhamento" }] }) as {
      prestacoes: { documentos: unknown[] }[];
    };
    expect(d.prestacoes[0].documentos).toEqual([]);
    expect(formaValida.publica(d)).toBe(true);
  });
});

describe("rótulos em palavras", () => {
  it("parecer e estado; valor desconhecido sai como veio", () => {
    expect(rotulo(ROTULO_PARECER, "favoravel_com_ressalvas")).toBe("Favorável com ressalvas");
    expect(rotulo(ROTULO_PARECER, "desfavoravel")).toBe("Desfavorável");
    expect(rotulo(ROTULO_ESTADO, "pronta_para_pauta")).toBe("Pronta para pauta");
    expect(rotulo(ROTULO_ESTADO, "aguardando_notificacao")).toBe("Aguardando notificação");
    expect(rotulo(ROTULO_ESTADO, "novo_estado")).toBe("novo_estado");
    expect(rotulo(ROTULO_ESTADO, null)).toBe("");
  });
});
