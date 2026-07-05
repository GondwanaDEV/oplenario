import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useMaterias } from "./use-materias";
import type { MateriaOut } from "./contrato-portal.gen";

// Task 1.3 (Fatia A2.1, Portal do Cidadão) — hook cliente que busca GET /api/portal/casa/{ente}/materias
// via buscarPublico (0.3). DESVIO do plano (documentado no relatório da fatia): o plano original previa
// o fetch em page.tsx (Server Component) — um `fetch("/api/...")` relativo NÃO resolve em SSR (o rewrite
// same-origin do Next só funciona para requests do BROWSER; o servidor não tem "origem"). Mirror do
// padrão de use-mesa.ts (A1): fetch client-side, com os mesmos 3 estados (carregando/pronto/erro) +
// degradação por seção (fetch falho/vazio nunca derruba a página, Global Constraints do plano).

describe("useMaterias", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /portal/casa/{ente}/materias e monta o estado 'pronto' com os itens", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => [{ "proposicao-id": "1", tipo: "projeto_lei", ano: 2026, sequencial: 42 }],
    })) as unknown as typeof fetch;

    const { result } = renderHook(() => useMaterias("fortaleza"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.itens).toEqual([
      { proposicaoId: "1", tipo: "projeto_lei", ano: 2026, sequencial: 42 },
    ]);
  });

  it("chama a URL correta (segmentos do ente + 'materias')", async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    global.fetch = fetchMock;

    renderHook(() => useMaterias("fortaleza"));
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url] = (fetchMock as unknown as ReturnType<typeof vi.fn>).mock.calls[0] as [string];
    expect(url).toBe("/api/portal/casa/fortaleza/materias");
  });

  it("fetch falho -> estado 'erro', itens null (degradação por seção, nunca lança)", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 })) as unknown as typeof fetch;
    const { result } = renderHook(() => useMaterias("fortaleza"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.itens).toBeNull();
  });

  it("lista vazia -> estado 'pronto' com itens = [] (vazio é dado válido, não erro)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    const { result } = renderHook(() => useMaterias("fortaleza"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.itens).toEqual([]);
  });

  it("troca de ente reseta o estado (sem vazamento cross-tenant da câmara anterior)", async () => {
    const itensFortaleza: MateriaOut[] = [
      { proposicaoId: "1", tipo: "projeto_lei", ano: 2026, sequencial: 1 } as MateriaOut,
    ];
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => itensFortaleza })) as unknown as typeof fetch;
    global.fetch = fetchMock;

    const { result, rerender } = renderHook(({ ente }) => useMaterias(ente), {
      initialProps: { ente: "fortaleza" },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.itens).toEqual(itensFortaleza);

    // troca para outra câmara com um fetch que fica pendente — se o hook não resetar
    // sincronamente, o estado/itens de "fortaleza" continuariam visíveis sob "aquiraz".
    let liberar: () => void = () => {};
    const pendente = new Promise<{ ok: boolean; json: () => Promise<unknown> }>((res) => {
      liberar = () => res({ ok: true, json: async () => [] });
    });
    global.fetch = vi.fn(() => pendente) as unknown as typeof fetch;

    rerender({ ente: "aquiraz" });

    expect(result.current.estado).toBe("carregando");
    expect(result.current.itens).toBeNull();

    liberar();
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
  });
});
