import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useCriarModeloDocumento } from "./use-criar-modelo-documento";

// Mirror EXATO de use-gerar-documento.test.ts — POST /api/legislativo/documento-modelos.

describe("useCriarModeloDocumento", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa chave/nome/tipo-documento/corpo-template em kebab-case e cameliza a resposta", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({
        id: "m1", chave: "oficio_padrao", nome: "Ofício padrão", "tipo-documento": "oficio",
        "corpo-template": "Prezado {{destinatario}}.", ativo: true, "lock-version": 0,
      }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useCriarModeloDocumento("tok"));

    let modelo;
    await act(async () => {
      modelo = await result.current.criar({
        chave: "oficio_padrao", nome: "Ofício padrão", tipoDocumento: "oficio",
        corpoTemplate: "Prezado {{destinatario}}.",
      });
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/documento-modelos",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          chave: "oficio_padrao", nome: "Ofício padrão", "tipo-documento": "oficio",
          "corpo-template": "Prezado {{destinatario}}.",
        }),
      }),
    );
    expect((modelo as unknown as { ativo: boolean }).ativo).toBe(true);
    expect(result.current.estado).toBe("ocioso");
  });

  it("sem token -> lança sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useCriarModeloDocumento(null));
    await expect(
      result.current.criar({ chave: "x", nome: "x", tipoDocumento: "oficio", corpoTemplate: "x" }),
    ).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("chave duplicada (400) fica em `erro`, nunca engolido", async () => {
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 400,
      json: async () => ({ erro: "requisicao invalida" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useCriarModeloDocumento("tok"));

    await act(async () => {
      await expect(
        result.current.criar({ chave: "oficio_padrao", nome: "x", tipoDocumento: "oficio", corpoTemplate: "x" }),
      ).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toBe("requisicao invalida");
  });
});
