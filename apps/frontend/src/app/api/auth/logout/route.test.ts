import { NextRequest } from "next/server";
import { describe, it, expect, vi } from "vitest";
import { encerrarSessao as POST } from "./route";

const ORIGIN = "http://localhost:3000";
const SEGREDO = "segredo-opaco-da-sessao-abc123_XYZ";
const ENTE = "11111111-1111-1111-1111-111111111111";
const REALM = `ente-${ENTE}`;
const BASE_URL = "http://localhost:8090";
const CLIENT_ID = "oplenario-web";

type FetchFn = (url: string | URL | Request, init?: RequestInit) => Promise<Response>;
const fetchMock = (impl: FetchFn) => vi.fn<FetchFn>(impl);

function req(opts?: { sessao?: string | null; sessaoKc?: Record<string, unknown> | string | null }) {
  const request = new NextRequest(new URL("/api/auth/logout", ORIGIN), { method: "POST" });
  if (opts?.sessao !== undefined) {
    if (opts.sessao !== null) {
      request.cookies.set("sessao", opts.sessao);
    }
  } else {
    request.cookies.set("sessao", SEGREDO);
  }
  if (opts?.sessaoKc !== undefined) {
    if (opts.sessaoKc !== null) {
      const value =
        typeof opts.sessaoKc === "string" ? opts.sessaoKc : JSON.stringify(opts.sessaoKc);
      request.cookies.set("sessao_kc", value);
    }
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

describe("POST /api/auth/logout — RP-logout no Keycloak via cookie companheiro sessao_kc", () => {
  it("cookie sessao_kc válido → redireciona para o end-session do KC (client_id + post_logout_redirect_uri), limpa AMBOS os cookies", async () => {
    const fetchImpl = fetchMock(async () => new Response(null, { status: 204 }));
    const resp = await POST(
      req({ sessaoKc: { baseUrl: BASE_URL, realm: REALM, clientId: CLIENT_ID } }),
      { fetchImpl },
    );

    expect(resp.status).toBeGreaterThanOrEqual(300);
    expect(resp.status).toBeLessThan(400);
    const location = new URL(resp.headers.get("location")!);
    expect(location.origin).toBe(BASE_URL);
    expect(location.pathname).toBe(`/realms/${REALM}/protocol/openid-connect/logout`);
    expect(location.searchParams.get("client_id")).toBe(CLIENT_ID);
    expect(location.searchParams.get("post_logout_redirect_uri")).toBe(ORIGIN);

    const sessaoCookie = findCookie(resp, "sessao");
    expect(sessaoCookie).toBeTruthy();
    expect(sessaoCookie).toMatch(/Path=\//);
    const sessaoKcCookie = findCookie(resp, "sessao_kc");
    expect(sessaoKcCookie).toBeTruthy();
    expect(sessaoKcCookie).toMatch(/Path=\//);
  });

  it("cookie sessao_kc AUSENTE → cai no fallback local /entrar, limpa AMBOS os cookies", async () => {
    const fetchImpl = fetchMock(async () => new Response(null, { status: 204 }));
    const resp = await POST(req({ sessaoKc: null }), { fetchImpl });

    const location = new URL(resp.headers.get("location")!);
    expect(location.origin).toBe(ORIGIN);
    expect(location.pathname).toBe("/entrar");

    expect(findCookie(resp, "sessao")).toBeTruthy();
    expect(findCookie(resp, "sessao_kc")).toBeTruthy();
  });

  it("cookie sessao_kc ADULTERADO (baseUrl com esquema perigoso) → NUNCA monta URL a partir dele, cai no fallback local /entrar", async () => {
    const fetchImpl = fetchMock(async () => new Response(null, { status: 204 }));
    const resp = await POST(
      req({
        sessaoKc: { baseUrl: "javascript:alert(1)", realm: REALM, clientId: CLIENT_ID },
      }),
      { fetchImpl },
    );

    const location = new URL(resp.headers.get("location")!);
    expect(location.origin).toBe(ORIGIN);
    expect(location.pathname).toBe("/entrar");
    expect(findCookie(resp, "sessao")).toBeTruthy();
    expect(findCookie(resp, "sessao_kc")).toBeTruthy();
  });

  it("cookie sessao_kc ADULTERADO (realm fora do formato ente-<uuid>) → cai no fallback local /entrar", async () => {
    const fetchImpl = fetchMock(async () => new Response(null, { status: 204 }));
    const resp = await POST(
      req({
        sessaoKc: { baseUrl: BASE_URL, realm: "not-a-realm", clientId: CLIENT_ID },
      }),
      { fetchImpl },
    );

    const location = new URL(resp.headers.get("location")!);
    expect(location.origin).toBe(ORIGIN);
    expect(location.pathname).toBe("/entrar");
    expect(findCookie(resp, "sessao")).toBeTruthy();
    expect(findCookie(resp, "sessao_kc")).toBeTruthy();
  });

  it("cookie sessao_kc com JSON inválido (não-JSON) → cai no fallback local /entrar", async () => {
    const fetchImpl = fetchMock(async () => new Response(null, { status: 204 }));
    const resp = await POST(req({ sessaoKc: "isto-nao-e-json" }), { fetchImpl });

    const location = new URL(resp.headers.get("location")!);
    expect(location.origin).toBe(ORIGIN);
    expect(location.pathname).toBe("/entrar");
  });
});
