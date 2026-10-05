import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { usePromulgarNorma, usePublicarNorma } from "./use-norma";
import type { NormaOut, PosAprovacaoOut } from "./contrato-legislativo.gen";

const resposta = (status: number, json: unknown) => ({ ok: status < 400, status, json: async () => json }) as Response;

const normaJson = (estado: string) => ({
  id: "n1",
  "proposicao-id": "p1",
  "tipo-norma": "lei",
  numero: 12,
  ano: 2026,
  urn: "urn:lex:br;ce;fortaleza:lei:2026-10-05;12",
  ementa: "Dispõe sobre X",
  estado,
  "promulgado-em": "2026-10-05T12:00:00Z",
  "publicado-em": estado === "publicada" ? "2026-10-06T12:00:00Z" : null,
  "veiculo-publicacao": estado === "publicada" ? "Diário Oficial do Município" : null,
  "lock-version": estado === "publicada" ? 1 : 0,
});

function capturar(status: number, json: unknown) {
  const chamada = { url: "", corpo: {} as Record<string, unknown> };
  global.fetch = vi.fn(async (u: string, init?: RequestInit) => {
    chamada.url = u;
    chamada.corpo = init?.body ? JSON.parse(init.body as string) : {};
    return resposta(status, json);
  }) as unknown as typeof fetch;
  return chamada;
}

describe("usePromulgarNorma", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa corpo vazio na proposição e devolve a pós-aprovação com a norma", async () => {
    const chamada = capturar(201, { autografo: null, "tramitacao-executiva": null, norma: normaJson("promulgada") });
    const { result } = renderHook(() => usePromulgarNorma("tok", "p1"));
    let res: PosAprovacaoOut | undefined;
    await act(async () => {
      res = await result.current.promulgar();
    });
    expect(chamada.url).toBe("/api/legislativo/proposicoes/p1/norma");
    expect(chamada.corpo).toEqual({});
    expect(res?.norma?.numero).toBe(12);
    expect(res?.norma?.tipoNorma).toBe("lei");
    expect(result.current.estado).toBe("ocioso");
  });

  it("409 traz o motivo do servidor para a tela", async () => {
    capturar(409, { erro: "ainda nao se promulga: o Executivo ainda nao respondeu ao autografo" });
    const { result } = renderHook(() => usePromulgarNorma("tok", "p1"));
    await act(async () => {
      await expect(result.current.promulgar()).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toBe("ainda nao se promulga: o Executivo ainda nao respondeu ao autografo");
  });
});

describe("usePublicarNorma", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa {lock-version, veiculo-publicacao} na norma e devolve a norma publicada", async () => {
    const chamada = capturar(200, normaJson("publicada"));
    const { result } = renderHook(() => usePublicarNorma("tok", "n1"));
    let res: NormaOut | undefined;
    await act(async () => {
      res = await result.current.publicar({ lockVersion: 0, veiculoPublicacao: "Diário Oficial do Município" });
    });
    expect(chamada.url).toBe("/api/legislativo/normas/n1/publicacao");
    expect(chamada.corpo).toEqual({ "lock-version": 0, "veiculo-publicacao": "Diário Oficial do Município" });
    expect(res?.estado).toBe("publicada");
    expect(res?.veiculoPublicacao).toBe("Diário Oficial do Município");
  });

  it("sem norma ainda, não chama o servidor", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => usePublicarNorma("tok", null));
    await act(async () => {
      await expect(result.current.publicar({ lockVersion: 0, veiculoPublicacao: "DOM" })).rejects.toThrow();
    });
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
