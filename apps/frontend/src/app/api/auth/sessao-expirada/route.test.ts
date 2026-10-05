import { NextRequest } from "next/server";
import { describe, expect, it, vi } from "vitest";
import { sessaoExpirada } from "./route";

const ORIGIN = "http://localhost:3000";
const BACKEND = "http://backend.test";

function req(caminho: string, sessao: string | null = "segredo-abc") {
  const r = new NextRequest(new URL(caminho, ORIGIN));
  if (sessao) r.cookies.set("sessao", sessao);
  return r;
}
const backendResponde = (status: number) => vi.fn(async () => new Response("{}", { status }));
const apagou = (resp: Response, nome: string) =>
  resp.headers.getSetCookie().some((c) => c.startsWith(`${nome}=;`) || (c.startsWith(`${nome}=`) && /Max-Age=0|Expires=Thu, 01 Jan 1970/i.test(c)));

describe("GET /api/auth/sessao-expirada", () => {
  it("o backend diz 401: apaga os cookies e leva a /entrar com o destino", async () => {
    const f = backendResponde(401);
    const resp = await sessaoExpirada(req("/api/auth/sessao-expirada?redirect=/proposicoes?x=1"), { backend: BACKEND, fetchImpl: f });
    expect(f).toHaveBeenCalledWith(`${BACKEND}/eu`, expect.objectContaining({ headers: { cookie: "sessao=segredo-abc" } }));
    const location = new URL(resp.headers.get("location")!);
    expect(location.pathname).toBe("/entrar");
    expect(location.searchParams.get("redirect")).toBe("/proposicoes?x=1");
    expect(apagou(resp, "sessao")).toBe(true);
    expect(apagou(resp, "sessao_kc")).toBe(true);
  });

  it("vindo do /inicio, o login decide o destino pelo papel: sem `redirect`", async () => {
    const resp = await sessaoExpirada(req("/api/auth/sessao-expirada?redirect=/inicio"), { backend: BACKEND, fetchImpl: backendResponde(401) });
    expect(resp.headers.get("location")).toBe(`${ORIGIN}/entrar`);
  });

  it("a sessão ainda vale: não desloga, devolve ao caminho pedido", async () => {
    const resp = await sessaoExpirada(req("/api/auth/sessao-expirada?redirect=/caixa"), { backend: BACKEND, fetchImpl: backendResponde(200) });
    expect(resp.headers.get("location")).toBe(`${ORIGIN}/caixa`);
    expect(resp.headers.getSetCookie()).toHaveLength(0);
  });

  it("backend fora do ar não é sessão expirada: não desloga", async () => {
    const f = vi.fn(async () => {
      throw new Error("ECONNREFUSED");
    });
    const resp = await sessaoExpirada(req("/api/auth/sessao-expirada?redirect=/caixa"), { backend: BACKEND, fetchImpl: f });
    expect(resp.headers.get("location")).toBe(`${ORIGIN}/caixa`);
    expect(resp.headers.getSetCookie()).toHaveLength(0);
  });

  it("sem cookie: vai a /entrar sem perguntar ao backend", async () => {
    const f = backendResponde(200);
    const resp = await sessaoExpirada(req("/api/auth/sessao-expirada?redirect=/caixa", null), { backend: BACKEND, fetchImpl: f });
    expect(f).not.toHaveBeenCalled();
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/entrar");
  });

  it("destino de outra origem é descartado", async () => {
    const resp = await sessaoExpirada(req("/api/auth/sessao-expirada?redirect=https://mal.example/x"), { backend: BACKEND, fetchImpl: backendResponde(401) });
    expect(resp.headers.get("location")).toBe(`${ORIGIN}/entrar`);
    const ok = await sessaoExpirada(req("/api/auth/sessao-expirada?redirect=//mal.example"), { backend: BACKEND, fetchImpl: backendResponde(200) });
    expect(ok.headers.get("location")).toBe(`${ORIGIN}/inicio`);
  });
});
