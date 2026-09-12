"use client";

// apps/frontend/src/app/(cidadao)/layout.tsx (fatia "demo-tres-consertos" #3) — o app shell da cidadã
// AUTENTICADA (vínculo `cidadao`, SEM papel nenhum). Achado ao vivo (Daouda, 12/09/2026): ela alcançava
// GET /portal/acompanhamentos com sessão real (200, dado real), mas nenhuma tela do FE chamava essa rota —
// enumerando as 28 rotas existentes, nenhuma serve a superfície autenticada dela. Este grupo é a PRIMEIRA.
//
// Mesma composição AuthProvider+TemaProvider de (interno)/layout.tsx (Suspense+LeitorToken — useSearchParams
// precisa do boundary, ver docstring irmã) — mas o guard aqui é DIFERENTE do de (vereador)/layout.tsx: exige
// SESSÃO, nunca papel. Ela não tem papel nenhum (nem "vereador", nem "secretario"); o guard de (vereador)
// bloquearia exatamente a persona que este grupo existe para servir — por isso este NÃO reusa aquele guard,
// e não o toca (reabri-lo pra papel nenhum quebraria a authz do vereador do outro lado).
//
// Cromo PRÓPRIO do portal do cidadão (não o de (interno)): reusa só o CHASSIS compartilhado
// (.topo/.marca/.tema-btn/.avatar, chassi.css) — a mesma base que TopoInterno usa — sem herdar a nav
// interna (Painéis/Tramitação/Proposições) nem a marca "servidor da Casa"; ver GuardCidadao/TopoCidadao.

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { AuthProvider, useAuth } from "@/lib/auth";
import { TemaProvider, useTema } from "@/lib/tema";
import { useMeuIdentidade } from "@/lib/use-meu-identidade";
import { rotuloPapel } from "@/lib/rotulo-papel";

export default function LayoutCidadao({ children }: { children: React.ReactNode }) {
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
        <TopoCidadao />
        <main>{children}</main>
      </TemaProvider>
    </AuthProvider>
  );
}

// SEM guard de papel: `auth` (a sessão precisar existir) é a ÚNICA exigência — a autorização real, como em
// todo lugar deste app, é sempre server-side (GET /portal/acompanhamentos já é `auth`-only no backend; um
// token sem sessão válida recebe 401 de lá, não daqui). O cabeçalho ainda mostra nome+rótulo honestos
// (useMeuIdentidade, mesma fatia #1) em vez de qualquer literal fixo.
function TopoCidadao() {
  const { tema, alternar } = useTema();
  const { token } = useAuth();
  const { dados, estado } = useMeuIdentidade(token);
  const nome = estado === "pronto" && dados ? dados.nome : estado === "carregando" ? "Carregando…" : "Sessão";
  const papel = estado === "pronto" && dados ? rotuloPapel(dados.papeis) : "";

  return (
    <header className="topo">
      <div className="envelope topo-grade">
        <div className="marca">
          <Brasao />
          <div>
            <p className="marca-nome">O&nbsp;Plenário</p>
            <p className="marca-orgao">Portal do Cidadão</p>
          </div>
        </div>
        <div className="topo-dir">
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
          <div className="presidente">
            <span className="avatar" aria-hidden="true">
              {nome.split(" ").map((p) => p[0]).slice(0, 2).join("").toUpperCase()}
            </span>
            <span className="quem">
              <b>{nome}</b>
              {papel && <span>{papel}</span>}
            </span>
          </div>
        </div>
      </div>
    </header>
  );
}

// Mesmo brasão-tokens de (publico)/status/page.tsx (arquétipo "leitura pública simples") — símbolo cívico
// neutro, só tokens de cor (nunca hex literal), consistente com a identidade do portal.
function Brasao() {
  return (
    <svg className="marca-simbolo" viewBox="0 0 34 34" role="img" aria-label="O Plenário">
      <rect width="34" height="34" rx="8" fill="var(--jade)" />
      <rect x="7" y="7" width="9" height="9" rx="2" fill="var(--telha)" />
      <rect x="18" y="7" width="9" height="9" rx="2" fill="var(--cobalto)" />
      <rect x="7" y="18" width="9" height="9" rx="2" fill="var(--amarelo)" />
      <rect x="18" y="18" width="9" height="9" rx="2" fill="var(--jade-claro)" />
    </svg>
  );
}
