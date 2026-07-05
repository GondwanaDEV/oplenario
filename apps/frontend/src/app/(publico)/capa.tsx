// Capa (hero) do Portal do Cidadão — Task 1.2 (Fatia A2.1). Porte de portal-cidadao.html:358-403.
//
// DUAS superfícies desta tela são em-breve HONESTO (Global Constraints do plano — "sem dado falso"):
// "ao vivo agora" (não há rota pública de sessão em curso nesta fatia) e a busca (não há backend de
// busca). Nenhuma delas finge um dado — o rótulo "Em breve" é sempre visível, com o motivo concreto.
//
// DESVIO do porte verbatim: o selo cívico da tela-fonte grava "FORTALEZA · CEARÁ" no rodapé do SVG —
// dado específico de UM tenant (Fortaleza). Esta fatia não resolve o nome/UF do `ente` (isso pede uma
// rota de metadados do ente, fora do escopo de A2.1) — gravar qualquer texto aqui seria inventar dado
// para câmaras que não são Fortaleza. O selo fica só com a marca hemiciclo (decorativa, aria-hidden),
// sem o texto de localização.

import { EmBreve } from "@/lib/em-breve";

export function Capa() {
  return (
    <section className="capa" aria-labelledby="capa-titulo">
      <div className="envelope capa-grade">
        <div>
          <EmBreve
            titulo="Sessão ao vivo"
            motivo="A transmissão pública de sessões em curso chega numa próxima fatia — a mesa de condução ao vivo já existe internamente."
          />
          <h1 id="capa-titulo">
            Acompanhe a sua <span className="luz">Câmara</span>, de perto.
          </h1>
          <p className="capa-sub">
            Veja o que está em pauta, assista às sessões, peça acesso à informação e exerça os seus
            direitos. Sem cadastro para consultar.
          </p>

          <EmBreve
            titulo="Buscar no portal"
            motivo="A busca por proposição, lei ou número de pedido ainda não tem uma rota pública real — chega numa próxima fatia."
          />

          <ul className="chips">
            <li>
              <a href="#destaque">Em tramitação agora</a>
            </li>
            <li>
              <a href="#civico">Sessões e atas</a>
            </li>
            <li>
              <a href="#balcoes">Acesso à informação</a>
            </li>
            <li>
              <a href="#balcoes">Os meus dados</a>
            </li>
          </ul>
        </div>

        <div className="capa-selo" aria-hidden="true">
          <SeloCivico />
        </div>
      </div>
    </section>
  );
}

function SeloCivico() {
  // hemiciclo cívico — decorativo, cores fixas do azulejo (não específicas de nenhum tenant).
  return (
    <svg viewBox="0 0 200 200" aria-hidden="true">
      <circle cx="100" cy="100" r="96" fill="none" stroke="var(--linha)" strokeWidth="1.5" />
      <circle cx="100" cy="100" r="80" fill="var(--surface)" stroke="var(--jade)" strokeWidth="2" />
      <circle cx="100" cy="100" r="72" fill="none" stroke="var(--amarelo)" strokeWidth="1" />
      <path
        d="M58 118 A42 42 0 0 1 142 118"
        fill="none"
        stroke="var(--jade)"
        strokeWidth="3.4"
        strokeLinecap="round"
      />
      <path
        d="M68 118 A32 32 0 0 1 132 118"
        fill="none"
        stroke="var(--cobalto)"
        strokeWidth="3.4"
        strokeLinecap="round"
      />
      <path
        d="M79 118 A21 21 0 0 1 121 118"
        fill="none"
        stroke="var(--telha)"
        strokeWidth="3.4"
        strokeLinecap="round"
      />
      <rect x="97" y="62" width="6" height="16" rx="3" fill="var(--amarelo)" />
    </svg>
  );
}
