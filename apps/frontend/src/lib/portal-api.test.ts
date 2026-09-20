import { afterEach, describe, expect, it, vi } from "vitest";
import { buscarNomeCasa, buscarPublico, resolverCasa } from "./portal-api";

// Task 0.3 (Fatia A2.0, Portal do Cidadão) — espelha buscarOuNull de use-mesa.ts, mas SEM o header
// Authorization (superfície pública, sem auth) e degradando SEMPRE para null (nunca lança — "degradação
// por seção", Global Constraints do plano).
//
// `buscarPublico` recebe SEGMENTOS (`...segmentos: string[]`), não um `caminho` já concatenado (review
// de segurança A2.0, item 2) — por isso as chamadas abaixo passam cada parte separada.

describe("buscarPublico", () => {
  afterEach(() => vi.restoreAllMocks());

  it("200 com corpo kebab-case -> cameliza o corpo", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ "autor-texto": "x" }),
    })) as unknown as typeof fetch;

    const r = await buscarPublico<{ autorTexto: string }>("fortaleza", "materias", "1");
    expect(r).toEqual({ autorTexto: "x" });
  });

  it("404 -> null", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 })) as unknown as typeof fetch;
    const r = await buscarPublico("fortaleza", "materias", "inexistente");
    expect(r).toBeNull();
  });

  it("fetch lança -> null (degradação por seção, nunca 500 global)", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("rede fora");
    }) as unknown as typeof fetch;
    const r = await buscarPublico("fortaleza", "materias", "1");
    expect(r).toBeNull();
  });

  it("chama GET /api/portal/casa/{segmentos-codificados}, sem header Authorization, cache no-store", async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({}) })) as unknown as typeof fetch;
    global.fetch = fetchMock;

    await buscarPublico("fortaleza", "materias");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = (fetchMock as unknown as ReturnType<typeof vi.fn>).mock.calls[0] as [
      string,
      RequestInit,
    ];
    expect(url.startsWith("/api/portal/casa/")).toBe(true);
    expect(url).toBe("/api/portal/casa/fortaleza/materias");
    expect(init.cache).toBe("no-store");
    expect(init.headers).toBeUndefined();
  });

  it("segmentos '..' não produzem path traversal (sem '../' literal na URL)", async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({}) })) as unknown as typeof fetch;
    global.fetch = fetchMock;

    await buscarPublico("..", "..", "x");

    const [url] = (fetchMock as unknown as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit];
    expect(url.startsWith("/api/portal/casa/")).toBe(true);
    expect(url).not.toContain("../");
    expect(url).not.toContain("..%2F");
  });
});

// FE Onda A2 fast-follow — a barra institucional/rodapé mostravam o UUID cru da rota em vez do nome real
// da Câmara. `buscarNomeCasa` roda em Server Component (não client): fetch ABSOLUTO ao backend (nunca
// `/api/...` relativo — não resolve em SSR, mesmo motivo documentado em use-materias.ts), com o MESMO
// contrato de degradação (nunca lança, `!ok` -> null) do resto do portal público.
describe("buscarNomeCasa", () => {
  afterEach(() => vi.restoreAllMocks());

  it("200 com corpo kebab-case -> cameliza e devolve nomeOficial/nomeCurto", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ "nome-oficial": "Câmara Municipal de Fortaleza", "nome-curto": "Câmara de Fortaleza" }),
    })) as unknown as typeof fetch;

    const r = await buscarNomeCasa("6e946df5-3c63-4e78-824d-10bc9b965816");
    expect(r).toEqual({ nomeOficial: "Câmara Municipal de Fortaleza", nomeCurto: "Câmara de Fortaleza" });
  });

  it("404 (ente sem perfil) -> null", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 })) as unknown as typeof fetch;
    expect(await buscarNomeCasa("inexistente")).toBeNull();
  });

  it("fetch lança (backend fora do ar) -> null, nunca derruba a página", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("rede fora");
    }) as unknown as typeof fetch;
    expect(await buscarNomeCasa("qualquer")).toBeNull();
  });

  it("chama o backend por URL ABSOLUTA (não /api/... relativo — não resolveria em SSR)", async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({}) })) as unknown as typeof fetch;
    global.fetch = fetchMock;

    await buscarNomeCasa("fortaleza");

    const [url, init] = (fetchMock as unknown as ReturnType<typeof vi.fn>).mock.calls[0] as [
      string,
      RequestInit,
    ];
    expect(url).toMatch(/^https?:\/\//);
    expect(url.endsWith("/portal/casa/fortaleza")).toBe(true);
    expect(init.cache).toBe("no-store");
  });
});

// Achado do teste exploratório contra a homologação (método docs/20, M4): `buscarNomeCasa` colapsava
// "esta Casa não existe" e "não consegui resolver agora" no MESMO `null`, e a capa do portal degradava
// pro slug cru nos dois — renderizando o Portal do Cidadão inteiro para um ente inexistente/malformado,
// com o id cru como nome da instituição. `resolverCasa` separa os dois vereditos; a capa gateia só o
// definitivo. Reproduzido ao vivo com UUID inexistente (404) e com slug malformado (400).
describe("resolverCasa (veredito de existência da Casa)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("200 -> ok, com o nome camelizado", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ "nome-oficial": "Câmara Municipal de Fortaleza" }),
    })) as unknown as typeof fetch;
    expect(await resolverCasa("ente-real")).toEqual({
      estado: "ok",
      nomeOficial: "Câmara Municipal de Fortaleza",
      nomeCurto: undefined,
    });
  });

  it("404 (uuid bem-formado, sem Casa) -> inexistente", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 })) as unknown as typeof fetch;
    expect(await resolverCasa("10000000-0000-0000-0000-000000000001")).toEqual({ estado: "inexistente" });
  });

  it("400 (id malformado) -> inexistente — um slug que não é id nunca é uma Casa", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 400 })) as unknown as typeof fetch;
    expect(await resolverCasa("xyz-invalido")).toEqual({ estado: "inexistente" });
  });

  it("500 -> indisponivel (transitório), NUNCA inexistente — não apaga uma Casa real por instabilidade", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 })) as unknown as typeof fetch;
    expect(await resolverCasa("ente-real")).toEqual({ estado: "indisponivel" });
  });

  it("fetch lança (backend fora do ar) -> indisponivel, nunca derruba a página", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("rede fora");
    }) as unknown as typeof fetch;
    expect(await resolverCasa("ente-real")).toEqual({ estado: "indisponivel" });
  });

  it("contrato de buscarNomeCasa intacto: indisponivel TAMBÉM vira null (telas internas seguem degradando)", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 })) as unknown as typeof fetch;
    expect(await buscarNomeCasa("ente-real")).toBeNull();
  });
});
