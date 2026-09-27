// ADR-0016 — início do login do OPERADOR: Authorization Code + PKCE (S256) no realm do operador, cuja tela exige
// senha E chave de segurança. Mesmo desenho do login das Casas (api/auth/login), com o realm vindo da descoberta
// do console. Falha de descoberta -> volta à tela de entrada com erro (fail-closed, nunca um authorize quebrado).

import { randomBytes, createHash } from "crypto";
import { NextRequest, NextResponse } from "next/server";
import { resolveAppOrigin } from "../../auth/appOrigin";
import { backendUrl, buscarDescobertaOperacao, destinoDoConsole } from "../descoberta";

export async function GET(request: NextRequest): Promise<NextResponse> {
  return iniciarLoginOperacao(request);
}

export async function iniciarLoginOperacao(
  request: NextRequest,
  opts?: { backend?: string; fetchImpl?: typeof fetch },
): Promise<NextResponse> {
  const origin = resolveAppOrigin(request);
  const d = await buscarDescobertaOperacao(backendUrl(opts), opts?.fetchImpl ?? fetch);
  if (!d) return NextResponse.redirect(new URL("/operacao/entrar?erro=login", origin));

  const codeVerifier = randomBytes(32).toString("base64url");
  const state = randomBytes(16).toString("base64url");
  const url = new URL(`${d.baseUrl}/realms/${d.realm}/protocol/openid-connect/auth`);
  url.searchParams.set("client_id", d.clientId);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("scope", "openid");
  url.searchParams.set("redirect_uri", `${origin}/api/operacao/callback`);
  url.searchParams.set("state", state);
  url.searchParams.set("code_challenge", createHash("sha256").update(codeVerifier).digest("base64url"));
  url.searchParams.set("code_challenge_method", "S256");

  const response = NextResponse.redirect(url);
  response.cookies.set(
    "pkce_operacao",
    JSON.stringify({ codeVerifier, state, redirectPath: destinoDoConsole(request.nextUrl.searchParams.get("redirect")) }),
    { httpOnly: true, secure: true, sameSite: "lax", maxAge: 300, path: "/api/operacao" },
  );
  return response;
}
