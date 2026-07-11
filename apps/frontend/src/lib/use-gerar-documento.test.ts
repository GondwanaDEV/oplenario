import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useGerarDocumento } from "./use-gerar-documento";

// Mirror EXATO de use-criar-proposicao.test.ts — POST /api/legislativo/documentos.

describe("useGerarDocumento", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa modelo-id/assunto/dados em kebab-case e cameliza a resposta", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ id: "d1", "modelo-id": "m1", "tipo-documento": "oficio", assunto: "Convite", corpo: "Ao Prefeito.", estado: "rascunho", "lock-version": 0, "criado-em": "2026-01-01T00:00:00Z" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useGerarDocumento("tok"));

    let doc;
    await act(async () => {
      doc = await result.current.gerar({ modeloId: "m1", assunto: "Convite", dados: { destinatario: "Prefeito" } });
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/documentos",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ "modelo-id": "m1", assunto: "Convite", dados: { destinatario: "Prefeito" } }),
      }),
    );
    expect((doc as unknown as { estado: string }).estado).toBe("rascunho");
    expect(result.current.estado).toBe("ocioso");
  });

  it("sem token -> lança sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useGerarDocumento(null));
    await expect(result.current.gerar({ modeloId: "m1", assunto: "x" })).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("erro do backend (404 modelo inexistente) fica em `erro`, nunca engolido", async () => {
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 404,
      json: async () => ({ erro: "modelo de documento nao encontrado" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useGerarDocumento("tok"));

    await act(async () => {
      await expect(result.current.gerar({ modeloId: "x", assunto: "y" })).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toBe("modelo de documento nao encontrado");
  });
});
