"use client";

// apps/frontend/src/app/(vereador)/layout.tsx
// Layout raiz do app do vereador (Onda C1, mobile-first) — mesma composição AuthProvider+TemaProvider de
// (interno)/layout.tsx (Suspense + LeitorToken, useSearchParams precisa do boundary — ver docstring irmã),
// mais um GUARD: sem o papel "vereador" no token, bloqueia o render dos children (a authz REAL é sempre
// server-side — /meu/painel e /meu/ciencias exigem papel "vereador" e devolvem 403 por conta própria; este
// guard é só UX, evita montar a tela errada por um instante).
//
// Chrome persistente (topo compacto + tabbar mobile) mora AQUI (não em page.tsx) — é navegação de app-
// shell que se repete por toda rota sob (vereador), mesmo racional de TopoInterno em (interno)/topo.tsx.
// "Pauta"/"Perfil" ainda não têm rota nesta fatia — os tabs ficam desabilitados em vez de linkar para "#"
// (honesto: não finge cobertura que não existe). "Votar" (Onda C3) ganhou rota real.

import { Suspense } from "react";
import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { AuthProvider, useAuth, usePapeis } from "@/lib/auth";
import { TemaProvider, useTema } from "@/lib/tema";
import { comToken } from "@/lib/nav";
import "./vereador-shell.css";

export default function LayoutVereador({ children }: { children: React.ReactNode }) {
  return (
    <Suspense fallback={null}>
      <LeitorToken>{children}</LeitorToken>
    </Suspense>
  );
}

function LeitorToken({ children }: { children: React.ReactNode }) {
  const searchParams = useSearchParams();
  return (
    <AuthProvider tokenQuery={searchParams.get("token")}>
      <TemaProvider>
        <GuardVereador>{children}</GuardVereador>
      </TemaProvider>
    </AuthProvider>
  );
}

export function GuardVereador({ children }: { children: React.ReactNode }) {
  const { papeis, estado } = usePapeis();
  // Modo real: enquanto /eu ainda não respondeu, não decide nada — evita piscar "Acesso restrito" antes da
  // resposta chegar (a authz real é sempre server-side de qualquer forma; isto é só UX).
  if (estado === "carregando") return null;
  if (!papeis.includes("vereador")) {
    return (
      <main className="acesso-restrito">
        <h1>Acesso restrito</h1>
        <p>Esta área é exclusiva para vereadores.</p>
        {/* Sem uma saída, a tela era um beco: um visitante sem o papel via a mensagem e nada mais (achado do
            teste exploratório). O caminho honesto de volta é o login por Câmara — não a home genérica. */}
        <p>
          <Link className="btn" href="/entrar">
            Entrar na sua Câmara
          </Link>
        </p>
      </main>
    );
  }
  return <AppShellVereador>{children}</AppShellVereador>;
}

function AppShellVereador({ children }: { children: React.ReactNode }) {
  return (
    <div className="app-vereador">
      <TopoVereador />
      <main className="conteudo-vereador">{children}</main>
      <TabbarVereador />
    </div>
  );
}

function TopoVereador() {
  const { tema, alternar } = useTema();
  return (
    <header className="app-topo">
      <Brasao />
      <span className="marca-nome">O&nbsp;Plenário</span>
      <button
        className="tema-btn"
        type="button"
        aria-pressed={tema === "escuro"}
        onClick={alternar}
        title="Alternar tema claro / escuro"
      >
        {tema === "escuro" ? "☾" : "☀"}
        <span className="tema-rotulo">{tema === "escuro" ? "Escuro" : "Claro"}</span>
      </button>
    </header>
  );
}

function Brasao() {
  return (
    <svg className="marca-simbolo" viewBox="0 0 34 34" role="img" aria-label="O Plenário">
      <rect width="34" height="34" rx="8" fill="#2C5638" />
      <rect x="7" y="7" width="9" height="9" rx="2" fill="#C0693F" />
      <rect x="18" y="7" width="9" height="9" rx="2" fill="#3F6E92" />
      <rect x="7" y="18" width="9" height="9" rx="2" fill="#CFA65C" />
      <rect x="18" y="18" width="9" height="9" rx="2" fill="#4E8259" />
    </svg>
  );
}

// Review react MEDIUM: "Matérias" apontava p/ (interno)/proposicoes — um link REAL, mas que tira o
// vereador do app shell mobile inteiro (perde topo/tabbar, cai num AuthProvider/TemaProvider e chassi
// DIFERENTES, desktop-oriented, sem GuardVereador). Desabilitado como os demais tabs ainda não
// construídos NESTA fatia (honesto: sem uma experiência de matérias PROPRIA do vereador dentro do shell
// mobile, mostrar o tab como "ativo" prometia uma continuidade que não existe). Candidato de fatia futura:
// uma rota `(vereador)/materias` própria, ou promover /proposicoes a layout-agnostic. "Votar" (Onda C3)
// ganhou rota real (cockpit ao vivo — confirma presença + vota do próprio celular).
// Onda E fatia 1: "Matérias" (placeholder, sem rota própria dentro do shell mobile) cede o lugar a
// "Avisos" — a inbox ganhou rota real (/notificacoes). NÃO leva contador: o sino/badge de contagem é dos
// PENDÊNCIAS (dedup §5.1 da tela de design), não das notificações — misturar os dois quebraria a dedup
// que a própria tela documenta.
const TABS = [
  { rotulo: "Início", href: "/vereador", ativo: true },
  { rotulo: "Pauta", href: null, ativo: false },
  { rotulo: "Votar", href: "/votar", ativo: true },
  { rotulo: "Avisos", href: "/notificacoes", ativo: true },
  { rotulo: "Perfil", href: null, ativo: false },
] as const;

function TabbarVereador() {
  const pathname = usePathname();
  const { token } = useAuth();
  return (
    <nav className="tabbar" aria-label="Navegação do app">
      <div className="tabbar-in">
        {TABS.map((t) =>
          t.ativo && t.href ? (
            <Link
              key={t.rotulo}
              className="tab"
              href={comToken(t.href, token)}
              aria-current={pathname === t.href ? "page" : undefined}
            >
              <span>{t.rotulo}</span>
            </Link>
          ) : (
            <button key={t.rotulo} className="tab" type="button" disabled aria-label={`${t.rotulo} (em breve)`}>
              <span>{t.rotulo}</span>
            </button>
          )
        )}
      </div>
    </nav>
  );
}
