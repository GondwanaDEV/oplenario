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

import { randomBytes, createHash } from "crypto";
import { NextRequest, NextResponse } from "next/server";
import { resolveAppOrigin } from "../appOrigin";
import { resolveRedirectPath } from "../redirect";

interface Descoberta {
  realm: string;
  "base-url": string;
  "client-id": string;
}

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

export async function iniciarLogin(
  request: NextRequest,
  opts?: { backend?: string; fetchImpl?: typeof fetch },
): Promise<NextResponse> {
  // `opts.backend` é injeção de dependência só de teste; em produção seria superfície de SSRF
  // (mesmo guard de src/lib/sse-proxy.ts).
  if (opts?.backend && process.env.NODE_ENV === "production") {
    throw new Error("opts.backend não é permitido fora de ambiente de teste");
  }

  const origin = resolveAppOrigin(request);
  const ente = request.nextUrl.searchParams.get("ente");
  if (!ente) return falhaFechada(origin);

  const backend = opts?.backend ?? process.env.BACKEND_URL ?? "http://localhost:8888";
  const f = opts?.fetchImpl ?? fetch;

  let descoberta: Descoberta;
  try {
    const resp = await f(`${backend}/auth/descoberta/${encodeURIComponent(ente)}`, {
      cache: "no-store",
    });
    if (!resp.ok) return falhaFechada(origin);
    descoberta = (await resp.json()) as Descoberta;
  } catch {
    return falhaFechada(origin);
  }

  const realm = descoberta.realm;
  const baseUrl = descoberta["base-url"];
  const clientId = descoberta["client-id"];

  const redirectPath = resolveRedirectPath(request.nextUrl.searchParams.get("redirect"), origin);
  const codeVerifier = randomBytes(32).toString("base64url");
  const codeChallenge = createHash("sha256").update(codeVerifier).digest("base64url");
  const state = randomBytes(16).toString("base64url");

  const authorizeUrl = new URL(`${baseUrl}/realms/${realm}/protocol/openid-connect/auth`);
  authorizeUrl.searchParams.set("client_id", clientId);
  authorizeUrl.searchParams.set("response_type", "code");
  authorizeUrl.searchParams.set("scope", "openid");
  authorizeUrl.searchParams.set("redirect_uri", `${origin}/api/auth/callback`);
  authorizeUrl.searchParams.set("state", state);
  authorizeUrl.searchParams.set("code_challenge", codeChallenge);
  authorizeUrl.searchParams.set("code_challenge_method", "S256");

  const response = NextResponse.redirect(authorizeUrl);
  // Cookie de curta duração — só precisa sobreviver entre este redirect e o callback (T9).
  response.cookies.set(
    "pkce",
    JSON.stringify({ codeVerifier, state, redirectPath, realm, baseUrl, clientId }),
    {
      httpOnly: true,
      secure: true,
      sameSite: "lax",
      maxAge: 300,
      path: "/api/auth",
    },
  );
  return response;
}
