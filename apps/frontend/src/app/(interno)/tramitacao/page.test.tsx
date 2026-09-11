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

  it("filtro de Espécie mostra só as matérias do tipo selecionado, sem novo round-trip", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({
        itens: [
          itemFake,
          { ...itemFake, "proposicao-id": "2", tipo: "mocao", ementa: "Moção de aplausos ao time local", "autor-texto": "Ana Melo" },
        ],
      }),
    }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("Institui o Programa Municipal de Hortas Comunitárias")).toBeTruthy());
    expect(screen.getByText("Moção de aplausos ao time local")).toBeTruthy();
    const chamadasAntes = (global.fetch as ReturnType<typeof vi.fn>).mock.calls.length;

    fireEvent.change(screen.getByLabelText("Espécie"), { target: { value: "mocao" } });

    await waitFor(() => expect(screen.queryByText("Institui o Programa Municipal de Hortas Comunitárias")).toBeNull());
    expect(screen.getByText("Moção de aplausos ao time local")).toBeTruthy();
    expect((global.fetch as ReturnType<typeof vi.fn>).mock.calls.length).toBe(chamadasAntes);
  });

  // Fatia "truncamento-familia": o board parava de chamar 50 (por estado) de "todas" — a contagem exibida
  // por coluna e o rodapé "matérias em curso" tinham que parar de usar `itens.length` (que corta no teto
  // por-estado do servidor) e passar a usar `totais-por-estado` (par autoritativo, sem teto).
  it("mostra o TOTAL real por coluna e no rodapé, mesmo quando a lista chegou cortada pelo teto do servidor", async () => {
    // só 2 itens chegaram em "em_comissoes" (a lista já veio cortada pelo servidor), mas o total real é 7
    // — o payload onde itens.length e o total DISCORDAM, o cenário exato que expõe a mentira antiga.
    const itens = [
      itemFake,
      { ...itemFake, "proposicao-id": "2", ementa: "Segunda matéria em comissões" },
    ];
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ itens, "totais-por-estado": [{ estado: "em_comissoes", total: 7 }] }),
    }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("Segunda matéria em comissões")).toBeTruthy());

    expect(screen.getByLabelText("Comissões · 7 matérias")).toBeTruthy();
    expect(screen.getByText((_, node) => node?.textContent === "7 matérias em curso")).toBeTruthy();
  });

  it("coluna com mais matérias que o teto mostra 'Mostrar mais' e expande ao clicar", async () => {
    const itens = Array.from({ length: 35 }, (_, i) => ({
      ...itemFake,
      "proposicao-id": `p${i}`,
      ementa: `Matéria número ${i}`,
    }));
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ itens }) }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("Matéria número 0")).toBeTruthy());

    expect(screen.queryByText("Matéria número 30")).toBeNull();
    const botao = screen.getByRole("button", { name: /mostrar mais 5 matérias/i });

    fireEvent.click(botao);

    await waitFor(() => expect(screen.getByText("Matéria número 30")).toBeTruthy());
  });
});
