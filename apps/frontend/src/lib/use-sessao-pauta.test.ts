import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useSessaoPauta } from "./use-sessao-pauta";

const sessaoFake = {
  id: "s1", "sessao-legislativa-id": "sl1", "tipo-sessao": "ordinaria", "numero-sequencial": 15,
  estado: "agendada", modalidade: "presencial", delibera: true, "transmite-publica": true,
  "gera-ata-regimental": true, "permite-voto-secreto": false, "permite-modalidade-remota": false,
  "agendada-para": "2026-06-24T17:00:00Z", "aberta-em": null, "encerrada-em": null,
  "motivo-nao-realizada": null,
};
const pautaFake = {
  "sessao-id": "s1",
  itens: [{ id: "i1", fase: "expediente", "tipo-item": "leitura", "texto-descricao": "Leitura da ata", ordem: 1 }],
};

function mockFetchPorUrl(mapa: Record<string, unknown>) {
  return vi.fn(async (url: string) => {
    const chave = Object.keys(mapa).find((k) => url.includes(k));
    if (!chave) return { ok: false, status: 404 } as Response;
    return { ok: true, json: async () => mapa[chave] } as Response;
  }) as unknown as typeof fetch;
}

describe("useSessaoPauta", () => {
  afterEach(() => vi.restoreAllMocks());

  it("sessaoId nulo -> 'pronto' com sessao/pauta null, sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useSessaoPauta("tok", null));
    expect(result.current.estado).toBe("pronto");
    expect(result.current.sessao).toBeNull();
    expect(result.current.pauta).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("busca sessão + pauta em paralelo e cameliza -> 'pronto'", async () => {
    global.fetch = mockFetchPorUrl({
      "/api/sessoes/s1/pauta": pautaFake,
      "/api/sessoes/s1": sessaoFake,
    });
    const { result } = renderHook(() => useSessaoPauta("tok", "s1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.sessao?.tipoSessao).toBe("ordinaria");
    expect(result.current.pauta?.itens[0]).toEqual({
      id: "i1", fase: "expediente", tipoItem: "leitura", textoDescricao: "Leitura da ata", ordem: 1,
    });
  });

  it("uma das duas chamadas falha -> 'erro'", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url.includes("/pauta")) return { ok: false, status: 500 } as Response;
      return { ok: true, json: async () => sessaoFake } as Response;
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useSessaoPauta("tok", "s1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("troca de sessaoId volta pra 'carregando' e refaz a busca", async () => {
    global.fetch = mockFetchPorUrl({
      "/api/sessoes/s1/pauta": pautaFake,
      "/api/sessoes/s1": sessaoFake,
      "/api/sessoes/s2/pauta": { "sessao-id": "s2", itens: [] },
      "/api/sessoes/s2": { ...sessaoFake, id: "s2" },
    });
    const { result, rerender } = renderHook(({ id }: { id: string }) => useSessaoPauta("tok", id), {
      initialProps: { id: "s1" },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    rerender({ id: "s2" });
    await waitFor(() => expect(result.current.sessao?.id).toBe("s2"));
  });

  it("sem token -> 'erro' já na primeira renderização, sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useSessaoPauta(null, "s1"));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
