import { describe, expect, it } from "vitest";
import { formatarTipoSessao, formatarTituloSessao, selecionarSessaoAlvo, sessoesAgendadas } from "./pauta-convocacao-vista";
import type { SliSessaoOut } from "./use-mesa";
import type { SessaoOut } from "./pauta-convocacao-vista";

function sliSessao(parcial: Partial<SliSessaoOut> & { sessaoId: string }): SliSessaoOut {
  return { estadoAtual: "agendada", situacao: "agendada", agendadaPara: "2026-06-24T17:00:00Z", ...parcial };
}

function sessao(parcial: Partial<SessaoOut> = {}): SessaoOut {
  return {
    id: "s1", sessaoLegislativaId: "sl1", tipoSessao: "ordinaria", numeroSequencial: 15,
    estado: "agendada", modalidade: "presencial", delibera: true, transmitePublica: true,
    geraAtaRegimental: true, permiteVotoSecreto: false, permiteModalidadeRemota: false,
    agendadaPara: "2026-06-24T17:00:00Z", abertaEm: null, encerradaEm: null, motivoNaoRealizada: null,
    ...parcial,
  };
}

describe("sessoesAgendadas / selecionarSessaoAlvo", () => {
  it("filtra só situacao 'agendada' com agendadaPara, ordena por data crescente", () => {
    const sessoes = [
      sliSessao({ sessaoId: "b", agendadaPara: "2026-07-01T00:00:00Z" }),
      sliSessao({ sessaoId: "a", agendadaPara: "2026-06-24T00:00:00Z" }),
      sliSessao({ sessaoId: "c", situacao: "aberta" }),
      sliSessao({ sessaoId: "d", agendadaPara: null }),
    ];
    expect(sessoesAgendadas(sessoes).map((s) => s.sessaoId)).toEqual(["a", "b"]);
  });

  it("sem override -> auto-seleciona a mais próxima", () => {
    const sessoes = [
      sliSessao({ sessaoId: "b", agendadaPara: "2026-07-01T00:00:00Z" }),
      sliSessao({ sessaoId: "a", agendadaPara: "2026-06-24T00:00:00Z" }),
    ];
    expect(selecionarSessaoAlvo(sessoes, null)?.sessaoId).toBe("a");
  });

  it("com override válido -> respeita a escolha manual", () => {
    const sessoes = [
      sliSessao({ sessaoId: "b", agendadaPara: "2026-07-01T00:00:00Z" }),
      sliSessao({ sessaoId: "a", agendadaPara: "2026-06-24T00:00:00Z" }),
    ];
    expect(selecionarSessaoAlvo(sessoes, "b")?.sessaoId).toBe("b");
  });

  it("override de id inexistente -> ignora, cai no auto", () => {
    const sessoes = [sliSessao({ sessaoId: "a" })];
    expect(selecionarSessaoAlvo(sessoes, "x")?.sessaoId).toBe("a");
  });

  it("nenhuma sessão agendada -> null", () => {
    expect(selecionarSessaoAlvo([], null)).toBeNull();
  });
});

describe("formatarTipoSessao / formatarTituloSessao", () => {
  it("mapeia os 5 tipos conhecidos (logic/tipos-sessao no backend)", () => {
    expect(formatarTipoSessao("ordinaria")).toBe("Ordinária");
    expect(formatarTipoSessao("extraordinaria")).toBe("Extraordinária");
    expect(formatarTipoSessao("solene")).toBe("Solene");
    expect(formatarTipoSessao("secreta")).toBe("Secreta");
    expect(formatarTipoSessao("especial")).toBe("Especial");
  });

  it("tipo desconhecido -> capitaliza o texto cru (fail-closed, não esconde)", () => {
    expect(formatarTipoSessao("nova_categoria")).toBe("Nova_categoria");
  });

  it("monta o título 'Nª Sessão Tipo'", () => {
    expect(formatarTituloSessao(sessao({ numeroSequencial: 15, tipoSessao: "ordinaria" }))).toBe("15ª Sessão Ordinária");
  });
});

import {
  agruparPautaPorFase,
  derivarConvocacao,
  derivarProntasForaDaPauta,
  indexarProposicoesPorId,
  resolverTituloItem,
} from "./pauta-convocacao-vista";
import type { PautaItemOut, PautaOut } from "./pauta-convocacao-vista";
import type { ProposicaoResumoOut } from "./contrato-legislativo.gen";

function pautaItem(parcial: Partial<PautaItemOut> & { id: string; fase: string; tipoItem: string; ordem: number }): PautaItemOut {
  return parcial;
}

function proposicao(parcial: Partial<ProposicaoResumoOut> & { id: string }): ProposicaoResumoOut {
  return {
    tipo: "projeto_lei", ano: 2026, sequencial: 1, urnLex: "urn:x", ementa: "Ementa de teste",
    estado: "em_comissoes", atualizadoEm: "2026-01-01T00:00:00Z", ...parcial,
  };
}

describe("agruparPautaPorFase", () => {
  it("pauta nula -> grupos Expediente/Ordem do Dia vazios, sem 'Outras fases'", () => {
    const grupos = agruparPautaPorFase(null);
    expect(grupos.map((g) => g.titulo)).toEqual(["Expediente", "Ordem do Dia"]);
    expect(grupos.every((g) => g.itens.length === 0)).toBe(true);
  });

  it("separa por fase e ordena por 'ordem' dentro do grupo", () => {
    const pauta: PautaOut = {
      sessaoId: "s1",
      itens: [
        pautaItem({ id: "2", fase: "ordem_do_dia", tipoItem: "leitura", ordem: 2 }),
        pautaItem({ id: "1", fase: "expediente", tipoItem: "leitura", ordem: 1 }),
        pautaItem({ id: "3", fase: "ordem_do_dia", tipoItem: "leitura", ordem: 1 }),
      ],
    };
    const grupos = agruparPautaPorFase(pauta);
    expect(grupos.find((g) => g.chave === "ordem-do-dia")?.itens.map((i) => i.id)).toEqual(["3", "2"]);
    expect(grupos.find((g) => g.chave === "expediente")?.itens.map((i) => i.id)).toEqual(["1"]);
  });

  it("fase fora de expediente/ordem_do_dia cai em 'Outras fases' (fail-closed, nunca some em silêncio)", () => {
    const pauta: PautaOut = {
      sessaoId: "s1",
      itens: [pautaItem({ id: "1", fase: "tribuna_livre_cidadao", tipoItem: "leitura", ordem: 1 })],
    };
    const grupos = agruparPautaPorFase(pauta);
    expect(grupos.map((g) => g.titulo)).toContain("Outras fases");
    expect(grupos.find((g) => g.chave === "outras")?.itens[0].id).toBe("1");
  });
});

describe("resolverTituloItem", () => {
  it("tipo 'proposicao' resolvido no índice -> número + ementa", () => {
    const item = pautaItem({ id: "1", fase: "ordem_do_dia", tipoItem: "proposicao", proposicaoId: "p1", ordem: 1 });
    const indice = indexarProposicoesPorId([proposicao({ id: "p1", tipo: "projeto_lei", sequencial: 29, ano: 2026, ementa: "Cria o programa X" })]);
    expect(resolverTituloItem(item, indice)).toEqual({ numero: "PL 29/2026", rotulo: "Cria o programa X", indisponivel: false });
  });

  it("tipo 'proposicao' fora do índice -> rótulo honesto de indisponível, não quebra", () => {
    const item = pautaItem({ id: "1", fase: "ordem_do_dia", tipoItem: "proposicao", proposicaoId: "p-fora", ordem: 1 });
    expect(resolverTituloItem(item, new Map())).toEqual({
      rotulo: "Matéria fora da página carregada de proposições",
      indisponivel: true,
    });
  });

  it("tipo não-'proposicao' -> usa textoDescricao direto", () => {
    const item = pautaItem({ id: "1", fase: "expediente", tipoItem: "leitura", textoDescricao: "Leitura da ata", ordem: 1 });
    expect(resolverTituloItem(item, new Map())).toEqual({ rotulo: "Leitura da ata", indisponivel: false });
  });
});

describe("derivarProntasForaDaPauta", () => {
  it("filtra por estado 'pronta para pauta' e exclui quem já está na pauta atual", () => {
    const props = [
      proposicao({ id: "p1", estado: "aguardando_pauta" }),
      proposicao({ id: "p2", estado: "em_pauta" }),
      proposicao({ id: "p3", estado: "em_comissoes" }),
    ];
    const pauta: PautaOut = { sessaoId: "s1", itens: [pautaItem({ id: "i1", fase: "ordem_do_dia", tipoItem: "proposicao", proposicaoId: "p2", ordem: 1 })] };
    const r = derivarProntasForaDaPauta(props, props.length, pauta);
    expect(r.itens.map((p) => p.id)).toEqual(["p1"]);
    expect(r.truncado).toBe(false);
  });

  it("total maior que a página buscada -> truncado honesto", () => {
    const props = [proposicao({ id: "p1", estado: "aguardando_pauta" })];
    const r = derivarProntasForaDaPauta(props, 150, null);
    expect(r.truncado).toBe(true);
  });
});

describe("derivarConvocacao", () => {
  it("sessão sem agendadaPara -> null (nada a convocar)", () => {
    expect(derivarConvocacao(sessao({ agendadaPara: null }), [])).toBeNull();
  });

  it("conta itens por grupo e monta o título do edital", () => {
    const grupos = agruparPautaPorFase({
      sessaoId: "s1",
      itens: [
        pautaItem({ id: "1", fase: "expediente", tipoItem: "leitura", ordem: 1 }),
        pautaItem({ id: "2", fase: "ordem_do_dia", tipoItem: "leitura", ordem: 1 }),
        pautaItem({ id: "3", fase: "ordem_do_dia", tipoItem: "leitura", ordem: 2 }),
      ],
    });
    const conv = derivarConvocacao(sessao({ numeroSequencial: 15, tipoSessao: "ordinaria" }), grupos);
    expect(conv?.tituloEdital).toBe("Edital de convocação — 15ª Sessão Ordinária");
    expect(conv?.data).toBe("24/06/2026");
    expect(conv?.contagemExpediente).toBe(1);
    expect(conv?.contagemOrdemDoDia).toBe(2);
  });
});
