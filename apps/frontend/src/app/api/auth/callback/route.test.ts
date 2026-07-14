import { NextRequest } from "next/server";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { receberCallback as GET } from "./route";

const ORIGIN = "http://localhost:3000";
const ENTE = "11111111-1111-1111-1111-111111111111";
const REALM = `ente-${ENTE}`;
const BASE_URL = "http://localhost:8090";
const CLIENT_ID = "oplenario-web";
const ACCESS_TOKEN = "kc-access-token-super-secreto";
const SEGREDO = "segredo-opaco-da-sessao-123";

type FetchFn = (url: string | URL | Request, init?: RequestInit) => Promise<Response>;
const fetchMock = (impl: FetchFn) => vi.fn<FetchFn>(impl);

function pkcePayload(overrides: Record<string, unknown> = {}) {
  return {
    codeVerifier: "verifier-abc",
    state: "state-xyz",
    redirectPath: "/tramitacao",
    realm: REALM,
    baseUrl: BASE_URL,
    clientId: CLIENT_ID,
    ...overrides,
  };
}

function req(path: string, opts?: { pkce?: Record<string, unknown> | null }) {
  const request = new NextRequest(new URL(path, ORIGIN));
  if (opts && opts.pkce !== undefined) {
    if (opts.pkce !== null) {
      request.cookies.set("pkce", JSON.stringify(opts.pkce));
    }
  } else {
    request.cookies.set("pkce", JSON.stringify(pkcePayload()));
  }
  return request;
}

function fetchHappyPath() {
  return fetchMock(async (url) => {
    const u = url.toString();
    if (u.includes("/protocol/openid-connect/token")) {
      return new Response(JSON.stringify({ access_token: ACCESS_TOKEN }), { status: 200 });
    }
    if (u.includes("/auth/sessoes")) {
      return new Response(JSON.stringify({ sessao: SEGREDO }), { status: 200 });
    }
    throw new Error(`URL inesperada no mock: ${u}`);
  });
}

function setCookies(resp: Response): string[] {
  return resp.headers.getSetCookie();
}

function findCookie(resp: Response, name: string): string | undefined {
  return setCookies(resp).find((c) => c.startsWith(`${name}=`));
}

describe("GET /api/auth/callback — troca code por token (PKCE), minta sessão opaca, seta cookie", () => {
  // Hermético: KEYCLOAK_INTERNAL_URL (split-horizon do BFF, T17) tem prioridade sobre pkce.baseUrl no
  // token-exchange. Em dev/CI o container do frontend pode tê-lo setado no ambiente — desliga aqui p/ os
  // testes do caminho de fallback (baseUrl da descoberta) serem determinísticos independente do ambiente.
  beforeEach(() => {
    vi.stubEnv("KEYCLOAK_INTERNAL_URL", undefined as unknown as string);
  });
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("state mismatch → 400 e NUNCA chama o endpoint de token (ordem CSRF antes de tocar no code)", async () => {
    const fetchImpl = fetchHappyPath();
    const resp = await GET(
      req(`/api/auth/callback?code=abc&state=state-ERRADO`),
      { fetchImpl },
    );

    expect(resp.status).toBe(400);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("happy path: troca code por access_token, chama o mint com o access_token, seta cookie sessao httpOnly/secure/lax/path=/, limpa pkce, redireciona ao path validado", async () => {
    const fetchImpl = fetchHappyPath();
    const resp = await GET(req(`/api/auth/callback?code=auth-code-123&state=state-xyz`), {
      fetchImpl,
    });

    expect(resp.status).toBeGreaterThanOrEqual(300);
    expect(resp.status).toBeLessThan(400);
    const location = new URL(resp.headers.get("location")!);
    expect(location.pathname).toBe("/tramitacao");

    // A troca de code→token foi chamada com o corpo esperado, sem client_secret (client público).
    const tokenCall = fetchImpl.mock.calls.find(([url]) =>
      url.toString().includes("/protocol/openid-connect/token"),
    );
    expect(tokenCall).toBeTruthy();
    const [tokenUrl, tokenInit] = tokenCall!;
    expect(tokenUrl.toString()).toBe(
      `${BASE_URL}/realms/${REALM}/protocol/openid-connect/token`,
    );
    const tokenBody = new URLSearchParams(tokenInit!.body as string);
    expect(tokenBody.get("grant_type")).toBe("authorization_code");
    expect(tokenBody.get("client_id")).toBe(CLIENT_ID);
    expect(tokenBody.get("code")).toBe("auth-code-123");
    expect(tokenBody.get("code_verifier")).toBe("verifier-abc");
    expect(tokenBody.get("redirect_uri")).toBe(`${ORIGIN}/api/auth/callback`);
    expect(tokenBody.get("client_secret")).toBeNull();
    expect(Array.from(tokenBody.keys())).not.toContain("client_secret");

    // O mint foi chamado com o access_token do KC.
    const mintCall = fetchImpl.mock.calls.find(([url]) => url.toString().includes("/auth/sessoes"));
    expect(mintCall).toBeTruthy();
    const [mintUrl, mintInit] = mintCall!;
    // Host vem de BACKEND_URL (varia por ambiente — `localhost` local, `app` em docker compose);
    // o que importa aqui é o path, não o host.
    expect(new URL(mintUrl.toString()).pathname).toBe("/auth/sessoes");
    expect(JSON.parse(mintInit!.body as string)).toEqual({ token: ACCESS_TOKEN });

    // Cookie sessao: httpOnly, secure, sameSite=lax, path=/, o valor é o segredo opaco (NUNCA o access_token).
    const sessaoCookie = findCookie(resp, "sessao");
    expect(sessaoCookie).toBeTruthy();
    expect(sessaoCookie).toMatch(new RegExp(`^sessao=${SEGREDO}`));
    expect(sessaoCookie).toMatch(/HttpOnly/i);
    expect(sessaoCookie).toMatch(/Secure/i);
    expect(sessaoCookie).toMatch(/SameSite=Lax/i);
    expect(sessaoCookie).toMatch(/Path=\//);
    expect(sessaoCookie).toMatch(/Max-Age=43200/i);

    // pkce foi limpo, na MESMA tupla (nome, path) com que foi setado no login (path=/api/auth).
    const pkceCookie = findCookie(resp, "pkce");
    expect(pkceCookie).toBeTruthy();
    expect(pkceCookie).toMatch(/Path=\/api\/auth/i);

    // Cookie companheiro sessao_kc: carrega SÓ os valores públicos de descoberta (baseUrl/realm/
    // clientId), lidos do MESMO cookie pkce já em escopo — nada de refetch. httpOnly/secure/lax,
    // path=/, mesma vida do cookie sessao (para sobreviver até o logout).
    const sessaoKcCookie = findCookie(resp, "sessao_kc");
    expect(sessaoKcCookie).toBeTruthy();
    expect(sessaoKcCookie).toMatch(/HttpOnly/i);
    expect(sessaoKcCookie).toMatch(/Secure/i);
    expect(sessaoKcCookie).toMatch(/SameSite=Lax/i);
    expect(sessaoKcCookie).toMatch(/Path=\//);
    expect(sessaoKcCookie).toMatch(/Max-Age=43200/i);
    const sessaoKcValueMatch = sessaoKcCookie!.match(/^sessao_kc=([^;]+)/);
    expect(sessaoKcValueMatch).toBeTruthy();
    const sessaoKcParsed = JSON.parse(decodeURIComponent(sessaoKcValueMatch![1]));
    expect(sessaoKcParsed).toEqual({ baseUrl: BASE_URL, realm: REALM, clientId: CLIENT_ID });

    // O access_token do KC NUNCA aparece em nenhum header/cookie/corpo que chega ao navegador.
    const allSetCookie = setCookies(resp).join("\n");
    expect(allSetCookie).not.toContain(ACCESS_TOKEN);
    const bodyText = await resp.clone().text().catch(() => "");
    expect(bodyText).not.toContain(ACCESS_TOKEN);

    // sessao_kc NÃO carrega segredo/access_token — só os 3 campos públicos de descoberta.
    expect(sessaoKcCookie).not.toContain(ACCESS_TOKEN);
    expect(sessaoKcCookie).not.toContain(SEGREDO);
  });

  it("redirectPath não-same-origin no cookie pkce → redireciona para o default '/'", async () => {
    const fetchImpl = fetchHappyPath();
    const resp = await GET(
      req(`/api/auth/callback?code=auth-code-123&state=state-xyz`, {
        pkce: pkcePayload({ redirectPath: "https://evil.example/roubado" }),
      }),
      { fetchImpl },
    );

    const location = new URL(resp.headers.get("location")!);
    expect(location.origin).toBe(ORIGIN);
    expect(location.pathname).toBe("/");
  });

  it("troca de code→token retorna não-2xx → 401", async () => {
    const fetchImpl = fetchMock(async (url) => {
      const u = url.toString();
      if (u.includes("/protocol/openid-connect/token")) {
        return new Response(JSON.stringify({ error: "invalid_grant" }), { status: 400 });
      }
      throw new Error(`URL inesperada no mock: ${u}`);
    });
    const resp = await GET(req(`/api/auth/callback?code=auth-code-123&state=state-xyz`), {
      fetchImpl,
    });
    expect(resp.status).toBe(401);
  });

  it("troca de code→token: falha de rede (fetch lança) → 502", async () => {
    const fetchImpl = fetchMock(async (url) => {
      const u = url.toString();
      if (u.includes("/protocol/openid-connect/token")) {
        throw new Error("ECONNREFUSED");
      }
      throw new Error(`URL inesperada no mock: ${u}`);
    });
    const resp = await GET(req(`/api/auth/callback?code=auth-code-123&state=state-xyz`), {
      fetchImpl,
    });
    expect(resp.status).toBe(502);
  });

  it("mint (POST /auth/sessoes) retorna não-2xx → 401", async () => {
    const fetchImpl = fetchMock(async (url) => {
      const u = url.toString();
      if (u.includes("/protocol/openid-connect/token")) {
        return new Response(JSON.stringify({ access_token: ACCESS_TOKEN }), { status: 200 });
      }
      if (u.includes("/auth/sessoes")) {
        return new Response(JSON.stringify({ erro: "token inválido" }), { status: 401 });
      }
      throw new Error(`URL inesperada no mock: ${u}`);
    });
    const resp = await GET(req(`/api/auth/callback?code=auth-code-123&state=state-xyz`), {
      fetchImpl,
    });
    expect(resp.status).toBe(401);
  });

  it("mint: falha de rede (fetch lança) → 502", async () => {
    const fetchImpl = fetchMock(async (url) => {
      const u = url.toString();
      if (u.includes("/protocol/openid-connect/token")) {
        return new Response(JSON.stringify({ access_token: ACCESS_TOKEN }), { status: 200 });
      }
      if (u.includes("/auth/sessoes")) {
        throw new Error("ECONNREFUSED");
      }
      throw new Error(`URL inesperada no mock: ${u}`);
    });
    const resp = await GET(req(`/api/auth/callback?code=auth-code-123&state=state-xyz`), {
      fetchImpl,
    });
    expect(resp.status).toBe(502);
  });

  it("code ausente → redireciona para o login sem tocar em fetch", async () => {
    const fetchImpl = vi.fn();
    const resp = await GET(req(`/api/auth/callback?state=state-xyz`), { fetchImpl });
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(resp.status).toBeGreaterThanOrEqual(300);
    expect(resp.status).toBeLessThan(400);
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/api/auth/login");
  });

  it("state ausente → redireciona para o login sem tocar em fetch", async () => {
    const fetchImpl = vi.fn();
    const resp = await GET(req(`/api/auth/callback?code=abc`), { fetchImpl });
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/api/auth/login");
  });

  it("cookie pkce ausente → redireciona para o login sem tocar em fetch", async () => {
    const fetchImpl = vi.fn();
    const resp = await GET(
      req(`/api/auth/callback?code=abc&state=state-xyz`, { pkce: null }),
      { fetchImpl },
    );
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/api/auth/login");
  });

  it("proíbe opts.backend fora de ambiente de teste (superfície de SSRF) — lança em produção", async () => {
    const orig = process.env.NODE_ENV;
    try {
      vi.stubEnv("NODE_ENV", "production");
      await expect(
        GET(req(`/api/auth/callback?code=abc&state=state-xyz`), {
          fetchImpl: fetchHappyPath(),
          backend: "http://evil",
        }),
      ).rejects.toThrow();
    } finally {
      vi.unstubAllEnvs();
      expect(process.env.NODE_ENV).toBe(orig);
    }
  });

  it("KEYCLOAK_INTERNAL_URL (split-horizon T17) tem prioridade sobre pkce.baseUrl no token-exchange", async () => {
    vi.stubEnv("KEYCLOAK_INTERNAL_URL", "http://keycloak:8080");
    const fetchImpl = fetchHappyPath();
    await GET(req(`/api/auth/callback?code=auth-code-123&state=state-xyz`), { fetchImpl });
    const tokenCall = fetchImpl.mock.calls.find(([url]) =>
      url.toString().includes("/protocol/openid-connect/token"),
    );
    expect(tokenCall).toBeTruthy();
    // troca server-side usa o host INTERNO do compose, não o baseUrl público da descoberta (localhost:8090)
    expect(tokenCall![0].toString()).toBe(
      `http://keycloak:8080/realms/${REALM}/protocol/openid-connect/token`,
    );
  });

  it("pkce com realm forjado (path-injection) → re-login e NUNCA chama o token endpoint (validação de forma)", async () => {
    const fetchImpl = fetchHappyPath();
    const resp = await GET(
      req(`/api/auth/callback?code=abc&state=state-xyz`, {
        pkce: pkcePayload({ realm: "ente-x/../../evil" }),
      }),
      { fetchImpl },
    );
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/api/auth/login");
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("pkce com baseUrl de esquema não-http → re-login (fail-closed)", async () => {
    const fetchImpl = fetchHappyPath();
    const resp = await GET(
      req(`/api/auth/callback?code=abc&state=state-xyz`, {
        pkce: pkcePayload({ baseUrl: "javascript:alert(1)" }),
      }),
      { fetchImpl },
    );
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/api/auth/login");
    expect(fetchImpl).not.toHaveBeenCalled();
  });
});
