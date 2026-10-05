// GET /api/auth/sessao-expirada?redirect=<caminho> — para onde a tela manda quem tem o cookie `sessao` mas o backend
// já não reconhece a sessão (expirou, foi encerrada, o vínculo caiu). Sem isto o middleware deixava a pessoa passar
// (ele só olha a PRESENÇA do cookie) e a tela mostrava "não foi possível carregar", sem login e sem como sair.
//
// A rota pergunta ao backend antes de apagar: só limpa os cookies e leva a `/entrar` quando `/eu` responde 401. Com a
// sessão ainda válida (ou o backend fora do ar), devolve a pessoa ao caminho pedido, sem deslogar — um link para esta
// rota vindo de fora não derruba a sessão de ninguém. O caminho passa pelo mesmo filtro do login (só a própria origem).
import { NextRequest, NextResponse } from "next/server";
import { resolveAppOrigin } from "../appOrigin";
import { pedidoDeRedirect } from "../redirect";

export async function GET(request: NextRequest): Promise<NextResponse> {
  return sessaoExpirada(request);
}

export async function sessaoExpirada(
  request: NextRequest,
  opts?: { backend?: string; fetchImpl?: typeof fetch },
): Promise<NextResponse> {
  if (opts?.backend && process.env.NODE_ENV === "production") {
    throw new Error("opts.backend não é permitido fora de ambiente de teste");
  }
  const origin = resolveAppOrigin(request);
  const pedido = pedidoDeRedirect(request.nextUrl.searchParams.get("redirect"), origin);
  const segredo = request.cookies.get("sessao")?.value;

  let expirou = !segredo;
  if (segredo) {
    const backend = opts?.backend ?? process.env.BACKEND_URL ?? "http://localhost:8888";
    const f = opts?.fetchImpl ?? fetch;
    try {
      const r = await f(`${backend}/eu`, { headers: { cookie: `sessao=${segredo}` }, cache: "no-store" });
      expirou = r.status === 401;
    } catch {
      expirou = false; // backend fora do ar não é sessão expirada: não desloga
    }
  }

  if (!expirou) return NextResponse.redirect(new URL(pedido ?? "/inicio", origin));

  const entrar = new URL("/entrar", origin);
  if (pedido && pedido !== "/" && !pedido.startsWith("/inicio")) entrar.searchParams.set("redirect", pedido);
  const response = NextResponse.redirect(entrar);
  response.cookies.delete({ name: "sessao", path: "/" });
  response.cookies.delete({ name: "sessao_kc", path: "/" });
  return response;
}
