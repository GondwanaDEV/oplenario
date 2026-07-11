// CardPrazoExecutivo — o anel "Prazo do Executivo" (Onda B Slice 7). Porte de pos-aprovacao.html:206-221
// (.prazo-card), reusando AnelPrazo (src/lib/charts/anel-prazo.tsx, já existente/provado em
// paineis/mesa/o-que-vence.tsx) em vez de desenhar outro SVG de anel do zero — mesma disciplina de "não
// duplicar chart" já em vigor no app. `diasRestantes`/`diasTotal` vêm 100% de
// derivarPrazoExecutivo (pos-aprovacao-vista.ts), calculado client-side a partir de `autografo.enviadoEm`/
// `prazoRespostaEm` — nenhum dado novo do backend (spec §2).
//
// `.prz-{urgente|atencao|tranquilo}` colore o arco (mesmo vocabulário de classe já definido em
// paineis/mesa/mesa.css, nota "ainda sem CSS em nenhum outro lugar do front" — agora tem um 2º uso real).
// `[GAP LOM]` — mesmo aviso do mockup: o prazo de sanção é da Lei Orgânica do Município, varia por câmara.

import { AnelPrazo } from "@/lib/charts/anel-prazo";
import { derivarPrazoExecutivo } from "@/lib/pos-aprovacao-vista";
import type { AutografoOut } from "@/lib/contrato-legislativo.gen";

export function CardPrazoExecutivo({ autografo, agora }: { autografo: AutografoOut; agora?: Date }) {
  const prazo = derivarPrazoExecutivo(autografo, agora);

  return (
    <div className="card prazo-card">
      <h2>Prazo do Executivo</h2>

      {prazo.estado === "sem-prazo" && (
        <p className="prazo-texto">Prazo de resposta não informado para este autógrafo.</p>
      )}

      {prazo.estado === "vencido" && (
        <>
          <div className="prz-urgente">
            <AnelPrazo diasRestantes={0} diasTotal={1} rotulo="Prazo do Executivo" />
          </div>
          <p>
            Prazo vencido há {prazo.diasVencidos} dia(s). Sem manifestação, a lei pode ser promulgada pela
            Câmara.
          </p>
        </>
      )}

      {prazo.estado === "em-curso" && (
        <>
          <div className={`prz-${prazo.categoria}`}>
            <AnelPrazo diasRestantes={prazo.diasRestantes} diasTotal={prazo.diasTotal} rotulo="Prazo do Executivo" />
          </div>
          <p>
            Restam para o Executivo sancionar ou vetar. Sem manifestação no prazo, a lei pode ser promulgada
            pela Câmara.
          </p>
        </>
      )}

      <p className="nota-gap">
        <span className="tag">LOM</span> O prazo de sanção é o da Lei Orgânica do Município — confira o
        artigo aplicável (varia por câmara).
      </p>
    </div>
  );
}
