import { describe, expect, it, vi, afterEach } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import PaginaAcompanhamentos from "./page";

vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));

const doisAcompanhamentos = {
  acompanhamentos: [
    {
      "proposicao-id": "p1", tipo: "projeto_lei", ano: 2026, sequencial: 12, "urn-lex": "urn:lex:1",
      ementa: "Altera a Lei Orgânica quanto à composição da Mesa Diretora.", estado: "em_pauta",
      "seguido-em": "2026-09-01T00:00:00Z", indisponivel: false,
    },
    {
      "proposicao-id": "p2", tipo: null, ano: null, sequencial: null, "urn-lex": null, ementa: null,
      estado: null, "seguido-em": "2026-09-02T00:00:00Z", indisponivel: true,
    },
  ],
  "acompanhamentos-total": 2,
};

describe("PaginaAcompanhamentos", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("mostra a lista real (achado ao vivo: a rota já respondia 200 com dado real, nenhuma tela chamava)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => doisAcompanhamentos }) as Response) as unknown as typeof fetch;
    render(<PaginaAcompanhamentos />);
    await waitFor(() => expect(screen.getByText(/Altera a Lei Orgânica/)).toBeDefined());
    expect(screen.getByText("PL 12/2026")).toBeDefined();
  });

  it("nunca esconde o teto — sempre diz 'mostrando N de M' (regra da casa)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => doisAcompanhamentos }) as Response) as unknown as typeof fetch;
    render(<PaginaAcompanhamentos />);
    await waitFor(() => expect(screen.getByText(/Mostrando 2 de 2/)).toBeDefined());
  });

  it("matéria indisponível (LEFT JOIN sem par) aparece na lista com rótulo honesto, nunca some nem quebra", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => doisAcompanhamentos }) as Response) as unknown as typeof fetch;
    render(<PaginaAcompanhamentos />);
    await waitFor(() => expect(screen.getByText("Matéria indisponível no momento")).toBeDefined());
    expect(screen.getByText(/ainda não chegaram ao portal/)).toBeDefined();
  });

  it("estado vazio é honesto — nunca finge que a lista tem itens", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ acompanhamentos: [], "acompanhamentos-total": 0 }) }) as Response,
    ) as unknown as typeof fetch;
    render(<PaginaAcompanhamentos />);
    await waitFor(() => expect(screen.getByText(/ainda não acompanha nenhuma matéria/)).toBeDefined());
  });

  it("erro de rede é dito, nunca mascarado como lista vazia", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    render(<PaginaAcompanhamentos />);
    await waitFor(() => expect(screen.getByRole("alert")).toBeDefined());
    expect(screen.queryByText(/ainda não acompanha/)).toBeNull();
  });
});
