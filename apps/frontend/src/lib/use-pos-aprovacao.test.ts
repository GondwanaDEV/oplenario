import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { usePosAprovacao } from "./use-pos-aprovacao";

// Onda B Slice 7 — mirror EXATO de use-ficha-materia.test.ts: 3 estados, reset em render-time ao trocar
// `proposicaoId`, GET /api/legislativo/proposicoes/:id/pos-aprovacao, camelizarChaves.

const respostaSemAutografo = { autografo: null, "tramitacao-executiva": null };

const respostaComAutografo = {
  autografo: {
    id: "a1",
    "proposicao-id": "1",
    numero: 22,
    ano: 2026,
    "destinatario-texto": "Prefeitura Municipal",
    "enviado-em": "2026-06-18T00:00:00Z",
    "prazo-resposta-em": "2026-07-03T00:00:00Z",
  },
  "tramitacao-executiva": {
    id: "te1",
    "autografo-id": "a1",
    estado: "aguardando",
    "lock-version": 0,
  },
};

describe("usePosAprovacao", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/legislativo/proposicoes/:id/pos-aprovacao e cameliza", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => respostaComAutografo }) as Response,
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => usePosAprovacao("tok", "1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.autografo?.destinatarioTexto).toBe("Prefeitura Municipal");
    expect(result.current.dados?.tramitacaoExecutiva?.estado).toBe("aguardando");
    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/legislativo/proposicoes/1/pos-aprovacao");
    expect(new Headers(chamada[1]?.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("autografo ausente -> {autografo: null}, sem lançar", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => respostaSemAutografo }) as Response,
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => usePosAprovacao("tok", "1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.autografo).toBeNull();
  });

  it("proposicaoId com caracteres especiais -> encodeURIComponent na URL do fetch", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => respostaComAutografo }) as Response,
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => usePosAprovacao("tok", "a/b?c"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/legislativo/proposicoes/a%2Fb%3Fc/pos-aprovacao");
    expect(new Headers(chamada[1]?.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("proposicaoId nulo -> 'pronto' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => usePosAprovacao("tok", null));
    expect(result.current.estado).toBe("pronto");
    expect(result.current.dados).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("404 -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => usePosAprovacao("tok", "1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => usePosAprovacao(null, "1"));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("troca de proposicaoId -> reseta pra 'carregando' no render, sem mostrar dados antigos", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const idPedido = url.split("/").at(-2);
      return {
        ok: true,
        json: async () => (idPedido === "2" ? respostaSemAutografo : respostaComAutografo),
      } as Response;
    }) as unknown as typeof fetch;
    const { result, rerender } = renderHook(({ id }) => usePosAprovacao("tok", id), {
      initialProps: { id: "1" },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.autografo).toBeTruthy();

    rerender({ id: "2" });
    expect(result.current.estado).toBe("carregando");

    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.autografo).toBeNull();
  });
});
