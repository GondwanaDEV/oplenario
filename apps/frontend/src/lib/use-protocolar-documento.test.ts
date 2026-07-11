import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useProtocolarDocumento } from "./use-protocolar-documento";

// Mirror de use-emitir-parecer.test.ts — POST /api/legislativo/documentos/:id/protocolo (o CTA "Protocolar
// e numerar"). CAS real via lock-version obrigatório.

describe("useProtocolarDocumento", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa lock-version e cameliza a resposta com o número real", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({
        id: "d1", "modelo-id": "m1", "tipo-documento": "oficio", assunto: "Convite", corpo: "Ao Prefeito.",
        estado: "emitido", "protocolo-numero": 847, "protocolo-ano": 2026, "lock-version": 1,
        "criado-em": "2026-01-01T00:00:00Z",
      }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useProtocolarDocumento("tok", "d1"));

    let doc;
    await act(async () => {
      doc = await result.current.protocolar({ lockVersion: 0 });
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/documentos/d1/protocolo",
      expect.objectContaining({ method: "POST", body: JSON.stringify({ "lock-version": 0 }) }),
    );
    expect((doc as unknown as { protocoloNumero: number }).protocoloNumero).toBe(847);
    expect(result.current.estado).toBe("ocioso");
  });

  it("id nulo -> lança sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useProtocolarDocumento("tok", null));
    await expect(result.current.protocolar({ lockVersion: 0 })).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("já emitido (400) fica em `erro`, nunca engolido", async () => {
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 400,
      json: async () => ({ erro: "documento ja foi protocolado" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useProtocolarDocumento("tok", "d1"));

    await act(async () => {
      await expect(result.current.protocolar({ lockVersion: 1 })).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toBe("documento ja foi protocolado");
  });
});
