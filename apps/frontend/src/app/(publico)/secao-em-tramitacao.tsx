"use client";

// SecaoEmTramitacao — Task 1.3 (Fatia A2.1, Portal do Cidadão). Compõe useMaterias (fetch client-side,
// ver DESVIO documentado em src/lib/use-materias.ts) + escolherDestaque (materia-vista, 1.1) +
// DestaqueTramitacao/MaisTramitacao. Arquivo NOVO fora do file-list literal do plano — a alternativa
// (fetch em page.tsx/Server Component) não resolve em SSR (rewrite same-origin só existe para requests
// do browser). Cada estado degrada só ESTA seção (Global Constraints — "degradação por seção").

import { useMaterias } from "@/lib/use-materias";
import { escolherDestaque } from "@/lib/materia-vista";
import { EmBreve } from "@/lib/em-breve";
import { DestaqueTramitacao } from "./destaque-tramitacao";
import { MaisTramitacao } from "./mais-tramitacao";

// mesmo ícone/classe de secao-perfil-vereador.tsx (".nota-secao") — não extraído para compartilhado porque
// as duas seções não têm um módulo comum hoje; duplicação de 1 ícone é aceitável, mesmo racional de
// adapters/out/materia.clj não importar norma (ADR-0001).
const ICONE_INFO = (
  <svg
    width="14"
    height="14"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={2}
    aria-hidden="true"
  >
    <circle cx="12" cy="12" r="9" />
    <path d="M12 8h.01M11 12h1v4h1" />
  </svg>
);

export function SecaoEmTramitacao({ ente }: { ente: string }) {
  const { itens, materiasTotal, estado } = useMaterias(ente);

  const { destaque, maisTramitacao, truncamento } =
    itens && itens.length > 0
      ? escolherDestaque(itens, materiasTotal)
      : { destaque: null, maisTramitacao: [], truncamento: null };

  return (
    <section
      className="secao"
      id="destaque"
      aria-labelledby="destaque-titulo"
      aria-busy={estado === "carregando"}
    >
      <div className="secao-cabeca">
        <h2 id="destaque-titulo">Em tramitação agora</h2>
      </div>

      {estado === "erro" && (
        <EmBreve
          titulo="Em tramitação agora"
          motivo="Não foi possível carregar as proposições em tramitação agora. Tente novamente em instantes."
        />
      )}

      {estado === "pronto" && itens && itens.length === 0 && (
        <EmBreve
          titulo="Em tramitação agora"
          motivo="Nenhuma matéria em tramitação no momento — volte em breve."
        />
      )}

      {estado === "pronto" && destaque && (
        <>
          <DestaqueTramitacao destaque={destaque} ente={ente} />
          <MaisTramitacao itens={maisTramitacao} ente={ente} />
          {/* frente "truncamento-familia" sitio (a): sem isto, um cidadão via 4 matérias e nunca soube que
              a Casa tem mais (sem contagem, sem "+N", sem página 2) — molde: secao-perfil-vereador.tsx. */}
          {truncamento && (
            <p className="nota-secao">
              {ICONE_INFO}
              {truncamento}
            </p>
          )}
        </>
      )}
    </section>
  );
}
