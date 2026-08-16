// Gate de presença do cookie `sessao` nas rotas protegidas (Onda D, T11).
//
// Next.js route groups — `(interno)`, `(vereador)`, `(publico)` — são URL-TRANSPARENTES: não
// aparecem no path real. O matcher abaixo lista os prefixos CONCRETOS que cada grupo serve
// (ver phase2-shared-decisions.md §11), não os nomes dos grupos.
//
// Só checagem de PRESENÇA do cookie — nunca validação de valor/assinatura aqui. Um cookie presente
// mas inválido é pego pelo interceptor de auth do backend (autoridade real); este middleware é só
// UX (evita round-trip para uma página que vai 401 de qualquer forma).
//
// `(publico)` (`/portal`), `/entrar` (T12), `/api/*` (autenticam-se sozinhas — gatear quebraria
// login/callback/logout) e os internos do Next nunca são gated: usamos um matcher por ALLOWLIST
// (positivo) em vez de deny-by-default, para que rotas públicas nunca corram o risco de cair sob o
// gate por engano.
import { NextResponse, type NextRequest } from "next/server";

// Mesma lista do `config.matcher` abaixo, em forma de prefixo simples. `config.matcher` já restringe
// QUANDO o Next invoca este middleware em produção, mas replicamos a checagem aqui dentro porque a
// função é testada diretamente (chamada em unit test não passa pelo roteador do Next, que é quem
// interpreta `matcher`) — sem isto, uma rota pública chamada fora do runtime do Next seria gated por
// engano.
const PREFIXOS_PROTEGIDOS = [
  "/editor-proposicao",
  "/expediente",
  "/ficha-materia",
  "/paineis",
  "/parecer",
  "/pauta-convocacao",
  "/pos-aprovacao",
  "/proposicoes",
  "/tramitacao",
  "/vereador",
  "/votar",
];

function ehRotaProtegida(pathname: string): boolean {
  if (/^\/sessoes\/[^/]+\/plenario$/.test(pathname)) return true;
  // Etapa 5 fatia 6 (a FOLHA) — mesmo gate que /plenario: a folha é nominal e carrega o `motivo` da
  // justificativa (LGPD, potencial dado de saúde), papel 'secretario' na borda real do backend. Esta
  // checagem é só UX (evita round-trip a uma página que o servidor recusaria de qualquer forma).
  if (/^\/sessoes\/[^/]+\/folha$/.test(pathname)) return true;
  return PREFIXOS_PROTEGIDOS.some(
    (prefixo) => pathname === prefixo || pathname.startsWith(`${prefixo}/`),
  );
}

export function middleware(request: NextRequest): NextResponse {
  if (!ehRotaProtegida(request.nextUrl.pathname)) {
    return NextResponse.next();
  }

  // Bypass de dev: mantém vivo o fluxo de dev-token (NEXT_PUBLIC_DEV_TOKEN) mesmo sem cookie de
  // sessão real. NUNCA em produção — o AuthProvider já lança nesse caso; aqui não abrimos brecha.
  const isDevTokenBypass =
    request.nextUrl.searchParams.has("token") && process.env.NODE_ENV !== "production";
  if (isDevTokenBypass) {
    return NextResponse.next();
  }

  if (!request.cookies.has("sessao")) {
    const entrarUrl = new URL("/entrar", request.url);
    entrarUrl.searchParams.set(
      "redirect",
      request.nextUrl.pathname + request.nextUrl.search,
    );
    return NextResponse.redirect(entrarUrl);
  }

  return NextResponse.next();
}

export const config = {
  matcher: [
    "/editor-proposicao/:path*",
    "/expediente/:path*",
    "/ficha-materia/:path*",
    "/paineis/:path*",
    "/parecer/:path*",
    "/pauta-convocacao/:path*",
    "/pos-aprovacao/:path*",
    "/proposicoes/:path*",
    "/tramitacao/:path*",
    "/vereador/:path*",
    "/votar/:path*",
    "/sessoes/:id/plenario",
    "/sessoes/:id/folha",
  ],
};
