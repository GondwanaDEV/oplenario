// Início do fluxo Authorization Code + PKCE (S256) contra o Keycloak realm-per-tenant.
//
// Diferente do munex (client_id/base-url estáticos por env), o Plenário é MULTI-REALM: o realm,
// a base-url pública do Keycloak e o client-id vêm da descoberta do tenant
// (`GET ${backend}/auth/descoberta/:ente`, backend T3). O cookie `pkce` carrega os três junto com
// verifier/state/redirectPath para que o callback (T9) não precise refazer a descoberta.
//
// Falha de descoberta (400 uuid malformado, 404 ente desconhecido, ou backend inacessível) falha
// FECHADO: nunca produz um redirect de authorize quebrado. As três causas caem no MESMO
// `/entrar?erro=login` — não diferenciar 400 de 404 evita vazar se um tenant existe.

import { NextRequest, NextResponse } from "next/server";
import { COOKIE_ESCOLHA, lerEscolha } from "@/lib/entrada-cpf";
import { resolveAppOrigin } from "../appOrigin";
import { buscarDescoberta, redirecionarAoKeycloak, type OpcoesBackend } from "../pkce";
import { pedidoDeRedirect } from "../redirect";

function falhaFechada(origin: string): NextResponse {
  return NextResponse.redirect(new URL("/entrar?erro=login", origin));
}

// GET é um wrapper fino com a assinatura EXATA que o Next.js espera (sem 2º parâmetro) — um 2º
// parâmetro tipado como `opts` colide com o validador de rotas gerado pelo Next (mesmo padrão de
// src/lib/sse-proxy.ts + src/app/api/sessoes/[id]/plenario/route.ts: a lógica testável fica numa
// função à parte, injetável por opts, que o Route Handler apenas invoca).
export async function GET(request: NextRequest): Promise<NextResponse> {
  return iniciarLogin(request);
}

export async function iniciarLogin(request: NextRequest, opts?: OpcoesBackend): Promise<NextResponse> {
  const origin = resolveAppOrigin(request);
  const ente = request.nextUrl.searchParams.get("ente");
  if (!ente) return falhaFechada(origin);

  const descoberta = await buscarDescoberta(ente, opts);
  if (!descoberta) return falhaFechada(origin);

  // `via=govbr`: o cidadão pediu para entrar pelo gov.br. Casa sem o broker ligado volta à tela de participar
  // dizendo que não está disponível — nunca cai na tela de login institucional como se fosse a mesma coisa.
  const viaGovbr = request.nextUrl.searchParams.get("via") === "govbr";
  if (viaGovbr && descoberta.govbr !== true) {
    const volta = new URL(`/portal/casa/${encodeURIComponent(ente)}/participar`, origin);
    volta.searchParams.set("erro", "indisponivel");
    return NextResponse.redirect(volta);
  }

  // ADR-0025: a Câmara escolhida em /entrar/escolher (quem tem acesso a mais de uma). O usuário já conferido pelo CPF
  // vai como `login_hint` só se ESTA Câmara está na escolha; o destino pedido antes do CPF segue junto (validado de
  // novo aqui, como todo `redirect`). O cookie NÃO é apagado: quem clicou na Câmara errada e voltou ainda escolhe a
  // outra até ele vencer (5 min).
  const escolha = viaGovbr ? null : lerEscolha(request.cookies.get(COOKIE_ESCOLHA)?.value);
  const daEscolha = escolha?.casas.some((c) => c.enteId === ente) ? escolha : null;

  // `null` = ninguém pediu destino; o callback então escolhe a home da persona (destinoPorPapeis).
  const redirectPath =
    pedidoDeRedirect(request.nextUrl.searchParams.get("redirect"), origin) ??
    pedidoDeRedirect(daEscolha?.redirectPath ?? null, origin);

  return redirecionarAoKeycloak({
    origin,
    descoberta,
    redirectPath,
    loginHint: daEscolha?.hint ?? null,
    viaGovbr,
  });
}
