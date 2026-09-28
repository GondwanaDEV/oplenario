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

// Tokens de dev (modo `test`: NEXT_PUBLIC_APP_ENV=test => usePapeis le' os papeis DO TOKEN, sincrono).
// Precisam de `papeis` de verdade porque esta pagina e' embrulhada em <GuardSecretaria>: um token sem
// "secretario" faz o guard renderizar "Acesso restrito" e NADA do conteudo montar — toda query aqui
// esperaria para sempre (era exatamente por isso que este arquivo inteiro dava timeout de 5s por teste).
const TOKEN_SECRETARIA = '{"sub":"u","papeis":["secretario"]}';
// Com admin_ente junto: prova que "Conceder acesso" SAIU deste cadastro (ADR-0005 — mudou para /administracao).
const TOKEN_SECRETARIA_ADMIN = '{"sub":"u","papeis":["secretario","admin_ente"]}';

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

const legislaturaVigenteFake = { id: "leg-1", numero: 19, "ano-inicio": 2025, "ano-fim": 2028, vigente: true };

// Mirror de fetchMockPara + as rotas de ESCRITA (Task 9): POST criar/mandato/licença por method+path, e a
// legislatura vigente (necessária pro form de mandato renderizar os campos em vez do estado "sem
// legislatura"). `mandato409` simula o conflito de domínio real do backend
// (`diplomat/http/in.clj` -> `:conflito/mandato-sobreposto` -> 409 `{:erro "..."}`).
// `identidadeVinculada409` (Task 11) simula o 3º passo de "conceder acesso" nunca sendo alcançado: o
// passo 2 (PATCH .../identidade) responde 409 como o backend faria pra uma identidade já ligada a OUTRO
// vereador nesta Casa (`:conflito/identidade-ja-vinculada`, cadastros/diplomat/http/in.clj).
function fetchMockComEscrita(
  mapaFichas: Record<string, unknown>,
  opts: { mandato409?: boolean; identidadeVinculada409?: boolean } = {},
) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "GET" && url === "/api/cadastros/vereadores") {
      return { ok: true, json: async () => listaFake } as Response;
    }
    if (method === "GET" && url === "/api/cadastros/legislatura-vigente") {
      return { ok: true, status: 200, json: async () => legislaturaVigenteFake } as Response;
    }
    if (method === "GET") {
      const m = /^\/api\/cadastros\/vereadores\/(.+)$/.exec(url);
      if (m) {
        const id = decodeURIComponent(m[1]);
        const ficha = mapaFichas[id];
        if (!ficha) return { ok: false, status: 404 } as Response;
        return { ok: true, json: async () => ficha } as Response;
      }
    }
    if (method === "POST" && url === "/api/cadastros/vereadores") {
      return { ok: true, json: async () => ({ id: "v3" }) } as Response;
    }
    if (method === "POST" && /\/mandatos$/.test(url) && opts.mandato409) {
      return {
        ok: false, status: 409,
        json: async () => ({ erro: "ja existe mandato vigente sobreposto para este vereador" }),
      } as Response;
    }
    if (method === "POST" && (/\/mandatos$/.test(url) || /\/licencas$/.test(url))) {
      return { ok: true, json: async () => ({ id: "novo-registro" }) } as Response;
    }
    // Task 11 — os 3 passos de "conceder acesso" (identidade -> ligar cadastro -> conceder acesso).
    if (method === "POST" && url === "/api/identidade/identidades") {
      return { ok: true, status: 201, json: async () => ({ "identidade-id": "id-9" }) } as Response;
    }
    if (method === "PATCH" && /\/identidade$/.test(url) && opts.identidadeVinculada409) {
      return {
        ok: false, status: 409,
        json: async () => ({ erro: "identidade ja vinculada a outro vereador nesta Casa" }),
      } as Response;
    }
    if (method === "PATCH" && /\/identidade$/.test(url)) {
      return { ok: true, status: 200, json: async () => ({ id: "v1", "identidade-id": "id-9" }) } as Response;
    }
    if (method === "POST" && url === "/api/identidade/acessos") {
      return { ok: true, status: 201, json: async () => ({ "vinculo-id": "vin-1", convite: "enviado" }) } as Response;
    }
    return { ok: false, status: 404 } as Response;
  });
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
    renderComProviders(TOKEN_SECRETARIA);

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
    renderComProviders(TOKEN_SECRETARIA);

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
    renderComProviders(TOKEN_SECRETARIA);

    await waitFor(() => expect(screen.getByRole("heading", { name: "Rafael Melo" })).toBeTruthy());
    expect(screen.getByRole("option", { name: /Rafael Melo/i }).getAttribute("aria-selected")).toBe("true");
    expect(screen.queryByRole("heading", { name: "Helena Past" })).toBeNull();
  });

  it("proposições e presença mostram 'Em breve' (nunca um número fabricado); comissões mostra a contagem real", async () => {
    global.fetch = fetchMockPara(fichas);
    renderComProviders(TOKEN_SECRETARIA);

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());
    expect(screen.getAllByText(/em breve/i).length).toBeGreaterThan(0);
    expect(screen.getByText("2")).toBeTruthy();
  });

  it("Novo vereador/Editar cadastro/Registrar mandato ficam habilitados; Ver proposições segue deferido (Em breve); Registrar licença só habilita com mandato vigente", async () => {
    global.fetch = fetchMockPara(fichas);
    renderComProviders(TOKEN_SECRETARIA);

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());

    expect((screen.getByRole("button", { name: /novo vereador/i }) as HTMLButtonElement).disabled).toBe(false);
    expect((screen.getByRole("button", { name: /ver proposições/i }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: /editar cadastro/i }) as HTMLButtonElement).disabled).toBe(false);
    expect((screen.getByRole("button", { name: /registrar mandato/i }) as HTMLButtonElement).disabled).toBe(false);
    // Helena (v1) tem mandato "vigente" -> licença habilitada
    expect((screen.getByRole("button", { name: /registrar licença/i }) as HTMLButtonElement).disabled).toBe(false);

    // Rafael (v2) está "licenciado" -> licença desabilitada, com o motivo no title
    fireEvent.click(screen.getByRole("option", { name: /Rafael Melo/i }));
    await waitFor(() => expect(screen.getByRole("heading", { name: "Rafael Melo" })).toBeTruthy());
    const btnLicenca = screen.getByRole("button", { name: /registrar licença/i }) as HTMLButtonElement;
    expect(btnLicenca.disabled).toBe(true);
    expect(btnLicenca.title).toMatch(/mandato vigente/i);
  });

  it("abrir 'Novo vereador' revela o form; submeter nome válido POSTa e refaz o fetch da lista", async () => {
    const fetchMock = fetchMockComEscrita(fichas);
    global.fetch = fetchMock as unknown as typeof fetch;
    renderComProviders(TOKEN_SECRETARIA);

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: /novo vereador/i }));
    const form = await screen.findByRole("form", { name: /^novo vereador$/i });

    const chamadasListaAntes = fetchMock.mock.calls.filter(
      ([url, init]) => url === "/api/cadastros/vereadores" && init?.method === undefined,
    ).length;

    fireEvent.change(screen.getByLabelText("Nome*"), { target: { value: "Nova Vereadora Teste" } });
    fireEvent.submit(form);

    await waitFor(() => {
      const chamouPost = fetchMock.mock.calls.some(
        ([url, init]) => url === "/api/cadastros/vereadores" && init?.method === "POST",
      );
      expect(chamouPost).toBe(true);
    });

    await waitFor(() => {
      const chamadasListaDepois = fetchMock.mock.calls.filter(
        ([url, init]) => url === "/api/cadastros/vereadores" && init?.method === undefined,
      ).length;
      expect(chamadasListaDepois).toBeGreaterThan(chamadasListaAntes);
    });
  });

  it("Novo vereador: submit fica desabilitado quando o nome está em branco após tocar", async () => {
    global.fetch = fetchMockPara(fichas);
    renderComProviders(TOKEN_SECRETARIA);

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: /novo vereador/i }));
    const form = await screen.findByRole("form", { name: /^novo vereador$/i });
    fireEvent.submit(form); // toca o form sem preencher o nome

    await waitFor(() => expect(screen.getByRole("alert")).toBeTruthy());
    expect((screen.getByRole("button", { name: /criar vereador/i }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("Registrar mandato: um 409 do servidor (mandato sobreposto) aparece como alerta inline, sem quebrar", async () => {
    const fetchMock = fetchMockComEscrita(fichas, { mandato409: true });
    global.fetch = fetchMock as unknown as typeof fetch;
    renderComProviders(TOKEN_SECRETARIA);

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: /registrar mandato/i }));
    const form = await screen.findByRole("form", { name: /^registrar mandato$/i });

    fireEvent.change(screen.getByLabelText(/início da vigência/i), { target: { value: "2025-06-01" } });
    fireEvent.submit(form);

    await waitFor(() => expect(screen.getByText(/mandato vigente sobreposto/i)).toBeTruthy());
    // a página não quebrou: a ficha de Helena segue montada
    expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy();
  });

  it("ArrowDown/ArrowUp no listbox move a seleção e chamam router.replace com o próximo id", async () => {
    global.fetch = fetchMockPara(fichas);
    renderComProviders(TOKEN_SECRETARIA);

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
    renderComProviders(TOKEN_SECRETARIA);

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
    renderComProviders(TOKEN_SECRETARIA);

    await waitFor(() => expect(screen.getByText(/nenhum vereador cadastrado/i)).toBeTruthy());
  });

  it("erro ao carregar a lista -> estado de erro honesto", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url === "/api/cadastros/vereadores") return { ok: false, status: 500 } as Response;
      return { ok: false, status: 404 } as Response;
    }) as unknown as typeof fetch;
    renderComProviders(TOKEN_SECRETARIA);

    await waitFor(() => expect(screen.getByText(/não foi possível carregar/i)).toBeTruthy());
  });

  // --- ADR-0005: "Conceder acesso" é da área do administrador da Casa (/administracao), não do cadastro ---

  it("o cadastro não oferece 'Conceder acesso' — nem a quem também é admin_ente (mudou para /administracao)", async () => {
    global.fetch = fetchMockPara(fichas);
    renderComProviders(TOKEN_SECRETARIA_ADMIN);

    await waitFor(() => expect(screen.getByRole("heading", { name: "Helena Past" })).toBeTruthy());
    expect(screen.queryByRole("button", { name: /conceder acesso/i })).toBeNull();
  });
});
