import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useEu, voltarAoLogin } from "./use-eu";

describe("useEu", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
  });

  it("modo dev (NEXT_PUBLIC_APP_ENV=test) -> não busca /api/eu, estado 'pronto' direto, papeis null", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useEu("algum-token"));
    expect(result.current.estado).toBe("pronto");
    expect(result.current.papeis).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("modo real -> busca /api/eu e extrai ator.papeis", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ ator: { papeis: ["vereador", "secretario"] } }) }) as Response
    ) as unknown as typeof fetch;

    const { result } = renderHook(() => useEu(null));
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.papeis).toEqual(["vereador", "secretario"]);
    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/eu");
  });

  it("modo real + resposta não-ok (que não é 401) -> papeis [], estado 'erro' (fail-closed)", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;

    const { result } = renderHook(() => useEu(null));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.papeis).toEqual([]);
  });

  it("modo real -> devolve o tipo do vínculo (a sessão do gov.br é a de 'cidadao'); ausente = null", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ ator: { papeis: [], "tipo-vinculo": "cidadao" } }) }) as Response
    ) as unknown as typeof fetch;
    const a = renderHook(() => useEu(null));
    await waitFor(() => expect(a.result.current.estado).toBe("pronto"));
    expect(a.result.current.tipoVinculo).toBe("cidadao");

    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ ator: { papeis: ["secretario"] } }) }) as Response
    ) as unknown as typeof fetch;
    const b = renderHook(() => useEu(null));
    await waitFor(() => expect(b.result.current.estado).toBe("pronto"));
    expect(b.result.current.tipoVinculo).toBeNull();
  });

  it("modo real + resposta malformada (sem ator.papeis) -> papeis [], estado 'erro'", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({}) }) as Response) as unknown as typeof fetch;

    const { result } = renderHook(() => useEu(null));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.papeis).toEqual([]);
  });
});

describe("voltarAoLogin — sessão que o backend não reconhece mais (401 em /eu)", () => {
  function loc(pathname: string, search = "") {
    return { pathname, search, assign: vi.fn() };
  }

  it("leva à rota que confere, limpa os cookies e abre /entrar, com o caminho atual", () => {
    const l = loc("/proposicoes", "?filtro=abertas");
    voltarAoLogin(l);
    expect(l.assign).toHaveBeenCalledWith("/api/auth/sessao-expirada?redirect=%2Fproposicoes%3Ffiltro%3Dabertas");
  });

  it("nunca numa página pública (lá não há sessão a perder)", () => {
    for (const p of ["/portal/casa/abc", "/entrar", "/entrar/escolher", "/status"]) {
      const l = loc(p);
      voltarAoLogin(l);
      expect(l.assign, p).not.toHaveBeenCalled();
    }
  });
});
