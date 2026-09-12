// Título de exibição do objeto EM VOTAÇÃO (fatia "demo-tres-consertos" #2). Achado ao vivo (Daouda,
// 12/09/2026): `/votar` (cockpit do vereador) e o telão da Mesa mostravam só o placar — nenhuma ementa,
// nenhum número de matéria; o vereador votava num objeto não identificado. Regra da casa: voto sem
// objeto identificado é PIOR que erro visível — este módulo nunca devolve título vazio nem inventado.
//
// `proposicao`/`redacao_final` resolvem TOTALMENTE (GET /sessoes/:id/votacoes/:id, backend): número real
// (reusa `formatarNumeroProposicao`, já usado em proposicoes-vista.ts — não reinventa o mapeamento
// tipo->sigla) + ementa real. `emenda`/`parecer`/`requerimento` são entidades próprias que esta fatia NÃO
// resolve por completo (escopo maior, registrado); o rótulo cai no TIPO — honesto, nunca em branco.

import { formatarNumeroProposicao } from "./proposicoes-vista";
import type { DetalheVotacaoOut, EstadoDetalheVotacao } from "./use-detalhe-votacao";

const ROTULO_TIPO_HONESTO: Record<string, string> = {
  emenda: "Emenda",
  parecer: "Parecer",
  requerimento: "Requerimento",
};

function rotuloDoTipo(objetoTipo: string): string {
  return ROTULO_TIPO_HONESTO[objetoTipo] ?? objetoTipo;
}

export function tituloObjetoVotacao(dados: DetalheVotacaoOut | null, estado: EstadoDetalheVotacao): string {
  if (estado === "carregando" || estado === "ocioso") return "Carregando a matéria em votação…";
  if (estado === "erro" || !dados) {
    return "Não foi possível identificar a matéria em votação agora — confira com a Mesa antes de votar.";
  }
  if (dados.proposicao) {
    return `${formatarNumeroProposicao(dados.proposicao.tipo, dados.proposicao.sequencial, dados.proposicao.ano)} — ${dados.proposicao.ementa}`;
  }
  return `${rotuloDoTipo(dados.objetoTipo)} em votação — número e ementa ainda não disponíveis nesta tela.`;
}
