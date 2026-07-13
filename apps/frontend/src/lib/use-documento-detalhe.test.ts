import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { useDocumentoDetalhe } from "./use-documento-detalhe";

// Mirror EXATO de use-parecer-editor.test.ts (3 estados, reset em render-time ao trocar `id`,
// camelizarChaves, `recarregar` pós-mutação) — aqui `id` começa NULO (a aba "Gerar documento" ainda não
// tem um documento até o POST de geração suceder).

const respostaFake = {
  id: "d1",
  "modelo-id": "m1",
  "tipo-documento": "oficio",
  assunto: "Convite",
  corpo: "Ao Prefeito.",
  estado: "rascunho",
  "protocolo-geral-id": null,
  "protocolo-numero": null,
  "protocolo-ano": null,
  "lock-version": 0,
  "criado-em": "2026-05-21T10:00:00Z",
};

const respostaFake2 = { ...respostaFake, id: "d2" };

describe("useDocumentoDetalhe", () => {
  afterEach(() => vi.restoreAllMocks());

  it("id nulo -> 'pronto' sem chamar fetch (fase de composição, ainda sem documento)", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useDocumentoDetalhe("tok", null));
    expect(result.current.estado).toBe("pronto");
    expect(result.current.dados).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("busca /api/legislativo/documentos/:id e cameliza quando id aparece", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useDocumentoDetalhe("tok", "d1"));
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.tipoDocumento).toBe("oficio");
    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/legislativo/documentos/d1");
    expect(new Headers(chamada[1]?.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("sem token, com id -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useDocumentoDetalhe(null, "d1"));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("404 -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useDocumentoDetalhe("tok", "d1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("troca de id (null -> id real, pós-geração) -> reseta pra 'carregando', sem dados antigos", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const id = url.split("/").at(-1);
      return { ok: true, json: async () => (id === "d2" ? respostaFake2 : respostaFake) } as Response;
    }) as unknown as typeof fetch;
    const { result, rerender } = renderHook(({ id }) => useDocumentoDetalhe("tok", id), {
      initialProps: { id: null as string | null },
    });
    expect(result.current.estado).toBe("pronto");
    expect(result.current.dados).toBeNull();

    rerender({ id: "d2" });
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.id).toBe("d2");
  });

  it("recarregar() refaz o fetch do mesmo id (pós-protocolar, sem trocar id)", async () => {
    let chamadas = 0;
    global.fetch = vi.fn(async () => {
      chamadas += 1;
      return {
        ok: true,
        json: async () => ({ ...respostaFake, estado: chamadas === 1 ? "rascunho" : "emitido" }),
      } as Response;
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useDocumentoDetalhe("tok", "d1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.estado).toBe("rascunho");

    await act(async () => {
      await result.current.recarregar();
    });
    await waitFor(() => expect(result.current.dados?.estado).toBe("emitido"));
    expect(global.fetch).toHaveBeenCalledTimes(2);
  });
});
