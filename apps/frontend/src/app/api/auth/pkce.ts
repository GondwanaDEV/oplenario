// O começo do Authorization Code + PKCE (S256) contra o Keycloak da Casa, comum às duas portas de entrada:
// GET /api/auth/login (o link da Câmara, a escolha de Câmara, o gov.br) e POST /api/auth/entrar (o CPF, ADR-0025).
//
// O realm, a base-url pública do Keycloak e o client-id vêm da descoberta do tenant
// (`GET ${backend}/auth/descoberta/:ente`). O cookie `pkce` carrega os três junto com verifier/state/redirectPath para
// que o callback não precise refazer a descoberta.

import { randomBytes, createHash } from "crypto";
import { NextResponse } from "next/server";

export interface Descoberta {
  realm: string;
  "base-url": string;
  "client-id": string;
  // ADR-0015: o realm desta Casa tem o broker gov.br (o backend só diz `true` quando está configurado).
  govbr?: boolean;
}

export interface OpcoesBackend {
  backend?: string;
  fetchImpl?: typeof fetch;
}

/** O backend desta instância. `opts.backend` é injeção só de teste; em produção seria superfície de SSRF. */
export function backendDe(opts?: OpcoesBackend): string {
  if (opts?.backend && process.env.NODE_ENV === "production") {
    throw new Error("opts.backend não é permitido fora de ambiente de teste");
  }
  return opts?.backend ?? process.env.BACKEND_URL ?? "http://localhost:8888";
}

/** A descoberta do tenant, ou `null` em QUALQUER falha (400, 404, rede, corpo torto) — quem chama falha fechado. */
export async function buscarDescoberta(ente: string, opts?: OpcoesBackend): Promise<Descoberta | null> {
  const f = opts?.fetchImpl ?? fetch;
  const url = `${backendDe(opts)}/auth/descoberta/${encodeURIComponent(ente)}`; // fora do try: o guard de SSRF lança
  try {
    const resp = await f(url, { cache: "no-store" });
    if (!resp.ok) return null;
    const d = (await resp.json()) as Descoberta;
    if (typeof d?.realm !== "string" || typeof d["base-url"] !== "string" || typeof d["client-id"] !== "string") {
      return null;
    }
    return d;
  } catch {
    return null;
  }
}

/**
 * O redirect ao `authorize` do Keycloak, com o cookie `pkce` de curta duração.
 * - `redirectPath`: destino já validado same-origin (ou null: o callback escolhe pela persona).
 * - `loginHint`: o usuário da pessoa no realm (o identidade-id), quando ela já se identificou pelo CPF (ADR-0025).
 * - `viaGovbr`: o Keycloak da Casa pula a própria tela e vai direto ao gov.br.
 * - `status`: 307 para GET; 303 quando quem chama atende um POST (o navegador segue com GET).
 */
export function redirecionarAoKeycloak({
  origin,
  descoberta,
  redirectPath,
  loginHint = null,
  viaGovbr = false,
  status = 307,
}: {
  origin: string;
  descoberta: Descoberta;
  redirectPath: string | null;
  loginHint?: string | null;
  viaGovbr?: boolean;
  status?: 303 | 307;
}): NextResponse {
  const realm = descoberta.realm;
  const baseUrl = descoberta["base-url"];
  const clientId = descoberta["client-id"];

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
  // O Keycloak da Casa pula a própria tela e vai direto ao gov.br (o IdP fica escondido da tela institucional).
  if (viaGovbr) authorizeUrl.searchParams.set("kc_idp_hint", "govbr");
  // ADR-0025: a tela de senha já sabe quem é — o campo de usuário vai escondido (tema `oplenario`).
  if (loginHint) authorizeUrl.searchParams.set("login_hint", loginHint);

  const response = NextResponse.redirect(authorizeUrl, status);
  // Cookie de curta duração — só precisa sobreviver entre este redirect e o callback.
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
