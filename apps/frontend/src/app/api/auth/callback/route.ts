// Callback OIDC (Authorization Code + PKCE, client público, sem client_secret): valida `state`
// contra o cookie `pkce` (proteção CSRF do próprio fluxo, ver `resolveRedirectPath`/ADR do login),
// troca `code` por um access_token junto ao Keycloak do tenant (realm/baseUrl/clientId vieram do
// cookie — o Keycloak devolve só code+state, nunca o ente; T8 já resolveu a descoberta) e minta a
// sessão opaca no backend (`POST /auth/sessoes`, T4/T5). O access_token do Keycloak NUNCA chega ao
// navegador — só o `segredo` opaco que o mint devolve, e só como cookie httpOnly.
//
// Ordem de segurança (INEGOCIÁVEL, não reordenar): 1) ler code/state/cookie; 2) comparar state ANTES
// de tocar no code (CSRF); 3) trocar code→token; 4) mintar sessão; 5) setar cookie + limpar pkce +
// redirecionar. Cada etapa falha fechado (401/502) sem vazar o token adiante.
//
// Cookie companheiro `sessao_kc`: o cookie `pkce` (única fonte de realm/baseUrl/clientId) é limpo
// AQUI mesmo, ao mintar a sessão — então o logout (T10), que roda bem depois, não teria de onde ler
// esses valores para montar o RP-logout do Keycloak. Solução (decisão do controller): setar, no
// MESMO passo em que `sessao` é setado, um cookie companheiro `sessao_kc` só com os 3 valores
// PÚBLICOS de descoberta (`GET /auth/descoberta/:ente`, sem auth) — nunca o access_token nem o
// segredo da sessão. httpOnly/secure/lax, mesma vida (12h) do cookie `sessao`.

import { NextRequest, NextResponse } from "next/server";
import { resolveAppOrigin } from "../appOrigin";
import { destinoPorPapeis, resolveRedirectPath } from "../redirect";
import { validarDescobertaKc } from "../kc-cookie";

interface PkcePayload {
  codeVerifier: string;
  state: string;
  redirectPath: string;
  realm: string;
  baseUrl: string;
  clientId: string;
}

function paraLogin(origin: string): NextResponse {
  return NextResponse.redirect(new URL("/api/auth/login", origin));
}

// GET é um wrapper fino com a assinatura EXATA que o Next.js espera (sem 2º parâmetro) — mesmo
// padrão de login/route.ts e src/lib/sse-proxy.ts: a lógica testável fica numa função à parte,
// injetável por opts, que o Route Handler apenas invoca.
export async function GET(request: NextRequest): Promise<NextResponse> {
  return receberCallback(request);
}

export async function receberCallback(
  request: NextRequest,
  opts?: { backend?: string; fetchImpl?: typeof fetch },
): Promise<NextResponse> {
  // `opts.backend`/`opts.fetchImpl` são injeção de dependência só de teste; em produção seriam
  // superfície de SSRF (mesmo guard de src/lib/sse-proxy.ts e login/route.ts).
  if (opts?.backend && process.env.NODE_ENV === "production") {
    throw new Error("opts.backend não é permitido fora de ambiente de teste");
  }

  const appOrigin = resolveAppOrigin(request);
  const code = request.nextUrl.searchParams.get("code");
  const state = request.nextUrl.searchParams.get("state");
  const pkceCookie = request.cookies.get("pkce")?.value;
  if (!code || !state || !pkceCookie) {
    return paraLogin(appOrigin);
  }

  let pkce: PkcePayload;
  try {
    pkce = JSON.parse(pkceCookie) as PkcePayload;
  } catch {
    return paraLogin(appOrigin);
  }

  // Valida a FORMA dos campos de descoberta ANTES de interpolá-los no token-exchange server-side: `realm`
  // entra no PATH da URL do token (não passa por URLSearchParams -> path-injection se forjado); baseUrl/
  // clientId idem. Mesma disciplina do logout (../kc-cookie). Fail-closed -> re-login. (revisão whole-branch)
  if (!validarDescobertaKc(pkce)) {
    return paraLogin(appOrigin);
  }

  // Comparar `state` ANTES de qualquer uso do `code` — proteção CSRF do fluxo OIDC. Nada de rede,
  // nada de fetch, acontece antes desta checagem.
  if (state !== pkce.state) {
    return new NextResponse("state inválido", { status: 400 });
  }

  const backend = opts?.backend ?? process.env.BACKEND_URL ?? "http://localhost:8888";
  const f = opts?.fetchImpl ?? fetch;
  const kcBase = process.env.KEYCLOAK_INTERNAL_URL ?? pkce.baseUrl;

  let accessToken: string;
  try {
    const tokenResp = await f(
      `${kcBase}/realms/${pkce.realm}/protocol/openid-connect/token`,
      {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        // Client PÚBLICO (PKCE S256, sem client_secret) — oplenario-web foi provisionado
        // publicClient=true (T7); jamais incluir client_secret aqui.
        body: new URLSearchParams({
          grant_type: "authorization_code",
          client_id: pkce.clientId,
          code,
          redirect_uri: `${appOrigin}/api/auth/callback`,
          code_verifier: pkce.codeVerifier,
        }),
        cache: "no-store",
      },
    );
    if (!tokenResp.ok) {
      return new NextResponse("falha na troca de code por token", { status: 401 });
    }
    ({ access_token: accessToken } = (await tokenResp.json()) as { access_token: string });
  } catch {
    return new NextResponse("Keycloak indisponível", { status: 502 });
  }

  let segredo: string;
  try {
    const sessaoResp = await f(`${backend}/auth/sessoes`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token: accessToken }),
      cache: "no-store",
    });
    if (!sessaoResp.ok) {
      return new NextResponse("backend rejeitou o token", { status: 401 });
    }
    ({ sessao: segredo } = (await sessaoResp.json()) as { sessao: string });
  } catch {
    return new NextResponse("backend indisponível", { status: 502 });
  }
  // A partir daqui `accessToken` nunca é referenciado de novo — só `segredo` (opaco) segue adiante.

  // Destino: o que a pessoa PEDIU vence; sem pedido, cada persona vai para a home dela. Os papéis são
  // perguntados ao BACKEND (`GET /eu`, a fonte autoritativa — vêm do vínculo no banco, não das claims do
  // Keycloak), apresentando a sessão recém-criada do mesmo jeito que o rewrite /api/* apresenta: o cookie
  // `sessao` no header Cookie cru (interceptors.clj/cookie-sessao). Fail-closed em qualquer erro: cai no
  // destino padrão em vez de travar o login que JÁ deu certo.
  const pedido = pkce.redirectPath ? resolveRedirectPath(pkce.redirectPath, appOrigin) : null;
  const safeRedirectPath = pedido ?? destinoPorPapeis(await papeisDaSessao(f, backend, segredo));
  const response = NextResponse.redirect(new URL(safeRedirectPath, appOrigin));
  response.cookies.set("sessao", segredo, {
    httpOnly: true,
    secure: true,
    sameSite: "lax",
    maxAge: 12 * 3600,
    path: "/",
  });
  // Companheiro de `sessao`: só os 3 valores públicos de descoberta, lidos do cookie `pkce` JÁ em
  // escopo (sem refetch). Nunca o access_token, nunca o segredo da sessão.
  response.cookies.set(
    "sessao_kc",
    JSON.stringify({ baseUrl: pkce.baseUrl, realm: pkce.realm, clientId: pkce.clientId }),
    {
      httpOnly: true,
      secure: true,
      sameSite: "lax",
      maxAge: 12 * 3600,
      path: "/",
    },
  );
  // O cookie pkce foi SETADO com path "/api/auth" (login/route.ts) — delete precisa da MESMA tupla
  // (nome, path); um delete com path "/" (default) não limpa um cookie escopado a "/api/auth".
  response.cookies.delete({ name: "pkce", path: "/api/auth" });
  return response;
}

/** Os papéis do ator recém-autenticado, do backend (`GET /eu` -> {ator:{papeis}}).
 *
 * `[]` e `null` são DIFERENTES de propósito: `[]` = o backend respondeu e a pessoa não tem papel de
 * trabalho (é a cidadã — destino próprio); `null` = não deu para saber (rede, !ok, corpo malformado) e o
 * chamador deve cair no destino padrão, que se adapta no cliente. Sem essa distinção, uma falha de rede
 * mandaria a secretária para a tela da cidadã. Nunca lança: a sessão já foi criada e o login não pode
 * falhar por causa da escolha de tela inicial. */
async function papeisDaSessao(
  f: typeof fetch,
  backend: string,
  segredo: string,
): Promise<string[] | null> {
  try {
    const r = await f(`${backend}/eu`, {
      headers: { cookie: `sessao=${segredo}`, accept: "application/json" },
      cache: "no-store",
    });
    if (!r.ok) return null;
    const d = (await r.json()) as { ator?: { papeis?: unknown } };
    const ps = d?.ator?.papeis;
    return Array.isArray(ps) ? ps.filter((p): p is string => typeof p === "string") : null;
  } catch {
    return null;
  }
}
