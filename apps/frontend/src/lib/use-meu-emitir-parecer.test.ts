import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useMeuEmitirParecer } from "./use-meu-emitir-parecer";

// Mirror EXATO de use-emitir-parecer.test.ts, POST /api/meu/pareceres/:id/emissao (borda /meu, gate
// 'vereador' + posse). `lockVersion` OBRIGATÓRIO no corpo (CAS otimista real).

const respostaFake = {
  id: "p1",
  "objeto-tipo": "proposicao",
  "objeto-id": "obj1",
  "comissao-id": "c1",
  "voto-relator": "favoravel_com_emendas",
  estado: "aprovado",
  "template-id": "t1",
  "lock-version": 2,
  "criado-em": "2026-05-01T10:00:00Z",
  relatorio: "r",
  analise: "a",
  "texto-estado": "vigente",
  "texto-numero-versao": 2,
};

describe("useMeuEmitirParecer", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POST /api/meu/pareceres/:id/emissao com votoRelator+lockVersion kebab; cameliza a resposta", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuEmitirParecer("tok", "p1"));

    let resposta;
    await act(async () => {
      resposta = await result.current.emitir({ votoRelator: "favoravel_com_emendas", lockVersion: 1 });
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/meu/pareceres/p1/emissao",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ "voto-relator": "favoravel_com_emendas", "lock-version": 1 }),
      }),
    );
    expect((resposta as unknown as { estado: string }).estado).toBe("aprovado");
    expect(result.current.estado).toBe("ocioso");
  });

  it("conflito de CAS (409, lock-version desatualizado) chega visível em `erro`", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: false, status: 409, json: async () => ({ erro: "parecer foi alterado por outra pessoa" }) }) as Response,
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuEmitirParecer("tok", "p1"));

    await act(async () => {
      await expect(result.current.emitir({ votoRelator: "favoravel", lockVersion: 0 })).rejects.toThrow();
    });

    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toBe("parecer foi alterado por outra pessoa");
  });

  it("falha de rede crua -> mensagem genérica", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("network down");
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuEmitirParecer("tok", "p1"));

    await act(async () => {
      await expect(result.current.emitir({ votoRelator: "favoravel", lockVersion: 0 })).rejects.toThrow();
    });

    expect(result.current.erro).toBe("falha de rede — tente novamente");
  });

  it("sem token -> lança sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuEmitirParecer(null, "p1"));
    await expect(result.current.emitir({ votoRelator: "favoravel", lockVersion: 0 })).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("guard de reentrância: 2º envio síncrono lança sem duplicar fetch", async () => {
    let resolvePrimeiro: (() => void) | null = null;
    global.fetch = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          resolvePrimeiro = () => resolve({ ok: true, json: async () => respostaFake } as Response);
        }),
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuEmitirParecer("tok", "p1"));

    let primeiraPromise!: Promise<unknown>;
    act(() => {
      primeiraPromise = result.current.emitir({ votoRelator: "favoravel", lockVersion: 0 });
    });
    await expect(result.current.emitir({ votoRelator: "favoravel", lockVersion: 0 })).rejects.toThrow();
    expect(global.fetch).toHaveBeenCalledTimes(1);

    await act(async () => {
      resolvePrimeiro?.();
      await primeiraPromise;
    });
  });
});
