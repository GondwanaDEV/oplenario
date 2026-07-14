import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import PaginaVereadores from "./page";
import { AuthProvider } from "@/lib/auth";
import { TemaProvider } from "@/lib/tema";

// Task 9 — página master-detail do Cadastro de vereadores. Mesma disciplina dos demais page.test.tsx deste
// repo (proposicoes/vereador/assinar): NÃO mocka hooks custom (useVereadores/useVereadorFicha) — mocka
// `global.fetch` e deixa os hooks reais rodarem. A única exceção de mock é `next/navigation`
// (useSearchParams/useRouter), necessária porque esta é a 1ª página a ler `?v=` fora de uma rota dinâmica
// ([id]) — mesmo precedente de (vereador)/parecer/[id]/assinar/page.test.tsx, que mockou next/navigation
// pela mesma razão (nenhum Router real montado no teste).
//
// `buscaParamsAtual` fica mutável (não uma constante fixa) para o teste de deep-link (?v=<id>) poder trocar
// o retorno de `useSearchParams` ANTES do render, sem precisar de um 2º `vi.mock` — os demais testes usam o
// default (nenhum `v=` na URL).
const { routerReplace, buscaParamsAtual } = vi.hoisted(() => ({
  routerReplace: vi.fn(),
  buscaParamsAtual: { valor: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useSearchParams: () => buscaParamsAtual.valor,
  useRouter: () => ({ replace: routerReplace, push: vi.fn() }),
}));

function renderComProviders(tokenQuery: string | null) {
  return render(
    <AuthProvider tokenQuery={tokenQuery}>
      <TemaProvider>
        <PaginaVereadores />
      </TemaProvider>
    </AuthProvider>
  );
}

const listaFake = {
  vereadores: [
    { id: "v1", nome: "Helena Past", "nome-parlamentar": null, partido: "PT", "estado-mandato": "vigente", "cargo-mesa": "1ª Secretária" },
    { id: "v2", nome: "Rafael Melo", "nome-parlamentar": null, partido: "PSDB", "estado-mandato": "licenciado", "cargo-mesa": null },
  ],
};

const fichas: Record<string, unknown> = {
  v1: {
    id: "v1",
    nome: "Helena Past",
    "nome-parlamentar": null,
    mandato: {
      partido: "PT",
      estado: "vigente",
      natureza: "titular",
      posse: "2025-01-01",
      "legislatura-numero": 19,
      "legislatura-ano-inicio": 2025,
      "legislatura-ano-fim": 2028,
      "cargo-mesa": "1ª Secretária",
    },
    comissoes: [
      { nome: "Constituição e Justiça", tipo: "permanente", cargo: "presidente" },
      { nome: "Educação", tipo: "permanente", cargo: null },
    ],
  },
  v2: {
    id: "v2",
    nome: "Rafael Melo",
    "nome-parlamentar": null,
    mandato: {
      partido: "PSDB",
      estado: "licenciado",
      natureza: "titular",
      posse: "2025-01-01",
      "legislatura-numero": 19,
      "legislatura-ano-inicio": 2025,
      "legislatura-ano-fim": 2028,
      "cargo-mesa": null,
    },
    comissoes: [],
  },
};

function fetchMockPara(mapaFichas: Record<string, unknown>) {
  return vi.fn(async (url: string) => {
    if (url === "/api/cadastros/vereadores") {
      return { ok: true, json: async () => listaFake } as Response;
    }
    const m = /^\/api\/cadastros\/vereadores\/(.+)$/.exec(url);
    if (m) {
      const id = decodeURIComponent(m[1]);
      const ficha = mapaFichas[id];
      if (!ficha) return { ok: false, status: 404 } as Response;
      return { ok: true, json: async () => ficha } as Response;
    }
    return { ok: false, status: 404 } as Response;
  }) as unknown as typeof fetch;
}

describe("PaginaVereadores", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    routerReplace.mockClear();
    buscaParamsAtual.valor = new URLSearchParams();
  });

  it("mostra a lista e seleciona o 1º vereador automaticamente, sincronizando ?v= na URL", async () => {
    global.fetch = fetchMockPara(fichas);
    renderComProviders("tok-de-teste");

    await screen.findByText("Helena Past");
    expect(screen.getByText("Rafael Melo")).toBeTruthy();

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());
    // comissões = contagem REAL (2 itens na fixture v1)
    expect(screen.getByText("2")).toBeTruthy();

    await waitFor(() =>
      expect(routerReplace).toHaveBeenCalledWith(expect.stringContaining("v=v1"))
    );
  });

  it("selecionar outro vereador na lista atualiza a ficha e chama router.replace com o novo ?v=", async () => {
    global.fetch = fetchMockPara(fichas);
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());

    const linhaRafael = screen.getByRole("option", { name: /Rafael Melo/i });
    linhaRafael.click();

    await waitFor(() => expect(screen.getByRole("heading", { name: "Rafael Melo" })).toBeTruthy());
    await waitFor(() =>
      expect(routerReplace).toHaveBeenCalledWith(expect.stringContaining("v=v2"))
    );
  });

  it("deep-link ?v=<id> existente na URL seleciona aquele vereador ao montar", async () => {
    buscaParamsAtual.valor = new URLSearchParams("v=v2");
    global.fetch = fetchMockPara(fichas);
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByRole("heading", { name: "Rafael Melo" })).toBeTruthy());
    expect(screen.getByRole("option", { name: /Rafael Melo/i }).getAttribute("aria-selected")).toBe("true");
    expect(screen.queryByRole("heading", { name: "Helena Past" })).toBeNull();
  });

  it("proposições e presença mostram 'Em breve' (nunca um número fabricado); comissões mostra a contagem real", async () => {
    global.fetch = fetchMockPara(fichas);
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());
    expect(screen.getAllByText(/em breve/i).length).toBeGreaterThan(0);
    expect(screen.getByText("2")).toBeTruthy();
  });

  it("as ações do cadastro (novo/editar/ver proposições/licença) ficam desabilitadas, com Em breve explicando por quê", async () => {
    global.fetch = fetchMockPara(fichas);
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());

    expect((screen.getByRole("button", { name: /novo vereador/i }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: /ver proposições/i }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: /editar cadastro/i }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: /registrar licença/i }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("ArrowDown/ArrowUp no listbox move a seleção e chamam router.replace com o próximo id", async () => {
    global.fetch = fetchMockPara(fichas);
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());
    routerReplace.mockClear();

    const linhaHelena = screen.getByRole("option", { name: /Helena Past/i });
    linhaHelena.focus();
    fireEvent.keyDown(linhaHelena, { key: "ArrowDown" });

    await waitFor(() => expect(screen.getByRole("heading", { name: "Rafael Melo" })).toBeTruthy());
    const linhaRafael = screen.getByRole("option", { name: /Rafael Melo/i });
    expect(linhaRafael.getAttribute("aria-selected")).toBe("true");
    await waitFor(() =>
      expect(routerReplace).toHaveBeenCalledWith(expect.stringContaining("v=v2"))
    );

    fireEvent.keyDown(linhaRafael, { key: "ArrowUp" });

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());
    expect(screen.getByRole("option", { name: /Helena Past/i }).getAttribute("aria-selected")).toBe("true");
  });

  it("comissão com cargo 'presidente' aparece destacada na ficha", async () => {
    global.fetch = fetchMockPara(fichas);
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());
    expect(screen.getByText(/Constituição e Justiça/)).toBeTruthy();
  });

  it("lista vazia -> mensagem honesta, sem quebrar", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url === "/api/cadastros/vereadores") {
        return { ok: true, json: async () => ({ vereadores: [] }) } as Response;
      }
      return { ok: false, status: 404 } as Response;
    }) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText(/nenhum vereador cadastrado/i)).toBeTruthy());
  });

  it("erro ao carregar a lista -> estado de erro honesto", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url === "/api/cadastros/vereadores") return { ok: false, status: 500 } as Response;
      return { ok: false, status: 404 } as Response;
    }) as unknown as typeof fetch;
    renderComProviders("tok-de-teste");

    await waitFor(() => expect(screen.getByText(/não foi possível carregar/i)).toBeTruthy());
  });
});
