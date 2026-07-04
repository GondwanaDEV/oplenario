import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useMesa } from "./use-mesa";

const mesaFake = {
  complianceTce: { resumo: {}, emAberto: [], remessasRecentes: [] },
  tramitacao: { total: 2, porEstado: [{ estado: "protocolada", n: 2 }] },
  pendencias: { abertas: 0, vencidas: 0, pendentes: 0 },
  sessoes: { emCurso: 0, naoRealizadas: 0, porSituacao: [] },
  presencaResumo: { mediaPercentual: 78, sessoesConsideradas: 10, membrosDaCasa: 43 },
  esicCumprimento: { totalEncerrados: 49, cumpridosNoPrazo: 47, percentual: 96 },
  relatoresPendentes: { itens: [] },
  lacunas: ["ciencia_convocacao", "assinatura_autografo", "incidente_grant_lgpd"],
};

describe("useMesa", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca as 4 rotas em paralelo e monta o estado 'pronto'", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const corpo = url.includes("/paineis/mesa")
        ? mesaFake
        : url.includes("/paineis/tramitacao")
          ? { itens: [] }
          : url.includes("/paineis/pendencias")
            ? { pendencias: [] }
            : { sessoes: [] };
      return { ok: true, json: async () => corpo } as Response;
    }) as unknown as typeof fetch;

    const { result } = renderHook(() => useMesa("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.mesa?.tramitacao.total).toBe(2);
    expect(result.current.tramitacaoItens).toEqual([]);
  });

  it("chamada principal falha -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMesa("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("chamada de detalhe (tramitação) falha isoladamente -> mesa continua 'pronto', detalhe vira null", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url.includes("/paineis/tramitacao")) return { ok: false, status: 500 } as Response;
      const corpo = url.includes("/paineis/mesa")
        ? mesaFake
        : url.includes("/paineis/pendencias")
          ? { pendencias: [] }
          : { sessoes: [] };
      return { ok: true, json: async () => corpo } as Response;
    }) as unknown as typeof fetch;

    const { result } = renderHook(() => useMesa("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.tramitacaoItens).toBeNull();
    expect(result.current.mesa?.tramitacao.total).toBe(2);
  });

  it("card relatoresPendentes vem com o sentinel de degradação -> relatoresPendentes vira null", async () => {
    const mesaComSentinel = { ...mesaFake, relatoresPendentes: { indisponivel: true } };
    global.fetch = vi.fn(async (url: string) => {
      const corpo = url.includes("/paineis/mesa")
        ? mesaComSentinel
        : url.includes("/paineis/tramitacao")
          ? { itens: [] }
          : url.includes("/paineis/pendencias")
            ? { pendencias: [] }
            : { sessoes: [] };
      return { ok: true, json: async () => corpo } as Response;
    }) as unknown as typeof fetch;

    const { result } = renderHook(() => useMesa("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.relatoresPendentes).toBeNull();
  });
});
