import { describe, expect, it, vi, afterEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useCriarProposicao } from "./use-criar-proposicao";

const respostaFake = {
  id: "1", tipo: "projeto_lei", ano: 2026, sequencial: 1, "urn-lex": "urn:x", ementa: "X",
  estado: "protocolada", "lock-version": 0, "atualizado-em": "2026-01-01T00:00:00Z",
};

describe("useCriarProposicao", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POST /api/legislativo/proposicoes e devolve o detalhe camelizado", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useCriarProposicao("tok"));
    let devolvido: unknown;
    await act(async () => {
      devolvido = await result.current.criar({ tipo: "projeto_lei", ano: 2026, ementa: "X" });
    });
    expect((devolvido as { urnLex: string }).urnLex).toBe("urn:x");
    expect(result.current.estado).toBe("ocioso");
  });

  it("envia o corpo em snake/kebab (o backend espera kebab)", async () => {
    let corpoCapturado = "";
    global.fetch = vi.fn(async (_url: string, opts: RequestInit) => {
      corpoCapturado = opts.body as string;
      return { ok: true, json: async () => respostaFake } as Response;
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useCriarProposicao("tok"));
    await act(async () => {
      await result.current.criar({ tipo: "projeto_lei", ano: 2026, ementa: "X" });
    });
    expect(JSON.parse(corpoCapturado)).toEqual({ tipo: "projeto_lei", ano: 2026, ementa: "X" });
  });

  it("erro do backend -> estado 'erro' + rejeita a promise", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 400, json: async () => ({ erro: "invalido" }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useCriarProposicao("tok"));
    await expect(result.current.criar({ tipo: "projeto_lei", ano: 2026, ementa: "X" })).rejects.toThrow();
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> rejeita sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useCriarProposicao(null));
    await expect(result.current.criar({ tipo: "projeto_lei", ano: 2026, ementa: "X" })).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
