import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { abrirContaDeAcesso as GET } from "./route";

// GET /api/auth/conta — leva a pessoa à página de conta do Keycloak da Casa dela ("Personal info": trocar o
// próprio e-mail de acesso, docs/16 achado B de 05/10/2026). O destino NUNCA vem de parâmetro nem de cabeçalho:
// a base é a do servidor (KEYCLOAK_PUBLIC_URL, a mesma que o login e o logout já usam), o ente sai da SESSÃO
// (GET /eu, no backend) e o realm do cookie `sessao_kc` tem de ser o dessa Casa. Qualquer dúvida: 404, sem redirect.

const ORIGIN = "http://localhost:3000";
const ENTE = "11111111-1111-1111-1111-111111111111";
const REALM = `ente-${ENTE}`;
const BASE = "https://kc.exemplo.com";
const CLIENT_ID = "oplenario-web";

type FetchFn = (url: string | URL | Request, init?: RequestInit) => Promise<Response>;

/** O backend responde /eu como a sessão de um servidor da Casa `ente` (ou como o que o teste pedir). */
function eu(ator: Record<string, unknown> | "falha" | "quebrado" = { "ente-id": ENTE, "tipo-vinculo": "servidor", papeis: ["secretario"] }) {
  return vi.fn<FetchFn>(async () => {
    if (ator === "falha") throw new Error("rede");
    if (ator === "quebrado") return new Response("<html>", { status: 200 });
    return new Response(JSON.stringify({ ator }), { status: 200, headers: { "content-type": "application/json" } });
  });
}

function req(opts: {
  kc?: Record<string, unknown> | string | null;
  sessao?: string | null;
  headers?: Record<string, string>;
  url?: string;
} = {}) {
  const request = new NextRequest(new URL(opts.url ?? "/api/auth/conta", ORIGIN), { headers: opts.headers });
  const sessao = opts.sessao === undefined ? "segredo-opaco-abc123" : opts.sessao;
  if (sessao) request.cookies.set("sessao", sessao);
  const kc = opts.kc === undefined ? { baseUrl: BASE, realm: REALM, clientId: CLIENT_ID } : opts.kc;
  if (kc !== null) request.cookies.set("sessao_kc", typeof kc === "string" ? kc : JSON.stringify(kc));
  return request;
}

async function semRedirect(resp: Response) {
  expect([400, 404]).toContain(resp.status);
  expect(resp.headers.get("location")).toBeNull();
}

beforeEach(() => {
  vi.stubEnv("NEXT_PUBLIC_APP_ENV", "production");
  vi.stubEnv("KEYCLOAK_PUBLIC_URL", BASE);
});
afterEach(() => vi.unstubAllEnvs());

describe("GET /api/auth/conta — o caminho feliz", () => {
  it("redireciona para /realms/<realm>/account da base configurada, com o ente vindo da sessão", async () => {
    const fetchImpl = eu();
    const resp = await GET(req(), { fetchImpl });
    expect(resp.status).toBeGreaterThanOrEqual(300);
    expect(resp.status).toBeLessThan(400);
    expect(resp.headers.get("location")).toBe(`${BASE}/realms/${REALM}/account`);
    // o ente foi perguntado ao backend com a sessão do cookie, não deduzido de parâmetro
    const [url, init] = fetchImpl.mock.calls[0];
    expect(new URL(url.toString()).pathname).toBe("/eu");
    expect((init?.headers as Record<string, string>).cookie).toBe("sessao=segredo-opaco-abc123");
  });

  it("a base configurada pode ter caminho (Keycloak atrás de /auth) e barra no fim", async () => {
    vi.stubEnv("KEYCLOAK_PUBLIC_URL", "https://kc.exemplo.com/auth/");
    const resp = await GET(req({ kc: { baseUrl: "https://kc.exemplo.com/auth", realm: REALM, clientId: CLIENT_ID } }), { fetchImpl: eu() });
    expect(resp.headers.get("location")).toBe(`https://kc.exemplo.com/auth/realms/${REALM}/account`);
  });
});

describe("GET /api/auth/conta — o destino não vem de fora", () => {
  it("Host, X-Forwarded-*, Referer, Origin e parâmetros forjados não mudam o destino", async () => {
    const resp = await GET(
      req({
        url: "/api/auth/conta?redirect=https://atacante.net&next=//atacante.net&realm=master&base=https://atacante.net",
        headers: {
          host: "atacante.net",
          "x-forwarded-host": "atacante.net",
          "x-forwarded-proto": "http",
          "x-forwarded-for": "6.6.6.6",
          referer: "https://atacante.net/",
          origin: "https://atacante.net",
        },
      }),
      { fetchImpl: eu() },
    );
    expect(resp.headers.get("location")).toBe(`${BASE}/realms/${REALM}/account`);
  });

  it("o ente é o da SESSÃO: realm de outra Casa no cookie não vira destino", async () => {
    const outra = "22222222-2222-2222-2222-222222222222";
    await semRedirect(await GET(req({ kc: { baseUrl: BASE, realm: `ente-${outra}`, clientId: CLIENT_ID } }), { fetchImpl: eu() }));
  });
});

describe("GET /api/auth/conta — fail-closed na base configurada", () => {
  it("base ausente, vazia ou malformada: 404, sem redirect", async () => {
    for (const ruim of [undefined, "", "   ", "kc.exemplo.com", "não é url", "https://", "https://u:p@kc.exemplo.com", "https://kc.exemplo.com/?x=1"]) {
      if (ruim === undefined) vi.stubEnv("KEYCLOAK_PUBLIC_URL", "");
      else vi.stubEnv("KEYCLOAK_PUBLIC_URL", ruim);
      await semRedirect(await GET(req(), { fetchImpl: eu() }));
    }
  });

  it("http em produção: 404, sem redirect (nem com cookie e configuração iguais)", async () => {
    vi.stubEnv("KEYCLOAK_PUBLIC_URL", "http://kc.exemplo.com");
    await semRedirect(await GET(req({ kc: { baseUrl: "http://kc.exemplo.com", realm: REALM, clientId: CLIENT_ID } }), { fetchImpl: eu() }));
  });

  it("modo dev (token de dev, sem Keycloak): 404, sem redirect", async () => {
    vi.stubEnv("NEXT_PUBLIC_APP_ENV", "dev");
    await semRedirect(await GET(req(), { fetchImpl: eu() }));
  });
});

describe("GET /api/auth/conta — o issuer da sessão tem de ter EXATAMENTE a origem configurada", () => {
  it("issuer de outra origem: sem redirect", async () => {
    await semRedirect(await GET(req({ kc: { baseUrl: "https://auth.atacante.net", realm: REALM, clientId: CLIENT_ID } }), { fetchImpl: eu() }));
  });

  it("issuer com prefixo igual (https://kc.exemplo.com.atacante.net): sem redirect", async () => {
    await semRedirect(
      await GET(req({ kc: { baseUrl: "https://kc.exemplo.com.atacante.net", realm: REALM, clientId: CLIENT_ID } }), { fetchImpl: eu() }),
    );
  });

  it("issuer com credencial embutida que parece a origem (https://kc.exemplo.com@atacante.net): sem redirect", async () => {
    await semRedirect(
      await GET(req({ kc: { baseUrl: "https://kc.exemplo.com@atacante.net", realm: REALM, clientId: CLIENT_ID } }), { fetchImpl: eu() }),
    );
  });

  it("outra porta ou outro esquema na mesma máquina: sem redirect", async () => {
    for (const baseUrl of ["https://kc.exemplo.com:8443", "http://kc.exemplo.com"]) {
      await semRedirect(await GET(req({ kc: { baseUrl, realm: REALM, clientId: CLIENT_ID } }), { fetchImpl: eu() }));
    }
  });
});

describe("GET /api/auth/conta — o realm vem do formato, nunca de concatenação", () => {
  it("realm com /, .., @, \\, quebra de linha, %2f ou fora do padrão ente-<uuid>: sem redirect", async () => {
    for (const realm of [
      `${REALM}/../master`,
      `${REALM}/x`,
      "../../master",
      `${REALM}@atacante.net`,
      `${REALM}\\x`,
      `${REALM}\r\nLocation: https://atacante.net`,
      `${REALM}%2f..%2fmaster`,
      "master",
      "ente-",
      `ente-${"-".repeat(36)}`,
      `${REALM}?x=1`,
      `${REALM}#x`,
    ]) {
      await semRedirect(await GET(req({ kc: { baseUrl: BASE, realm, clientId: CLIENT_ID } }), { fetchImpl: eu() }));
    }
  });
});

describe("GET /api/auth/conta — só para quem tem conta no Keycloak da Casa", () => {
  it("sem sessão (sem cookie): 404, sem redirect e sem perguntar ao backend", async () => {
    const fetchImpl = eu();
    await semRedirect(await GET(req({ sessao: null }), { fetchImpl }));
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("cookie de sessão com formato estranho: 404, sem perguntar ao backend", async () => {
    const fetchImpl = eu();
    await semRedirect(await GET(req({ sessao: "a b;c\r\nx" }), { fetchImpl }));
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("sessão do gov.br (vínculo de cidadão): 404, sem redirect", async () => {
    await semRedirect(await GET(req(), { fetchImpl: eu({ "ente-id": ENTE, "tipo-vinculo": "cidadao", papeis: [] }) }));
  });

  it("backend sem resposta, com erro, corpo quebrado ou sem ente/tipo: 404, sem redirect (não segue adiante)", async () => {
    await semRedirect(await GET(req(), { fetchImpl: eu("falha") }));
    await semRedirect(await GET(req(), { fetchImpl: eu("quebrado") }));
    await semRedirect(await GET(req(), { fetchImpl: vi.fn<FetchFn>(async () => new Response(null, { status: 401 })) }));
    await semRedirect(await GET(req(), { fetchImpl: eu({ "tipo-vinculo": "servidor" }) }));
    await semRedirect(await GET(req(), { fetchImpl: eu({ "ente-id": ENTE }) }));
  });

  it("sem o cookie da descoberta (sessão anterior a ele) ou com JSON quebrado: 404, sem redirect", async () => {
    await semRedirect(await GET(req({ kc: null }), { fetchImpl: eu() }));
    await semRedirect(await GET(req({ kc: "{não é json" }), { fetchImpl: eu() }));
    await semRedirect(await GET(req({ kc: "42" }), { fetchImpl: eu() }));
  });

  it("a mensagem de 404 diz o que fazer", async () => {
    const resp = await GET(req({ kc: null }), { fetchImpl: eu() });
    expect(await resp.text()).toMatch(/entre de novo/i);
  });
});
