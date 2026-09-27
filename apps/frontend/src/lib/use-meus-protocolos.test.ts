import { afterEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useMeusProtocolos } from "./use-meus-protocolos";

describe("useMeusProtocolos", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/portal/meus-protocolos, cameliza e recarrega sob demanda", async () => {
    global.fetch = vi.fn(async () =>
      ({
        ok: true,
        status: 200,
        json: async () => ({
          "pedidos-esic": [{ id: "p1", protocolo: "ESIC-2026-000001", "dias-restantes": 3, resposta: null }],
          "solicitacoes-lgpd": [],
          manifestacoes: [],
        }),
      }) as Response,
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeusProtocolos("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.pedidosEsic[0].diasRestantes).toBe(3);
    expect(vi.mocked(global.fetch).mock.calls[0][0]).toBe("/api/portal/meus-protocolos");
    act(() => result.current.recarregar());
    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(2));
  });

  it("falha → erro", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 401, json: async () => ({}) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeusProtocolos("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });
});
