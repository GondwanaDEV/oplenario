import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, fireEvent } from "@testing-library/react";
import PaginaTramitacao from "./page";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

// Onda B Slice 4 — "Tramitação" (quadro de estágios, visão do servidor). Mesmo padrão de
// proposicoes/page.test.tsx: AuthProvider/TemaProvider explícitos (o layout do grupo (interno)/ não é
// exercitado por render() isolado), fixture kebab-case (payload real do backend via jsonista, B7b).

function renderComProviders(tokenQuery: string | null) {
  return render(
    <AuthProvider tokenQuery={tokenQuery}>
      <TemaProvider>
        <PaginaTramitacao />
      </TemaProvider>
    </AuthProvider>,
  );
}

const itemFake = {
  "proposicao-id": "1", tipo: "projeto_lei", ano: 2026, sequencial: 42, "urn-lex": "urn:x",
  ementa: "Institui o Programa Municipal de Hortas Comunitárias", "autor-texto": "Helena Matos",
  estado: "em_comissoes", "transicionou-em": "2026-05-21T10:00:00Z",
};
const respostaFake = { itens: [itemFake] };

describe("PaginaTramitacao", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("renderiza o quadro com as colunas e a matéria real após carregar", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("PL 42/2026")).toBeTruthy());
    expect(screen.getByText("Institui o Programa Municipal de Hortas Comunitárias")).toBeTruthy();
    expect(screen.getByText("Helena Matos")).toBeTruthy();
    // as 5 colunas fixas aparecem, mesmo as vazias
    expect(screen.getByText("Protocolo")).toBeTruthy();
    expect(screen.getByText("Comissões")).toBeTruthy();
    expect(screen.getByText("Pronta p/ pauta")).toBeTruthy();
    expect(screen.getByText("Em Plenário")).toBeTruthy();
    expect(screen.getByText("Concluídas")).toBeTruthy();
  });

  it("erro de rede mostra o estado de erro da página", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText(/não foi possível carregar/i)).toBeTruthy());
  });

  it("renderiza o link 'Nova proposição' apontando para /editor-proposicao (com token preservado)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ itens: [] }) }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    const link = await screen.findByRole("link", { name: /nova proposição/i });
    expect(link.getAttribute("href")).toContain("/editor-proposicao");
    expect(link.getAttribute("href")).toContain("token=tok-de-teste");
  });

  it("cada matéria linka para /ficha-materia/:id (com token preservado)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    const link = await screen.findByRole("link", { name: /institui o programa municipal de hortas comunitárias/i });
    expect(link.getAttribute("href")).toContain("/ficha-materia/1");
    expect(link.getAttribute("href")).toContain("token=tok-de-teste");
  });

  it("busca client-side filtra os cartões sem novo round-trip (sem 2ª chamada de fetch)", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({
        itens: [
          itemFake,
          { ...itemFake, "proposicao-id": "2", ementa: "Reforma do plano de cargos dos servidores", "autor-texto": "Ana Melo" },
        ],
      }),
    }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("Institui o Programa Municipal de Hortas Comunitárias")).toBeTruthy());
    const chamadasAntes = (global.fetch as ReturnType<typeof vi.fn>).mock.calls.length;

    const busca = screen.getByRole("searchbox");
    fireEvent.change(busca, { target: { value: "hortas" } });

    await waitFor(() => expect(screen.queryByText("Reforma do plano de cargos dos servidores")).toBeNull());
    expect(screen.getByText("Institui o Programa Municipal de Hortas Comunitárias")).toBeTruthy();
    expect((global.fetch as ReturnType<typeof vi.fn>).mock.calls.length).toBe(chamadasAntes);
  });
});
