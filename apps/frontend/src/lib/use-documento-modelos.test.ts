import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useDocumentoModelos } from "./use-documento-modelos";

// Mirror simplificado de use-proposicoes.test.ts — sem filtros (a lista de modelos ATIVOS é sempre a mesma
// para o tenant), 3 estados.

describe("useDocumentoModelos", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/legislativo/documento-modelos e cameliza", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ itens: [{ id: "m1", chave: "oficio", nome: "Ofício padrão", "tipo-documento": "oficio" }] }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useDocumentoModelos("tok"));
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.itens[0].tipoDocumento).toBe("oficio");
    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/documento-modelos",
      expect.objectContaining({ headers: { Authorization: "Bearer tok" } }),
    );
  });

  it("sem token -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useDocumentoModelos(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("resposta não-ok -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 403 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useDocumentoModelos("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });
});
