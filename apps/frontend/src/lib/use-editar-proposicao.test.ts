import { describe, expect, it, vi, afterEach } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useEditarProposicao } from "./use-editar-proposicao";

const respostaFake = {
  id: "1", tipo: "projeto_lei", ano: 2026, sequencial: 1, "urn-lex": "urn:x", ementa: "Y",
  estado: "protocolada", "lock-version": 1, "atualizado-em": "2026-01-01T00:00:00Z",
};

describe("useEditarProposicao", () => {
  afterEach(() => vi.restoreAllMocks());

  it("PATCH /api/legislativo/proposicoes/:id", async () => {
    let urlCapturada = "";
    let metodoCapturado = "";
    global.fetch = vi.fn(async (url: string, opts: RequestInit) => {
      urlCapturada = url;
      metodoCapturado = opts.method ?? "";
      return { ok: true, json: async () => respostaFake } as Response;
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useEditarProposicao("tok", "1"));
    await act(async () => {
      await result.current.editar({ lockVersion: 0, ementa: "Y" });
    });
    expect(urlCapturada).toBe("/api/legislativo/proposicoes/1");
    expect(metodoCapturado).toBe("PATCH");
  });

  it("erro do backend -> estado 'erro' + rejeita", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 409, json: async () => ({ erro: "conflito" }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useEditarProposicao("tok", "1"));
    await expect(result.current.editar({ lockVersion: 0 })).rejects.toThrow();
  });
});
