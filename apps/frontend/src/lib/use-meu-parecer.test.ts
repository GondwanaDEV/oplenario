import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { useMeuParecer } from "./use-meu-parecer";

// Mirror EXATO de use-parecer-editor.test.ts (3 estados, reset em render-time ao trocar `id`,
// camelizarChaves, `recarregar` extra) — a UNICA diferenca e' a URL esperada (borda /meu).

const respostaFake = {
  id: "p1",
  "objeto-tipo": "proposicao",
  "objeto-id": "obj1",
  "comissao-id": "c1",
  "relator-id": null,
  "voto-relator": null,
  estado: "em_elaboracao",
  "template-id": "t1",
  "lock-version": 0,
  "criado-em": "2026-05-01T10:00:00Z",
  objeto: { id: "obj1", tipo: "projeto_lei", ano: 2026, sequencial: 42, "urn-lex": "urn:x", ementa: "X" },
  relatorio: "",
  analise: "",
  "texto-estado": "vazio",
  "texto-numero-versao": null,
};

const respostaFake2 = { ...respostaFake, id: "p2", "objeto-id": "obj2" };

describe("useMeuParecer", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/meu/pareceres/:id e cameliza", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuParecer("tok", "p1"));
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.comissaoId).toBe("c1");
    expect(result.current.dados?.objeto?.urnLex).toBe("urn:x");
    expect(global.fetch).toHaveBeenCalledWith(
      "/api/meu/pareceres/p1",
      expect.objectContaining({ headers: { Authorization: "Bearer tok" } }),
    );
  });

  it("id nulo -> 'pronto' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuParecer("tok", null));
    expect(result.current.estado).toBe("pronto");
    expect(result.current.dados).toBeNull();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("sem token -> nao chama fetch (fica 'carregando')", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuParecer(null, "p1"));
    expect(result.current.estado).toBe("carregando");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("404 -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 404 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuParecer("tok", "p1"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("troca de id -> reseta pra 'carregando' no render, sem mostrar dados antigos", async () => {
    global.fetch = vi.fn(async (url: string) => {
      const id = url.split("/").at(-1);
      return { ok: true, json: async () => (id === "p2" ? respostaFake2 : respostaFake) } as Response;
    }) as unknown as typeof fetch;
    const { result, rerender } = renderHook(({ id }) => useMeuParecer("tok", id), {
      initialProps: { id: "p1" },
    });
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.id).toBe("p1");

    rerender({ id: "p2" });
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.id).toBe("p2");
  });

  it("recarregar() refaz o fetch do mesmo id (pós-emissão, sem trocar id)", async () => {
    let chamadas = 0;
    global.fetch = vi.fn(async () => {
      chamadas += 1;
      return { ok: true, json: async () => ({ ...respostaFake, estado: chamadas === 1 ? "em_elaboracao" : "aprovado" }) } as Response;
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuParecer("tok", "p1"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.estado).toBe("em_elaboracao");

    await act(async () => {
      await result.current.recarregar();
    });
    await waitFor(() => expect(result.current.dados?.estado).toBe("aprovado"));
    expect(global.fetch).toHaveBeenCalledTimes(2);
  });
});
