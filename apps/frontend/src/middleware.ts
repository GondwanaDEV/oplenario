// Gate de presença do cookie `sessao` (Onda D, T11; deny-by-default desde 05/10/2026).
//
// Só checagem de PRESENÇA do cookie — nunca validação de valor/assinatura aqui. Um cookie presente
// mas inválido é pego pelo interceptor de auth do backend (autoridade real); este middleware é só
// UX: quem chega sem sessão vai direto à tela de entrada pelo CPF (`/entrar`), que já descobre a
// Câmara da pessoa — não há página intermediária para escolher a Câmara.
//
// O gate é por DENY-BY-DEFAULT: tudo exige sessão, menos o que está em `CAMINHOS_PUBLICOS`. Antes era o
// contrário (uma lista de prefixos protegidos), e a raiz `/` e qualquer caminho desconhecido abriam
// sem login uma página de passagem. `middleware.test.ts` mede as páginas públicas em disco contra a lista:
// página pública nova fora dela reprova (o erro, agora, é cair no login, nunca ficar aberta).
//
// A raiz `/` não tem página própria para quem chega: sem sessão vai a `/entrar`, com sessão a `/inicio`
// (que leva cada papel à sua tela).
import { NextResponse, type NextRequest } from "next/server";

// O que abre sem sessão. `/portal` é o portal do cidadão de cada Casa (público por lei: LAI, transparência);
// `/entrar` é a própria entrada (CPF, escolha entre Câmaras quando há mais de uma, link por Câmara); `/status`
// é a página pública da plataforma; `/api` autentica-se sozinha (gatear quebraria login/callback/logout);
// `/_next` são os internos do Next e `/fontes` as fontes servidas do `public/`.
const CAMINHOS_PUBLICOS = ["/portal", "/entrar", "/status", "/api", "/_next", "/fontes", "/.well-known"];

function ehPublico(pathname: string): boolean {
  if (CAMINHOS_PUBLICOS.some((p) => pathname === p || pathname.startsWith(`${p}/`))) return true;
  // arquivo estático do `public/` (favicon.ico, *.svg, robots.txt): o último segmento tem extensão
  const ultimo = pathname.slice(pathname.lastIndexOf("/") + 1);
  return ultimo.includes(".");
}

// ADR-0016 — o console do OPERADOR é outra esfera: outro cookie (`sessao_operacao`), outra porta de entrada
// (`/operacao/entrar`). O cookie de uma Casa não abre o console (e o backend recusa de qualquer forma).
function ehRotaDoConsole(pathname: string): boolean {
  if (pathname === "/operacao/entrar" || pathname.startsWith("/operacao/entrar/")) return false;
  return pathname === "/operacao" || pathname.startsWith("/operacao/");
}

export function middleware(request: NextRequest): NextResponse {
  const { pathname } = request.nextUrl;
  if (pathname === "/operacao/entrar" || pathname.startsWith("/operacao/entrar/")) return NextResponse.next();
  if (ehRotaDoConsole(pathname)) {
    const devToken = request.nextUrl.searchParams.has("token") && process.env.NODE_ENV !== "production";
    if (devToken || request.cookies.has("sessao_operacao")) return NextResponse.next();
    const entrar = new URL("/operacao/entrar", request.url);
    entrar.searchParams.set("redirect", pathname + request.nextUrl.search);
    return NextResponse.redirect(entrar);
  }

  if (pathname === "/") {
    const destino = request.cookies.has("sessao") ? "/inicio" : "/entrar";
    return NextResponse.redirect(new URL(destino, request.url));
  }

  if (ehPublico(pathname)) return NextResponse.next();

  // Bypass de dev: mantém vivo o fluxo de dev-token (NEXT_PUBLIC_DEV_TOKEN) mesmo sem cookie de
  // sessão real. NUNCA em produção — o AuthProvider já lança nesse caso; aqui não abrimos brecha.
  const isDevTokenBypass =
    request.nextUrl.searchParams.has("token") && process.env.NODE_ENV !== "production";
  if (isDevTokenBypass) {
    return NextResponse.next();
  }

  if (!request.cookies.has("sessao")) {
    const entrarUrl = new URL("/entrar", request.url);
    entrarUrl.searchParams.set("redirect", pathname + request.nextUrl.search);
    return NextResponse.redirect(entrarUrl);
  }

  return NextResponse.next();
}

// O Next só chama o middleware no que o matcher cobre: tudo, menos os internos do Next e a API (que nunca
// passam pelo gate). O resto da decisão (público ou não) é da função acima, que também é a testada.
export const config = {
  matcher: ["/((?!_next/|api/).*)"],
};
