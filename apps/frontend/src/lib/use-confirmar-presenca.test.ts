import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useConfirmarPresenca } from "./use-confirmar-presenca";

const reciboFake = { id: "pe1" };

describe("useConfirmarPresenca", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa sem corpo e cameliza o recibo devolvido", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => reciboFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useConfirmarPresenca("tok"));

    let recibo;
    await act(async () => {
      recibo = await result.current.confirmar("s1");
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/sessoes/s1/presenca/confirmar",
      expect.objectContaining({ method: "POST" }),
    );
    const [, init] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(init.body).toBeUndefined();
    expect((recibo as unknown as { id: string }).id).toBe("pe1");
    expect(result.current.estado).toBe("ocioso");
  });

  it("sem token -> lança sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useConfirmarPresenca(null));
    await expect(result.current.confirmar("s1")).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("404 (sem cadastro vinculado) fica em 'erro', nunca engolido", async () => {
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 404,
      json: async () => ({ erro: "sessao nao encontrada, ou vereador sem cadastro vinculado neste ente" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useConfirmarPresenca("tok"));

    await act(async () => {
      await expect(result.current.confirmar("s1")).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toMatch(/sem cadastro vinculado/);
  });

  it("chamada concorrente enquanto 'enviando' lança sincronamente", async () => {
    let resolverFetch!: (r: Response) => void;
    global.fetch = vi.fn(
      () => new Promise<Response>((resolve) => (resolverFetch = resolve)),
    ) as unknown as typeof fetch;
    const { result } = renderHook(() => useConfirmarPresenca("tok"));

    let primeira!: Promise<unknown>;
    act(() => {
      primeira = result.current.confirmar("s1");
    });
    await expect(result.current.confirmar("s1")).rejects.toThrow(/envio em andamento/);

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
    const { result, unmount } = renderHook(() => useConfirmarPresenca("tok"));

    let promessa!: Promise<unknown>;
    act(() => {
      promessa = result.current.confirmar("s1");
    });
    unmount();

    await act(async () => {
      resolverFetch({ ok: true, json: async () => reciboFake } as Response);
      await promessa;
    });
  });
});
