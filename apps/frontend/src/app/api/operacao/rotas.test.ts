import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { destinoDoConsole } from "./descoberta";
import { iniciarLoginOperacao } from "./login/route";
import { receberCallbackOperacao } from "./callback/route";
import { sairDoConsole } from "./logout/route";

const ORIGIN = "http://localhost:3000";
const DESC = { realm: "operacao", "base-url": "http://kc.local:8080", "client-id": "oplenario-console" };

function req(path: string, cookie?: string) {
  const headers = new Headers();
  if (cookie) headers.set("cookie", cookie);
  return new NextRequest(new URL(path, ORIGIN), { headers });
}

function resposta(corpo: unknown, status = 200) {
  return new Response(JSON.stringify(corpo), { status, headers: { "content-type": "application/json" } });
}

beforeEach(() => {
  vi.stubEnv("APP_ORIGIN", ORIGIN);
  // a troca do code prefere a URL interna do Keycloak quando o ambiente a define (callback/route.ts). O container
  // de desenvolvimento define `KEYCLOAK_INTERNAL_URL`; sem apagar as duas aqui o teste dependia de onde rodava.
  vi.stubEnv("OPERACAO_KEYCLOAK_INTERNAL_URL", undefined);
  vi.stubEnv("KEYCLOAK_INTERNAL_URL", undefined);
});
afterEach(() => vi.unstubAllEnvs());

describe("BFF do console — login (ADR-0016)", () => {
  it("começa o PKCE no realm do operador, com o callback do console", async () => {
    const f = vi.fn().mockResolvedValue(resposta(DESC));
    const r = await iniciarLoginOperacao(req("/api/operacao/login?redirect=/operacao/casas/nova"), { backend: "http://b", fetchImpl: f });
    const url = new URL(r.headers.get("location")!);
    expect(url.origin + url.pathname).toBe("http://kc.local:8080/realms/operacao/protocol/openid-connect/auth");
    expect(url.searchParams.get("client_id")).toBe("oplenario-console");
    expect(url.searchParams.get("redirect_uri")).toBe(`${ORIGIN}/api/operacao/callback`);
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    const pkce = r.cookies.get("pkce_operacao")!;
    expect(pkce.path).toBe("/api/operacao");
    expect(JSON.parse(pkce.value).redirectPath).toBe("/operacao/casas/nova");
  });

  it("descoberta fora do ar ou malformada volta à entrada (fail-closed)", async () => {
    for (const f of [vi.fn().mockRejectedValue(new Error("x")), vi.fn().mockResolvedValue(resposta({ ...DESC, realm: "../x" }))]) {
      const r = await iniciarLoginOperacao(req("/api/operacao/login"), { backend: "http://b", fetchImpl: f });
      expect(new URL(r.headers.get("location")!).pathname).toBe("/operacao/entrar");
    }
  });

  it("o destino depois do login é sempre do console", () => {
    expect(destinoDoConsole("/operacao/casas/x")).toBe("/operacao/casas/x");
    expect(destinoDoConsole("/proposicoes")).toBe("/operacao");
    expect(destinoDoConsole("//evil.com/operacao")).toBe("/operacao");
    expect(destinoDoConsole("/operacao-falsa")).toBe("/operacao");
    expect(destinoDoConsole("/operacao/entrar")).toBe("/operacao");
    expect(destinoDoConsole(null)).toBe("/operacao");
  });
});

describe("BFF do console — callback", () => {
  const pkce = `pkce_operacao=${encodeURIComponent(JSON.stringify({ codeVerifier: "v", state: "s1", redirectPath: "/operacao" }))}`;

  it("state diferente é recusado ANTES de qualquer rede", async () => {
    const f = vi.fn();
    const r = await receberCallbackOperacao(req("/api/operacao/callback?code=c&state=outro", pkce), { backend: "http://b", fetchImpl: f });
    expect(r.status).toBe(400);
    expect(f).not.toHaveBeenCalled();
  });

  it("a troca do code vai pela URL interna do Keycloak quando o ambiente a define; a do operador vence a geral", async () => {
    const trocar = async () => {
      const f = vi.fn()
        .mockResolvedValueOnce(resposta(DESC))
        .mockResolvedValueOnce(resposta({ access_token: "at" }))
        .mockResolvedValueOnce(resposta({ sessao: "segredo-op" }));
      await receberCallbackOperacao(req("/api/operacao/callback?code=c&state=s1", pkce), { backend: "http://b", fetchImpl: f });
      return f.mock.calls[1][0];
    };
    vi.stubEnv("KEYCLOAK_INTERNAL_URL", "http://keycloak:8080");
    expect(await trocar()).toBe("http://keycloak:8080/realms/operacao/protocol/openid-connect/token");
    vi.stubEnv("OPERACAO_KEYCLOAK_INTERNAL_URL", "http://kc-operacao:8080");
    expect(await trocar()).toBe("http://kc-operacao:8080/realms/operacao/protocol/openid-connect/token");
  });

  it("troca o code no realm do operador, minta a sessão do console e seta o cookie próprio", async () => {
    const f = vi.fn()
      .mockResolvedValueOnce(resposta(DESC))
      .mockResolvedValueOnce(resposta({ access_token: "at" }))
      .mockResolvedValueOnce(resposta({ sessao: "segredo-op" }));
    const r = await receberCallbackOperacao(req("/api/operacao/callback?code=c&state=s1", pkce), { backend: "http://b", fetchImpl: f });
    expect(f.mock.calls[1][0]).toBe("http://kc.local:8080/realms/operacao/protocol/openid-connect/token");
    expect(f.mock.calls[2][0]).toBe("http://b/operacao/sessoes");
    expect(new URL(r.headers.get("location")!).pathname).toBe("/operacao");
    const c = r.cookies.get("sessao_operacao")!;
    expect(c.value).toBe("segredo-op");
    expect(c.httpOnly).toBe(true);
    expect(r.cookies.get("sessao")).toBeUndefined();
  });

  it("operador recusado pelo backend (desligado) volta à entrada dizendo que o acesso não está ativo", async () => {
    const f = vi.fn()
      .mockResolvedValueOnce(resposta(DESC))
      .mockResolvedValueOnce(resposta({ access_token: "at" }))
      .mockResolvedValueOnce(resposta({ erro: "operador inativo" }, 401));
    const r = await receberCallbackOperacao(req("/api/operacao/callback?code=c&state=s1", pkce), { backend: "http://b", fetchImpl: f });
    const u = new URL(r.headers.get("location")!);
    expect(u.pathname + u.search).toBe("/operacao/entrar?erro=negado");
    expect(r.cookies.get("sessao_operacao")).toBeUndefined();
  });
});

describe("BFF do console — sair", () => {
  it("apaga a sessão no backend, limpa o cookie e encerra no realm do operador", async () => {
    const f = vi.fn().mockResolvedValueOnce(new Response(null, { status: 204 })).mockResolvedValueOnce(resposta(DESC));
    const r = await sairDoConsole(req("/api/operacao/logout", "sessao_operacao=abc_DEF-1"), { backend: "http://b", fetchImpl: f });
    expect(f.mock.calls[0][0]).toBe("http://b/operacao/sessoes");
    expect(f.mock.calls[0][1].headers.cookie).toBe("sessao_operacao=abc_DEF-1");
    const u = new URL(r.headers.get("location")!);
    expect(u.pathname).toBe("/realms/operacao/protocol/openid-connect/logout");
    expect(u.searchParams.get("post_logout_redirect_uri")).toBe(`${ORIGIN}/operacao/entrar`);
    expect(r.cookies.get("sessao_operacao")?.value).toBe("");
  });
});
