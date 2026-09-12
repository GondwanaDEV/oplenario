import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useDetalheVotacao } from "./use-detalhe-votacao";

describe("useDetalheVotacao", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
  });

  it("sem votacaoId (nenhuma votação aberta) -> 'ocioso', nunca 'erro', sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useDetalheVotacao("s1", null, "tok"));
    expect(result.current.estado).toBe("ocioso");
    expect(result.current.dados).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("busca /api/sessoes/:id/votacoes/:votacaoId e cameliza", async () => {
    global.fetch = vi.fn(
      async () =>
        ({
          ok: true,
          json: async () => ({
            "objeto-tipo": "proposicao",
            proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 42, ementa: "Ementa real da matéria" },
          }),
        }) as Response,
    ) as unknown as typeof fetch;

    const { result } = renderHook(() => useDetalheVotacao("s1", "vt1", "tok"));
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados).toEqual({
      objetoTipo: "proposicao",
      proposicao: { tipo: "projeto_lei", ano: 2026, sequencial: 42, ementa: "Ementa real da matéria" },
    });
    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/sessoes/s1/votacoes/vt1");
  });

  it("objeto-tipo sem proposição resolvida (emenda/parecer/requerimento) -> proposicao null, honesto", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ "objeto-tipo": "emenda", proposicao: null }) }) as Response,
    ) as unknown as typeof fetch;

    const { result } = renderHook(() => useDetalheVotacao("s1", "vt1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados).toEqual({ objetoTipo: "emenda", proposicao: null });
  });

  it("resposta não-ok -> 'erro', dados null", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useDetalheVotacao("s1", "vt1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.dados).toBeNull();
  });

  it("corpo malformado (sem objeto-tipo) -> 'erro', nunca um título inventado", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({ foo: "bar" }) }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useDetalheVotacao("s1", "vt1", "tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
    expect(result.current.dados).toBeNull();
  });

  it("troca de votacaoId (nova votação aberta) refaz a busca", async () => {
    global.fetch = vi.fn(
      async () => ({ ok: true, json: async () => ({ "objeto-tipo": "proposicao", proposicao: null }) }) as Response,
    ) as unknown as typeof fetch;
    const { result, rerender } = renderHook(({ vid }) => useDetalheVotacao("s1", vid, "tok"), {
      initialProps: { vid: "vt1" as string | null },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    rerender({ vid: "vt2" });
    await waitFor(() => expect(vi.mocked(global.fetch).mock.calls.length).toBe(2));
    expect(vi.mocked(global.fetch).mock.calls[1][0]).toBe("/api/sessoes/s1/votacoes/vt2");
  });
});
