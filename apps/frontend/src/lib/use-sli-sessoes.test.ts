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
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ sessoes: [sessaoFake] }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useSliSessoes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.sessoes).toEqual([
      { sessaoId: "s1", estadoAtual: "agendada", situacao: "agendada", agendadaPara: "2026-06-24T17:00:00Z", abertaEm: null, encerradaEm: null, duracaoSegundos: null },
    ]);
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
