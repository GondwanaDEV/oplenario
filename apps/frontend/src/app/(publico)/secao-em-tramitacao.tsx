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

export function SecaoEmTramitacao({ ente }: { ente: string }) {
  const { itens, estado } = useMaterias(ente);

  const { destaque, maisTramitacao } =
    itens && itens.length > 0 ? escolherDestaque(itens) : { destaque: null, maisTramitacao: [] };

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
        </>
      )}
    </section>
  );
}
