import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useEditarDocumento } from "./use-editar-documento";

// Mirror de use-salvar-rascunho-parecer.test.ts — PATCH /api/legislativo/documentos/:id. `id` pode ser
// nulo (fase de composição, antes do documento existir) — chamar `editar` nesse caso lança sem tentar rede.

describe("useEditarDocumento", () => {
  afterEach(() => vi.restoreAllMocks());

  it("PATCHa lock-version/corpo/assunto em kebab-case e cameliza a resposta", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ id: "d1", "modelo-id": "m1", "tipo-documento": "oficio", assunto: "Y", corpo: "Ao Vice-Prefeito.", estado: "rascunho", "lock-version": 1, "criado-em": "2026-01-01T00:00:00Z" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useEditarDocumento("tok", "d1"));

    await act(async () => {
      await result.current.editar({ lockVersion: 0, corpo: "Ao Vice-Prefeito.", assunto: "Y" });
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/documentos/d1",
      expect.objectContaining({
        method: "PATCH",
        body: JSON.stringify({ "lock-version": 0, corpo: "Ao Vice-Prefeito.", assunto: "Y" }),
      }),
    );
    expect(result.current.estado).toBe("ocioso");
  });

  it("id nulo -> lança sem chamar fetch (documento ainda não gerado)", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useEditarDocumento("tok", null));
    await expect(result.current.editar({ lockVersion: 0, corpo: "x" })).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("conflito de lock-version (400) fica em `erro`, nunca engolido", async () => {
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 400,
      json: async () => ({ erro: "parecer foi alterado por outra pessoa" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useEditarDocumento("tok", "d1"));

    await act(async () => {
      await expect(result.current.editar({ lockVersion: 0, corpo: "x" })).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toBe("parecer foi alterado por outra pessoa");
  });
});
