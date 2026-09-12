import { NextRequest } from "next/server";
import { describe, it, expect, afterEach, vi } from "vitest";
import { middleware } from "./middleware";

const ORIGIN = "http://localhost:3000";

function req(path: string, cookie?: string): NextRequest {
  const headers = new Headers();
  if (cookie) headers.set("cookie", cookie);
  return new NextRequest(new URL(path, ORIGIN), { headers });
}

function setNodeEnv(value: string | undefined) {
  vi.stubEnv("NODE_ENV", value ?? "");
}

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("middleware — gate de presença do cookie sessao em rotas protegidas", () => {
  it("rota protegida sem cookie sessao → redireciona para /entrar?redirect=<path original>", async () => {
    const resp = middleware(req("/proposicoes?filtro=abertas"));
    expect(resp.status).toBe(307);
    const location = new URL(resp.headers.get("location")!);
    expect(location.pathname).toBe("/entrar");
    expect(location.searchParams.get("redirect")).toBe("/proposicoes?filtro=abertas");
  });

  it("rota protegida standalone /sessoes/:id/plenario sem cookie → redireciona", async () => {
    const resp = middleware(req("/sessoes/abc123/plenario"));
    expect(resp.status).toBe(307);
    const location = new URL(resp.headers.get("location")!);
    expect(location.pathname).toBe("/entrar");
    expect(location.searchParams.get("redirect")).toBe("/sessoes/abc123/plenario");
  });

  it("rota protegida COM cookie sessao → passa (sem redirect)", async () => {
    const resp = middleware(req("/proposicoes", "sessao=segredo-opaco"));
    expect(resp.headers.get("location")).toBeNull();
  });

  // Etapa 5 fatia 6 (a FOLHA) — a folha é nominal e carrega o motivo da justificativa (LGPD, potencial
  // dado de saúde), MESMO gate 'secretario' da chamada. Este é um teste de UX (evita round-trip a uma
  // página que o backend recusaria de qualquer forma) — a autoridade real é o 401/403 do servidor.
  it("rota protegida standalone /sessoes/:id/folha sem cookie → redireciona", async () => {
    const resp = middleware(req("/sessoes/abc123/folha"));
    expect(resp.status).toBe(307);
    const location = new URL(resp.headers.get("location")!);
    expect(location.pathname).toBe("/entrar");
    expect(location.searchParams.get("redirect")).toBe("/sessoes/abc123/folha");
  });

  it("rota vereador protegida sem cookie → redireciona", async () => {
    const resp = middleware(req("/vereador"));
    expect(resp.status).toBe(307);
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/entrar");
  });

  // fatia "demo-tres-consertos" #3 — a cidadã autenticada (vínculo `cidadao`, sem papel) tem sessão real
  // mas nenhuma tela chamava a rota. `/acompanhamentos` é a primeira; exige SESSÃO (o mesmo gate de
  // presença de cookie de qualquer outra rota protegida), nunca papel — não há guard de papel em (cidadao).
  it("rota /acompanhamentos (cidadã) sem cookie → redireciona pro mesmo /entrar", async () => {
    const resp = middleware(req("/acompanhamentos"));
    expect(resp.status).toBe(307);
    const location = new URL(resp.headers.get("location")!);
    expect(location.pathname).toBe("/entrar");
    expect(location.searchParams.get("redirect")).toBe("/acompanhamentos");
  });

  it("rota /acompanhamentos COM cookie sessao → passa (sem redirect)", async () => {
    const resp = middleware(req("/acompanhamentos", "sessao=segredo-opaco"));
    expect(resp.headers.get("location")).toBeNull();
  });

  it("(publico) /portal sem cookie → nunca gated, passa direto", async () => {
    const resp = middleware(req("/portal/materias/123"));
    expect(resp.headers.get("location")).toBeNull();
  });

  it("/entrar sem cookie → passa (não gated)", async () => {
    const resp = middleware(req("/entrar"));
    expect(resp.headers.get("location")).toBeNull();
  });

  it("dev: ?token= presente em rota protegida sem cookie, NODE_ENV!=='production' → passa (bypass)", async () => {
    setNodeEnv("development");
    const resp = middleware(req("/proposicoes?token=dev-abc"));
    expect(resp.headers.get("location")).toBeNull();
  });

  it("produção: ?token= NÃO faz bypass — ainda redireciona", async () => {
    setNodeEnv("production");
    const resp = middleware(req("/proposicoes?token=dev-abc"));
    expect(resp.status).toBe(307);
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/entrar");
  });
});
