import { describe, expect, it } from "vitest";
import { derivarBoard, itemCorrespondeBusca, filtrarColunasPorBusca } from "./tramitacao-board-vista";
import type { ItemBoardOut } from "./contrato-mesa.gen";

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

  it("em_pauta e aguardando_pauta -> ambos caem em Pronta p/ pauta (azulejo amarelo)", () => {
    const colunas = derivarBoard([
      item({ proposicaoId: "3", estado: "em_pauta" }),
      item({ proposicaoId: "4", estado: "aguardando_pauta" }),
    ]);
    const pronta = colunas.find((c) => c.titulo === "Pronta p/ pauta")!;
    expect(pronta.azulejo).toBe("amarelo");
    expect(pronta.itens.map((i) => i.proposicaoId)).toEqual(["3", "4"]);
  });

  it("primeiro_turno, segundo_turno e em_sancao -> todos caem em Em Plenário (azulejo telha)", () => {
    const colunas = derivarBoard([
      item({ proposicaoId: "5", estado: "primeiro_turno" }),
      item({ proposicaoId: "6", estado: "segundo_turno" }),
      item({ proposicaoId: "7", estado: "em_sancao" }),
    ]);
    const plenario = colunas.find((c) => c.titulo === "Em Plenário")!;
    expect(plenario.azulejo).toBe("telha");
    expect(plenario.itens.map((i) => i.proposicaoId)).toEqual(["5", "6", "7"]);
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
