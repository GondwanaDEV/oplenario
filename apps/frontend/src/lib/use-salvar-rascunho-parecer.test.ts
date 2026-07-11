import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useSalvarRascunhoParecer } from "./use-salvar-rascunho-parecer";

// Mirror EXATO de use-editar-proposicao.test.ts (PATCH, corpoKebab, guard de reentrância, "erro
// clobbering" — a fatia anterior teve um CRÍTICO de erro do backend engolido, corrigido; não regredir).

const respostaFake = {
  id: "p1",
  "objeto-tipo": "proposicao",
  "objeto-id": "obj1",
  "comissao-id": "c1",
  estado: "em_elaboracao",
  "template-id": "t1",
  "lock-version": 1,
  "criado-em": "2026-05-01T10:00:00Z",
  relatorio: "novo relatório",
  analise: "nova análise",
  "texto-estado": "rascunho",
  "texto-numero-versao": 2,
};

describe("useSalvarRascunhoParecer", () => {
  afterEach(() => vi.restoreAllMocks());

  it("PATCH /api/legislativo/pareceres/:id com corpo kebab-case; cameliza a resposta", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useSalvarRascunhoParecer("tok", "p1"));

    let resposta;
    await act(async () => {
      resposta = await result.current.salvar({ relatorio: "novo relatório", analise: "nova análise" });
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/legislativo/pareceres/p1",
      expect.objectContaining({
        method: "PATCH",
        body: JSON.stringify({ relatorio: "novo relatório", analise: "nova análise" }),
      }),
    );
    expect((resposta as unknown as { textoEstado: string }).textoEstado).toBe("rascunho");
    expect(result.current.estado).toBe("ocioso");
    expect(result.current.erro).toBeNull();
  });

  it("erro do backend (corpo {erro}) fica visível em `erro`, nunca engolido", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: false, status: 409, json: async () => ({ erro: "parecer foi alterado por outra pessoa" }) }) as Response,
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useSalvarRascunhoParecer("tok", "p1"));

    await act(async () => {
      await expect(result.current.salvar({ relatorio: "x", analise: "y" })).rejects.toThrow();
    });

    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toBe("parecer foi alterado por outra pessoa");
  });

  it("falha de rede crua -> mensagem genérica, não sobrescreve erro já tratado do backend", async () => {
    global.fetch = vi.fn(async () => {
      throw new Error("network down");
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useSalvarRascunhoParecer("tok", "p1"));

    await act(async () => {
      await expect(result.current.salvar({ relatorio: "x", analise: "y" })).rejects.toThrow();
    });

    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toBe("falha de rede — tente novamente");
  });

  it("sem token -> lança sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useSalvarRascunhoParecer(null, "p1"));
    await expect(result.current.salvar({ relatorio: "x", analise: "y" })).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
