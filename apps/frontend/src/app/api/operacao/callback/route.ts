// ADR-0016 — callback do login do OPERADOR. Mesma ordem de segurança do callback das Casas: state ANTES do code
// (CSRF), troca code->token no realm do operador (descoberta refeita no servidor, nunca lida de cookie), mint da
// sessão do console no backend (`POST /operacao/sessoes`, que re-verifica o token), cookie `sessao_operacao`
// httpOnly. O access_token nunca chega ao navegador.

import { NextRequest, NextResponse } from "next/server";
import { resolveAppOrigin } from "../../auth/appOrigin";
import { backendUrl, buscarDescobertaOperacao, COOKIE_OPERACAO, destinoDoConsole } from "../descoberta";

export async function GET(request: NextRequest): Promise<NextResponse> {
  return receberCallbackOperacao(request);
}

function paraEntrar(origin: string, erro = "login"): NextResponse {
  return NextResponse.redirect(new URL(`/operacao/entrar?erro=${erro}`, origin));
}

export async function receberCallbackOperacao(
  request: NextRequest,
  opts?: { backend?: string; fetchImpl?: typeof fetch },
): Promise<NextResponse> {
  const origin = resolveAppOrigin(request);
  const code = request.nextUrl.searchParams.get("code");
  const state = request.nextUrl.searchParams.get("state");
  const cru = request.cookies.get("pkce_operacao")?.value;
  if (!code || !state || !cru) return paraEntrar(origin);

  let pkce: { codeVerifier?: unknown; state?: unknown; redirectPath?: unknown };
  try {
    pkce = JSON.parse(cru);
  } catch {
    return paraEntrar(origin);
  }
  if (typeof pkce.codeVerifier !== "string" || typeof pkce.state !== "string") return paraEntrar(origin);
  if (state !== pkce.state) return new NextResponse("state inválido", { status: 400 });

  const backend = backendUrl(opts);
  const f = opts?.fetchImpl ?? fetch;
  const d = await buscarDescobertaOperacao(backend, f);
  if (!d) return paraEntrar(origin);
  const kcBase = process.env.OPERACAO_KEYCLOAK_INTERNAL_URL ?? process.env.KEYCLOAK_INTERNAL_URL ?? d.baseUrl;

  let accessToken: string;
  try {
    const r = await f(`${kcBase}/realms/${d.realm}/protocol/openid-connect/token`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        client_id: d.clientId,
        code,
        redirect_uri: `${origin}/api/operacao/callback`,
        code_verifier: pkce.codeVerifier,
      }),
      cache: "no-store",
    });
    if (!r.ok) return paraEntrar(origin);
    ({ access_token: accessToken } = (await r.json()) as { access_token: string });
  } catch {
    return new NextResponse("Keycloak da Operação indisponível", { status: 502 });
  }

  let segredo: string;
  try {
    const r = await f(`${backend}/operacao/sessoes`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token: accessToken }),
      cache: "no-store",
    });
    // 401 aqui = operador desligado ou desconhecido: volta à entrada dizendo que o acesso não foi aceito
    if (!r.ok) return paraEntrar(origin, "negado");
    ({ sessao: segredo } = (await r.json()) as { sessao: string });
  } catch {
    return new NextResponse("backend indisponível", { status: 502 });
  }

  const destino = destinoDoConsole(typeof pkce.redirectPath === "string" ? pkce.redirectPath : null);
  const response = NextResponse.redirect(new URL(destino, origin));
  response.cookies.set(COOKIE_OPERACAO, segredo, {
    httpOnly: true,
    secure: true,
    sameSite: "lax",
    maxAge: 8 * 3600,
    path: "/",
  });
  response.cookies.delete({ name: "pkce_operacao", path: "/api/operacao" });
  return response;
}
