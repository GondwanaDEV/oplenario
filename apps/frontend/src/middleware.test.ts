import { NextRequest } from "next/server";
import { describe, it, expect, afterEach, vi } from "vitest";
import { readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { config, middleware } from "./middleware";

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

  it("rota /meus-protocolos (cidadã) sem cookie → mesmo gate de sessão", async () => {
    const resp = middleware(req("/meus-protocolos"));
    expect(resp.status).toBe(307);
    expect(new URL(resp.headers.get("location")!).searchParams.get("redirect")).toBe("/meus-protocolos");
    expect(middleware(req("/meus-protocolos", "sessao=segredo-opaco")).headers.get("location")).toBeNull();
  });

  it("/atas (livro de atas interno) sem cookie → mesmo gate de sessão", async () => {
    const resp = middleware(req("/atas"));
    expect(new URL(resp.headers.get("location")!).searchParams.get("redirect")).toBe("/atas");
    expect(middleware(req("/atas", "sessao=segredo-opaco")).headers.get("location")).toBeNull();
  });

  it("/contas e a ficha (ADR-0021) sem cookie → mesmo gate de sessão; o portal das contas não", async () => {
    const resp = middleware(req("/contas/pc1"));
    expect(resp.status).toBe(307);
    expect(new URL(resp.headers.get("location")!).searchParams.get("redirect")).toBe("/contas/pc1");
    expect(middleware(req("/contas", "sessao=segredo-opaco")).headers.get("location")).toBeNull();
    expect(middleware(req("/portal/casa/ce/contas")).headers.get("location")).toBeNull();
  });

  it("/administracao (área do admin_ente) sem cookie → mesmo gate de sessão", async () => {
    const resp = middleware(req("/administracao"));
    expect(resp.status).toBe(307);
    expect(new URL(resp.headers.get("location")!).searchParams.get("redirect")).toBe("/administracao");
    expect(middleware(req("/administracao", "sessao=segredo-opaco")).headers.get("location")).toBeNull();
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

describe("middleware — o console do operador (ADR-0016) é outra esfera", () => {
  it("sem o cookie do console → vai para /operacao/entrar com o destino", () => {
    const resp = middleware(req("/operacao/casas/nova"));
    const location = new URL(resp.headers.get("location")!);
    expect(location.pathname).toBe("/operacao/entrar");
    expect(location.searchParams.get("redirect")).toBe("/operacao/casas/nova");
  });

  it("o cookie `sessao` de uma Casa NÃO abre o console", () => {
    const resp = middleware(req("/operacao", "sessao=segredo-da-casa"));
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/operacao/entrar");
  });

  it("o cookie do console abre o console", () => {
    expect(middleware(req("/operacao", "sessao_operacao=segredo")).headers.get("location")).toBeNull();
  });

  it("a tela de entrada do console é pública", () => {
    expect(middleware(req("/operacao/entrar")).headers.get("location")).toBeNull();
  });

  it("o cookie do console não abre uma área de Casa", () => {
    const resp = middleware(req("/proposicoes", "sessao_operacao=segredo"));
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/entrar");
  });
});

// O gate é por allowlist, então rota nova sem entrada na lista fica aberta sem ninguém notar (eram 17 em
// 04/10/2026). Este teste mede a lista contra as páginas que existem em disco.
describe("middleware — toda página autenticada está atrás do gate", () => {
  const APP = join(__dirname, "app");

  function paginas(dir: string, prefixo: string): string[] {
    return readdirSync(dir).flatMap((nome) => {
      const caminho = join(dir, nome);
      if (statSync(caminho).isDirectory()) return paginas(caminho, `${prefixo}/${nome}`);
      return nome === "page.tsx" ? [prefixo] : [];
    });
  }

  // `[id]` vira um valor qualquer: o gate decide pelo prefixo, não pelo parâmetro.
  const rotas = [
    ...["(interno)", "(vereador)", "(cidadao)"].flatMap((grupo) => paginas(join(APP, grupo), "")),
    ...paginas(join(APP, "sessoes"), "/sessoes"),
  ].map((rota) => rota.replace(/\[[^\]]+\]/g, "abc"));

  it("mede páginas de verdade (se a pasta mudar de lugar, o teste não passa vazio)", () => {
    expect(rotas.length).toBeGreaterThan(50);
    expect(rotas).toContain("/atendimento");
    expect(rotas).toContain("/sessoes/abc/conduzir");
  });

  it.each(rotas)("%s sem cookie → /entrar", (rota) => {
    const resp = middleware(req(rota));
    expect(resp.status).toBe(307);
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/entrar");
  });

  it.each(rotas)("%s está no matcher (sem isso o Next nem chama o middleware)", (rota) => {
    expect(casaNoMatcher(rota)).toBe(true);
  });
});

/** O matcher é uma expressão do Next ("/(...)"); aqui ela vale como regex sobre o caminho inteiro. */
function casaNoMatcher(caminho: string): boolean {
  return config.matcher.some((m) => new RegExp(`^${m}$`).test(caminho));
}

// 05/10/2026: deny-by-default. Quem chega sem sessão em qualquer caminho que não seja público vai direto à tela do
// CPF; a raiz não é mais uma página de passagem ("Entrar na sua Câmara"), e um caminho desconhecido não abre um 404.
describe("middleware — sem sessão, todo caminho que não é público vai direto ao login", () => {
  it("a raiz sem sessão → /entrar, sem `redirect` (não há para onde voltar)", () => {
    const resp = middleware(req("/"));
    expect(resp.status).toBe(307);
    const location = new URL(resp.headers.get("location")!);
    expect(location.pathname).toBe("/entrar");
    expect(location.search).toBe("");
  });

  it("a raiz com sessão → /inicio (que leva cada papel à sua tela)", () => {
    const resp = middleware(req("/", "sessao=segredo-opaco"));
    expect(new URL(resp.headers.get("location")!).pathname).toBe("/inicio");
  });

  it("caminho desconhecido sem sessão → /entrar com o destino; com sessão → segue (e o Next dá o 404)", () => {
    const resp = middleware(req("/qualquer-coisa/abc?x=1"));
    expect(resp.status).toBe(307);
    const location = new URL(resp.headers.get("location")!);
    expect(location.pathname).toBe("/entrar");
    expect(location.searchParams.get("redirect")).toBe("/qualquer-coisa/abc?x=1");
    expect(middleware(req("/qualquer-coisa", "sessao=segredo-opaco")).headers.get("location")).toBeNull();
  });

  it("o que é público passa sem sessão: portal, entrada, status, API, internos e arquivos estáticos", () => {
    for (const caminho of [
      "/portal/casa/abc",
      "/entrar",
      "/entrar/escolher",
      "/entrar/abc",
      "/status",
      "/api/auth/login",
      "/_next/static/chunks/x.js",
      "/fontes/MonaSans.woff2",
      "/favicon.ico",
      "/globe.svg",
      "/operacao/entrar",
    ]) {
      expect(middleware(req(caminho)).headers.get("location"), caminho).toBeNull();
    }
  });

  it("prefixo parecido com um público não passa: /portalx, /statusx e /entrarx pedem sessão", () => {
    for (const caminho of ["/portalx", "/statusx", "/entrarx"]) {
      expect(new URL(middleware(req(caminho)).headers.get("location")!).pathname, caminho).toBe("/entrar");
    }
  });

  it("o matcher cobre a raiz e o caminho desconhecido, e deixa de fora a API e os internos do Next", () => {
    expect(casaNoMatcher("/")).toBe(true);
    expect(casaNoMatcher("/qualquer-coisa")).toBe(true);
    expect(casaNoMatcher("/portal/casa/abc")).toBe(true);
    expect(casaNoMatcher("/api/auth/login")).toBe(false);
    expect(casaNoMatcher("/_next/static/x.js")).toBe(false);
  });

  // As páginas públicas em disco (o grupo `(publico)` e as que ficam na raiz do app sem grupo autenticado) abrem sem
  // sessão. Página pública nova fora de `CAMINHOS_PUBLICOS` reprova aqui — o defeito seria ela cair no login.
  const APP = join(__dirname, "app");
  function paginasPublicas(dir: string, prefixo: string): string[] {
    return readdirSync(dir).flatMap((nome) => {
      const caminho = join(dir, nome);
      if (!statSync(caminho).isDirectory()) return nome === "page.tsx" ? [prefixo || "/"] : [];
      return paginasPublicas(caminho, /^\(.*\)$/.test(nome) ? prefixo : `${prefixo}/${nome}`);
    });
  }
  const publicas = paginasPublicas(join(APP, "(publico)"), "")
    .filter((r) => r !== "/")
    .map((r) => r.replace(/\[[^\]]+\]/g, "abc"));

  it("mede as páginas públicas de verdade", () => {
    expect(publicas).toContain("/portal/casa/abc/leis");
    expect(publicas).toContain("/entrar");
  });

  it.each(publicas)("%s (pública) abre sem sessão", (rota) => {
    expect(middleware(req(rota)).headers.get("location")).toBeNull();
  });
});
