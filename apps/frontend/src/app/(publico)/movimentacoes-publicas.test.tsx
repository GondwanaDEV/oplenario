import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { etapaAtualDasMovimentacoes, MovimentacoesPublicas } from "./movimentacoes-publicas";
import { SecaoFicha } from "./secao-ficha";

// "Por onde a matéria passou" (ficha pública): a linha do tempo com data + etapa em palavras, da mais recente para a mais
// antiga. Nunca a chave de cadastro, nunca quem despachou; nunca finge completude (histórico que começa no meio, lista
// cortada no teto); falha de rede não derruba a ficha.

const ENTE = "10000000-0000-0000-0000-000000000001";
const URL_MOV = `/api/portal/casa/${ENTE}/materias/p1/movimentacoes`;

const corpo = (extra: Record<string, unknown> = {}) => ({
  movimentacoes: [
    { "ocorrido-em": "2026-09-10T15:00:00Z", etapa: "Pronta para o Plenário", abertura: false },
    { "ocorrido-em": "2026-09-01T12:00:00Z", etapa: "Em análise nas comissões", abertura: false },
    { "ocorrido-em": "2026-08-20T13:00:00Z", etapa: "Recebida pela Mesa Diretora", abertura: true },
  ],
  "movimentacoes-total": 3,
  "historico-completo": true,
  "historico-desde": "2026-08-20T13:00:00Z",
  ...extra,
});

function mockar(resposta: { status: number; corpo?: unknown } | "rede" | "pendente") {
  const f = vi.fn(async (url: string) => {
    if (String(url) !== URL_MOV) return { ok: false, status: 404, json: async () => ({}) } as Response;
    if (resposta === "rede") throw new TypeError("network");
    if (resposta === "pendente") return new Promise<Response>(() => {});
    return { ok: resposta.status < 300, status: resposta.status, json: async () => resposta.corpo } as Response;
  });
  global.fetch = f as unknown as typeof fetch;
  return f;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("MovimentacoesPublicas", () => {
  it("lista da mais recente para a mais antiga, com a data e o NOME da etapa no rito", async () => {
    const f = mockar({ status: 200, corpo: corpo() });
    render(<MovimentacoesPublicas ente={ENTE} proposicaoId="p1" />);
    const secao = await screen.findByRole("region", { name: "Por onde a matéria passou" });
    const itens = within(secao).getAllByRole("listitem");
    expect(itens.map((li) => li.querySelector(".mov-etapa")?.textContent)).toEqual([
      "Pronta para o Plenário",
      "Em análise nas comissões",
      "Recebida pela Mesa Diretora",
    ]);
    expect(itens.map((li) => li.querySelector("time")?.textContent)).toEqual(["10/09/2026", "01/09/2026", "20/08/2026"]);
    expect(itens[0].querySelector("time")?.getAttribute("datetime")).toBe("2026-09-10T15:00:00Z");
    expect(f).toHaveBeenCalledWith(URL_MOV, { cache: "no-store" });
  });

  it("marca a etapa atual só na mais recente, e a lista é ordenada e rotulada para leitor de tela", async () => {
    mockar({ status: 200, corpo: corpo() });
    render(<MovimentacoesPublicas ente={ENTE} proposicaoId="p1" />);
    const lista = await screen.findByRole("list", { name: /da mais recente para a mais antiga/i });
    expect(lista.tagName).toBe("OL");
    expect(within(lista).getAllByText("Etapa atual")).toHaveLength(1);
    expect(within(lista).getAllByRole("listitem")[0].getAttribute("aria-current")).toBe("step");
  });

  it("a votação não é 'Etapa atual': a marca fica na etapa mais recente que não é votação", async () => {
    mockar({
      status: 200,
      corpo: corpo({
        movimentacoes: [
          { "ocorrido-em": "2026-09-23T15:36:29Z", etapa: "Aprovada em 1º turno", abertura: false, votacao: true },
          { "ocorrido-em": "2026-09-23T12:00:00Z", etapa: "Em Pauta", abertura: false, votacao: false },
          { "ocorrido-em": "2026-09-01T12:00:00Z", etapa: "Protocolada", abertura: true, votacao: false },
        ],
        "movimentacoes-total": 3,
      }),
    });
    render(<MovimentacoesPublicas ente={ENTE} proposicaoId="p1" />);
    const lista = await screen.findByRole("list", { name: /da mais recente para a mais antiga/i });
    const itens = within(lista).getAllByRole("listitem");
    expect(within(lista).getAllByText("Etapa atual")).toHaveLength(1);
    expect(within(itens[1]).getByText("Etapa atual")).toBeTruthy();
    expect(itens[1].getAttribute("aria-current")).toBe("step");
    expect(itens[0].getAttribute("aria-current")).toBeNull();
    expect(within(itens[0]).queryByText("Etapa atual")).toBeNull();
    expect(within(itens[0]).getByText("Aprovada em 1º turno")).toBeTruthy();
  });

  it("histórico completo e sem corte: nenhum aviso de recorte", async () => {
    mockar({ status: 200, corpo: corpo() });
    render(<MovimentacoesPublicas ente={ENTE} proposicaoId="p1" />);
    await screen.findByRole("list");
    expect(screen.queryByText(/Histórico disponível a partir de/)).toBeNull();
    expect(screen.queryByText(/Mostrando as/)).toBeNull();
  });

  it("histórico que começa no meio: diz desde quando existe, nunca finge completude", async () => {
    mockar({
      status: 200,
      corpo: corpo({
        movimentacoes: [{ "ocorrido-em": "2026-09-01T12:00:00Z", etapa: "Em análise nas comissões", abertura: false }],
        "movimentacoes-total": 1,
        "historico-completo": false,
        "historico-desde": "2026-09-01T12:00:00Z",
      }),
    });
    render(<MovimentacoesPublicas ente={ENTE} proposicaoId="p1" />);
    expect(await screen.findByText(/Histórico disponível a partir de 01\/09\/2026\./)).toBeTruthy();
    expect(screen.getByText(/não está\s+registrado nesta página/)).toBeTruthy();
  });

  it("lista cortada no teto do servidor: diz quantas mostra e o total real", async () => {
    mockar({ status: 200, corpo: corpo({ "movimentacoes-total": 140 }) });
    render(<MovimentacoesPublicas ente={ENTE} proposicaoId="p1" />);
    expect(await screen.findByText("Mostrando as 3 movimentações mais recentes, de um total de 140.")).toBeTruthy();
  });

  it("etapa sem rótulo no rito: 'Mudança de etapa', nunca a chave nem vazio", async () => {
    mockar({
      status: 200,
      corpo: corpo({ movimentacoes: [{ "ocorrido-em": "2026-09-10T15:00:00Z", etapa: null, abertura: false }], "movimentacoes-total": 1 }),
    });
    render(<MovimentacoesPublicas ente={ENTE} proposicaoId="p1" />);
    expect(await screen.findByText("Mudança de etapa")).toBeTruthy();
  });

  it("sem movimentação nenhuma: diz que o histórico ainda não está disponível", async () => {
    mockar({
      status: 200,
      corpo: corpo({ movimentacoes: [], "movimentacoes-total": 0, "historico-completo": false, "historico-desde": null }),
    });
    render(<MovimentacoesPublicas ente={ENTE} proposicaoId="p1" />);
    expect(await screen.findByText("O histórico desta matéria ainda não está disponível aqui.")).toBeTruthy();
    expect(screen.queryByRole("list")).toBeNull();
  });

  it("carregando: não mostra nada (sem flash de 'sem histórico')", () => {
    mockar("pendente");
    const { container } = render(<MovimentacoesPublicas ente={ENTE} proposicaoId="p1" />);
    expect(container.textContent).toBe("");
  });

  it.each([
    ["404", { status: 404 }],
    ["500", { status: 500 }],
    ["rede caiu", "rede" as const],
    ["resposta estranha", { status: 200, corpo: { movimentacoes: "nao-e-lista" } }],
  ])("falha (%s): a seção diz que não carregou e nada é inventado", async (_nome, resposta) => {
    mockar(resposta as Parameters<typeof mockar>[0]);
    render(<MovimentacoesPublicas ente={ENTE} proposicaoId="p1" />);
    expect(await screen.findByText(/Não foi possível carregar o histórico da matéria agora/)).toBeTruthy();
    expect(screen.queryByRole("list")).toBeNull();
  });

  it("trocar de matéria esconde a anterior até a nova chegar", async () => {
    mockar({ status: 200, corpo: corpo() });
    const { rerender, container } = render(<MovimentacoesPublicas ente={ENTE} proposicaoId="p1" />);
    await screen.findByRole("list");
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch;
    rerender(<MovimentacoesPublicas ente={ENTE} proposicaoId="p2" />);
    expect(container.textContent).toBe("");
  });
});

describe("SecaoFicha com a linha do tempo", () => {
  const ficha = {
    "proposicao-id": "p1", tipo: "projeto_lei", ano: 2026, sequencial: 42,
    "urn-lex": "urn:lex:br;ce;fortaleza:camara.municipal:projeto.lei:2026;042",
    ementa: "Cria o Programa Municipal de Hortas Comunitárias.", "autor-texto": "Ver.ª Helena Matos", estado: "em_comissoes",
  };

  it("mostra 'Por onde a matéria passou' na ficha; e a frase que prometia 'toda a tramitação' agora aponta a linha do tempo", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const u = String(url);
      if (u === URL_MOV) return { ok: true, status: 200, json: async () => corpo() } as Response;
      if (u.endsWith("/comentarios")) return { ok: true, status: 200, json: async () => [] } as Response;
      if (u.endsWith("/p1")) return { ok: true, status: 200, json: async () => ficha } as Response;
      return { ok: false, status: 404, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
    render(<SecaoFicha ente={ENTE} proposicaoId="p1" />);
    expect(await screen.findByRole("region", { name: "Por onde a matéria passou" })).toBeTruthy();
    expect(screen.getByText(/por onde a matéria já passou está na linha do tempo acima/i)).toBeTruthy();
    expect(screen.queryByText(/toda a tramitação/i)).toBeNull();
  });

  it("a linha do tempo falha e a ficha segue de pé (degradação por seção)", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const u = String(url);
      if (u === URL_MOV) throw new TypeError("network");
      if (u.endsWith("/comentarios")) return { ok: true, status: 200, json: async () => [] } as Response;
      if (u.endsWith("/p1")) return { ok: true, status: 200, json: async () => ficha } as Response;
      return { ok: false, status: 404, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
    render(<SecaoFicha ente={ENTE} proposicaoId="p1" />);
    await waitFor(() => expect(screen.getByRole("heading", { level: 1 }).textContent).toBe(ficha.ementa));
    expect(await screen.findByText(/Não foi possível carregar o histórico da matéria agora/)).toBeTruthy();
  });
});

describe("etapaAtualDasMovimentacoes (o chip da ficha)", () => {
  const linha = (etapa: string | null, votacao: boolean) => ({ ocorridoEm: "2026-09-23T15:00:00Z", etapa, abertura: false, votacao });
  const dado = (movimentacoes: ReturnType<typeof linha>[]) =>
    ({ movimentacoes, movimentacoesTotal: movimentacoes.length, historicoCompleto: true, historicoDesde: null });
  it("pula a votação e devolve a etapa marcada como atual", () => {
    expect(etapaAtualDasMovimentacoes(dado([linha("Aprovada em 1º turno", true), linha("Em Pauta", false)]))).toBe("Em Pauta");
  });
  it("só votação, histórico com erro ou ainda carregando: sem nome (o chip cai no rótulo fixo)", () => {
    expect(etapaAtualDasMovimentacoes(dado([linha("Aprovada em 1º turno", true)]))).toBeNull();
    expect(etapaAtualDasMovimentacoes("erro")).toBeNull();
    expect(etapaAtualDasMovimentacoes(null)).toBeNull();
  });
});
