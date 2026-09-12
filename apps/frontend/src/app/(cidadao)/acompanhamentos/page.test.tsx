import { describe, expect, it, vi, afterEach } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import PaginaAcompanhamentos from "./page";

vi.mock("@/lib/auth", () => ({ useAuth: () => ({ token: "tok" }) }));

// horário MEIO-DIA UTC de propósito, não "T00:00:00Z" — a suíte fixa TZ=America/Fortaleza
// (vitest.config.ts) e um timestamp na virada do dia mediria o fuso do RUNNER, não o código (armadilha
// já registrada neste projeto). 15h UTC cai no MESMO dia calendário em qualquer fuso razoável (UTC-12 a
// UTC+12 ainda ficam entre 03h e 03h do dia seguinte, nunca cruzando pra trás).
const doisAcompanhamentos = {
  acompanhamentos: [
    {
      "proposicao-id": "p1", tipo: "projeto_lei", ano: 2026, sequencial: 12, "urn-lex": "urn:lex:1",
      ementa: "Altera a Lei Orgânica quanto à composição da Mesa Diretora.", estado: "em_pauta",
      "seguido-em": "2026-09-01T15:00:00.839325Z", indisponivel: false,
    },
    {
      "proposicao-id": "p2", tipo: null, ano: null, sequencial: null, "urn-lex": null, ementa: null,
      estado: null, "seguido-em": "2026-09-02T15:00:00Z", indisponivel: true,
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

  it("achado ao vivo (Daouda, 12/09/2026): 'seguindo desde' sai formatado dd/mm/aaaa, NUNCA o timestamp ISO cru", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => doisAcompanhamentos }) as Response) as unknown as typeof fetch;
    render(<PaginaAcompanhamentos />);
    await waitFor(() => expect(screen.getByText(/seguindo desde 01\/09\/2026/)).toBeDefined());
    expect(screen.getByText(/seguindo desde 02\/09\/2026/)).toBeDefined();
    // a tela inteira nunca mostra o ISO cru (nem a data, nem o "T", nem os microssegundos/"Z" do wire)
    expect(screen.queryByText(/2026-09-01T/)).toBeNull();
    expect(screen.queryByText(/839325/)).toBeNull();
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
