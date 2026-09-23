import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { useModeloDocumentoDetalhe } from "./use-modelo-documento-detalhe";

// Mirror EXATO de use-documento-detalhe.test.ts (3 estados, reset em render-time ao trocar `id`,
// camelizarChaves, `recarregar` pós-mutação) — aqui `id` começa NULO (a lista não abre editor nenhum até o
// usuário clicar "editar").

const respostaFake = {
  id: "m1",
  chave: "oficio_padrao",
  nome: "Ofício padrão",
  "tipo-documento": "oficio",
  "corpo-template": "Prezado {{destinatario}}.",
  ativo: true,
  "lock-version": 0,
};

const respostaFake2 = { ...respostaFake, id: "m2" };

describe("useModeloDocumentoDetalhe", () => {
  afterEach(() => vi.restoreAllMocks());

  it("id nulo -> 'pronto' sem chamar fetch (lista sem editor aberto)", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useModeloDocumentoDetalhe("tok", null));
    expect(result.current.estado).toBe("pronto");
    expect(result.current.dados).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("busca /api/legislativo/documento-modelos/:id e cameliza quando id aparece", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useModeloDocumentoDetalhe("tok", "m1"));
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.corpoTemplate).toBe("Prezado {{destinatario}}.");
    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/legislativo/documento-modelos/m1");
    expect(new Headers(chamada[1]?.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("sem token, com id -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useModeloDocumentoDetalhe(null, "m1"));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("404 -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useModeloDocumentoDetalhe("tok", "m1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("troca de id (null -> id real) -> reseta pra 'carregando', sem dados antigos", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const id = url.split("/").at(-1);
      return { ok: true, json: async () => (id === "m2" ? respostaFake2 : respostaFake) } as Response;
    }) as unknown as typeof fetch;
    const { result, rerender } = renderHook(({ id }) => useModeloDocumentoDetalhe("tok", id), {
      initialProps: { id: null as string | null },
    });
    expect(result.current.estado).toBe("pronto");
    expect(result.current.dados).toBeNull();

    rerender({ id: "m2" });
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.id).toBe("m2");
  });

  it("recarregar() refaz o fetch do mesmo id (pós-PATCH, sem trocar id)", async () => {
    let chamadas = 0;
    global.fetch = vi.fn(async () => {
      chamadas += 1;
      return {
        ok: true,
        json: async () => ({ ...respostaFake, "lock-version": chamadas === 1 ? 0 : 1 }),
      } as Response;
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useModeloDocumentoDetalhe("tok", "m1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.lockVersion).toBe(0);

    await act(async () => {
      await result.current.recarregar();
    });
    await waitFor(() => expect(result.current.dados?.lockVersion).toBe(1));
    expect(global.fetch).toHaveBeenCalledTimes(2);
  });
});
