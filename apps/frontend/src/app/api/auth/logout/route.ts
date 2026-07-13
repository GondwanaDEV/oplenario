// POST /api/auth/logout — encerra a sessão opaca no backend (`DELETE /auth/sessoes`, cookie
// encaminhado EXPLICITAMENTE — o `fetch` do Node não repassa cookies do request recebido, isso é
// comportamento de fetch de NAVEGADOR, não server-to-server; mesmo achado documentado no callback,
// T9) e limpa o cookie `sessao` local. BEST-EFFORT por desenho: falha do backend (indisponível ou
// não-2xx) ou ausência do cookie NUNCA bloqueia o logout local — o usuário precisa conseguir sair
// mesmo com o backend fora do ar ou já deslogado (backend T5 já é idempotente: DELETE por hash
// sem match é no-op, não erro).
//
// RP-logout no Keycloak (end-session) fica FORA desta rota, por desenho, não por esquecimento:
// diferente do callback (T9), que recebe realm/base-url/client-id via o cookie `pkce`, o logout
// roda DEPOIS que esse cookie já foi apagado (T9 limpa `pkce` ao mintar a sessão) — e o cookie
// `sessao` carrega só o segredo OPACO (§2 das shared-decisions), sem `ente`/`realm`. O backend
// (T5, `DELETE /auth/sessoes`) também não devolve nada úteis (204 sem corpo). Sem uma fonte de
// tenant disponível NESTA rota, montar a URL de end-session exigiria inventar plumbing fora do
// escopo desta task (nova rota de "session info", ou um cookie de tenant de vida mais longa que o
// `pkce`) — CONCERN para o controller, não decisão unilateral aqui. Por ora: encerra local e
// redireciona para `/entrar` (a tela pública de login).
import { NextRequest, NextResponse } from "next/server";
import { resolveAppOrigin } from "../appOrigin";

// Defesa em profundidade antes de interpolar o valor do cookie no header `cookie` enviado ao
// backend (o cookie é HttpOnly, mas isso só bloqueia acesso via JS — não impede um Cookie header
// forjado). O segredo é gerado como 32 bytes aleatórios em base64url SEM padding (backend,
// `gerar-segredo`) — 43 chars de `[A-Za-z0-9_-]`; o teto abaixo é generoso o bastante para nunca
// barrar um segredo real.
const SEGREDO_VALIDO = /^[A-Za-z0-9_-]{1,128}$/;

// POST é um wrapper fino com a assinatura EXATA que o Next.js espera (sem 2º parâmetro) — mesmo
// padrão de login/route.ts e callback/route.ts: a lógica testável fica numa função à parte,
// injetável por opts, que o Route Handler apenas invoca.
export async function POST(request: NextRequest): Promise<NextResponse> {
  return encerrarSessao(request);
}

export async function encerrarSessao(
  request: NextRequest,
  opts?: { backend?: string; fetchImpl?: typeof fetch },
): Promise<NextResponse> {
  // `opts.backend`/`opts.fetchImpl` são injeção de dependência só de teste; em produção seriam
  // superfície de SSRF (mesmo guard de src/lib/sse-proxy.ts, login/route.ts e callback/route.ts).
  if (opts?.backend && process.env.NODE_ENV === "production") {
    throw new Error("opts.backend não é permitido fora de ambiente de teste");
  }

  const segredo = request.cookies.get("sessao")?.value;
  if (segredo && SEGREDO_VALIDO.test(segredo)) {
    const backend = opts?.backend ?? process.env.BACKEND_URL ?? "http://localhost:8888";
    const f = opts?.fetchImpl ?? fetch;
    try {
      const resp = await f(`${backend}/auth/sessoes`, {
        method: "DELETE",
        headers: { cookie: `sessao=${segredo}` },
        cache: "no-store",
      });
      if (!resp.ok) {
        console.error("logout: backend rejeitou o encerramento da sessão", { status: resp.status });
      }
    } catch {
      // Backend indisponível: segue o logout local mesmo assim (best-effort, ver docstring do ns).
    }
  }

  const response = NextResponse.redirect(new URL("/entrar", resolveAppOrigin(request)));
  // O cookie `sessao` foi SETADO com path "/" (callback/route.ts) — delete precisa da MESMA tupla
  // (nome, path).
  response.cookies.delete({ name: "sessao", path: "/" });
  return response;
}
