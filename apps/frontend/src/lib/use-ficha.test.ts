import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useFicha } from "./use-ficha";

// Task 3.2 (Fatia A2.3, Portal do Cidadão) — hook cliente da ficha pública: busca
// GET /api/portal/casa/{ente}/materias/{proposicaoId} (ficha) + .../comentarios EM PARALELO, mesmo
// DESVIO de SSR já documentado em use-materias.ts/use-encarregado.ts (fetch relativo não resolve em
// Server Component). As duas chamadas degradam DE FORMA INDEPENDENTE (Global Constraints — "degradação
// por seção"): comentários falhando nunca esvazia a ficha; ficha ausente (404) é o único caso que vira
// "erro" de página (não há o que mostrar sem a matéria).

function mockFetch(porUrl: (url: string) => { ok: boolean; json?: () => Promise<unknown> }) {
  return vi.fn(async (url: string) => {
    const r = porUrl(url);
    return { ok: r.ok, json: r.json ?? (async () => ({})) };
  }) as unknown as typeof fetch;
}

describe("useFicha", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca ficha + comentários em paralelo e monta 'pronto' com os dois", async () => {
    global.fetch = mockFetch((url) => {
      if (url.endsWith("/comentarios")) {
        return { ok: true, json: async () => [{ id: "c1", corpo: "Apoio.", "criado-em": "2026-06-01T00:00:00Z" }] };
      }
      return { ok: true, json: async () => ({ "proposicao-id": "p1", tipo: "projeto_lei", ano: 2026, sequencial: 42, estado: "em_comissoes" }) };
    });

    const { result } = renderHook(() => useFicha("fortaleza", "p1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.ficha).toEqual({ proposicaoId: "p1", tipo: "projeto_lei", ano: 2026, sequencial: 42, estado: "em_comissoes" });
    expect(result.current.comentarios).toEqual([{ id: "c1", corpo: "Apoio.", criadoEm: "2026-06-01T00:00:00Z" }]);
  });

  it("chama as URLs corretas (segmentos ente/materias/proposicaoId[/comentarios])", async () => {
    const fetchMock = mockFetch(() => ({ ok: true, json: async () => ({}) }));
    global.fetch = fetchMock;

    renderHook(() => useFicha("fortaleza", "p1"));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    const urls = (fetchMock as unknown as ReturnType<typeof vi.fn>).mock.calls.map((c) => c[0]);
    expect(urls).toContain("/api/portal/casa/fortaleza/materias/p1");
    expect(urls).toContain("/api/portal/casa/fortaleza/materias/p1/comentarios");
  });

  it("ficha ausente (404) -> estado 'erro' — mesmo que os comentários tenham vindo", async () => {
    global.fetch = mockFetch((url) => {
      if (url.endsWith("/comentarios")) return { ok: true, json: async () => [] };
      return { ok: false };
    });

    const { result } = renderHook(() => useFicha("fortaleza", "p1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.ficha).toBeNull();
  });

  it("comentários falhos -> ficha continua 'pronta', comentários = null (degradação isolada, nunca lança)", async () => {
    global.fetch = mockFetch((url) => {
      if (url.endsWith("/comentarios")) return { ok: false };
      return { ok: true, json: async () => ({ "proposicao-id": "p1", tipo: "projeto_lei", ano: 2026, sequencial: 42, estado: "em_comissoes" }) };
    });

    const { result } = renderHook(() => useFicha("fortaleza", "p1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.ficha).not.toBeNull();
    expect(result.current.comentarios).toBeNull();
  });

  it("troca de ente OU de proposicaoId reseta o estado (sem vazamento cross-matéria/tenant)", async () => {
    global.fetch = mockFetch(() => ({
      ok: true,
      json: async () => ({ "proposicao-id": "p1", tipo: "projeto_lei", ano: 2026, sequencial: 1, estado: "protocolada" }),
    }));

    const { result, rerender } = renderHook(({ ente, id }) => useFicha(ente, id), {
      initialProps: { ente: "fortaleza", id: "p1" },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    let liberar: () => void = () => {};
    const pendente = new Promise<{ ok: boolean; json: () => Promise<unknown> }>((res) => {
      liberar = () => res({ ok: true, json: async () => ({}) });
    });
    global.fetch = vi.fn(() => pendente) as unknown as typeof fetch;

    rerender({ ente: "fortaleza", id: "p2" });
    expect(result.current.estado).toBe("carregando");
    expect(result.current.ficha).toBeNull();
    expect(result.current.comentarios).toBeNull();

    liberar();
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
  });
});
