import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useProposicoes } from "./use-proposicoes";

const filtrosBase = { pagina: 1, tamanho: 20, ordenarPor: "atualizado_em", ordenarDir: "desc" as const };

const respostaFake = {
  itens: [{ id: "1", tipo: "projeto_lei", ano: 2026, sequencial: 42, "urn-lex": "urn:x", ementa: "X", estado: "protocolada", "atualizado-em": "2026-05-21T10:00:00Z" }],
  total: 1, pagina: 1, "tamanho-pagina": 20,
};

describe("useProposicoes", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/legislativo/proposicoes e cameliza o payload", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicoes("tok", filtrosBase));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.total).toBe(1);
    expect(result.current.dados?.itens[0].urnLex).toBe("urn:x");
    expect(result.current.dados?.tamanhoPagina).toBe(20);
  });

  it("monta a querystring a partir dos filtros", async () => {
    let urlCapturada = "";
    global.fetch = vi.fn(async (url: string) => {
      urlCapturada = url;
      return { ok: true, json: async () => respostaFake } as Response;
    }) as unknown as typeof fetch;
    renderHook(() => useProposicoes("tok", { ...filtrosBase, busca: "hortas", tipo: "projeto_lei", pagina: 2 }));
    await waitFor(() => expect(urlCapturada).toContain("busca=hortas"));
    expect(urlCapturada).toContain("tipo=projeto_lei");
    expect(urlCapturada).toContain("pagina=2");
  });

  it("falha de rede -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicoes("tok", filtrosBase));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> 'erro' já na primeira renderização, sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useProposicoes(null, filtrosBase));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("refetch quando os filtros mudam (ex.: página)", async () => {
    let chamadas = 0;
    global.fetch = vi.fn(async () => {
      chamadas++;
      return { ok: true, json: async () => respostaFake } as Response;
    }) as unknown as typeof fetch;
    const { rerender } = renderHook(({ filtros }) => useProposicoes("tok", filtros), {
      initialProps: { filtros: filtrosBase },
    });
    await waitFor(() => expect(chamadas).toBe(1));
    rerender({ filtros: { ...filtrosBase, pagina: 2 } });
    await waitFor(() => expect(chamadas).toBe(2));
  });
});
