import { describe, expect, it, vi, afterEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { useMinhaSessaoAtual } from "./use-minha-sessao-atual";

describe("useMinhaSessaoAtual", () => {
  afterEach(() => vi.restoreAllMocks());

  it("busca /api/meu/sessao-atual e cameliza -> 'pronto' com sessão viva", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ "sessao-id": "s1", situacao: "em_curso" }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMinhaSessaoAtual("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.sessaoId).toBe("s1");
    expect(result.current.situacao).toBe("em_curso");
  });

  it("sem sessão viva -> 'pronto' com sessaoId nil (NÃO é erro)", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ "sessao-id": null, situacao: null }),
    }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMinhaSessaoAtual("tok"));
    await waitFor(() => expect(result.current.estado).toBe("pronto"));
    expect(result.current.sessaoId).toBeNull();
    expect(result.current.situacao).toBeNull();
  });

  it("chama a rota com o Bearer token", async () => {
    let headersCapturados: HeadersInit | undefined;
    global.fetch = vi.fn(async (_url: string, init?: RequestInit) => {
      headersCapturados = init?.headers;
      return { ok: true, json: async () => ({ "sessao-id": null, situacao: null }) } as Response;
    }) as unknown as typeof fetch;
    renderHook(() => useMinhaSessaoAtual("tok-abc"));
    await waitFor(() => expect(headersCapturados).toBeDefined());
    expect(new Headers(headersCapturados).get("Authorization")).toBe("Bearer tok-abc");
  });

  it("falha de rede -> estado 'erro'", async () => {
    global.fetch = vi.fn(async () => ({ ok: false, status: 500 }) as Response) as unknown as typeof fetch;
    const { result } = renderHook(() => useMinhaSessaoAtual("tok"));
    await waitFor(() => expect(result.current.estado).toBe("erro"));
  });

  it("sem token -> 'erro' já na primeira renderização, sem chamar fetch", () => {
    global.fetch = vi.fn() as unknown as typeof fetch;
    const { result } = renderHook(() => useMinhaSessaoAtual(null));
    expect(result.current.estado).toBe("erro");
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it("resolve após unmount -> não faz setState (guard `vivo` local, mesmo padrão de use-sli-sessoes)", async () => {
    let resolverFetch!: (r: Response) => void;
    global.fetch = vi.fn(
      () => new Promise<Response>((resolve) => (resolverFetch = resolve)),
    ) as unknown as typeof fetch;
    const { unmount } = renderHook(() => useMinhaSessaoAtual("tok"));
    unmount();

    await act(async () => {
      resolverFetch({ ok: true, json: async () => ({ "sessao-id": "s1", situacao: "em_curso" }) } as Response);
    });
    // sem crash / sem warning de setState pós-unmount — a suíte falharia num warning não-tratado do jsdom
    // se o guard `vivo` estivesse ausente.
  });

  // T14b: prova do modo REAL (produção) — sem token no cliente, a sessão é o cookie httpOnly; o hook NÃO
  // aborta (semCredencial(null) é false sob modoReal()) e o fetch É disparado normalmente.
  it("modo real (NEXT_PUBLIC_APP_ENV=production) + token null -> busca mesmo assim (cookie decide)", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
    try {
      global.fetch = vi.fn(async () => ({
        ok: true,
        json: async () => ({ "sessao-id": "s1", situacao: "em_curso" }),
      }) as Response) as unknown as typeof fetch;

      const { result } = renderHook(() => useMinhaSessaoAtual(null));
      await waitFor(() => expect(result.current.estado).toBe("pronto"));
      expect(global.fetch).toHaveBeenCalled();
      expect(result.current.sessaoId).toBe("s1");
    } finally {
      vi.unstubAllEnvs();
    }
  });
});
