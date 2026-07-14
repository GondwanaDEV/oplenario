// POST /api/auth/logout — encerra a sessão opaca no backend (`DELETE /auth/sessoes`, cookie
// encaminhado EXPLICITAMENTE — o `fetch` do Node não repassa cookies do request recebido, isso é
// comportamento de fetch de NAVEGADOR, não server-to-server; mesmo achado documentado no callback,
// T9) e limpa o cookie `sessao` local. BEST-EFFORT por desenho: falha do backend (indisponível ou
// não-2xx) ou ausência do cookie NUNCA bloqueia o logout local — o usuário precisa conseguir sair
// mesmo com o backend fora do ar ou já deslogado (backend T5 já é idempotente: DELETE por hash
// sem match é no-op, não erro).
//
// RP-logout no Keycloak (end-session): o cookie `sessao` carrega só o segredo OPACO (§2 das
// shared-decisions) e o `pkce` (única fonte de realm/base-url/client-id) já foi apagado pelo
// callback (T9) ao mintar a sessão — então esta rota não tem de onde ler o tenant sozinha. Fonte:
// o cookie companheiro `sessao_kc`, setado pelo callback no MESMO passo em que `sessao` é setado,
// com os 3 valores PÚBLICOS de descoberta (`GET /auth/descoberta/:ente`, sem auth — nunca segredo
// nem access_token). Se `sessao_kc` estiver ausente (ex.: sessão pré-existente antes desta feature)
// ou os valores não passarem na validação abaixo, o logout cai no fallback local (`/entrar`) — o
// logout nunca quebra e nunca monta uma URL a partir de um valor não confiável.
import { NextRequest, NextResponse } from "next/server";
import { resolveAppOrigin } from "../appOrigin";
import { validarDescobertaKc } from "../kc-cookie";

// Defesa em profundidade antes de interpolar o valor do cookie no header `cookie` enviado ao
// backend (o cookie é HttpOnly, mas isso só bloqueia acesso via JS — não impede um Cookie header
// forjado). O segredo é gerado como 32 bytes aleatórios em base64url SEM padding (backend,
// `gerar-segredo`) — 43 chars de `[A-Za-z0-9_-]`; o teto abaixo é generoso o bastante para nunca
// barrar um segredo real.
const SEGREDO_VALIDO = /^[A-Za-z0-9_-]{1,128}$/;

// A validação da forma do `sessao_kc` (realm/baseUrl/clientId) vive em `../kc-cookie` (compartilhada com o
// callback) — não interpolamos valores de cookie numa URL de redirect sem validar primeiro.
type SessaoKcPayload = NonNullable<ReturnType<typeof validarDescobertaKc>>;

function lerSessaoKc(raw: string | undefined): SessaoKcPayload | null {
  if (!raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null) return null;
  return validarDescobertaKc(parsed as Record<string, unknown>);
}

function urlLogoutLocal(request: NextRequest): URL {
  return new URL("/entrar", resolveAppOrigin(request));
}

function urlLogoutKc(request: NextRequest, payload: SessaoKcPayload): URL {
  const url = new URL(
    `${payload.baseUrl}/realms/${payload.realm}/protocol/openid-connect/logout`,
  );
  url.searchParams.set("client_id", payload.clientId);
  url.searchParams.set("post_logout_redirect_uri", resolveAppOrigin(request));
  return url;
}

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

  const sessaoKc = lerSessaoKc(request.cookies.get("sessao_kc")?.value);
  const destino = sessaoKc ? urlLogoutKc(request, sessaoKc) : urlLogoutLocal(request);

  const response = NextResponse.redirect(destino);
  // Os cookies `sessao` e `sessao_kc` foram SETADOS com path "/" (callback/route.ts) — delete
  // precisa da MESMA tupla (nome, path). Ambos são limpos SEMPRE, em todo caminho (KC ou local).
  response.cookies.delete({ name: "sessao", path: "/" });
  response.cookies.delete({ name: "sessao_kc", path: "/" });
  return response;
}
