import { createHash } from "crypto";
import { NextRequest } from "next/server";
import { describe, it, expect, vi } from "vitest";
import { iniciarLogin as GET } from "./route";

const ENTE = "11111111-1111-1111-1111-111111111111";
const ORIGIN = "http://localhost:3000";

const descobertaOk = () => ({
  "ente-id": ENTE,
  realm: `ente-${ENTE}`,
  "base-url": "http://localhost:8090",
  "client-id": "oplenario-web",
});

type FetchFn = (url: string | URL | Request, init?: RequestInit) => Promise<Response>;
const fetchMock = (impl: FetchFn) => vi.fn<FetchFn>(impl);

const fetchOk = () =>
  fetchMock(async () => new Response(JSON.stringify(descobertaOk()), { status: 200 }));

const req = (path: string) => new NextRequest(new URL(path, ORIGIN));

function pkceCookie(resp: Response) {
  const raw = resp.headers.get("set-cookie");
  expect(raw).toBeTruthy();
  // "pkce=<json-url-encoded>; Path=/api/auth; Expires=...; HttpOnly; SameSite=Lax"
  const match = raw!.match(/^pkce=([^;]+)/);
  expect(match).toBeTruthy();
  return { raw: raw!, payload: JSON.parse(decodeURIComponent(match![1])) };
}

describe("GET /api/auth/login — inicia PKCE (S256) contra o Keycloak resolvido por descoberta", () => {
  it("gera code_challenge = base64url(sha256(code_verifier)) e o repassa na URL de authorize", async () => {
    const fetchImpl = fetchOk();
    const resp = await GET(req(`/api/auth/login?ente=${ENTE}`), { fetchImpl });

    expect(resp.status).toBe(307);
    const location = new URL(resp.headers.get("location")!);
    const { payload } = pkceCookie(resp);

    const challengeEsperado = createHash("sha256")
      .update(payload.codeVerifier)
      .digest("base64url");
    expect(location.searchParams.get("code_challenge")).toBe(challengeEsperado);
    expect(location.searchParams.get("code_challenge_method")).toBe("S256");
  });

  it("seta o cookie pkce httpOnly com o payload completo (verifier/state/redirectPath/realm/baseUrl/clientId)", async () => {
    const fetchImpl = fetchOk();
    const resp = await GET(req(`/api/auth/login?ente=${ENTE}`), { fetchImpl });

    const { raw, payload } = pkceCookie(resp);
    expect(raw).toMatch(/HttpOnly/i);
    expect(raw).toMatch(/SameSite=Lax/i);
    expect(raw).toMatch(/Path=\/api\/auth/i);
    expect(raw).toMatch(/Max-Age=300/i);
    expect(payload).toMatchObject({
      state: expect.any(String),
      codeVerifier: expect.any(String),
      // `null` = ninguém pediu destino; quem escolhe a home é o callback, pelo papel.
      redirectPath: null,
      realm: `ente-${ENTE}`,
      baseUrl: "http://localhost:8090",
      clientId: "oplenario-web",
    });
  });

  it("monta a URL de authorize com response_type=code, client_id=oplenario-web, state e redirect_uri corretos", async () => {
    const fetchImpl = fetchOk();
    const resp = await GET(req(`/api/auth/login?ente=${ENTE}`), { fetchImpl });

    const location = new URL(resp.headers.get("location")!);
    const { payload } = pkceCookie(resp);

    expect(location.origin + location.pathname).toBe(
      `http://localhost:8090/realms/ente-${ENTE}/protocol/openid-connect/auth`,
    );
    expect(location.searchParams.get("response_type")).toBe("code");
    expect(location.searchParams.get("client_id")).toBe("oplenario-web");
    expect(location.searchParams.get("scope")).toBe("openid");
    expect(location.searchParams.get("state")).toBe(payload.state);
    expect(location.searchParams.get("redirect_uri")).toBe(
      `${ORIGIN}/api/auth/callback`,
    );
  });

  it("um redirect não-same-origin é DESCARTADO — o cookie fica sem pedido, e o papel decide", async () => {
    const fetchImpl = fetchOk();
    const resp = await GET(
      req(`/api/auth/login?ente=${ENTE}&redirect=https://evil.example/roubado`),
      { fetchImpl },
    );
    const { payload } = pkceCookie(resp);
    // o que importa é NÃO ter aceitado evil.example; sem pedido válido, o callback usa a home da persona
    expect(payload.redirectPath).toBeNull();
  });

  it("um redirect same-origin válido sobrevive no cookie", async () => {
    const fetchImpl = fetchOk();
    const resp = await GET(
      req(`/api/auth/login?ente=${ENTE}&redirect=${encodeURIComponent("/tramitacao")}`),
      { fetchImpl },
    );
    const { payload } = pkceCookie(resp);
    expect(payload.redirectPath).toBe("/tramitacao");
  });

  it("descoberta 404 (ente desconhecido) falha fechado — sem redirect de authorize, sem cookie pkce", async () => {
    const fetchImpl = fetchMock(async () => new Response(null, { status: 404 }));
    const resp = await GET(req(`/api/auth/login?ente=${ENTE}`), { fetchImpl });

    expect(resp.status).toBe(307);
    const location = new URL(resp.headers.get("location")!);
    expect(location.hostname).toBe("localhost");
    expect(location.port).toBe("3000"); // volta pro próprio front, não pro Keycloak
    expect(location.pathname).toBe("/entrar");
    expect(resp.headers.get("set-cookie")).toBeNull();
  });

  it("descoberta 400 (uuid malformado) falha fechado da mesma forma que 404 (não vaza qual ente existe)", async () => {
    const fetchImpl = fetchMock(async () => new Response(null, { status: 400 }));
    const resp400 = await GET(req(`/api/auth/login?ente=${ENTE}`), { fetchImpl });

    const fetchImpl404 = fetchMock(async () => new Response(null, { status: 404 }));
    const resp404 = await GET(req(`/api/auth/login?ente=${ENTE}`), { fetchImpl: fetchImpl404 });

    expect(new URL(resp400.headers.get("location")!).search).toBe(
      new URL(resp404.headers.get("location")!).search,
    );
  });

  it("descoberta inacessível (fetch lança erro de rede) também falha fechado", async () => {
    const fetchImpl = fetchMock(async () => {
      throw new Error("ECONNREFUSED");
    });
    const resp = await GET(req(`/api/auth/login?ente=${ENTE}`), { fetchImpl });
    expect(resp.status).toBe(307);
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/entrar");
    expect(resp.headers.get("set-cookie")).toBeNull();
  });

  it("sem ?ente= falha fechado sem sequer tentar a descoberta", async () => {
    const fetchImpl = vi.fn();
    const resp = await GET(req(`/api/auth/login`), { fetchImpl });
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(resp.status).toBe(307);
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/entrar");
  });

  it("proíbe opts.backend fora de ambiente de teste (superfície de SSRF) — lança em produção", async () => {
    const orig = process.env.NODE_ENV;
    try {
      vi.stubEnv("NODE_ENV", "production");
      await expect(
        GET(req(`/api/auth/login?ente=${ENTE}`), { backend: "http://evil" }),
      ).rejects.toThrow();
    } finally {
      vi.unstubAllEnvs();
      expect(process.env.NODE_ENV).toBe(orig);
    }
  });
});

describe("GET /api/auth/login?via=govbr — o cidadão entra pelo gov.br (ADR-0015)", () => {
  const fetchComGovbr = (govbr: boolean) =>
    fetchMock(async () => new Response(JSON.stringify({ ...descobertaOk(), govbr }), { status: 200 }));

  it("pede ao Keycloak da Casa que vá direto ao gov.br (kc_idp_hint)", async () => {
    const resp = await GET(req(`/api/auth/login?ente=${ENTE}&via=govbr`), { fetchImpl: fetchComGovbr(true) });
    expect(resp.status).toBe(307);
    const location = new URL(resp.headers.get("location")!);
    expect(location.searchParams.get("kc_idp_hint")).toBe("govbr");
  });

  it("o login institucional não leva a dica", async () => {
    const resp = await GET(req(`/api/auth/login?ente=${ENTE}`), { fetchImpl: fetchComGovbr(true) });
    expect(new URL(resp.headers.get("location")!).searchParams.get("kc_idp_hint")).toBeNull();
  });

  it("Casa sem gov.br ligado: volta à tela de participar dizendo que não está disponível, sem ir ao Keycloak", async () => {
    const resp = await GET(req(`/api/auth/login?ente=${ENTE}&via=govbr`), { fetchImpl: fetchComGovbr(false) });
    const location = new URL(resp.headers.get("location")!);
    expect(location.pathname).toBe(`/portal/casa/${ENTE}/participar`);
    expect(location.searchParams.get("erro")).toBe("indisponivel");
    expect(resp.headers.get("set-cookie") ?? "").not.toMatch(/pkce=/);
  });
});

describe("GET /api/auth/login — a Câmara escolhida depois do CPF (ADR-0025)", () => {
  const HINT = "0eabd6df-d0cc-40bb-a0ca-043027cb3a1f";
  const OUTRA = "22222222-2222-2222-2222-222222222222";
  const comCookie = (path: string, valor: string) => {
    const r = new NextRequest(new URL(path, ORIGIN));
    r.cookies.set("entrar_escolha", valor);
    return r;
  };
  const escolha = (casas: string[], redirectPath: string | null = null) =>
    JSON.stringify({ hint: HINT, redirectPath, casas: casas.map((enteId) => ({ enteId, nome: "Câmara" })) });

  it("a Câmara está na escolha: o usuário já conferido vai como login_hint e o cookie fica (voltar e escolher outra)", async () => {
    const resp = await GET(comCookie(`/api/auth/login?ente=${ENTE}`, escolha([ENTE, OUTRA])), { fetchImpl: fetchOk() });
    expect(new URL(resp.headers.get("location")!).searchParams.get("login_hint")).toBe(HINT);
    expect(resp.headers.get("set-cookie")).not.toMatch(/entrar_escolha/);
  });

  it("o destino guardado na escolha passa pela mesma validação de todo redirect", async () => {
    const resp = await GET(comCookie(`/api/auth/login?ente=${ENTE}`, escolha([ENTE], "/.//evil.example")), { fetchImpl: fetchOk() });
    expect(pkceCookie(resp).payload.redirectPath).toBeNull();
  });

  it("o destino pedido antes do CPF segue para o callback", async () => {
    const resp = await GET(comCookie(`/api/auth/login?ente=${ENTE}`, escolha([ENTE], "/tramitacao")), { fetchImpl: fetchOk() });
    expect(pkceCookie(resp).payload.redirectPath).toBe("/tramitacao");
  });

  it("Câmara fora da escolha (ou cookie adulterado): sem login_hint — o Keycloak pede o usuário", async () => {
    const fora = await GET(comCookie(`/api/auth/login?ente=${ENTE}`, escolha([OUTRA])), { fetchImpl: fetchOk() });
    expect(new URL(fora.headers.get("location")!).searchParams.get("login_hint")).toBeNull();
    const torto = await GET(comCookie(`/api/auth/login?ente=${ENTE}`, "lixo"), { fetchImpl: fetchOk() });
    expect(new URL(torto.headers.get("location")!).searchParams.get("login_hint")).toBeNull();
  });

  it("sem cookie, como antes: sem login_hint", async () => {
    const resp = await GET(req(`/api/auth/login?ente=${ENTE}`), { fetchImpl: fetchOk() });
    expect(new URL(resp.headers.get("location")!).searchParams.get("login_hint")).toBeNull();
  });
});
