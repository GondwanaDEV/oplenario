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
            ? { pendencias: [], pendenciasTotal: 0 }
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
            ? { pendencias: [], pendenciasTotal: 0 }
            : { sessoes: [] };
      return { ok: true, json: async () => corpo } as Response;
    }) as unknown as typeof fetch;

    const { result } = renderHook(() => useMesa("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.relatoresPendentes).toBeNull();
  });

  // Fatia "truncamento-familia": GET /paineis/pendencias passa a publicar pendenciasTotal (o par
  // autoritativo, sem teto) junto da lista — o hook precisa expor os dois, não só a lista.
  it("pendenciasTotal (o par autoritativo de GET /paineis/pendencias) chega ao estado do hook", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const corpo = url.includes("/paineis/mesa")
        ? mesaFake
        : url.includes("/paineis/tramitacao")
          ? { itens: [] }
          : url.includes("/paineis/pendencias")
            ? { pendencias: [{ objetoTipo: "pedido_esic", objetoId: "p1", protocolo: "A", venceEm: "2099-01-01", estado: "pendente" }], pendenciasTotal: 4 }
            : { sessoes: [] };
      return { ok: true, json: async () => corpo } as Response;
    }) as unknown as typeof fetch;

    const { result } = renderHook(() => useMesa("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.pendenciasItens).toHaveLength(1);
    expect(result.current.pendenciasTotal).toBe(4);
  });

  it("sem token -> estado 'erro' já na primeira renderização (sem passar por 'carregando')", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useMesa(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  // B7b (bug crítico): jsonista (backend) emite JSON com chaves kebab-case VERBATIM a partir de keywords
  // Clojure (:por-estado -> "por-estado") — nunca camelCase. Os fixtures acima (mesaFake etc.) são
  // hand-rolled JÁ em camelCase, o que mascarou o bug: contra um payload REAL do backend, o acesso
  // `mesa.tramitacao.porEstado` (camelCase, como todo o código downstream já está escrito) resolvia pra
  // `undefined`. Este teste usa um payload kebab-case realista (inclusive aninhado) pra provar que
  // buscarOuNull cameliza antes do `as T`.
  it("payload kebab-case real do backend (jsonista) -> acesso camelCase resolve (não undefined)", async () => {
    const mesaKebab = {
      "compliance-tce": { resumo: {}, "em-aberto": [], "remessas-recentes": [] },
      tramitacao: { total: 47, "por-estado": [{ estado: "protocolada", n: 12 }] },
      pendencias: { abertas: 8, vencidas: 1, pendentes: 7 },
      sessoes: { "em-curso": 0, "nao-realizadas": 1, "por-situacao": [] },
      "presenca-resumo": { "media-percentual": 78, "sessoes-consideradas": 10, "membros-da-casa": 43 },
      "esic-cumprimento": { "total-encerrados": 49, "cumpridos-no-prazo": 47, percentual: 96 },
      "relatores-pendentes": { itens: [] },
      lacunas: ["ciencia_convocacao"],
    };
    global.fetch = vi.fn(async (url: string) => {
      const corpo = url.includes("/paineis/mesa")
        ? mesaKebab
        : url.includes("/paineis/tramitacao")
          ? { itens: [] }
          : url.includes("/paineis/pendencias")
            ? { pendencias: [], pendenciasTotal: 0 }
            : { sessoes: [] };
      return { ok: true, json: async () => corpo } as Response;
    }) as unknown as typeof fetch;

    const { result } = renderHook(() => useMesa("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.mesa?.tramitacao.porEstado).toEqual([{ estado: "protocolada", n: 12 }]);
    expect(result.current.mesa?.complianceTce).toEqual({ resumo: {}, emAberto: [], remessasRecentes: [] });
    expect(result.current.mesa?.presencaResumo).toEqual({
      mediaPercentual: 78,
      sessoesConsideradas: 10,
      membrosDaCasa: 43,
    });
  });
});
