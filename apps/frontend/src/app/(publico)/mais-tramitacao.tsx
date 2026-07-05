// MaisTramitacao — a lista "mais em tramitação" (Task 1.3, Fatia A2.1). Porte de
// portal-cidadao.html:492-508. Ícone da situação derivado de forma best-effort (categoria ampla —
// concluído/em-comissões/em-andamento); não é dado novo, só recategoriza `situacao` (já derivada por
// derivarTramitacao) para o grafismo do marcador.

import type { MateriaVista } from "@/lib/materia-vista";

function classeIcone(situacao: string): string {
  if (/aprovad|conclu[ií]d/i.test(situacao)) return "i-feito";
  if (/comiss/i.test(situacao)) return "i-comissao";
  return "i-andamento";
}

export function MaisTramitacao({ itens, ente }: { itens: MateriaVista[]; ente: string }) {
  if (itens.length === 0) return null;
  return (
    <ul className="mais-trami" aria-label="Outras proposições em tramitação">
      {itens.map((item) => (
        <li key={item.proposicaoId}>
          <a href={`/portal/casa/${ente}/materias/${item.proposicaoId}`}>
            <span className="mt-ref">{item.ref}</span>
            <span className="mt-tit">{item.titulo}</span>
            <span className="mt-sit">
              <i className={classeIcone(item.situacao)} aria-hidden="true" />
              {item.situacao}
            </span>
          </a>
        </li>
      ))}
    </ul>
  );
}
