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

import { useTema } from "@/lib/tema";
import "./public.css";

export function BarraInstitucional({ nomeCasa }: { nomeCasa: string }) {
  const { tema, alternar } = useTema();
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

        <nav aria-label="Seções do portal">
          <ul className="nav-publica">
            <li>
              <a href="#" aria-current="page">
                Início
              </a>
            </li>
            <li>
              <a href="#destaque">Proposições</a>
            </li>
            <li>
              <a href="#sessoes">Sessões</a>
            </li>
            <li>
              <a href="#transparencia">Transparência</a>
            </li>
            <li>
              <a href="#balcoes">Acesso à informação</a>
            </li>
            <li>
              <a href="#ouvidoria">Ouvidoria</a>
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
            {tema === "escuro" ? "☾" : "☀"}
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
      <circle cx="24" cy="24" r="22.5" fill="#FBF8F0" stroke="#E0D7BF" strokeWidth="1.5" />
      <circle cx="24" cy="24" r="18.5" fill="none" stroke="#E8B23A" strokeWidth="1" />
      <path d="M14 19 L24 13 L34 19 Z" fill="#0C5340" />
      <rect x="14.5" y="20.5" width="19" height="2.2" rx="1" fill="#0C5340" />
      <g fill="#0C5340">
        <rect x="16" y="23.5" width="2.6" height="9" rx="1" />
        <rect x="22.7" y="23.5" width="2.6" height="9" rx="1" />
        <rect x="29.4" y="23.5" width="2.6" height="9" rx="1" />
      </g>
      <rect x="14.5" y="33" width="19" height="2.4" rx="1" fill="#0C5340" />
      <circle cx="24" cy="9.6" r="1.7" fill="#D9542B" />
    </svg>
  );
}
