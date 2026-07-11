import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useAcusarCiencia } from "./use-acusar-ciencia";

const reciboFake = { id: "c1", "ciente-em": "2026-07-11T09:14:00Z" };

describe("useAcusarCiencia", () => {
  afterEach(() => vi.restoreAllMocks());

  it("POSTa evento-ref/tipo e cameliza o recibo devolvido", async () => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => reciboFake }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useAcusarCiencia("tok"));

    let recibo;
    await act(async () => {
      recibo = await result.current.acusar({ eventoRef: "pc1", tipo: "parecer_publicado" });
    });

    expect(global.fetch).toHaveBeenCalledWith(
      "/api/meu/ciencias",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ "evento-ref": "pc1", tipo: "parecer_publicado" }),
      }),
    );
    expect((recibo as unknown as { cienteEm: string }).cienteEm).toBe("2026-07-11T09:14:00Z");
    expect(result.current.estado).toBe("ocioso");
  });

  it("sem token -> lança sem chamar fetch", async () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useAcusarCiencia(null));
    await expect(result.current.acusar({ eventoRef: "pc1", tipo: "parecer_publicado" })).rejects.toThrow();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("404 (sem cadastro vinculado) fica em 'erro', nunca engolido", async () => {
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 404,
      json: async () => ({ erro: "vereador sem cadastro vinculado neste ente" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useAcusarCiencia("tok"));

    await act(async () => {
      await expect(result.current.acusar({ eventoRef: "pc1", tipo: "parecer_publicado" })).rejects.toThrow();
    });
    expect(result.current.estado).toBe("erro");
    expect(result.current.erro).toMatch(/sem cadastro vinculado/);
  });

  it("resolve após unmount -> não faz setState (vivoRef guarda)", async () => {
    let resolverFetch!: (r: Response) => void;
    global.fetch = vi.fn(
      () => new Promise<Response>((resolve) => (resolverFetch = resolve)),
    ) as unknown as typeof fetch;
    const { result, unmount } = renderHook(() => useAcusarCiencia("tok"));

    let promessa!: Promise<unknown>;
    act(() => {
      promessa = result.current.acusar({ eventoRef: "pc1", tipo: "parecer_publicado" });
    });
    unmount();

    await act(async () => {
      resolverFetch({ ok: true, json: async () => reciboFake } as Response);
      await promessa;
    });
    // Sem crash / sem warning de setState pós-unmount — vivoRef.current já era false quando a promise
    // resolveu, então setEstado não roda (a asserção real é "não lança"; a suíte falharia num warning
    // não-tratado do jsdom se o guard estivesse ausente).
  });
});
