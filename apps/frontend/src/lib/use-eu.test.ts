import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useEu } from "./use-eu";

describe("useEu", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
  });

  it("modo dev (NODE_ENV=test) -> não busca /api/eu, estado 'pronto' direto, papeis null", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useEu("algum-token"));
    expect(result.current.estado).toBe("pronto");
    expect(result.current.papeis).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("modo real -> busca /api/eu e extrai ator.papeis", async () => {
    vi.stubEnv("NODE_ENV", "production");
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

  it("modo real + resposta não-ok -> papeis [], estado 'erro' (fail-closed)", async () => {
    vi.stubEnv("NODE_ENV", "production");
    global.fetch = vi.fn(async () => ({ ok: false, status: 401 }) as Response) as unknown as typeof fetch;

    const { result } = renderHook(() => useEu(null));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.papeis).toEqual([]);
  });

  it("modo real + resposta malformada (sem ator.papeis) -> papeis [], estado 'erro'", async () => {
    vi.stubEnv("NODE_ENV", "production");
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({}) }) as Response) as unknown as typeof fetch;

    const { result } = renderHook(() => useEu(null));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.papeis).toEqual([]);
  });
});
