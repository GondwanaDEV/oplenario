// GET /api/auth/conta — leva a pessoa à página de conta do Keycloak da Casa dela ("Personal info"), onde ela
// troca o PRÓPRIO e-mail de acesso. O `admin_ente` não troca o e-mail de quem já entrou (seria tomar a conta);
// quem já entrou troca ali (docs/16, achado B de 05/10/2026). A página é do Keycloak, em inglês.
//
// De onde vem a URL: do cookie companheiro `sessao_kc` (httpOnly), que o callback do login grava com a
// descoberta do tenant (`GET /auth/descoberta/:ente`): realm, base pública do Keycloak e client. É a MESMA fonte
// do logout do Keycloak. Nada é montado a mão, e o navegador nunca lê esses valores — o link da tela aponta
// para esta rota, e a rota devolve o redirect. Forma e host-pin vêm de `lerSessaoKc` (kc-cookie): cookie
// ausente, quebrado ou de outro Keycloak NÃO vira destino.
//
// Sem sessão ou sem o cookie (sessão aberta antes dele existir): 404 em texto, dizendo o que fazer. Nunca
// cai num destino chutado.
import { NextRequest, NextResponse } from "next/server";
import { lerSessaoKc } from "../kc-cookie";

// GET é um wrapper fino com a assinatura EXATA que o Next.js espera (sem 2º parâmetro) — mesmo padrão das
// outras rotas de /api/auth.
export async function GET(request: NextRequest): Promise<NextResponse> {
  return abrirContaDeAcesso(request);
}

export async function abrirContaDeAcesso(request: NextRequest): Promise<NextResponse> {
  const temSessao = !!request.cookies.get("sessao")?.value;
  const sessaoKc = temSessao ? lerSessaoKc(request.cookies.get("sessao_kc")?.value) : null;
  if (!sessaoKc) {
    return new NextResponse(
      "Não foi possível abrir a página de conta desta sessão. Saia do sistema e entre de novo.",
      { status: 404, headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" } },
    );
  }
  const resposta = NextResponse.redirect(`${sessaoKc.baseUrl}/realms/${sessaoKc.realm}/account`);
  resposta.headers.set("cache-control", "no-store");
  return resposta;
}
