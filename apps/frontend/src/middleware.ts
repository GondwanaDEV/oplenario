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
// Em ordem alfabética. `middleware.test.ts` mede esta lista contra as páginas que existem em disco: página nova
// num grupo autenticado sem entrada aqui reprova o teste. `/sessoes` inteiro é autenticado (painel, folha, chamada,
// condução, ata, transcrição, TV, audiência); as sessões que o público vê ficam em `/portal`.
const PREFIXOS_PROTEGIDOS = [
  "/acompanhamentos",
  "/administracao",
  "/agendar-sessao",
  "/assistente",
  "/atas",
  "/atendimento",
  "/auditoria",
  "/busca",
  "/cadastros",
  "/caixa",
  "/calendario",
  "/comunicados",
  "/conferencias",
  "/contas",
  "/editor-proposicao",
  "/expediente",
  "/ficha-materia",
  "/gravacoes",
  "/inicio",
  "/juridico",
  "/meus-protocolos",
  "/moderacao",
  "/normas",
  "/notificacoes",
  "/paineis",
  "/parecer",
  "/pauta-convocacao",
  "/pos-aprovacao",
  "/proposicoes",
  "/propostas",
  "/recebimentos",
  "/requerimento",
  "/sessoes",
  "/tempos-da-tribuna",
  "/tramitacao",
  "/vereador",
  "/votar",
];

function ehRotaProtegida(pathname: string): boolean {
  return PREFIXOS_PROTEGIDOS.some(
    (prefixo) => pathname === prefixo || pathname.startsWith(`${prefixo}/`),
  );
}

// ADR-0016 — o console do OPERADOR é outra esfera: outro cookie (`sessao_operacao`), outra porta de entrada
// (`/operacao/entrar`). O cookie de uma Casa não abre o console (e o backend recusa de qualquer forma).
function ehRotaDoConsole(pathname: string): boolean {
  if (pathname === "/operacao/entrar" || pathname.startsWith("/operacao/entrar/")) return false;
  return pathname === "/operacao" || pathname.startsWith("/operacao/");
}

export function middleware(request: NextRequest): NextResponse {
  if (ehRotaDoConsole(request.nextUrl.pathname)) {
    const devToken = request.nextUrl.searchParams.has("token") && process.env.NODE_ENV !== "production";
    if (devToken || request.cookies.has("sessao_operacao")) return NextResponse.next();
    const entrar = new URL("/operacao/entrar", request.url);
    entrar.searchParams.set("redirect", request.nextUrl.pathname + request.nextUrl.search);
    return NextResponse.redirect(entrar);
  }

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

// O Next exige o matcher como literal estático (não aceita `.map` sobre a lista acima); o teste confere que os
// dois andam juntos.
export const config = {
  matcher: [
    "/acompanhamentos/:path*",
    "/administracao/:path*",
    "/agendar-sessao/:path*",
    "/assistente/:path*",
    "/atas/:path*",
    "/atendimento/:path*",
    "/auditoria/:path*",
    "/busca/:path*",
    "/cadastros/:path*",
    "/caixa/:path*",
    "/calendario/:path*",
    "/comunicados/:path*",
    "/conferencias/:path*",
    "/contas/:path*",
    "/editor-proposicao/:path*",
    "/expediente/:path*",
    "/ficha-materia/:path*",
    "/gravacoes/:path*",
    "/inicio/:path*",
    "/juridico/:path*",
    "/meus-protocolos/:path*",
    "/moderacao/:path*",
    "/normas/:path*",
    "/notificacoes/:path*",
    "/paineis/:path*",
    "/parecer/:path*",
    "/pauta-convocacao/:path*",
    "/pos-aprovacao/:path*",
    "/proposicoes/:path*",
    "/propostas/:path*",
    "/recebimentos/:path*",
    "/requerimento/:path*",
    "/sessoes/:path*",
    "/tempos-da-tribuna/:path*",
    "/tramitacao/:path*",
    "/vereador/:path*",
    "/votar/:path*",
    "/operacao",
    "/operacao/:path*",
  ],
};
