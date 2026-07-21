import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { useMarcarLida } from "./use-marcar-lida";

describe("useMarcarLida", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa no id certo e cameliza o recibo", async () => {
    global.fetch = vi.fn(async () =>
      ({ ok: true, json: async () => ({ id: "n1", "lida-em": "2026-07-19T13:00:00Z" }) }) as Response
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useMarcarLida("tok"));
    let recibo;
    await act(async () => {
      recibo = await result.current.marcar("n1");
    });
    expect(recibo).toEqual({ id: "n1", lidaEm: "2026-07-19T13:00:00Z" });
    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/meu/notificacoes/n1/lida");
    expect(chamada[1]?.method).toBe("POST");
    expect(result.current.estado).toBe("ocioso");
  });

  it("sem token -> lança e não chama fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useMarcarLida(null));
    await expect(result.current.marcar("n1")).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("404 -> estado 'erro' com mensagem legível", async () => {
    global.fetch = vi.fn(async () =>
      ({ ok: false, status: 404, json: async () => ({ erro: "notificacao nao encontrada" }) }) as Response
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useMarcarLida("tok"));
    await act(async () => {
      await expect(result.current.marcar("n1")).rejects.toThrow("notificacao nao encontrada");
    });
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.erro).toBe("notificacao nao encontrada");
  });

  it("id vazio não vira URL malformada", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useMarcarLida("tok"));
    await expect(result.current.marcar("")).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
