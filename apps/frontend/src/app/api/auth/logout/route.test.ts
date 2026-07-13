import { NextRequest } from "next/server";
import { describe, it, expect, vi } from "vitest";
import { encerrarSessao as POST } from "./route";

const ORIGIN = "http://localhost:3000";
const SEGREDO = "segredo-opaco-da-sessao-abc123_XYZ";

type FetchFn = (url: string | URL | Request, init?: RequestInit) => Promise<Response>;
const fetchMock = (impl: FetchFn) => vi.fn<FetchFn>(impl);

function req(opts?: { sessao?: string | null }) {
  const request = new NextRequest(new URL("/api/auth/logout", ORIGIN), { method: "POST" });
  if (opts?.sessao !== undefined) {
    if (opts.sessao !== null) {
      request.cookies.set("sessao", opts.sessao);
    }
  } else {
    request.cookies.set("sessao", SEGREDO);
  }
  return request;
}

function setCookies(resp: Response): string[] {
  return resp.headers.getSetCookie();
}

function findCookie(resp: Response, name: string): string | undefined {
  return setCookies(resp).find((c) => c.startsWith(`${name}=`));
}

describe("POST /api/auth/logout — encerra sessão no backend + limpa cookie local (best-effort)", () => {
  it("cookie sessao presente: chama DELETE /auth/sessoes com o cookie encaminhado, limpa o cookie local, redireciona", async () => {
    const fetchImpl = fetchMock(async () => new Response(null, { status: 204 }));
    const resp = await POST(req(), { fetchImpl });

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const [url, init] = fetchImpl.mock.calls[0];
    expect(new URL(url.toString()).pathname).toBe("/auth/sessoes");
    expect(init?.method).toBe("DELETE");
    expect((init?.headers as Record<string, string>).cookie).toBe(`sessao=${SEGREDO}`);

    // Cookie local limpo (delete emite Set-Cookie com Max-Age=0 / data no passado, mesmo tuple name+path=/).
    const sessaoCookie = findCookie(resp, "sessao");
    expect(sessaoCookie).toBeTruthy();
    expect(sessaoCookie).toMatch(/Path=\//);

    // Redireciona (302/303) para algum destino de pós-logout.
    expect(resp.status).toBeGreaterThanOrEqual(300);
    expect(resp.status).toBeLessThan(400);
    expect(resp.headers.get("location")).toBeTruthy();
  });

  it("backend responde não-2xx → best-effort: ainda limpa o cookie e redireciona", async () => {
    const fetchImpl = fetchMock(async () => new Response(null, { status: 500 }));
    const resp = await POST(req(), { fetchImpl });

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const sessaoCookie = findCookie(resp, "sessao");
    expect(sessaoCookie).toBeTruthy();
    expect(resp.status).toBeGreaterThanOrEqual(300);
    expect(resp.status).toBeLessThan(400);
  });

  it("backend indisponível (fetch lança) → best-effort: ainda limpa o cookie e redireciona", async () => {
    const fetchImpl = fetchMock(async () => {
      throw new Error("ECONNREFUSED");
    });
    const resp = await POST(req(), { fetchImpl });

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const sessaoCookie = findCookie(resp, "sessao");
    expect(sessaoCookie).toBeTruthy();
    expect(resp.status).toBeGreaterThanOrEqual(300);
    expect(resp.status).toBeLessThan(400);
  });

  it("sem cookie sessao → NÃO chama o backend, ainda assim limpa o cookie e redireciona", async () => {
    const fetchImpl = vi.fn();
    const resp = await POST(req({ sessao: null }), { fetchImpl });

    expect(fetchImpl).not.toHaveBeenCalled();
    const sessaoCookie = findCookie(resp, "sessao");
    expect(sessaoCookie).toBeTruthy();
    expect(resp.status).toBeGreaterThanOrEqual(300);
    expect(resp.status).toBeLessThan(400);
  });

  it("proíbe opts.backend fora de ambiente de teste (superfície de SSRF) — lança em produção", async () => {
    const orig = process.env.NODE_ENV;
    try {
      vi.stubEnv("NODE_ENV", "production");
      await expect(
        POST(req(), {
          fetchImpl: fetchMock(async () => new Response(null, { status: 204 })),
          backend: "http://evil",
        }),
      ).rejects.toThrow();
    } finally {
      vi.unstubAllEnvs();
      expect(process.env.NODE_ENV).toBe(orig);
    }
  });
});
