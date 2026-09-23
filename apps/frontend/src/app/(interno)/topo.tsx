"use client";

// Barra institucional do App Shell interno (FE Onda A1) — porta ../sistema/chassi.css .topo (mesma
// marca+área-tag+tema-btn já usados em sessoes/[id]/plenario/page.tsx), agora compartilhada por QUALQUER
// página autenticada nova. `area` = o rótulo da seção atual (ex. "Painéis da Mesa"); quem está logado
// (nome+papel) o componente RESOLVE por conta própria via `useMeuIdentidade` — não é mais prop do caller.
//
// Conserto (fatia "demo-tres-consertos" #1, achado ao vivo — Daouda, 12/09/2026): até aqui `ator` era um
// literal fixo passado por CADA página (`{ nome: "Sérgio Lopes", papel: "Presidente da Mesa" }` na Mesa,
// "Rita Campos"/"Ana Ribeiro" alhures) — toda persona logada via o MESMO nome, sempre. GET /meu/identidade
// (identidade/diplomat/http/in.clj) devolve o ator REAL; `rotuloPapel` deriva o rótulo de exibição dos
// PAPÉIS (nunca do cargo de Mesa, que o backend de identidade não enxerga — ver docstring de rotulo-papel.ts).
// Enquanto carrega ou se a busca falhar, o cabeçalho NUNCA mostra um nome inventado — mostra que está
// carregando ou que a sessão está indisponível (mesma disciplina de honestidade da tela de votação, fatia 2).
//
// .topo/.marca/.tema-btn/.avatar já vivem em ../chassi.css (porte verbatim do design-system). .area-tag e
// .quem-mesa ainda NÃO foram promovidas ao chassi — hoje só existem inline em
// produto/design-system/o-plenario/telas/paineis-mesa.html (a superfície "cockpit"); copiadas verbatim
// para ./topo.css aqui. Promover ao chassi.css é decisão do design-system (PADROES-DE-COMPOSICAO.md,
// gatilho 2º-uso), não deste componente.

import Link from "next/link";
import { useTema } from "@/lib/tema";
import { useAuth } from "@/lib/auth";
import { useMeuIdentidade } from "@/lib/use-meu-identidade";
import { rotuloPapel } from "@/lib/rotulo-papel";
import { comToken } from "@/lib/nav";
import "./topo.css";

const DESTINOS_NAV = [
  // Primeiro da lista de propósito: é o ponto de partida (a tela que responde "o que eu faço agora?") e a
  // única porta para as telas de sessão ao vivo, que não têm entrada de navegação própria.
  { rotulo: "Início", href: "/inicio" },
  { rotulo: "Painéis da Mesa", href: "/paineis/mesa" },
  { rotulo: "Tramitação", href: "/tramitacao" },
  { rotulo: "Proposições", href: "/proposicoes" },
  // Onda B Slice 6 — Expediente (gerar documento + Protocolo Geral) é área de topo nova, não sub-rota de
  // Proposições (documento administrativo não é matéria legislativa).
  { rotulo: "Expediente", href: "/expediente" },
  // Onda C Slice C2 — leitura da pauta de uma sessão agendada + convocação derivada (gated "secretario").
  { rotulo: "Pauta", href: "/pauta-convocacao" },
  // Agendar sessão (GAP docs/20 → tela de servidor): cria a sessão no estado agendada. Gated "secretario"
  // (GuardSecretaria na página + exige-papel no backend). Sem esta entrada a rota ficaria órfã.
  { rotulo: "Agendar sessão", href: "/agendar-sessao" },
  // Cadastro de Vereadores (Task 9) — cadastros estruturais, área de topo nova (arquétipo master-detail).
  { rotulo: "Vereadores", href: "/cadastros/vereadores" },
  // Onda E fatia 2 — Calendário institucional (a agenda da Casa: sessões agendadas + prazos de
  // compliance). Sem esta entrada a rota existiria órfã, alcançável só por URL digitada.
  { rotulo: "Calendário", href: "/calendario" },
  // Moderação de comentários (GAP docs/20 → tela de servidor): fila de pendentes + aprovar/rejeitar.
  // Gated "secretario" (GuardSecretaria na página + exige-papel no backend).
  { rotulo: "Moderação", href: "/moderacao" },
];

export function TopoInterno({ area }: { area: string }) {
  const { tema, alternar } = useTema();
  const { token } = useAuth();
  const { dados, estado } = useMeuIdentidade(token);
  // Nunca um nome inventado: "carregando"/"erro" são rótulos HONESTOS, não um ator fixo. `estado==="erro"`
  // cobre tanto a falha de rede quanto a resposta não-ok (ver docstring de useMeuIdentidade).
  const nome = estado === "pronto" && dados ? dados.nome : estado === "carregando" ? "Carregando…" : "Sessão";
  const papel =
    estado === "pronto" && dados ? rotuloPapel(dados.papeis) : estado === "carregando" ? "" : "indisponível";
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
              // prefetch={false}: cada destino da nav é autenticado e, sem sessão válida, responde 401 e
              // redireciona para /entrar. O prefetch do Next dispara essas navegações em segundo plano — que
              // abortam em massa (ERR_ABORTED) e nunca deixam a rede assentar (achado do teste exploratório:
              // "prefetch storm"). Sem prefetch, a rota só é buscada no clique real. Custo: primeira navegação
              // sem pré-aquecimento — desprezível numa barra de app interno.
              prefetch={false}
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
              {nome.split(" ").map((p) => p[0]).slice(0, 2).join("").toUpperCase()}
            </span>
            <span className="quem">
              <b>{nome}</b>
              <span>{papel}</span>
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
      <circle cx="20" cy="20" r="19" fill="#FFF7EA" stroke="#A6BFA2" />
      <path d="M7 27 A13 13 0 0 1 33 27" fill="none" stroke="#2C5638" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M10 27 A10 10 0 0 1 30 27" fill="none" stroke="#3F6E92" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M13.5 27 A6.5 6.5 0 0 1 26.5 27" fill="none" stroke="#C0693F" strokeWidth="2.4" strokeLinecap="round" />
      <rect x="18.4" y="9.5" width="3.2" height="6" rx="1.2" fill="#CFA65C" />
    </svg>
  );
}
