import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import PaginaHomeVereador from "./page";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

function renderComProviders(tokenQuery: string | null) {
  return render(
    <AuthProvider tokenQuery={tokenQuery}>
      <TemaProvider>
        <PaginaHomeVereador />
      </TemaProvider>
    </AuthProvider>
  );
}

const painelFake = {
  proposicoes: [
    {
      id: "p1", tipo: "projeto_lei", ano: 2026, sequencial: 42, "urn-lex": "urn:lex:fixture",
      ementa: "Hortas Comunitárias em terrenos públicos", estado: "em_comissoes",
      "atualizado-em": "2026-07-01T00:00:00Z",
    },
  ],
  pareceres: [],
  ciencias: [
    {
      "parecer-id": "pc1", "proposicao-id": "p1", tipo: "projeto_lei", ano: 2026, sequencial: 42,
      "urn-lex": "urn:lex:fixture", ementa: "Hortas Comunitárias em terrenos públicos",
    },
  ],
};

describe("PaginaHomeVereador", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("renderiza o herói fora-de-sessão, a ciência pendente e as proposições", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => painelFake }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText("Sem sessão agora")).toBeTruthy());
    expect(screen.getByText("Tudo em dia.")).toBeTruthy();
    expect(screen.getByText("Para sua ciência")).toBeTruthy();
    expect(screen.getByText("Dar ciência")).toBeTruthy();
    expect(screen.getByText("Hortas Comunitárias em terrenos públicos")).toBeTruthy();
    // "PL 42/2026" aparece 2x (o num-inline do card de ciência + o num do card de proposição) — a mesma
    // matéria referenciada nos dois lugares, não duplicação de bug.
    expect(screen.getAllByText("PL 42/2026")).toHaveLength(2);
  });

  it("'Dar ciência' POSTa e revalida o painel (a ciência some da lista)", async () => {
    const painelSemCiencia = { ...painelFake, ciencias: [] };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => painelFake } as Response) // GET inicial
      .mockResolvedValueOnce({ ok: true, json: async () => ({ id: "c1", "ciente-em": "2026-07-11T09:14:00Z" }) } as Response) // POST acusar
      .mockResolvedValueOnce({ ok: true, json: async () => painelSemCiencia } as Response); // GET recarregar
    global.fetch = fetchMock as unknown as typeof fetch;
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText("Dar ciência")).toBeTruthy());
    screen.getByText("Dar ciência").click();

    await waitFor(() => expect(screen.queryByText("Para sua ciência")).toBeNull());
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/meu/ciencias",
      expect.objectContaining({ method: "POST" })
    );
  });

  it("sem proposições nem ciências -> mensagens vazias honestas, sem lançar", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ proposicoes: [], pareceres: [], ciencias: [] }),
    }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText("Nenhuma proposição sua ainda.")).toBeTruthy());
    expect(screen.queryByText("Para sua ciência")).toBeNull();
  });

  it("resposta não-ok -> estado de erro", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("Não foi possível carregar sua home")).toBeTruthy());
  });
});
