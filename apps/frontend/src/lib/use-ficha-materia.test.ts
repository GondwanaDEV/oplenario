import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useFichaMateria } from "./use-ficha-materia";

// Onda B Slice 3 — mirror EXATO de use-proposicao-detalhe.test.ts: 3 estados, reset em render-time ao
// trocar `id`, GET /api/legislativo/proposicoes/:id/ficha, camelizarChaves.

const respostaFake2 = {
  proposicao: {
    id: "2",
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 2,
    "urn-lex": "urn:y",
    ementa: "Y",
    estado: "protocolada",
    "lock-version": 0,
    "atualizado-em": "2026-01-02T00:00:00Z",
  },
  tramitacao: [],
  apensadas: [],
  emendas: [],
  pareceres: [],
};

const respostaFake = {
  proposicao: {
    id: "1",
    tipo: "projeto_lei",
    ano: 2026,
    sequencial: 1,
    "urn-lex": "urn:x",
    ementa: "X",
    estado: "em_comissoes",
    "lock-version": 0,
    "atualizado-em": "2026-01-01T00:00:00Z",
  },
  tramitacao: [
    { "de-estado": "protocolada", "para-estado": "em_comissoes", gatilho: "distribuir", "ocorrido-em": "2026-01-01T00:00:00Z" },
  ],
  apensadas: [],
  emendas: [],
  pareceres: [],
};

describe("useFichaMateria", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/legislativo/proposicoes/:id/ficha e cameliza (incl. arrays aninhados)", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => respostaFake } as Response),
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useFichaMateria("tok", "1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.proposicao.urnLex).toBe("urn:x");
    expect(result.current.dados?.tramitacao[0]?.deEstado).toBe("protocolada");
    expect(result.current.dados?.tramitacao[0]?.paraEstado).toBe("em_comissoes");
    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/proposicoes/1/ficha",
      expect.objectContaining({ headers: { Authorization: "Bearer tok" } }),
    );
  });

  it("id com caracteres especiais -> encodeURIComponent na URL do fetch", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => respostaFake } as Response),
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useFichaMateria("tok", "a/b?c"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/proposicoes/a%2Fb%3Fc/ficha",
      expect.objectContaining({ headers: { Authorization: "Bearer tok" } }),
    );
  });

  it("id nulo -> 'pronto' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useFichaMateria("tok", null));
    expect(result.current.estado).toBe("pronto");
    expect(result.current.dados).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("404 -> estado 'erro'", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: false, status: 404 } as Response),
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useFichaMateria("tok", "1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useFichaMateria(null, "1"));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("troca de id (mesmo token) -> reseta pra 'carregando' no render, sem mostrar dados antigos", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const idPedido = url.split("/").at(-2);
      return {
        ok: true,
        json: async () => (idPedido === "2" ? respostaFake2 : respostaFake),
      } as Response;
    }) as unknown as typeof fetch;
    const { result, rerender } = renderHook(({ id }) => useFichaMateria("tok", id), {
      initialProps: { id: "1" },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.proposicao.urnLex).toBe("urn:x");

    rerender({ id: "2" });
    expect(result.current.estado).toBe("carregando");

    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.proposicao.urnLex).toBe("urn:y");
  });
});
