import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useVereadores } from "./use-vereadores";

// Hook da lista de vereadores (Cadastro de Vereadores, Task 8) — mirror EXATO de use-tramitacao-board.ts /
// use-mesa.ts (fetch autenticado, camelizarChaves do boundary, estados carregando/pronto/erro, abort-safe
// por `vivo`), pra 1 rota só (GET /api/cadastros/vereadores).

const vereadorFake = {
  id: "1",
  nome: "Ana Maria Souza",
  "nome-parlamentar": "Ana Souza",
  partido: "PT",
  "estado-mandato": "vigente",
  "cargo-mesa": null,
};

describe("useVereadores", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/cadastros/vereadores e cameliza o payload -> estado 'pronto'", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ vereadores: [vereadorFake] }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useVereadores("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados).toEqual([
      {
        id: "1",
        nome: "Ana Maria Souza",
        nomeParlamentar: "Ana Souza",
        partido: "PT",
        estadoMandato: "vigente",
        cargoMesa: null,
      },
    ]);
  });

  it("chama a rota com o Bearer token", async () => {
    let headersCapturados: HeadersInit | undefined;
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      headersCapturados = init?.headers;
      return { ok: true, json: async () => ({ vereadores: [] }) } as Response;
    }) as unknown as typeof fetch;
    renderHook(() => useVereadores("tok-abc"));
    await waitFor(() => expect(headersCapturados).toBeDefined());
    expect(new Headers(headersCapturados).get("Authorization")).toBe("Bearer tok-abc");
  });

  it("falha de rede -> estado 'erro', nao lanca", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useVereadores("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("exception no fetch -> estado 'erro', nao lanca", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("network down");
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useVereadores("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> 'erro' já na primeira renderização, sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useVereadores(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("carregando -> dados [] enquanto a chamada está em voo", () => {
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch;
    const { result } = renderHook(() => useVereadores("tok"));
    expect(result.current.estado).toBe("carregando");
    expect(result.current.dados).toEqual([]);
  });

  it("lista vazia -> estado 'pronto' com dados = [] (vazio é dado válido, não erro)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ vereadores: [] }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useVereadores("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados).toEqual([]);
  });
});
