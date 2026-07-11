import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useRegistrarResposta } from "./use-registrar-resposta";

// Onda B Slice 7 — mirror de use-protocolar-documento.test.ts: id (aqui, autografoId) fixo no path,
// lock-version obrigatório (CAS real), corpoKebab filtra `undefined` (veto-tipo/veto-razoes só p/ vetado).

const respostaSancionado = {
  id: "te1",
  "autografo-id": "a1",
  estado: "sancionado",
  "respondido-em": "2026-07-01T00:00:00Z",
  "lock-version": 1,
};

describe("useRegistrarResposta", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa lock-version + resultado e cameliza a tramitação executiva devolvida", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaSancionado }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useRegistrarResposta("tok", "a1"));

    let tramitacao;
    await act(async () => {
      tramitacao = await result.current.registrar({ lockVersion: 0, resultado: "sancionado" });
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/autografos/a1/resposta",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ "lock-version": 0, resultado: "sancionado" }),
      }),
    );
    expect((tramitacao as unknown as { estado: string }).estado).toBe("sancionado");
    expect(result.current.estado).toBe("ocioso");
  });

  it("resultado 'vetado' inclui veto-tipo/veto-razoes no corpo", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaSancionado }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useRegistrarResposta("tok", "a1"));

    await act(async () => {
      await result.current.registrar({
        lockVersion: 0,
        resultado: "vetado",
        vetoTipo: "total",
        vetoRazoes: "Inconstitucionalidade formal.",
      });
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/autografos/a1/resposta",
      expect.objectContaining({
        body: JSON.stringify({
          "lock-version": 0,
          resultado: "vetado",
          "veto-tipo": "total",
          "veto-razoes": "Inconstitucionalidade formal.",
        }),
      }),
    );
  });

  it("autografoId nulo -> lança sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useRegistrarResposta("tok", null));
    await expect(result.current.registrar({ lockVersion: 0, resultado: "sancionado" })).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("conflito de lock-version (400) fica em 'erro', nunca engolido", async () => {
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 400,
      json: async () => ({ erro: "registrar-resposta!: conflito de lock_version ou inexistente" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useRegistrarResposta("tok", "a1"));

    await act(async () => {
      await expect(result.current.registrar({ lockVersion: 0, resultado: "sancionado" })).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toMatch(/conflito de lock_version/);
  });
});
