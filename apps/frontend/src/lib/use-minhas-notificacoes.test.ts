import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { useMinhasNotificacoes } from "./use-minhas-notificacoes";

const respostaFake = {
  notificacoes: [
    {
      id: "n1",
      categoria: "norma_publicada",
      assunto: "A sua proposicao virou lei",
      corpo: "Ementa: ...",
      "objeto-tipo": "proposicao",
      "objeto-id": "p1",
      "criado-em": "2026-07-19T12:00:00Z",
      "lida-em": null,
    },
  ],
  "nao-lidas": 1,
};

describe("useMinhasNotificacoes", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/meu/notificacoes e cameliza", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMinhasNotificacoes("tok"));
    expect(result.current.estado).toBe("carregando");
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.dados?.naoLidas).toBe(1);
    expect(result.current.dados?.notificacoes[0].objetoId).toBe("p1");
    const chamada = vi.mocked(global.fetch).mock.calls[0];
    expect(chamada[0]).toBe("/api/meu/notificacoes");
    expect(new Headers(chamada[1]?.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("sem token -> 'erro' sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useMinhasNotificacoes(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("resposta não-ok -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 401 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMinhasNotificacoes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("recarregar() refaz o GET e substitui os dados", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => respostaFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMinhasNotificacoes("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));

    const depois = { notificacoes: [], "nao-lidas": 0 };
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => depois }) as Response) as unknown as typeof fetch;
    await act(async () => {
      await result.current.recarregar();
    });
    expect(result.current.dados?.naoLidas).toBe(0);
  });
});
