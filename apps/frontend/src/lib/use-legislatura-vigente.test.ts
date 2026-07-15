import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useLegislaturaVigente } from "./use-legislatura-vigente";

// Hook da legislatura vigente (Cadastro de Vereadores, Onda D Slice 4) — mirror EXATO de use-vereadores.ts
// (fetch autenticado, camelizarChaves, estados carregando/pronto/erro, abort-safe por `vivo`), pra 1 rota só
// (GET /api/cadastros/legislatura-vigente), com a exceção: 404 é um estado vazio válido ("ainda não há
// legislatura cadastrada"), não um erro.

describe("useLegislaturaVigente", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/cadastros/legislatura-vigente e cameliza o payload -> estado 'pronto'", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ id: "leg-1", numero: 19, "ano-inicio": 2025, "ano-fim": 2028, vigente: true }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useLegislaturaVigente("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados).toEqual({ id: "leg-1", numero: 19, anoInicio: 2025, anoFim: 2028, vigente: true });
  });

  it("chama a rota com o Bearer token", async () => {
    let headersCapturados: HeadersInit | undefined;
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      headersCapturados = init?.headers;
      return { ok: true, status: 200, json: async () => ({ id: "leg-1", numero: 1, "ano-inicio": 2021, "ano-fim": 2024, vigente: false }) } as Response;
    }) as unknown as typeof fetch;
    renderHook(() => useLegislaturaVigente("tok-abc"));
    await waitFor(() => expect(headersCapturados).toBeDefined());
    expect(new Headers(headersCapturados).get("Authorization")).toBe("Bearer tok-abc");
  });

  it("404 -> dados null, estado 'pronto' (nao e' erro)", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useLegislaturaVigente("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados).toBeNull();
  });

  it("falha do servidor (nao-404) -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useLegislaturaVigente("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("exception no fetch -> estado 'erro', nao lanca", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("network down");
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useLegislaturaVigente("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });
});
