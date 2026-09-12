// View-model puro de GET /portal/acompanhamentos (fatia "demo-tres-consertos" #3) — traduz MinhaMateria
// (wire, camelizado) pro que a tela do portal do cidadão mostra. Reusa `formatarNumeroProposicao`/
// `categorizarSituacao` (proposicoes-vista.ts) e `derivarTramitacao` (tramitacao-vista.ts) — nenhum
// vocabulário novo de tipo/estado é inventado aqui, mesma disciplina de derivarProposicoesVista.
//
// `indisponivel` (achado real da frente "truncamento-familia"): a projeção de transparência pode não ter
// chegado ainda pra uma matéria seguida (LEFT JOIN sem par). A linha aparece SEMPRE — nunca some da lista
// — com um rótulo honesto, nunca formatando `null`/`undefined` como se fossem dado real.

import { categorizarSituacao, formatarNumeroProposicao, type CategoriaSituacao } from "./proposicoes-vista";
import { derivarTramitacao } from "./tramitacao-vista";
import type { MinhaMateria } from "./use-meus-acompanhamentos";

export type LinhaAcompanhamentoVista = {
  proposicaoId: string;
  titulo: string;
  ementa: string | null;
  situacao: { rotulo: string; categoria: CategoriaSituacao } | null;
  seguidoEm: string;
  indisponivel: boolean;
};

export function derivarMeusAcompanhamentosVista(itens: MinhaMateria[]): LinhaAcompanhamentoVista[] {
  return itens.map((m) => {
    if (m.indisponivel || m.tipo === null || m.sequencial === null || m.ano === null) {
      return {
        proposicaoId: m.proposicaoId,
        titulo: "Matéria indisponível no momento",
        ementa: null,
        situacao: null,
        seguidoEm: m.seguidoEm,
        indisponivel: true,
      };
    }
    const estado = m.estado ?? "";
    const { rotuloSituacao } = derivarTramitacao(estado);
    return {
      proposicaoId: m.proposicaoId,
      titulo: formatarNumeroProposicao(m.tipo, m.sequencial, m.ano),
      ementa: m.ementa,
      situacao: { rotulo: rotuloSituacao, categoria: categorizarSituacao(estado) },
      seguidoEm: m.seguidoEm,
      indisponivel: false,
    };
  });
}
