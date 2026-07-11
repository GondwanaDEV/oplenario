import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useTramitacaoBoard } from "./use-tramitacao-board";

// Onda B Slice 4 — hook espelha use-mesa.ts (mesmo padrão: fetch autenticado, camelizarChaves do
// boundary, estados carregando/pronto/erro, abort-safe por `vivo`), só que pra 1 rota só
// (GET /api/paineis/tramitacao).

const itemFake = {
  "proposicao-id": "1", tipo: "projeto_lei", ano: 2026, sequencial: 42, "urn-lex": "urn:x",
  ementa: "Institui o Programa Municipal de Hortas Comunitárias", "autor-texto": "Helena Matos",
  estado: "em_comissoes", "transicionou-em": "2026-05-21T10:00:00Z",
};

describe("useTramitacaoBoard", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/paineis/tramitacao e cameliza o payload -> estado 'pronto'", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ itens: [itemFake] }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useTramitacaoBoard("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.itens).toEqual([
      {
        proposicaoId: "1", tipo: "projeto_lei", ano: 2026, sequencial: 42, urnLex: "urn:x",
        ementa: "Institui o Programa Municipal de Hortas Comunitárias", autorTexto: "Helena Matos",
        estado: "em_comissoes", transicionouEm: "2026-05-21T10:00:00Z",
      },
    ]);
  });

  it("chama a rota com o Bearer token", async () => {
    let headersCapturados: HeadersInit | undefined;
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      headersCapturados = init?.headers;
      return { ok: true, json: async () => ({ itens: [] }) } as Response;
    }) as unknown as typeof fetch;
    renderHook(() => useTramitacaoBoard("tok-abc"));
    await waitFor(() => expect(headersCapturados).toBeDefined());
    expect((headersCapturados as Record<string, string>).Authorization).toBe("Bearer tok-abc");
  });

  it("falha de rede -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useTramitacaoBoard("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> 'erro' já na primeira renderização, sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useTramitacaoBoard(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("carregando -> itens null enquanto a chamada está em voo", () => {
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch;
    const { result } = renderHook(() => useTramitacaoBoard("tok"));
    expect(result.current.estado).toBe("carregando");
    expect(result.current.itens).toBeNull();
  });
});
