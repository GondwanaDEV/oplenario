import { describe, expect, it, vi, afterEach } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import PaginaNotificacoes from "./page";

// A aba "Avisos" do vereador é, desde a ADR-0020, a CAIXA (src/app/caixa-da-casa.tsx): comunicados da Casa + avisos do
// sistema. Este arquivo guarda o que a antiga inbox já provava sobre os AVISOS (ficha, "marcar como lido", região viva,
// cortes da lista) e prova o que muda no app do vereador: o comunicado abre DENTRO do app (/notificacoes/:id). O
// comportamento da caixa em si (ordem, filtros, faixa de ciência, falha parcial) está em src/app/caixa-da-casa.test.tsx.

vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));

const agora = () => new Date().toISOString();

function aviso(id: string, lidaEm: string | null = null, assunto = `Aviso ${id}`) {
  return {
    id,
    categoria: "norma_publicada",
    assunto,
    corpo: "Ementa: Dispõe sobre as hortas comunitárias.",
    "objeto-tipo": "proposicao",
    "objeto-id": `p-${id}`,
    "criado-em": agora(),
    "lida-em": lidaEm,
  };
}

const caixaVazia = { itens: [], "nao-lidos": 0, "pendentes-ciencia": 0, "proxima-ciencia-ate": null };

type Rota = { status?: number; corpo: unknown };

function servindo(rotas: { avisos?: unknown; caixa?: unknown; post?: Rota }) {
  const chamadas: string[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = init?.method ?? "GET";
    chamadas.push(`${metodo} ${url}`);
    if (metodo === "POST") {
      const r = rotas.post ?? { corpo: { id: "n1", "lida-em": "2026-07-19T13:00:00Z" } };
      const status = r.status ?? 200;
      return { ok: status < 300, status, json: async () => r.corpo } as Response;
    }
    if (url === "/api/meu/notificacoes") return { ok: true, status: 200, json: async () => rotas.avisos ?? { notificacoes: [], "nao-lidas": 0, "notificacoes-total": 0 } } as Response;
    if (url === "/api/meu/comunicados") return { ok: true, status: 200, json: async () => rotas.caixa ?? caixaVazia } as Response;
    return { ok: false, status: 404, json: async () => ({}) } as Response;
  }) as unknown as typeof fetch;
  return chamadas;
}

afterEach(() => {
  // o projeto NÃO liga o auto-cleanup do RTL (a suíte não usa os globals do Vitest): sem isto a árvore anterior
  // fica montada e, sob carga, o React ainda tem trabalho agendado quando o jsdom sai ("window is not defined").
  cleanup();
  vi.restoreAllMocks();
});

describe("Avisos do vereador = a caixa", () => {
  it("o comunicado abre DENTRO do app do vereador (/notificacoes/:id), com o remetente e o protocolo", async () => {
    servindo({
      caixa: {
        itens: [{ id: "c1", protocolo: "COM-2026-000007", assunto: "Sessão extraordinária na sexta", "remetente-nome": "Rita Campos",
          "enviado-em": agora(), "exige-ciencia": false, "ciencia-ate": null, vencido: false, "recebido-em": agora(), "lido-em": null, "ciente-em": null }],
        "nao-lidos": 1, "pendentes-ciencia": 0, "proxima-ciencia-ate": null,
      },
    });
    render(<PaginaNotificacoes />);
    const link = await screen.findByRole("link", { name: "Sessão extraordinária na sexta" });
    expect(link.getAttribute("href")).toBe("/notificacoes/c1?token=tok");
    expect(screen.getByText("De Rita Campos · COM-2026-000007")).toBeTruthy();
    expect(screen.getByRole("heading", { level: 1, name: "Caixa, 1 por ler" })).toBeTruthy();
  });

  it("o aviso do sistema mantém o atalho para a ficha da matéria", async () => {
    servindo({ avisos: { notificacoes: [aviso("n1", null, "A sua proposição virou lei — Lei 3/2026")], "nao-lidas": 1, "notificacoes-total": 1 } });
    render(<PaginaNotificacoes />);
    await screen.findByText(/virou lei/);
    expect(screen.getByRole("heading", { level: 2, name: "Hoje" })).toBeTruthy();
    expect(screen.getByRole("link", { name: /Abrir a ficha/ }).getAttribute("href")).toBe("/ficha-materia/p-n1?token=tok");
  });

  it("não lido é sinalizado por mais que cor; o lido diz 'Lido' e não tem botão", async () => {
    servindo({
      avisos: { notificacoes: [aviso("a", null, "A que ainda não li"), aviso("b", "2026-07-19T10:00:00Z", "A que já li")], "nao-lidas": 1, "notificacoes-total": 2 },
    });
    render(<PaginaNotificacoes />);
    const lido = (await screen.findByText("A que já li")).closest("article")!;
    const naoLido = screen.getByText("A que ainda não li").closest("article")!;
    expect(within(lido).getByText("Lido")).toBeTruthy();
    expect(within(lido).queryByRole("img", { name: "Não lido" })).toBeNull();
    expect(within(lido).queryByRole("button", { name: /Marcar como lido/ })).toBeNull();
    expect(lido.className).not.toContain("cx-nao-lido");
    expect(within(naoLido).getByRole("img", { name: "Não lido" })).toBeTruthy();
    expect(within(naoLido).getByRole("button", { name: /Marcar como lido/ })).toBeTruthy();
    expect(naoLido.className).toContain("cx-nao-lido");
  });

  it("marcar o aviso como lido chama o POST de paineis e revalida os avisos", async () => {
    const chamadas = servindo({ avisos: { notificacoes: [aviso("n1")], "nao-lidas": 1, "notificacoes-total": 1 } });
    render(<PaginaNotificacoes />);
    fireEvent.click(await screen.findByRole("button", { name: /Marcar como lido/ }));
    await waitFor(() => expect(chamadas).toContain("POST /api/meu/notificacoes/n1/lida"));
    await waitFor(() => expect(chamadas.filter((c) => c === "GET /api/meu/notificacoes").length).toBeGreaterThan(1));
  });

  it("falha ao marcar como lido é anunciada a leitor de tela (região viva, não só pixel)", async () => {
    servindo({ avisos: { notificacoes: [aviso("n1")], "nao-lidas": 1, "notificacoes-total": 1 }, post: { status: 500, corpo: {} } });
    render(<PaginaNotificacoes />);
    fireEvent.click(await screen.findByRole("button", { name: /Marcar como lido/ }));
    const regiao = await waitFor(() => screen.getByRole("status"));
    expect(regiao.className).toContain("cx-erro");
    expect(regiao.textContent).toBeTruthy();
  });

  it("caixa vazia convida, sem fingir lista", async () => {
    servindo({});
    render(<PaginaNotificacoes />);
    expect(await screen.findByText(/Sua caixa está vazia/)).toBeTruthy();
  });
});

describe("Avisos do vereador · a lista tem teto, o número não", () => {
  it("diz que a lista de avisos foi cortada quando há não lido fora dela", async () => {
    servindo({ avisos: { notificacoes: [aviso("b", "2026-07-19T10:00:00Z", "A que já li")], "nao-lidas": 10, "notificacoes-total": 1 } });
    render(<PaginaNotificacoes />);
    await screen.findByText("A que já li");
    expect(screen.getByText(/A caixa mostra só os itens mais recentes/).textContent).toContain("10 avisos não lidos mais antigos ficaram fora dela");
  });

  it("o total cortado nunca fica calado atrás do aviso de não lidos", async () => {
    servindo({
      avisos: { notificacoes: [aviso("a"), aviso("b", "2026-07-19T10:00:00Z"), aviso("c", "2026-07-19T10:00:00Z")], "nao-lidas": 3, "notificacoes-total": 500 },
    });
    render(<PaginaNotificacoes />);
    await screen.findByText("Aviso a");
    expect(screen.getByText(/497 avisos do sistema mais antigos ficaram fora dela/)).toBeTruthy();
  });

  it("número e lista concordando: nenhuma linha de corte", async () => {
    servindo({ avisos: { notificacoes: [aviso("a")], "nao-lidas": 1, "notificacoes-total": 1 } });
    render(<PaginaNotificacoes />);
    await screen.findByText("Aviso a");
    expect(screen.queryByText(/A caixa mostra só os itens mais recentes/)).toBeNull();
  });
});
