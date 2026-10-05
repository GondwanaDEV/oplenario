import { NextRequest } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { abrirContaDeAcesso as GET } from "./route";

// GET /api/auth/conta — leva a pessoa à página de conta do Keycloak da Casa dela ("Personal info": trocar o
// próprio e-mail de acesso, docs/16 achado B de 05/10/2026). A URL sai do cookie httpOnly `sessao_kc`, que o
// callback do login grava com a descoberta do tenant (realm, base pública do Keycloak, client) — o navegador
// nunca vê esses valores, e nada é montado a mão.

const ORIGIN = "http://localhost:3000";
const ENTE = "11111111-1111-1111-1111-111111111111";
const REALM = `ente-${ENTE}`;
const BASE_URL = "https://auth.exemplo.test";
const CLIENT_ID = "oplenario-web";

function req(sessaoKc?: Record<string, unknown> | string | null, sessao: string | null = "segredo-opaco-abc123") {
  const request = new NextRequest(new URL("/api/auth/conta", ORIGIN));
  if (sessao) request.cookies.set("sessao", sessao);
  if (sessaoKc !== undefined && sessaoKc !== null) {
    request.cookies.set("sessao_kc", typeof sessaoKc === "string" ? sessaoKc : JSON.stringify(sessaoKc));
  }
  return request;
}

describe("GET /api/auth/conta", () => {
  afterEach(() => vi.unstubAllEnvs());

  it("redireciona para a página de conta do realm da Casa, a partir do cookie da descoberta", async () => {
    const resp = await GET(req({ baseUrl: BASE_URL, realm: REALM, clientId: CLIENT_ID }));
    expect(resp.status).toBeGreaterThanOrEqual(300);
    expect(resp.status).toBeLessThan(400);
    expect(resp.headers.get("location")).toBe(`${BASE_URL}/realms/${REALM}/account`);
  });

  it("sem o cookie da descoberta (sessão anterior ao cookie): não inventa destino — diz o que fazer", async () => {
    const resp = await GET(req(null));
    expect(resp.status).toBe(404);
    expect(resp.headers.get("location")).toBeNull();
    expect(await resp.text()).toMatch(/entre de novo/i);
  });

  it("sem sessão nenhuma: não redireciona para o Keycloak", async () => {
    const resp = await GET(req({ baseUrl: BASE_URL, realm: REALM, clientId: CLIENT_ID }, null));
    expect(resp.status).toBe(404);
    expect(resp.headers.get("location")).toBeNull();
  });

  it("cookie forjado (realm fora do padrão, esquema estranho, JSON quebrado) não vira destino", async () => {
    for (const ruim of [
      { baseUrl: BASE_URL, realm: "master", clientId: CLIENT_ID },
      { baseUrl: "javascript:alert(1)", realm: REALM, clientId: CLIENT_ID },
      { baseUrl: BASE_URL, realm: `${REALM}/../../x`, clientId: CLIENT_ID },
      "{não é json",
    ]) {
      const resp = await GET(req(ruim));
      expect(resp.status).toBe(404);
      expect(resp.headers.get("location")).toBeNull();
    }
  });

  it("com KEYCLOAK_PUBLIC_URL configurada, host de outro Keycloak não vira destino (host-pin)", async () => {
    vi.stubEnv("KEYCLOAK_PUBLIC_URL", "https://auth.oficial.test");
    const resp = await GET(req({ baseUrl: "https://auth.atacante.test", realm: REALM, clientId: CLIENT_ID }));
    expect(resp.status).toBe(404);
    expect(resp.headers.get("location")).toBeNull();
    const ok = await GET(req({ baseUrl: "https://auth.oficial.test", realm: REALM, clientId: CLIENT_ID }));
    expect(ok.headers.get("location")).toBe(`https://auth.oficial.test/realms/${REALM}/account`);
  });
});
