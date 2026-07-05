import { afterEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useEncarregado } from "./use-encarregado";

// Task 2.2 (Fatia A2.2, Portal do Cidadão) — hook cliente que busca GET
// /api/portal/casa/{ente}/encarregado. Mesmo padrão de use-materias.ts (A2.1): 3 estados, degradação
// por seção, sem vazamento cross-tenant.

describe("useEncarregado", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /portal/casa/{ente}/encarregado e monta o estado 'pronto' com o encarregado", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ nome: "Mariana Couto", rotulo: "Encarregada de Dados (DPO)", email: "encarregado.dados@cmfor.ce.gov.br" }),
    })) as unknown as typeof fetch;

    const { result } = renderHook(() => useEncarregado("fortaleza"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.encarregado).toEqual({
      nome: "Mariana Couto",
      rotulo: "Encarregada de Dados (DPO)",
      email: "encarregado.dados@cmfor.ce.gov.br",
    });
  });

  it("chama a URL correta", async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({ nome: "x", rotulo: "y", email: "z" }) })) as unknown as typeof fetch;
    global.fetch = fetchMock;

    renderHook(() => useEncarregado("fortaleza"));
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url] = (fetchMock as unknown as ReturnType<typeof vi.fn>).mock.calls[0] as [string];
    expect(url).toBe("/api/portal/casa/fortaleza/encarregado");
  });

  it("fetch falho -> estado 'erro', encarregado null (degradação isolada, nunca lança)", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 })) as unknown as typeof fetch;
    const { result } = renderHook(() => useEncarregado("fortaleza"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.encarregado).toBeNull();
  });

  it("troca de ente reseta o estado (sem vazamento cross-tenant)", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ nome: "A", rotulo: "R", email: "a@x" }) })) as unknown as typeof fetch;
    const { result, rerender } = renderHook(({ ente }) => useEncarregado(ente), {
      initialProps: { ente: "fortaleza" },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    let liberar: () => void = () => {};
    const pendente = new Promise<{ ok: boolean; json: () => Promise<unknown> }>((res) => {
      liberar = () => res({ ok: true, json: async () => ({ nome: "B", rotulo: "R", email: "b@x" }) });
    });
    global.fetch = vi.fn(() => pendente) as unknown as typeof fetch;

    rerender({ ente: "aquiraz" });
    expect(result.current.estado).toBe("carregando");
    expect(result.current.encarregado).toBeNull();

    liberar();
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
  });
});
