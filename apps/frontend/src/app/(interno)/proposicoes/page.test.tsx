import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import PaginaProposicoes from "./page";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

function renderComProviders(tokenQuery: string | null) {
  return render(
    <AuthProvider tokenQuery={tokenQuery}>
      <TemaProvider>
        <PaginaProposicoes />
      </TemaProvider>
    </AuthProvider>,
  );
}

const respostaFake = {
  itens: [
    {
      id: "1", tipo: "projeto_lei", ano: 2026, sequencial: 42, "urn-lex": "urn:x",
      ementa: "Cria o Programa Municipal de Hortas Comunitárias", "autor-texto": "Helena Matos",
      estado: "em_comissoes", "atualizado-em": "2026-05-21T10:00:00Z",
    },
  ],
  total: 1, pagina: 1, "tamanho-pagina": 20,
};

describe("PaginaProposicoes", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("renderiza a tabela com os dados reais após carregar", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("PL 42/2026")).toBeTruthy());
    expect(screen.getByText("Cria o Programa Municipal de Hortas Comunitárias")).toBeTruthy();
    expect(screen.getByText("Helena Matos")).toBeTruthy();
  });

  it("lista vazia mostra o estado 'nenhuma matéria encontrada'", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ itens: [], total: 0, pagina: 1, "tamanho-pagina": 20 }) }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText(/Nenhuma matéria encontrada/)).toBeTruthy());
  });

  it("erro de rede mostra o estado de erro da página", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText(/não foi possível carregar/i)).toBeTruthy());
  });

  it("renderiza o link 'Nova proposição' apontando para /editor-proposicao (com token preservado)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ itens: [], total: 0, pagina: 1, "tamanho-pagina": 20 }) }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    const link = await screen.findByRole("link", { name: /nova proposição/i });
    expect(link.getAttribute("href")).toContain("/editor-proposicao");
    expect(link.getAttribute("href")).toContain("token=tok-de-teste");
  });

  it("renderiza o link 'Editar' por linha apontando para /editor-proposicao/:id (com token preservado)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    const link = await screen.findByRole("link", { name: /editar pl 42\/2026/i });
    expect(link.getAttribute("href")).toContain("/editor-proposicao/1");
    expect(link.getAttribute("href")).toContain("token=tok-de-teste");
  });
});
