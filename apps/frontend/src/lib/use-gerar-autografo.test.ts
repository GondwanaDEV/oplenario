import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useGerarAutografo } from "./use-gerar-autografo";

// Onda B Slice 7 — mirror de use-protocolar-documento.test.ts: id (aqui, proposicaoId) fixo no path,
// corpoKebab, distingue erro tratado de falha de rede.

const respostaFake = {
  autografo: {
    id: "a1",
    "proposicao-id": "1",
    numero: 22,
    ano: 2026,
    "destinatario-texto": "Prefeitura Municipal",
    "enviado-em": "2026-06-18T00:00:00Z",
    "prazo-resposta-em": null,
  },
  "tramitacao-executiva": { id: "te1", "autografo-id": "a1", estado: "aguardando", "lock-version": 0 },
};

describe("useGerarAutografo", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa o corpo (vazio por padrão) e cameliza a leitura composta devolvida", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useGerarAutografo("tok", "1"));

    let dados;
    await act(async () => {
      dados = await result.current.gerar();
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/proposicoes/1/autografo",
      expect.objectContaining({ method: "POST", body: JSON.stringify({}) }),
    );
    expect((dados as unknown as { autografo: { destinatarioTexto: string } }).autografo.destinatarioTexto).toBe(
      "Prefeitura Municipal",
    );
    expect(result.current.estado).toBe("ocioso");
  });

  it("envia prazoRespostaEm em kebab quando informado", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useGerarAutografo("tok", "1"));

    await act(async () => {
      await result.current.gerar({ prazoRespostaEm: "2026-07-10T00:00:00Z" });
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/proposicoes/1/autografo",
      expect.objectContaining({
        body: JSON.stringify({ "prazo-resposta-em": "2026-07-10T00:00:00Z" }),
      }),
    );
  });

  it("proposicaoId nulo -> lança sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useGerarAutografo("tok", null));
    await expect(result.current.gerar()).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("autógrafo duplicado (400) fica em 'erro', nunca engolido", async () => {
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 400,
      json: async () => ({ erro: "gerar-autografo: a proposicao ja tem autografo (UNIQUE por proposicao)" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useGerarAutografo("tok", "1"));

    await act(async () => {
      await expect(result.current.gerar()).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toMatch(/ja tem autografo/);
  });

  it("falha de rede crua -> mensagem genérica, estado 'erro'", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("network down");
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useGerarAutografo("tok", "1"));

    await act(async () => {
      await expect(result.current.gerar()).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toBe("falha de rede — tente novamente");
  });
});
