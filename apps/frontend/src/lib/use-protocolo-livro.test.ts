import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useProtocoloLivro } from "./use-protocolo-livro";

// Mirror de use-documento-modelos.test.ts — GET /api/legislativo/protocolo-geral, sem filtros (o Livro do
// ano corrente inteiro; a resolução do ano é do backend, kernel/tempo).

describe("useProtocoloLivro", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/legislativo/protocolo-geral e cameliza", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({
        itens: [
          {
            id: "p1",
            numero: 847,
            ano: 2026,
            "objeto-tipo": "documento",
            "objeto-id": "d1",
            sentido: "expedido",
            assunto: "Convite",
            "protocolado-em": "2026-05-21T14:02:00Z",
          },
        ],
      }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useProtocoloLivro("tok"));
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.itens[0].objetoTipo).toBe("documento");
    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/legislativo/protocolo-geral");
    expect(new Headers(chamada[1]?.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("sem token -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useProtocoloLivro(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("resposta não-ok -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useProtocoloLivro("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });
});
