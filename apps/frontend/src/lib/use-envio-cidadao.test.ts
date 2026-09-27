import { afterEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useEnvioCidadao } from "./use-envio-cidadao";

describe("useEnvioCidadao — as escritas do cidadão no portal", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa JSON no /api e devolve o recibo camelizado", async () => {
    global.fetch = vi.fn(async () =>
      ({ ok: true, status: 201, json: async () => ({ protocolo: "ESIC-2026-000001", "recibo-em": "2026-07-03T12:00:00Z" }) }) as Response,
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useEnvioCidadao("tok"));
    let recibo: unknown;
    await act(async () => {
      recibo = await result.current.enviar("/portal/esic/pedidos", { assunto: "A", descricao: "B" });
    });
    expect(recibo).toEqual({ protocolo: "ESIC-2026-000001", reciboEm: "2026-07-03T12:00:00Z" });
    const [url, init] = vi.mocked(global.fetch).mock.calls[0];
    expect(url).toBe("/api/portal/esic/pedidos");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({ assunto: "A", descricao: "B" });
    expect(new Headers(init?.headers).get("Content-Type")).toBe("application/json");
    expect(result.current.estado).toBe("ocioso");
  });

  it("DELETE sem corpo (deixar de acompanhar)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ estado: "cancelado" }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useEnvioCidadao("tok"));
    await act(async () => {
      await result.current.enviar("/portal/materias/p1/acompanhar", undefined, "DELETE");
    });
    const [, init] = vi.mocked(global.fetch).mock.calls[0];
    expect(init?.method).toBe("DELETE");
    expect(init?.body).toBeUndefined();
  });

  it.each([
    [400, { erro: "requisicao invalida" }, "Confira os campos e tente de novo."],
    [401, {}, "Sua sessão terminou. Entre de novo com o gov.br para enviar."],
    [409, { erro: "estado incompativel com a operacao" }, "Isto não pode ser feito agora."],
    [500, {}, "Não foi possível enviar agora. Tente de novo em instantes."],
  ])("status %i vira mensagem legível e estado 'erro'", async (status, corpo, msg) => {
    global.fetch = vi.fn(async () => ({ ok: false, status, json: async () => corpo }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useEnvioCidadao("tok"));
    await act(async () => {
      await expect(result.current.enviar("/portal/x", {})).rejects.toThrow(msg);
    });
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.erro).toBe(msg);
  });

  it("não dispara duas vezes enquanto envia", async () => {
    let soltar: (v: Response) => void = () => {};
    global.fetch = vi.fn(() => new Promise<Response>((r) => (soltar = r))) as unknown as typeof fetch;
    const { result } = renderHook(() => useEnvioCidadao("tok"));
    let p1: Promise<unknown> = Promise.resolve();
    act(() => {
      p1 = result.current.enviar("/portal/x", {});
    });
    await act(async () => {
      await expect(result.current.enviar("/portal/x", {})).rejects.toThrow();
      soltar({ ok: true, status: 201, json: async () => ({}) } as Response);
      await p1;
    });
    expect(global.fetch).toHaveBeenCalledTimes(1);
  });
});
