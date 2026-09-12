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
// `totais-por-estado` é campo OBRIGATÓRIO do contrato (TramitacaoBoardOut é {:closed true}) — toda
// fixture de resposta 200 abaixo carrega o par completo (nunca só `itens`), senão o teste exercita o
// ramo de CONTRATO VIOLADO (ver o teste dedicado a isso mais abaixo) em vez do caminho real.
const respostaFake = { itens: [itemFake], "totais-por-estado": [{ estado: "em_comissoes", total: 1 }] };

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
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ itens: [], "totais-por-estado": [] }) }) as Response) as unknown as typeof fetch;
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
        "totais-por-estado": [{ estado: "em_comissoes", total: 2 }],
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
        "totais-por-estado": [{ estado: "em_comissoes", total: 2 }],
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
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ itens, "totais-por-estado": [{ estado: "em_comissoes", total: 35 }] }),
    }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("Matéria número 0")).toBeTruthy());

    expect(screen.queryByText("Matéria número 30")).toBeNull();
    const botao = screen.getByRole("button", { name: /mostrar mais 5 matérias/i });

    fireEvent.click(botao);

    await waitFor(() => expect(screen.getByText("Matéria número 30")).toBeTruthy());
  });

  // Achado da revisão adversarial (IMPORTANTE): o rodapé somava `coluna.total` de TODAS as colunas,
  // inclusive "Concluídas" (que funde estados TERMINAIS — aprovada/arquivada/etc, sem teto, cresce ao
  // longo de legislaturas). "N matérias em curso" virava um número dominado pelo acervo histórico, não
  // pela carga de trabalho ativa.
  it("o rodapé 'em curso' NÃO soma a coluna Concluídas (estados terminais não são 'em curso')", async () => {
    const itens = [
      itemFake, // em_comissoes
      { ...itemFake, "proposicao-id": "2", estado: "protocolada" },
      { ...itemFake, "proposicao-id": "3", estado: "aprovada" }, // cai em Concluídas
    ];
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({
        itens,
        "totais-por-estado": [
          { estado: "em_comissoes", total: 12 },
          { estado: "protocolada", total: 3 },
          { estado: "aprovada", total: 4000 }, // acervo histórico — não pode entrar na soma
        ],
      }),
    }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByLabelText("Comissões · 12 matérias")).toBeTruthy());

    // 12 (em_comissoes) + 3 (protocolada) = 15 — SEM os 4000 de Concluídas.
    expect(screen.getByText((_, node) => node?.textContent === "15 matérias em curso")).toBeTruthy();
    expect(screen.queryByText((_, node) => node?.textContent === "4015 matérias em curso")).toBeNull();
  });

  // Achado da revisão adversarial (IMPORTANTE): quando o servidor corta a lista no teto por-estado, a
  // tela publicava o total real no cabeçalho da coluna mas não dizia em NENHUM lugar que a lista abaixo
  // estava incompleta — o botão "Mostrar mais" (paginação CLIENT-SIDE sobre o que já chegou) simplesmente
  // some quando os itens carregados acabam, sem explicar o resto que nunca chegou do servidor.
  it("coluna cortada pelo servidor (total > itens recebidos) avisa, mesmo depois do 'Mostrar mais' se esgotar", async () => {
    // 50 itens chegaram em em_comissoes (o teto do servidor), mas o total real é 87 — 37 nunca chegaram.
    const itens = Array.from({ length: 50 }, (_, i) => ({
      ...itemFake,
      "proposicao-id": `p${i}`,
      ementa: `Matéria número ${i}`,
    }));
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ itens, "totais-por-estado": [{ estado: "em_comissoes", total: 87 }] }),
    }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");
    await waitFor(() => expect(screen.getByText("Matéria número 0")).toBeTruthy());

    // esgota a paginação client-side (30 -> 50, o que já chegou do servidor)
    fireEvent.click(screen.getByRole("button", { name: /mostrar mais 20 matérias/i }));
    await waitFor(() => expect(screen.getByText("Matéria número 49")).toBeTruthy());

    expect(screen.queryByRole("button", { name: /mostrar mais/i })).toBeNull();
    expect(
      screen.getByText((_, node) => node?.textContent === "Mostrando 50 de 87 matérias — o servidor limita a lista por estágio."),
    ).toBeTruthy();
  });

  // Achado da revisão adversarial (IMPORTANTE): `totaisPorEstado ?? undefined` em page.tsx conflava "não
  // carregou ainda" com "o servidor violou o contrato" (TramitacaoBoardOut é {:closed true}, o campo é
  // OBRIGATÓRIO) — as duas caíam no MESMO fallback de compat (itens.length), que é exatamente o número
  // que corta no teto. Sob drift (backend antigo / proxy que descarta o campo), a mentira original
  // voltava sem nenhum sinal. A regra 4 (aposentar heurística) exige tratar a ausência como FALHA de
  // contrato, nunca como número plausível.
  it("resposta 200 sem 'totais-por-estado' (contrato violado) mostra erro, NUNCA cai pro fallback itens.length", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ itens: [itemFake] }), // sem totais-por-estado — violação do contrato
    }) as Response) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText(/quadro de tramitação veio incompleto/i)).toBeTruthy());
    expect(screen.queryByText("Comissões · 1 matérias")).toBeNull();
  });
});
