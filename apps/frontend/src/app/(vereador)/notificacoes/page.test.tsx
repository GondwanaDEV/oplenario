import { describe, expect, it, vi, afterEach } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import PaginaNotificacoes from "./page";

vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));

const umaNaoLida = {
  notificacoes: [
    {
      id: "n1",
      categoria: "norma_publicada",
      assunto: "A sua proposicao virou lei — Lei 3/2026",
      corpo: "Ementa: Dispoe sobre as hortas comunitarias.",
      "objeto-tipo": "proposicao",
      "objeto-id": "p1",
      "criado-em": new Date().toISOString(),
      "lida-em": null,
    },
  ],
  "nao-lidas": 1,
};

describe("PaginaNotificacoes", () => {
  afterEach(() => {
    // sem isto, tests 1/2 (item não-lido montado) deixavam a árvore anterior no DOM (este projeto NÃO
    // liga o auto-cleanup do RTL — todo outro *.test.tsx do shell chama `cleanup()` explicitamente, ex.
    // vereador/page.test.tsx) e o teste 5 batia em "Marcar como lida" duplicado/travava — achado real via
    // ciclo red→green, não teatro de timeout.
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra a notificação, o grupo e o badge de não lidas", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => umaNaoLida }) as Response) as unknown as typeof fetch;
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText(/virou lei/)).toBeDefined());
    expect(screen.getByRole("heading", { level: 2, name: "Hoje" })).toBeDefined();
    expect(screen.getByLabelText("1 não lida")).toBeDefined();
    // Sem destino acessível ao vereador, não se renderiza âncora nenhuma (ver notificacoes-vista.test.ts).
    expect(screen.queryByRole("link", { name: /Abrir a ficha/ })).toBeNull();
  });

  it("não-lida é sinalizada por mais que cor (ponto com rótulo acessível)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => umaNaoLida }) as Response) as unknown as typeof fetch;
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByRole("img", { name: "Não lida" })).toBeDefined());
  });

  it("estado vazio é honesto", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ notificacoes: [], "nao-lidas": 0 }) }) as Response) as unknown as typeof fetch;
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText(/Nenhuma notificação/)).toBeDefined());
  });

  it("erro de carga não quebra a tela", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText(/Não foi possível carregar/)).toBeDefined());
  });

  it("marcar como lida chama o POST e revalida", async () => {
    const chamadas: string[] = [];
    global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      chamadas.push(`${init?.method ?? "GET"} ${url}`);
      if (init?.method === "POST") {
        return { ok: true, json: async () => ({ id: "n1", "lida-em": "2026-07-19T13:00:00Z" }) } as Response;
      }
      return { ok: true, json: async () => umaNaoLida } as Response;
    }) as unknown as typeof fetch;
    render(<PaginaNotificacoes />);
    await waitFor(() => expect(screen.getByText(/virou lei/)).toBeDefined());
    screen.getByRole("button", { name: /Marcar como lida/ }).click();
    await waitFor(() =>
      expect(chamadas).toContain("POST /api/meu/notificacoes/n1/lida")
    );
    await waitFor(() => expect(chamadas.filter((c) => c.startsWith("GET")).length).toBeGreaterThan(1));
  });
});
