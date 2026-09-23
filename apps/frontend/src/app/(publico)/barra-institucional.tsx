"use client";

// Barra institucional WHITE-LABEL do Portal do Cidadão (Task 0.6, Fatia A2.0). Porte de
// portal-cidadao.html:314-354 — a Câmara lidera a marca (nome em destaque), "O Plenário" recua ao
// rodapé (RodapeInstitucional). `nomeCasa` vem de fora (a fatia A2.1+ resolve o `ente` real via
// backend; aqui o esqueleto recebe o nome já resolvido pelo caller, sem inventar dado).
//
// O toggle de tema segue o MESMO padrão de TopoInterno ((interno)/topo.tsx) — estado React
// (tema === "escuro") em vez do truque CSS de dois <svg> visíveis por [data-tema] do HTML original
// (aqui já temos JS/estado, não precisamos do truque puro-CSS). O brasão é um símbolo GENÉRICO — a
// tela-fonte usa o brasão real de Fortaleza (arte específica de um cliente); sem esse dado por-tenant
// disponível ainda, um símbolo cívico neutro evita inventar heráldica de terceiros.
//
// Nav mobile (review A2.0, item 1): abaixo de 1000px a `<ul class="nav-publica">` inline não cabe —
// em vez de simplesmente escondê-la (como o CSS fazia antes, deixando os 6 links inalcançáveis), um
// botão hambúrguer revela/oculta o MESMO <ul> (não duplica a lista) via `aria-expanded`/`aria-controls`
// + `id` — disclosure pattern padrão. Esc fecha; o botão só aparece <1000px (CSS).
//
// Links ABSOLUTOS ao ente (review final A2, "Important"): os anchors `#sessoes`/`#transparencia`/
// `#ouvidoria` nunca existiram como seção própria (viraram cards <EmBreve> dentro de `#civico`, via
// NavegacaoCivica) e `href="#"` no Início não ia a lugar nenhum. Pior: como esta barra também
// renderiza na ficha (materias/[proposicaoId]/page.tsx), TODO link virava âncora-morta lá (a ficha
// não tem essas seções na própria página). Corrigido apontando cada item para a home do `ente`
// (`/portal/casa/${ente}#secao`) — funciona de qualquer página, inclusive a própria home (mesma URL,
// só rola). IDs reais conferidos nas seções: `#destaque` (secao-em-tramitacao.tsx), `#balcoes`
// (page.tsx), `#civico` (navegacao-civica.tsx). Convenção de link interno desta pasta é `<a>` puro
// (ver mais-tramitacao.tsx) — mantido aqui por consistência.

import { useEffect, useState } from "react";
import { useTema } from "@/lib/tema";
import "./public.css";

export function BarraInstitucional({
  ente,
  nomeCasa,
  paginaAtual,
}: {
  ente: string;
  nomeCasa: string;
  paginaAtual?: "inicio";
}) {
  const { tema, alternar } = useTema();
  const [navAberta, setNavAberta] = useState(false);

  useEffect(() => {
    if (!navAberta) return;
    function aoTeclar(evento: KeyboardEvent) {
      if (evento.key === "Escape") setNavAberta(false);
    }
    document.addEventListener("keydown", aoTeclar);
    return () => document.removeEventListener("keydown", aoTeclar);
  }, [navAberta]);

  return (
    <header className="topo">
      <div className="envelope topo-grade">
        <div className="brasao-marca">
          <BrasaoGenerico />
          <div className="brasao-nome">
            <b>{nomeCasa}</b>
            <span>Portal do Cidadão</span>
          </div>
        </div>

        <nav aria-label="Seções do portal" className="nav-publica-nav">
          <button
            type="button"
            className="nav-publica-toggle"
            aria-expanded={navAberta}
            aria-controls="nav-publica-lista"
            aria-label={navAberta ? "Fechar menu" : "Abrir menu"}
            onClick={() => setNavAberta((aberta) => !aberta)}
          >
            <span aria-hidden="true">☰</span>
          </button>
          <ul id="nav-publica-lista" className="nav-publica" data-aberta={navAberta}>
            <li>
              <a href={`/portal/casa/${ente}`} aria-current={paginaAtual === "inicio" ? "page" : undefined}>
                Início
              </a>
            </li>
            <li>
              <a href={`/portal/casa/${ente}#destaque`}>Proposições</a>
            </li>
            <li>
              <a href={`/portal/casa/${ente}#civico`}>Sessões</a>
            </li>
            <li>
              <a href={`/portal/casa/${ente}#civico`}>Transparência</a>
            </li>
            <li>
              <a href={`/portal/casa/${ente}#balcoes`}>Acesso à informação</a>
            </li>
            <li>
              <a href={`/portal/casa/${ente}#civico`}>Ouvidoria</a>
            </li>
          </ul>
        </nav>

        <div className="topo-dir">
          <button
            className="tema-btn"
            type="button"
            aria-pressed={tema === "escuro"}
            onClick={alternar}
            title="Alternar tema claro / escuro"
          >
            <span aria-hidden="true">{tema === "escuro" ? "☾" : "☀"}</span>
            <span className="tema-rotulo">{tema === "escuro" ? "Escuro" : "Claro"}</span>
          </button>
          <a className="govbr-topo" href="#" aria-label="Entrar com conta gov.br">
            <span className="g" aria-hidden="true">
              gov.br
            </span>
            Entrar
          </a>
        </div>
      </div>
    </header>
  );
}

function BrasaoGenerico() {
  // símbolo cívico neutro (hemiciclo + coluna), NÃO tematiza — objeto heráldico genérico, mesmo
  // racional do <Brasao/> de TopoInterno: cores fixas, não var(--tokens).
  return (
    <svg className="brasao" viewBox="0 0 48 48" role="img" aria-label="Símbolo da Câmara">
      <circle cx="24" cy="24" r="22.5" fill="#FFF7EA" stroke="#A6BFA2" strokeWidth="1.5" />
      <circle cx="24" cy="24" r="18.5" fill="none" stroke="#CFA65C" strokeWidth="1" />
      <path d="M14 19 L24 13 L34 19 Z" fill="#2C5638" />
      <rect x="14.5" y="20.5" width="19" height="2.2" rx="1" fill="#2C5638" />
      <g fill="#2C5638">
        <rect x="16" y="23.5" width="2.6" height="9" rx="1" />
        <rect x="22.7" y="23.5" width="2.6" height="9" rx="1" />
        <rect x="29.4" y="23.5" width="2.6" height="9" rx="1" />
      </g>
      <rect x="14.5" y="33" width="19" height="2.4" rx="1" fill="#2C5638" />
      <circle cx="24" cy="9.6" r="1.7" fill="#C0693F" />
    </svg>
  );
}
