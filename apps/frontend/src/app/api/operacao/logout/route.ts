// ADR-0016 — sair do console: apaga a sessão no backend (best-effort), limpa o cookie e encerra a sessão no realm
// do operador (RP-logout, descoberta refeita no servidor). Backend fora do ar nunca impede sair.

import { NextRequest, NextResponse } from "next/server";
import { resolveAppOrigin } from "../../auth/appOrigin";
import { backendUrl, buscarDescobertaOperacao, COOKIE_OPERACAO, SEGREDO_VALIDO } from "../descoberta";

export async function POST(request: NextRequest): Promise<NextResponse> {
  return sairDoConsole(request);
}

export async function sairDoConsole(
  request: NextRequest,
  opts?: { backend?: string; fetchImpl?: typeof fetch },
): Promise<NextResponse> {
  const origin = resolveAppOrigin(request);
  const backend = backendUrl(opts);
  const f = opts?.fetchImpl ?? fetch;
  const segredo = request.cookies.get(COOKIE_OPERACAO)?.value;
  if (segredo && SEGREDO_VALIDO.test(segredo)) {
    try {
      await f(`${backend}/operacao/sessoes`, {
        method: "DELETE",
        headers: { cookie: `${COOKIE_OPERACAO}=${segredo}` },
        cache: "no-store",
      });
    } catch {
      // segue o logout local
    }
  }
  const d = await buscarDescobertaOperacao(backend, f);
  const destino = d
    ? (() => {
        const u = new URL(`${d.baseUrl}/realms/${d.realm}/protocol/openid-connect/logout`);
        u.searchParams.set("client_id", d.clientId);
        u.searchParams.set("post_logout_redirect_uri", `${origin}/operacao/entrar`);
        return u;
      })()
    : new URL("/operacao/entrar", origin);
  const response = NextResponse.redirect(destino, 303);
  response.cookies.delete({ name: COOKIE_OPERACAO, path: "/" });
  return response;
}
