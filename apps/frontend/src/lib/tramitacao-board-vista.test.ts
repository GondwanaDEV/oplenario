import { describe, expect, it } from "vitest";
import {
  derivarBoard,
  itemCorrespondeBusca,
  filtrarColunasPorBusca,
  filtrarColunasPorEspecie,
  paginarColuna,
  TETO_ITENS_VISIVEIS_POR_COLUNA,
} from "./tramitacao-board-vista";
import { formatarEspecieProposicao } from "./proposicoes-vista";
import { derivarTramitacao } from "./tramitacao-vista";
import type { ItemBoardOut, TotalPorEstadoOut } from "./contrato-mesa.gen";

// Onda B Slice 4 (tramitacao-board-vista) — agrupa ItemBoardOut (já vem agrupado/ordenado por
// estado asc, transicionouEm asc dentro do grupo, vindo do backend) em 5 colunas FIXAS do quadro-fonte
// (tramitacao-board.html) + 1 coluna honesta "Outros" para qualquer estado fora do mapa (fail-closed:
// nunca descarta uma matéria em silêncio — ver o mesmo princípio em tramitacao-vista.ts/proposicoes-vista.ts).

function item(parcial: Partial<ItemBoardOut> & { estado: string; proposicaoId: string }): ItemBoardOut {
  return {
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 1,
    urnLex: "urn:x",
    ementa: "Ementa de teste",
    autorTexto: "Fulano",
    transicionouEm: "2026-01-01T00:00:00Z",
    ...parcial,
  };
}

describe("derivarBoard", () => {
  it("lista vazia -> as 5 colunas conhecidas presentes, todas com itens:[], sem coluna Outros", () => {
    const colunas = derivarBoard([]);
    expect(colunas.map((c) => c.titulo)).toEqual([
      "Protocolo",
      "Comissões",
      "Pronta p/ pauta",
      "Em Plenário",
      "Concluídas",
    ]);
    expect(colunas.every((c) => c.itens.length === 0)).toBe(true);
  });

  it("protocolada -> cai na coluna Protocolo (azulejo jade)", () => {
    const colunas = derivarBoard([item({ proposicaoId: "1", estado: "protocolada" })]);
    const protocolo = colunas.find((c) => c.titulo === "Protocolo")!;
    expect(protocolo.azulejo).toBe("jade");
    expect(protocolo.itens).toHaveLength(1);
    expect(protocolo.itens[0].proposicaoId).toBe("1");
  });

  it("em_comissoes -> cai na coluna Comissões (azulejo cobalto)", () => {
    const colunas = derivarBoard([item({ proposicaoId: "2", estado: "em_comissoes" })]);
    const comissoes = colunas.find((c) => c.titulo === "Comissões")!;
    expect(comissoes.azulejo).toBe("cobalto");
    expect(comissoes.itens).toHaveLength(1);
  });

  // 05/10/2026: o quadro punha `em_pauta` em "Pronta p/ pauta", e a ficha dizia "Em pauta". No rito da Casa
  // (apps/backend/demo/acervo.clj) a matéria só está PRONTA em `aguardando_pauta`; ao `incluir_pauta` ela já
  // está numa pauta e o Plenário é quem decide (`em_pauta` -> `aprovada`/`arquivada`).
  it("aguardando_pauta -> Pronta p/ pauta (azulejo amarelo); em_pauta NÃO está pronta, já está na pauta", () => {
    const colunas = derivarBoard([
      item({ proposicaoId: "3", estado: "em_pauta" }),
      item({ proposicaoId: "4", estado: "aguardando_pauta" }),
    ]);
    const pronta = colunas.find((c) => c.titulo === "Pronta p/ pauta")!;
    expect(pronta.azulejo).toBe("amarelo");
    expect(pronta.itens.map((i) => i.proposicaoId)).toEqual(["4"]);
  });

  it("em_pauta, primeiro_turno, segundo_turno e em_sancao -> todos caem em Em Plenário (azulejo telha)", () => {
    const colunas = derivarBoard([
      item({ proposicaoId: "3", estado: "em_pauta" }),
      item({ proposicaoId: "5", estado: "primeiro_turno" }),
      item({ proposicaoId: "6", estado: "segundo_turno" }),
      item({ proposicaoId: "7", estado: "em_sancao" }),
    ]);
    const plenario = colunas.find((c) => c.titulo === "Em Plenário")!;
    expect(plenario.azulejo).toBe("telha");
    expect(plenario.itens.map((i) => i.proposicaoId)).toEqual(["3", "5", "6", "7"]);
  });

  it("o total autoritativo segue a mesma regra: em_pauta soma em Em Plenário, não em Pronta p/ pauta", () => {
    const colunas = derivarBoard([], [
      { estado: "aguardando_pauta", total: 4 },
      { estado: "em_pauta", total: 2 },
    ]);
    expect(colunas.find((c) => c.titulo === "Pronta p/ pauta")!.total).toBe(4);
    expect(colunas.find((c) => c.titulo === "Em Plenário")!.total).toBe(2);
  });

  // O cartão do quadro e a ficha leem o rótulo da mesma função (rotularEstado). Chaves reais da demo.
  it("o cartão traz a situação com o MESMO rótulo da ficha, para cada estado do rito da demo", () => {
    for (const estado of ["protocolada", "em_comissoes", "aguardando_pauta", "em_pauta", "aprovada", "arquivada"]) {
      const colunas = derivarBoard([item({ proposicaoId: "x", estado })]);
      const cartao = colunas.flatMap((c) => c.itens)[0];
      expect(cartao.situacao).toBe(derivarTramitacao(estado).rotuloSituacao);
    }
    const colunas = derivarBoard([item({ proposicaoId: "x", estado: "em_pauta" })]);
    expect(colunas.flatMap((c) => c.itens)[0].situacao).toBe("Em pauta");
  });

  it("estados aprovados e arquivados -> ambos caem em Concluídas (azulejo verde)", () => {
    const colunas = derivarBoard([
      item({ proposicaoId: "8", estado: "aprovada" }),
      item({ proposicaoId: "9", estado: "arquivada" }),
      item({ proposicaoId: "10", estado: "sancionado" }),
    ]);
    const concluidas = colunas.find((c) => c.titulo === "Concluídas")!;
    expect(concluidas.azulejo).toBe("verde");
    expect(concluidas.itens.map((i) => i.proposicaoId)).toEqual(["8", "9", "10"]);
  });

  it("o cartão diz o nome que o rito da Casa dá à etapa; sem ele, o rótulo fixo", () => {
    const colunas = derivarBoard([
      item({ proposicaoId: "casa", estado: "em_comissoes", rotuloEstado: "Em análise nas comissões" }),
      item({ proposicaoId: "fixo", estado: "em_comissoes", rotuloEstado: null }),
      item({ proposicaoId: "branco", estado: "em_comissoes", rotuloEstado: "  " }),
    ]);
    const situacoes = Object.fromEntries(colunas.flatMap((c) => c.itens).map((i) => [i.proposicaoId, i.situacao]));
    expect(situacoes).toEqual({
      casa: "Em análise nas comissões",
      fixo: derivarTramitacao("em_comissoes").rotuloSituacao,
      branco: derivarTramitacao("em_comissoes").rotuloSituacao,
    });
    // a coluna continua vindo da chave do estado, não do nome
    expect(colunas.find((c) => c.chave === "comissoes")!.itens).toHaveLength(3);
  });

  it("a partir do autógrafo o desfecho vence o nome da Casa", () => {
    const [cartao] = derivarBoard([
      item({ proposicaoId: "lei", estado: "aguardando_pauta", rotuloEstado: "Aguardando a Ordem do Dia", desfecho: "publicada" }),
    ]).flatMap((c) => c.itens);
    expect(cartao.situacao).toBe("Virou lei");
  });

  it("a partir do autógrafo a coluna vem do desfecho: lei em Concluídas, veto por apreciar em Em Plenário (docs/16 linha 18)", () => {
    const colunas = derivarBoard(
      [
        item({ proposicaoId: "lei", estado: "aguardando_pauta", desfecho: "publicada" }),
        item({ proposicaoId: "veto", estado: "aguardando_pauta", desfecho: "vetado" }),
        item({ proposicaoId: "espera", estado: "aguardando_pauta", desfecho: null }),
      ],
      [
        { estado: "aguardando_pauta", desfecho: "publicada", total: 3 },
        { estado: "aguardando_pauta", desfecho: "vetado", total: 1 },
        { estado: "aguardando_pauta", desfecho: null, total: 5 },
      ],
    );
    const col = (chave: string) => colunas.find((c) => c.chave === chave)!;
    expect(col("concluidas").itens.map((i) => [i.proposicaoId, i.situacao])).toEqual([["lei", "Virou lei"]]);
    expect(col("em-plenario").itens.map((i) => [i.proposicaoId, i.situacao])).toEqual([["veto", "Vetada"]]);
    expect(col("pronta-pauta").itens.map((i) => i.proposicaoId)).toEqual(["espera"]);
    expect([col("concluidas").total, col("em-plenario").total, col("pronta-pauta").total]).toEqual([3, 1, 5]);
  });

  it("estado desconhecido -> não descarta em silêncio: cai numa 6ª coluna 'Outros' (azulejo neutro)", () => {
    const colunas = derivarBoard([item({ proposicaoId: "11", estado: "xpto-desconhecido" })]);
    const outros = colunas.find((c) => c.titulo === "Outros")!;
    expect(outros).toBeDefined();
    expect(outros.azulejo).toBe("neutro");
    expect(outros.itens.map((i) => i.proposicaoId)).toEqual(["11"]);
  });

  it("coluna Outros só aparece quando há ao menos 1 item nela", () => {
    const colunas = derivarBoard([item({ proposicaoId: "1", estado: "protocolada" })]);
    expect(colunas.find((c) => c.titulo === "Outros")).toBeUndefined();
  });

  it("preserva a ordem de chegada dentro de uma coluna (não reordena)", () => {
    const colunas = derivarBoard([
      item({ proposicaoId: "z", estado: "em_comissoes", transicionouEm: "2026-03-01T00:00:00Z" }),
      item({ proposicaoId: "a", estado: "em_comissoes", transicionouEm: "2026-01-01T00:00:00Z" }),
    ]);
    const comissoes = colunas.find((c) => c.titulo === "Comissões")!;
    // "z" chegou primeiro no payload (mesmo com transicionouEm mais recente) — a ordem NÃO é
    // re-derivada aqui, ela já vem correta do backend (estado asc, transicionouEm asc).
    expect(comissoes.itens.map((i) => i.proposicaoId)).toEqual(["z", "a"]);
  });

  it("cada item do board carrega número/espécie formatados (reuso de proposicoes-vista) + autor com fallback", () => {
    const colunas = derivarBoard([
      item({ proposicaoId: "1", estado: "protocolada", tipo: "projeto_lei", sequencial: 42, ano: 2026, autorTexto: null }),
    ]);
    const protocolo = colunas.find((c) => c.titulo === "Protocolo")!;
    expect(protocolo.itens[0].numero).toBe("PL 42/2026");
    expect(protocolo.itens[0].especie).toBe("Projeto de Lei");
    expect(protocolo.itens[0].autor).toBe("—");
  });
});

describe("derivarBoard — totais-por-estado (fatia 'truncamento-familia')", () => {
  // O board corta 50 itens POR ESTADO no servidor (paineis/db/tramitacao/listar-board) — `coluna.itens`
  // reflete esse corte. `coluna.total` tem que vir do par autoritativo `totaisPorEstado` (GET
  // /paineis/tramitacao), NUNCA de `coluna.itens.length` (que mentiria sob corte).

  function total(estado: string, n: number): TotalPorEstadoOut {
    return { estado, total: n };
  }

  it("sem totaisPorEstado (chamada antiga/compat) -> total cai pra itens.length", () => {
    const colunas = derivarBoard([item({ proposicaoId: "1", estado: "protocolada" })]);
    const protocolo = colunas.find((c) => c.titulo === "Protocolo")!;
    expect(protocolo.total).toBe(1);
  });

  it("coluna.total vem do servidor, DISCORDANDO de itens.length quando a lista foi cortada", () => {
    // só 2 itens chegaram (a lista cortada pelo teto), mas o total real do estado é 7 — o cenário exato
    // em que a heurística ingênua (itens.length) mentiria.
    const colunas = derivarBoard(
      [
        item({ proposicaoId: "1", estado: "em_comissoes" }),
        item({ proposicaoId: "2", estado: "em_comissoes" }),
      ],
      [total("em_comissoes", 7)],
    );
    const comissoes = colunas.find((c) => c.titulo === "Comissões")!;
    expect(comissoes.itens).toHaveLength(2);
    expect(comissoes.total).toBe(7);
    expect(comissoes.total).not.toBe(comissoes.itens.length);
  });

  it("coluna que funde vários estados (Concluídas) soma o total de cada um", () => {
    const colunas = derivarBoard(
      [item({ proposicaoId: "1", estado: "aprovada" }), item({ proposicaoId: "2", estado: "arquivada" })],
      [total("aprovada", 3), total("arquivada", 5), total("sancionado", 1)],
    );
    const concluidas = colunas.find((c) => c.titulo === "Concluídas")!;
    expect(concluidas.total).toBe(9);
  });

  it("estado fora do mapa fixo soma no total de 'Outros'", () => {
    const colunas = derivarBoard(
      [item({ proposicaoId: "1", estado: "xpto-desconhecido" })],
      [total("xpto-desconhecido", 4)],
    );
    const outros = colunas.find((c) => c.titulo === "Outros")!;
    expect(outros.total).toBe(4);
  });

  it("totaisPorEstado explicitamente vazio -> total 0 (nunca cai pra itens.length quando o servidor respondeu)", () => {
    const colunas = derivarBoard([item({ proposicaoId: "1", estado: "protocolada" })], []);
    const protocolo = colunas.find((c) => c.titulo === "Protocolo")!;
    expect(protocolo.total).toBe(0);
  });
});

describe("itemCorrespondeBusca", () => {
  const alvo = derivarBoard([
    item({ proposicaoId: "1", estado: "protocolada", tipo: "projeto_lei", sequencial: 42, ano: 2026, ementa: "Institui o Programa Municipal de Hortas Comunitárias" }),
  ])[0].itens[0];

  it("substring da ementa, case-insensitive -> true", () => {
    expect(itemCorrespondeBusca(alvo, "hortas comunitárias")).toBe(true);
  });

  it("substring do número formatado -> true", () => {
    expect(itemCorrespondeBusca(alvo, "PL 42")).toBe(true);
  });

  it("sem acento (aproximação barata) -> true", () => {
    expect(itemCorrespondeBusca(alvo, "comunitarias")).toBe(true);
  });

  it("substring ausente -> false", () => {
    expect(itemCorrespondeBusca(alvo, "arborização")).toBe(false);
  });

  it("busca vazia -> sempre true (não filtra nada)", () => {
    expect(itemCorrespondeBusca(alvo, "")).toBe(true);
  });
});

describe("filtrarColunasPorBusca", () => {
  it("aplica o filtro item a item dentro de cada coluna, preservando as colunas (mesmo vazias)", () => {
    const colunas = derivarBoard([
      item({ proposicaoId: "1", estado: "protocolada", ementa: "Hortas comunitárias" }),
      item({ proposicaoId: "2", estado: "protocolada", ementa: "Coleta seletiva" }),
    ]);
    const filtradas = filtrarColunasPorBusca(colunas, "hortas");
    const protocolo = filtradas.find((c) => c.titulo === "Protocolo")!;
    expect(protocolo.itens.map((i) => i.proposicaoId)).toEqual(["1"]);
    // as outras colunas continuam presentes, só vazias
    expect(filtradas.find((c) => c.titulo === "Comissões")!.itens).toEqual([]);
  });

  it("busca vazia -> retorna as colunas intactas", () => {
    const colunas = derivarBoard([item({ proposicaoId: "1", estado: "protocolada" })]);
    expect(filtrarColunasPorBusca(colunas, "")).toEqual(colunas);
  });
});

describe("filtrarColunasPorEspecie", () => {
  it("tipo vazio -> retorna as colunas intactas", () => {
    const colunas = derivarBoard([item({ proposicaoId: "1", estado: "protocolada", tipo: "projeto_lei" })]);
    expect(filtrarColunasPorEspecie(colunas, "", formatarEspecieProposicao)).toEqual(colunas);
  });

  it("filtra item a item por espécie formatada, preservando as colunas mesmo vazias", () => {
    const colunas = derivarBoard([
      item({ proposicaoId: "1", estado: "protocolada", tipo: "projeto_lei" }),
      item({ proposicaoId: "2", estado: "protocolada", tipo: "mocao" }),
    ]);
    const filtradas = filtrarColunasPorEspecie(colunas, "projeto_lei", formatarEspecieProposicao);
    const protocolo = filtradas.find((c) => c.titulo === "Protocolo")!;
    expect(protocolo.itens.map((i) => i.proposicaoId)).toEqual(["1"]);
    expect(filtradas.find((c) => c.titulo === "Comissões")!.itens).toEqual([]);
  });

  it("espécie sem nenhuma matéria correspondente -> coluna fica vazia (não descarta a coluna)", () => {
    const colunas = derivarBoard([item({ proposicaoId: "1", estado: "protocolada", tipo: "projeto_lei" })]);
    const filtradas = filtrarColunasPorEspecie(colunas, "mocao", formatarEspecieProposicao);
    expect(filtradas.find((c) => c.titulo === "Protocolo")!.itens).toEqual([]);
  });
});

describe("paginarColuna", () => {
  function colunaComNItens(n: number) {
    return derivarBoard(
      Array.from({ length: n }, (_, i) => item({ proposicaoId: `p${i}`, estado: "protocolada" })),
    ).find((c) => c.titulo === "Protocolo")!;
  }

  it("abaixo do teto -> retorna a coluna intacta", () => {
    const coluna = colunaComNItens(TETO_ITENS_VISIVEIS_POR_COLUNA - 1);
    expect(paginarColuna(coluna, false)).toEqual(coluna);
  });

  it("acima do teto e não expandida -> trunca no teto", () => {
    const coluna = colunaComNItens(TETO_ITENS_VISIVEIS_POR_COLUNA + 20);
    const paginada = paginarColuna(coluna, false);
    expect(paginada.itens).toHaveLength(TETO_ITENS_VISIVEIS_POR_COLUNA);
  });

  it("acima do teto e expandida -> retorna todos os itens", () => {
    const coluna = colunaComNItens(TETO_ITENS_VISIVEIS_POR_COLUNA + 20);
    const paginada = paginarColuna(coluna, true);
    expect(paginada.itens).toHaveLength(TETO_ITENS_VISIVEIS_POR_COLUNA + 20);
  });
});
