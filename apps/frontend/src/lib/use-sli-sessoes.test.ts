import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useSliSessoes } from "./use-sli-sessoes";

const sessaoFake = {
  "sessao-id": "s1", "estado-atual": "agendada", situacao: "agendada",
  "agendada-para": "2026-06-24T17:00:00Z", "aberta-em": null, "encerrada-em": null, "duracao-segundos": null,
};

describe("useSliSessoes", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/paineis/sli/sessoes e cameliza -> 'pronto'", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ sessoes: [sessaoFake], "sessoes-total": 1 }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useSliSessoes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.sessoes).toEqual([
      { sessaoId: "s1", estadoAtual: "agendada", situacao: "agendada", agendadaPara: "2026-06-24T17:00:00Z", abertaEm: null, encerradaEm: null, duracaoSegundos: null },
    ]);
  });

  // Fatia "truncamento-familia" sitio (a): sessoesTotal (o total REAL, sem o teto de 200) tem que sair do
  // hook camelizado, par de `sessoes` — e tem que ser o numero do SERVIDOR, distinto de count(sessoes).
  it("cameliza e expõe sessoesTotal ao lado de sessoes, distinto de count(sessoes)", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ sessoes: [sessaoFake], "sessoes-total": 7 }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useSliSessoes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.sessoes).toHaveLength(1);
    expect(result.current.sessoesTotal).toBe(7);
  });

  it("carregando -> sessoesTotal null enquanto a chamada está em voo", () => {
    global.fetch = vi.fn(() => new Promise(() => {})) as unknown as typeof fetch;
    const { result } = renderHook(() => useSliSessoes("tok"));
    expect(result.current.sessoesTotal).toBeNull();
  });

  it("chama a rota com o Bearer token", async () => {
    let headersCapturados: HeadersInit | undefined;
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      headersCapturados = init?.headers;
      return { ok: true, json: async () => ({ sessoes: [] }) } as Response;
    }) as unknown as typeof fetch;
    renderHook(() => useSliSessoes("tok-abc"));
    await waitFor(() => expect(headersCapturados).toBeDefined());
    expect(new Headers(headersCapturados).get("Authorization")).toBe("Bearer tok-abc");
  });

  it("falha de rede -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useSliSessoes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> 'erro' já na primeira renderização, sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useSliSessoes(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
