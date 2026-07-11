// View-model puro da lista de proposições (Onda B Slice 1) — traduz ProposicaoResumoOut (wire, camelizado)
// para o que a tabela mostra. Reaproveita derivarTramitacao (já construído na A2 para este mesmo `estado`
// livre/template-driven) — nenhum vocabulário novo de estado é inventado aqui.

import { derivarTramitacao, type EstagioTramitacao } from "./tramitacao-vista";
import type { ProposicaoResumoOut } from "./contrato-legislativo.gen";

const SIGLA_POR_TIPO: Record<string, string> = {
  projeto_lei: "PL",
  projeto_lei_complementar: "PLC",
  projeto_resolucao: "PR",
  projeto_decreto_legislativo: "PDL",
  proposta_emenda_lom: "PELOM",
  indicacao: "IND",
  requerimento: "REQ",
  mocao: "MOÇ",
};

const ESPECIE_POR_TIPO: Record<string, string> = {
  projeto_lei: "Projeto de Lei",
  projeto_lei_complementar: "Projeto de Lei Complementar",
  projeto_resolucao: "Projeto de Resolução",
  projeto_decreto_legislativo: "Decreto Legislativo",
  proposta_emenda_lom: "Emenda à LOM",
  indicacao: "Indicação",
  requerimento: "Requerimento",
  mocao: "Moção",
};

// Única fonte do vocabulário de tipos de proposição conhecidos — reusado por qualquer filtro/seletor de
// espécie (ex.: tramitacao-board-vista.ts) em vez de cada tela manter sua própria lista hardcoded.
export const TIPOS_PROPOSICAO = Object.keys(ESPECIE_POR_TIPO);

export type CategoriaSituacao = "tram" | "aguarda" | "aprovada" | "arquivada";

// Exportados (Onda B Slice 3, ficha-materia) — o cabeçalho `.ficha-cab` precisa do MESMO formato "SIGLA
// sequencial/ano" / rótulo de espécie já usado aqui; reusa em vez de reinventar o mapeamento.
export function formatarNumeroProposicao(tipo: string, sequencial: number, ano: number): string {
  return `${SIGLA_POR_TIPO[tipo] ?? tipo} ${sequencial}/${ano}`;
}

export function formatarEspecieProposicao(tipo: string): string {
  return ESPECIE_POR_TIPO[tipo] ?? tipo;
}

export type LinhaProposicaoVista = {
  id: string;
  numero: string;
  especie: string;
  ementa: string;
  autor: string;
  situacao: { rotulo: string; estagios: EstagioTramitacao[]; categoria: CategoriaSituacao };
  atualizadoEm: string;
};

// Categoria do chip de status (review ecc:react-reviewer — a cor do chip estava morta: toda linha
// renderizava a mesma variante). Concern NOVO e separado de `derivarTramitacao` acima: aquele deriva a
// FAIXA de progresso (5 estágios ilustrativos); este deriva só a cor/categoria do chip solto. Mesmo
// `estado` de entrada, leitura diferente — não substitui nem estende `tramitacao-vista.ts`.
//
// `estado` é string livre/template-driven por câmara (mesmo aviso de vocabulário de tramitacao-vista.ts)
// — então esta função é FAIL-CLOSED por construção: qualquer `estado` fora das listas conhecidas cai no
// default neutro "tram" (nunca lança, nunca vira "aguarda"/"aprovada"/"arquivada" por engano).
// Exportados (Onda B Slice 4, tramitacao-board-vista) — o quadro de tramitação agrupa colunas pelos MESMOS
// conjuntos de estado usados aqui pro chip de status; nenhum vocabulário novo é inventado lá.
export const ESTADOS_APROVADOS = new Set(["aprovada", "sancionado", "sancao_tacita", "veto_derrubado"]);
export const ESTADOS_ARQUIVADOS = new Set(["arquivada", "rejeitada", "prejudicada", "retirada"]);
export const ESTADOS_AGUARDANDO_PAUTA = new Set(["em_pauta", "aguardando_pauta"]);

export function categorizarSituacao(estado: string): CategoriaSituacao {
  if (ESTADOS_APROVADOS.has(estado)) return "aprovada";
  if (ESTADOS_ARQUIVADOS.has(estado)) return "arquivada";
  if (ESTADOS_AGUARDANDO_PAUTA.has(estado)) return "aguarda";
  return "tram";
}

export function derivarProposicoesVista(itens: ProposicaoResumoOut[]): LinhaProposicaoVista[] {
  return itens.map((item) => {
    const { estagios, rotuloSituacao } = derivarTramitacao(item.estado);
    return {
      id: item.id,
      numero: formatarNumeroProposicao(item.tipo, item.sequencial, item.ano),
      especie: formatarEspecieProposicao(item.tipo),
      ementa: item.ementa,
      autor: item.autorTexto ?? "—",
      situacao: { rotulo: rotuloSituacao, estagios, categoria: categorizarSituacao(item.estado) },
      atualizadoEm: item.atualizadoEm,
    };
  });
}
