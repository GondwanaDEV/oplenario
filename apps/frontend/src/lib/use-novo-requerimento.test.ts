import { afterEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useNovoRequerimento } from "./use-novo-requerimento";

const ok = (json: unknown, status = 200) => ({ ok: true, status, json: async () => json }) as Response;
const fail = (status: number, json: unknown = {}) => ({ ok: false, status, json: async () => json }) as Response;

type Chamada = { url: string; init?: RequestInit };

function mockFetch(respostas: (c: Chamada) => Response) {
  const chamadas: Chamada[] = [];
  global.fetch = vi.fn(async (url: string, init?: RequestInit) => {
    chamadas.push({ url, init });
    return respostas({ url, init });
  }) as unknown as typeof fetch;
  return chamadas;
}

const modelos = { itens: [{ id: "m1", nome: "Requerimento de informação", campos: ["destinatario", "endereco_familia"] }] };

describe("useNovoRequerimento", () => {
  afterEach(() => vi.restoreAllMocks());

  it("carrega os modelos da Casa (campos com as chaves do modelo intactas)", async () => {
    mockFetch(() => ok(modelos));
    const { result } = renderHook(() => useNovoRequerimento("tok"));
    await waitFor(() => expect(result.current.estadoModelos).toBe("pronto"));
    expect(result.current.modelos).toEqual([{ id: "m1", nome: "Requerimento de informação", campos: ["destinatario", "endereco_familia"] }]);
  });

  it("prévia: POST com modelo-id e os campos SEM converter as chaves", async () => {
    const chamadas = mockFetch((c) => (c.url.endsWith("/previa") ? ok({ texto: "TEXTO" }) : ok(modelos)));
    const { result } = renderHook(() => useNovoRequerimento("tok"));
    await waitFor(() => expect(result.current.estadoModelos).toBe("pronto"));
    let texto = "";
    await act(async () => {
      texto = await result.current.previa({ modeloId: "m1", campos: { endereco_familia: "Rua X" } });
    });
    expect(texto).toBe("TEXTO");
    const post = chamadas.find((c) => c.url.endsWith("/api/meu/requerimentos/previa"))!;
    expect(JSON.parse(post.init!.body as string)).toEqual({ "modelo-id": "m1", campos: { endereco_familia: "Rua X" } });
  });

  it("protocolar: devolve o recibo camelizado; erro do servidor vira mensagem na tela", async () => {
    let falhar = false;
    mockFetch((c) => {
      if (c.url.endsWith("/api/meu/requerimentos"))
        return falhar
          ? fail(400, { erro: "campo nao-preenchido" })
          : ok({ "proposicao-id": "p1", ano: 2026, sequencial: 7, "urn-lex": "urn", estado: "protocolada", "assinatura-algoritmo": "STUB-ICP-v0" }, 201);
      return ok(modelos);
    });
    const { result } = renderHook(() => useNovoRequerimento("tok"));
    await waitFor(() => expect(result.current.estadoModelos).toBe("pronto"));
    let recibo: unknown;
    await act(async () => {
      recibo = await result.current.protocolar({ modeloId: "m1", campos: {}, ementa: "E" });
    });
    expect(recibo).toMatchObject({ proposicaoId: "p1", sequencial: 7, assinaturaAlgoritmo: "STUB-ICP-v0" });

    falhar = true;
    await act(async () => {
      await expect(result.current.protocolar({ modeloId: "m1", campos: {}, ementa: "E" })).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toBe("campo nao-preenchido");
  });

  it("falha de rede: mensagem genérica, nunca 'undefined'", async () => {
    global.fetch = vi.fn(async (url: string) => {
      if (url.endsWith("/api/meu/requerimentos/previa")) throw new TypeError("network");
      return ok(modelos);
    }) as unknown as typeof fetch;
    const { result } = renderHook(() => useNovoRequerimento("tok"));
    await waitFor(() => expect(result.current.estadoModelos).toBe("pronto"));
    await act(async () => {
      await expect(result.current.previa({ modeloId: "m1", campos: {} })).rejects.toThrow();
    });
    expect(result.current.erro).toBe("Falha de rede — tente novamente.");
  });

  it("modelos indisponíveis → estado de erro", async () => {
    mockFetch(() => fail(403));
    const { result } = renderHook(() => useNovoRequerimento("tok"));
    await waitFor(() => expect(result.current.estadoModelos).toBe("erro"));
  });
});
