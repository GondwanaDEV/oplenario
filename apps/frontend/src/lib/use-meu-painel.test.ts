import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { useMeuPainel } from "./use-meu-painel";

const painelFake = {
  proposicoes: [{ id: "p1", tipo: "projeto_lei", ano: 2026, sequencial: 1, "urn-lex": "urn:lex:fixture",
                  ementa: "Ementa fixture", estado: "protocolada", "atualizado-em": "2026-07-01T00:00:00Z" }],
  pareceres: [],
  ciencias: [],
};

describe("useMeuPainel", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/meu/painel e cameliza", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => painelFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuPainel("tok"));
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.proposicoes[0].urnLex).toBe("urn:lex:fixture");
    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/meu/painel");
    expect(new Headers(chamada[1]?.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("sem token -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuPainel(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("resposta não-ok -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 403 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuPainel("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("recarregar() refaz o GET e substitui os dados", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => painelFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuPainel("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    const painelAtualizado = { ...painelFake, ciencias: [] };
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => painelAtualizado }) as Response) as unknown as typeof fetch;
    await act(async () => {
      await result.current.recarregar();
    });
    expect(global.fetch).toHaveBeenCalledWith("/api/meu/painel", expect.anything());
    expect(result.current.estado).toBe("pronto");
  });
});
