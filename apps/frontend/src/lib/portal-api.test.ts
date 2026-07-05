import { afterEach, describe, expect, it, vi } from "vitest";
import { buscarPublico } from "./portal-api";

// Task 0.3 (Fatia A2.0, Portal do Cidadão) — espelha buscarOuNull de use-mesa.ts, mas SEM o header
// Authorization (superfície pública, sem auth) e degradando SEMPRE para null (nunca lança — "degradação
// por seção", Global Constraints do plano).

describe("buscarPublico", () => {
  afterEach(() => vi.restoreAllMocks());

  it("200 com corpo kebab-case -> cameliza o corpo", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ "autor-texto": "x" }),
    })) as unknown as typeof fetch;

    const r = await buscarPublico<{ autorTexto: string }>("fortaleza/materias/1");
    expect(r).toEqual({ autorTexto: "x" });
  });

  it("404 -> null", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 })) as unknown as typeof fetch;
    const r = await buscarPublico("fortaleza/materias/inexistente");
    expect(r).toBeNull();
  });

  it("fetch lança -> null (degradação por seção, nunca 500 global)", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("rede fora");
    }) as unknown as typeof fetch;
    const r = await buscarPublico("fortaleza/materias/1");
    expect(r).toBeNull();
  });

  it("chama GET /api/portal/casa/{caminho}, sem header Authorization, cache no-store", async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({}) })) as unknown as typeof fetch;
    global.fetch = fetchMock;

    await buscarPublico("fortaleza/materias");

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
});
