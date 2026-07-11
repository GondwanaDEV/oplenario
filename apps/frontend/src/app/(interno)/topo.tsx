"use client";

// Barra institucional do App Shell interno (FE Onda A1) — porta ../sistema/chassi.css .topo (mesma
// marca+área-tag+tema-btn já usados em sessoes/[id]/plenario/page.tsx), agora compartilhada por QUALQUER
// página autenticada nova. `area` = o rótulo da seção atual (ex. "Painéis da Mesa"); `ator` = quem está
// logado (nome+papel — vem do JWT decodificado, injetado pelo caller).
//
// .topo/.marca/.tema-btn/.avatar já vivem em ../chassi.css (porte verbatim do design-system). .area-tag e
// .quem-mesa ainda NÃO foram promovidas ao chassi — hoje só existem inline em
// produto/design-system/o-plenario/telas/paineis-mesa.html (a superfície "cockpit"); copiadas verbatim
// para ./topo.css aqui. Promover ao chassi.css é decisão do design-system (PADROES-DE-COMPOSICAO.md,
// gatilho 2º-uso), não deste componente.

import Link from "next/link";
import { useTema } from "@/lib/tema";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import "./topo.css";

const DESTINOS_NAV = [
  { rotulo: "Painéis da Mesa", href: "/paineis/mesa" },
  { rotulo: "Tramitação", href: "/tramitacao" },
  { rotulo: "Proposições", href: "/proposicoes" },
  // Onda B Slice 6 — Expediente (gerar documento + Protocolo Geral) é área de topo nova, não sub-rota de
  // Proposições (documento administrativo não é matéria legislativa).
  { rotulo: "Expediente", href: "/expediente" },
  // Onda C Slice C2 — leitura da pauta de uma sessão agendada + convocação derivada (gated "secretario").
  { rotulo: "Pauta", href: "/pauta-convocacao" },
];

export function TopoInterno({ area, ator }: { area: string; ator: { nome: string; papel: string } }) {
  const { tema, alternar } = useTema();
  const { token } = useAuth();
  return (
    <header className="topo">
      <div className="envelope topo-grade">
        <div className="marca">
          <Brasao />
          <div>
            <p className="marca-nome">O&nbsp;Plenário</p>
            <p className="marca-orgao">Câmara Municipal</p>
          </div>
        </div>
        <div className="topo-sep" aria-hidden="true" />
        <span className="area-tag">{area}</span>
        <nav className="nav-interna" aria-label="Navegação interna">
          {DESTINOS_NAV.map((d) => (
            <Link
              key={d.href}
              href={comToken(d.href, token)}
              aria-current={d.rotulo === area ? "page" : undefined}
            >
              {d.rotulo}
            </Link>
          ))}
        </nav>
        <div className="topo-dir">
          <button className="tema-btn" type="button" aria-pressed={tema === "escuro"} onClick={alternar} title="Alternar tema claro / escuro">
            {tema === "escuro" ? "☾" : "☀"}
            <span className="tema-rotulo">{tema === "escuro" ? "Escuro" : "Claro"}</span>
          </button>
          <div className="quem-mesa">
            <span className="avatar" aria-hidden="true">
              {ator.nome.split(" ").map((p) => p[0]).slice(0, 2).join("").toUpperCase()}
            </span>
            <span className="quem">
              <b>{ator.nome}</b>
              <span>{ator.papel}</span>
            </span>
          </div>
        </div>
      </div>
    </header>
  );
}

function Brasao() {
  return (
    <svg className="marca-simbolo" viewBox="0 0 40 40" role="img" aria-label="O Plenário">
      <circle cx="20" cy="20" r="19" fill="#FBF8F0" stroke="#E0D7BF" />
      <path d="M7 27 A13 13 0 0 1 33 27" fill="none" stroke="#0C5340" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M10 27 A10 10 0 0 1 30 27" fill="none" stroke="#1E5FA8" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M13.5 27 A6.5 6.5 0 0 1 26.5 27" fill="none" stroke="#D9542B" strokeWidth="2.4" strokeLinecap="round" />
      <rect x="18.4" y="9.5" width="3.2" height="6" rx="1.2" fill="#E8B23A" />
    </svg>
  );
}
