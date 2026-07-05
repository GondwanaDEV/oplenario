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

export type LinhaProposicaoVista = {
  id: string;
  numero: string;
  especie: string;
  ementa: string;
  autor: string;
  situacao: { rotulo: string; estagios: EstagioTramitacao[] };
  atualizadoEm: string;
};

export function derivarProposicoesVista(itens: ProposicaoResumoOut[]): LinhaProposicaoVista[] {
  return itens.map((item) => {
    const sigla = SIGLA_POR_TIPO[item.tipo] ?? item.tipo;
    const { estagios, rotuloSituacao } = derivarTramitacao(item.estado);
    return {
      id: item.id,
      numero: `${sigla} ${item.sequencial}/${item.ano}`,
      especie: ESPECIE_POR_TIPO[item.tipo] ?? item.tipo,
      ementa: item.ementa,
      autor: item.autorTexto ?? "—",
      situacao: { rotulo: rotuloSituacao, estagios },
      atualizadoEm: item.atualizadoEm,
    };
  });
}
