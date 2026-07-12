import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useMeuVoto } from "./use-meu-voto";

const reciboFake = { id: "v1" };

describe("useMeuVoto", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa {voto} e cameliza o recibo devolvido", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => reciboFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuVoto("tok"));

    let recibo;
    await act(async () => {
      recibo = await result.current.votar("s1", "vot1", "sim");
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/sessoes/s1/votacoes/vot1/meu-voto",
      expect.objectContaining({ method: "POST", body: JSON.stringify({ voto: "sim" }) }),
    );
    expect((recibo as unknown as { id: string }).id).toBe("v1");
    expect(result.current.estado).toBe("ocioso");
  });

  it("sem token -> lança sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuVoto(null));
    await expect(result.current.votar("s1", "vot1", "sim")).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("403 vira a mensagem genérica 'Não é possível votar agora.' (nunca detalha a precondição)", async () => {
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 403,
      json: async () => ({ erro: "autorizacao negada" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuVoto("tok"));

    await act(async () => {
      await expect(result.current.votar("s1", "vot1", "sim")).rejects.toThrow("Não é possível votar agora.");
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toBe("Não é possível votar agora.");
  });

  it("404 (votação/sessão não encontrada) repassa a mensagem do servidor", async () => {
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 404,
      json: async () => ({ erro: "vereador sem cadastro vinculado, ou sessao/votacao nao encontrada" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuVoto("tok"));

    await act(async () => {
      await expect(result.current.votar("s1", "vot1", "sim")).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toMatch(/sem cadastro vinculado/);
  });

  it("falha de rede -> estado 'erro' com mensagem genérica", async () => {
    global.fetch = vi.fn(() => Promise.reject(new Error("network down"))) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuVoto("tok"));

    await act(async () => {
      await expect(result.current.votar("s1", "vot1", "sim")).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toMatch(/falha de rede/);
  });

  it("chamada concorrente enquanto 'enviando' lança sincronamente", async () => {
    let resolverFetch!: (r: Response) => void;
    global.fetch = vi.fn(
      () => new Promise<Response>((resolve) => (resolverFetch = resolve)),
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useMeuVoto("tok"));

    let primeira!: Promise<unknown>;
    act(() => {
      primeira = result.current.votar("s1", "vot1", "sim");
    });
    await expect(result.current.votar("s1", "vot1", "sim")).rejects.toThrow(/envio em andamento/);

    await act(async () => {
      resolverFetch({ ok: true, json: async () => reciboFake } as Response);
      await primeira;
    });
  });

  it("resolve após unmount -> não faz setState (vivoRef guarda)", async () => {
    let resolverFetch!: (r: Response) => void;
    global.fetch = vi.fn(
      () => new Promise<Response>((resolve) => (resolverFetch = resolve)),
    ) as unknown as typeof fetch;
    const { result, unmount } = renderHook(() => useMeuVoto("tok"));

    let promessa!: Promise<unknown>;
    act(() => {
      promessa = result.current.votar("s1", "vot1", "sim");
    });
    unmount();

    await act(async () => {
      resolverFetch({ ok: true, json: async () => reciboFake } as Response);
      await promessa;
    });
  });
});
