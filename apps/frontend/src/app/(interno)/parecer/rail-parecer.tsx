// RailParecer — os 3 cards do rail do editor de parecer (Onda B Slice 5): Matéria, Relatoria, Antes de
// emitir. Porte de produto/design-system/o-plenario/telas/parecer.html:146-170. Sem prazo (chip/linha
// "Prazo" do mockup omitidos — §22.7.7 não ligado a pareceres ainda, Global Constraint "sem dado falso").
// Comissão/relator NÃO têm resolução id->nome no backend ainda (carry F2/FE) — comissão sai como o id cru
// (mesma disciplina de ficha-materia-tabs.tsx pra `p.comissaoId`), relator vira rótulo honesto sem nome ou
// some da lista quando ausente (derivarRelatoria, parecer-vista.ts).

import Link from "next/link";
import { derivarMateria, derivarRelatoria } from "@/lib/parecer-vista";
import { formatarData } from "@/lib/formatar-data";
import { comToken } from "@/lib/nav";
import type { ParecerEditorOut } from "@/lib/contrato-legislativo.gen";

export function RailParecer({ parecer, token }: { parecer: ParecerEditorOut; token: string | null }) {
  const materia = derivarMateria(parecer);
  const relatoria = derivarRelatoria(parecer);

  return (
    <aside className="rail" aria-label="Matéria e relatoria">
      <div className="rcard">
        <h3>Matéria</h3>
        {materia ? (
          <>
            <span className="num">{materia.numero}</span>
            <p className="ementa">{materia.ementa}</p>
            <Link className="ir" href={comToken(`/ficha-materia/${parecer.objetoId}`, token)}>
              Abrir a ficha
            </Link>
          </>
        ) : (
          <p className="ementa">Matéria não disponível.</p>
        )}
      </div>

      <div className="rcard">
        <h3>Relatoria</h3>
        <dl className="dl">
          <dt>Comissão</dt>
          <dd>{relatoria.comissaoId}</dd>
          {relatoria.relatorRotulo && (
            <>
              <dt>Relator</dt>
              <dd>{relatoria.relatorRotulo}</dd>
            </>
          )}
          <dt>Distribuído</dt>
          <dd className="mono">{formatarData(parecer.criadoEm)}</dd>
        </dl>
      </div>

      <div className="rcard">
        <h3>Antes de emitir</h3>
        <ul className="dica-lista">
          <li>Relatório e análise preenchidos.</li>
          <li>Voto coerente com a análise.</li>
          <li>Emendas anexadas, se o voto as previr.</li>
        </ul>
      </div>
    </aside>
  );
}
