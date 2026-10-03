import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { InscricoesAudiencia } from "./inscricoes-audiencia";
import PaginaMeusProtocolos from "./page";

vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));

const inscricao = (p: Record<string, unknown>) => ({
  id: "i1", protocolo: "AUD-2026-000003", "sessao-id": "s1", tema: "Mobilidade urbana", "comissao-nome": "Comissão de Meio Ambiente",
  "agendada-para": new Date(2026, 5, 11, 9, 0).toISOString(), ordem: 3, estado: "inscrita", "recibo-em": "2026-06-01T12:00:00Z", ...p,
});

type Chamada = { url: string; metodo: string };
let chamadas: Chamada[] = [];

function servidor(rotas: Record<string, () => { status: number; corpo: unknown }>) {
  chamadas = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const metodo = init?.method ?? "GET";
    chamadas.push({ url, metodo });
    const r = rotas[`${metodo} ${url}`]?.() ?? { status: 404, corpo: {} };
    return { ok: r.status < 300, status: r.status, json: async () => r.corpo } as Response;
  }) as unknown as typeof fetch;
}

const GET_MINHAS = "GET /api/portal/minhas-inscricoes";
const GET_PROTOCOLOS = "GET /api/portal/meus-protocolos";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Inscrições em audiências", () => {
  it("cada inscrição com protocolo, estado em palavras, a audiência e a posição na fila", async () => {
    servidor({ [GET_MINHAS]: () => ({ status: 200, corpo: { inscricoes: [inscricao({}), inscricao({ id: "i2", protocolo: "AUD-2026-000001", estado: "falou" })] } }) });
    render(<InscricoesAudiencia token="tok" />);
    const itens = await screen.findAllByRole("listitem");
    expect(itens[0].textContent).toContain("AUD-2026-000003");
    expect(itens[0].textContent).toContain("Na fila para falar");
    expect(itens[0].textContent).toContain("Comissão de Meio Ambiente");
    expect(itens[0].textContent).toContain("Quinta, 11/06/2026 · 9h");
    expect(itens[0].textContent).toContain("3ª na fila");
    expect(within(itens[1]).getByText("Falou")).toBeTruthy();
    // só quem ainda espera a vez pode desistir
    expect(within(itens[0]).getByRole("button", { name: "Desistir da fala" })).toBeTruthy();
    expect(within(itens[1]).queryByRole("button", { name: /Desistir/ })).toBeNull();
  });

  it("desistir pede confirmação, faz o POST e recarrega", async () => {
    let estado = "inscrita";
    servidor({
      [GET_MINHAS]: () => ({ status: 200, corpo: { inscricoes: [inscricao({ estado })] } }),
      "POST /api/portal/minhas-inscricoes/i1/desistencia": () => {
        estado = "desistiu";
        return { status: 200, corpo: {} };
      },
    });
    render(<InscricoesAudiencia token="tok" />);
    fireEvent.click(await screen.findByRole("button", { name: "Desistir da fala" }));
    expect(chamadas.filter((c) => c.metodo === "POST")).toEqual([]);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Confirmar desistência" }));
    });
    await screen.findByText("Desistiu");
    expect(chamadas.filter((c) => c.metodo === "POST")).toEqual([{ url: "/api/portal/minhas-inscricoes/i1/desistencia", metodo: "POST" }]);
    expect(screen.queryByRole("button", { name: /Desistir/ })).toBeNull();
  });

  it("sem inscrições: diz, sem inventar", async () => {
    servidor({ [GET_MINHAS]: () => ({ status: 200, corpo: { inscricoes: [] } }) });
    render(<InscricoesAudiencia token="tok" />);
    expect(await screen.findByText("Nenhuma inscrição para falar em audiência pública.")).toBeTruthy();
  });
});

describe("/meus-protocolos — cada fonte falha sozinha", () => {
  const protocolos = { "pedidos-esic": [], "solicitacoes-lgpd": [], manifestacoes: [] };

  it("as inscrições falham; os protocolos continuam aparecendo", async () => {
    servidor({ [GET_PROTOCOLOS]: () => ({ status: 200, corpo: protocolos }), [GET_MINHAS]: () => ({ status: 500, corpo: {} }) });
    render(<PaginaMeusProtocolos />);
    expect(await screen.findByText("Nenhum pedido de informação ainda.")).toBeTruthy();
    await waitFor(() => expect(screen.getByRole("alert").textContent).toMatch(/inscrições em audiências/));
  });

  it("os protocolos falham; as inscrições continuam aparecendo", async () => {
    servidor({ [GET_PROTOCOLOS]: () => ({ status: 500, corpo: {} }), [GET_MINHAS]: () => ({ status: 200, corpo: { inscricoes: [inscricao({})] } }) });
    render(<PaginaMeusProtocolos />);
    expect(await screen.findByText("AUD-2026-000003")).toBeTruthy();
    await waitFor(() => expect(screen.getByRole("alert").textContent).toMatch(/os seus protocolos/));
  });
});
