import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useAtualizarModeloDocumento } from "./use-atualizar-modelo-documento";

// Mirror EXATO de use-editar-documento.test.ts — PATCH /api/legislativo/documento-modelos/:id.

describe("useAtualizarModeloDocumento", () => {
  afterEach(() => vi.restoreAllMocks());

  it("PATCHa lock-version/nome/corpo-template em kebab-case e cameliza a resposta", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({
        id: "m1", chave: "oficio_padrao", nome: "Novo nome", "tipo-documento": "oficio",
        "corpo-template": "Corpo novo.", ativo: true, "lock-version": 1,
      }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useAtualizarModeloDocumento("tok", "m1"));

    await act(async () => {
      await result.current.atualizar({ lockVersion: 0, nome: "Novo nome", corpoTemplate: "Corpo novo." });
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/documento-modelos/m1",
      expect.objectContaining({
        method: "PATCH",
        body: JSON.stringify({ "lock-version": 0, nome: "Novo nome", "corpo-template": "Corpo novo." }),
      }),
    );
    expect(result.current.estado).toBe("ocioso");
  });

  it("desativar (ativo:false) manda so' lock-version + ativo", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({
        id: "m1", chave: "oficio_padrao", nome: "X", "tipo-documento": "oficio",
        "corpo-template": "Y", ativo: false, "lock-version": 2,
      }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useAtualizarModeloDocumento("tok", "m1"));

    await act(async () => {
      await result.current.atualizar({ lockVersion: 1, ativo: false });
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/documento-modelos/m1",
      expect.objectContaining({
        method: "PATCH",
        body: JSON.stringify({ "lock-version": 1, ativo: false }),
      }),
    );
  });

  it("id nulo -> lança sem chamar fetch (modelo ainda nao foi criado)", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useAtualizarModeloDocumento("tok", null));
    await expect(result.current.atualizar({ lockVersion: 0, nome: "x" })).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("conflito de lock-version (400) fica em `erro`, nunca engolido", async () => {
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 400,
      json: async () => ({ erro: "requisicao invalida" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useAtualizarModeloDocumento("tok", "m1"));

    await act(async () => {
      await expect(result.current.atualizar({ lockVersion: 0, nome: "x" })).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toBe("requisicao invalida");
  });
});
