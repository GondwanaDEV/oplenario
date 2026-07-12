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

  it("mostra a seção Meus pareceres com link para a página de assinatura", async () => {
    const painelComParecer = {
      ...painelFake,
      pareceres: [
        {
          id: "p1", "objeto-tipo": "proposicao", "objeto-id": "o1", "comissao-id": "c1",
          estado: "com_relator", "voto-relator": null, "criado-em": "2026-07-11T00:00:00Z",
        },
      ],
    };
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => painelComParecer }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText(/Meus pareceres/i)).toBeTruthy());
    // jest-dom não está instalado neste repo (mesmo padrão dos outros testes deste arquivo) — assert DOM cru.
    // Rota REAL confirmada em Task 11 (apps/frontend/src/app/(vereador)/parecer/[id]/assinar/page.tsx): o
    // grupo de rota `(vereador)` não entra na URL, então é `/parecer/:id/assinar`, NUNCA
    // `/vereador/parecer/:id/assinar`. `comToken` preserva o `?token=` de dev entre navegações internas —
    // MESMO padrão de layout.tsx (tabbar) e do `router.push` de volta em assinar/page.tsx; sem isso o link
    // perderia o token dev no clique e a página de assinatura cairia no guard de auth.
    expect((screen.getByRole("link", { name: /assinar/i }) as HTMLAnchorElement).getAttribute("href")).toBe(
      "/parecer/p1/assinar?token=tok-de-teste"
    );
  });

  it("estado pronto tem um <h1> (review MAJOR react — a11y: sem isso a árvore de headings pula pro h2)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => painelFake }) as Response) as unknown as typeof fetch;
    const { container } = renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("Tudo em dia.")).toBeTruthy());
    expect(container.querySelector("h1")).not.toBeNull();
  });

  it("'Dar ciência' falha -> mostra o erro, não lança (unhandled rejection) e reabilita o botão", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => painelFake } as Response) // GET inicial
      .mockResolvedValueOnce({ ok: false, status: 500, json: async () => ({ erro: "falha ao dar ciência" }) } as Response); // POST acusar falha
    global.fetch = fetchMock as unknown as typeof fetch;
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText("Dar ciência")).toBeTruthy());
    const clique = screen.getByText("Dar ciência").click();

    await waitFor(() => expect(screen.getByText(/Não foi possível registrar a ciência/)).toBeTruthy());
    // o botão continua ali (a ciência não some) e volta a ficar clicável — nada trava em "enviando" pra sempre.
    // jest-dom não está instalado neste repo (mesmo padrão de outros testes) — assert DOM cru.
    expect((screen.getByText("Dar ciência").closest("button") as HTMLButtonElement).disabled).toBe(false);
    expect(() => clique).not.toThrow();
  });
});
