import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useProposicaoDetalhe } from "./use-proposicao-detalhe";

const respostaFake = {
  id: "1",
  tipo: "projeto_lei",
  ano: 2026,
  sequencial: 1,
  "urn-lex": "urn:x",
  ementa: "X",
  estado: "protocolada",
  "lock-version": 0,
  "atualizado-em": "2026-01-01T00:00:00Z",
};

describe("useProposicaoDetalhe", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/legislativo/proposicoes/:id e cameliza", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => respostaFake } as Response)
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicaoDetalhe("tok", "1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.urnLex).toBe("urn:x");
  });

  it("id nulo -> 'pronto' sem chamar fetch (fluxo de criar)", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicaoDetalhe("tok", null));
    expect(result.current.estado).toBe("pronto");
    expect(result.current.dados).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("404 -> estado 'erro'", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: false, status: 404 } as Response)
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicaoDetalhe("tok", "1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicaoDetalhe(null, "1"));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
