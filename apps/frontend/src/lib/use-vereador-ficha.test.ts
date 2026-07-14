import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useVereadorFicha } from "./use-vereador-ficha";

// Hook da ficha de um vereador (Cadastro de Vereadores, Task 8) — mirror EXATO de use-ficha-materia.ts /
// use-proposicao-detalhe.ts (fetch-on-id: `id === null` -> "pronto"/dados-nulo sem fetch; troca de `id`
// reseta pra "carregando" + `dados: null` DURANTE O RENDER, antes do fetch do novo id resolver; 404/erro ->
// "erro", nunca lança — degrada independentemente de useVereadores).

const fichaFake1 = {
  id: "1",
  nome: "Ana Maria Souza",
  "nome-parlamentar": "Ana Souza",
  mandato: {
    partido: "PT",
    estado: "vigente",
    natureza: "titular",
    posse: "2025-01-01",
    "legislatura-numero": 19,
    "legislatura-ano-inicio": 2025,
    "legislatura-ano-fim": 2028,
    "cargo-mesa": null,
  },
  comissoes: [],
};

const fichaFake2 = { ...fichaFake1, id: "2", nome: "Herculano Pereira" };

describe("useVereadorFicha", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/cadastros/vereadores/:id e cameliza -> estado 'pronto'", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => fichaFake1 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useVereadorFicha("tok", "1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.nome).toBe("Ana Maria Souza");
    expect(result.current.dados?.mandato?.legislaturaNumero).toBe(19);
  });

  it("chama a rota com o Bearer token", async () => {
    let headersCapturados: HeadersInit | undefined;
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      headersCapturados = init?.headers;
      return { ok: true, json: async () => fichaFake1 } as Response;
    }) as unknown as typeof fetch;
    renderHook(() => useVereadorFicha("tok-abc", "1"));
    await waitFor(() => expect(headersCapturados).toBeDefined());
    expect(new Headers(headersCapturados).get("Authorization")).toBe("Bearer tok-abc");
  });

  it("id nulo -> 'pronto' sem chamar fetch (nenhum vereador selecionado)", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useVereadorFicha("tok", null));
    expect(result.current.estado).toBe("pronto");
    expect(result.current.dados).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("404 -> estado 'erro', não lança (degrada independente da lista)", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useVereadorFicha("tok", "1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("exception no fetch -> estado 'erro', não lança", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("network down");
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useVereadorFicha("tok", "1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useVereadorFicha(null, "1"));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("troca de id (mesmo token) -> reseta pra 'carregando' + dados null no render, refetch pro novo id", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const idPedido = url.split("/").pop();
      return {
        ok: true,
        json: async () => (idPedido === "2" ? fichaFake2 : fichaFake1),
      } as Response;
    }) as unknown as typeof fetch;
    const { result, rerender } = renderHook(({ id }) => useVereadorFicha("tok", id), {
      initialProps: { id: "1" as string | null },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.nome).toBe("Ana Maria Souza");

    rerender({ id: "2" });
    // Reset DURANTE O RENDER: já reflete "carregando" + dados null logo após o rerender, antes do fetch
    // resolver — nunca deve ficar mostrando a ficha stale de id="1".
    expect(result.current.estado).toBe("carregando");
    expect(result.current.dados).toBeNull();

    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.nome).toBe("Herculano Pereira");
  });

  it("troca de id para null -> volta a 'pronto' com dados null, sem chamar fetch de novo", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => fichaFake1 }) as Response) as unknown as typeof fetch;
    const { result, rerender } = renderHook(({ id }) => useVereadorFicha("tok", id), {
      initialProps: { id: "1" as string | null },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    rerender({ id: null });
    expect(result.current.estado).toBe("pronto");
    expect(result.current.dados).toBeNull();
  });
});
