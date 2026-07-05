// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.

export interface ProposicaoResumoOut {
  id: string;
  tipo: "indicacao" | "mocao" | "projeto_decreto_legislativo" | "projeto_lei" | "projeto_lei_complementar" | "projeto_resolucao" | "proposta_emenda_lom" | "requerimento";
  ano: number;
  sequencial: number;
  urnLex: string;
  ementa: string;
  autorTipo?: string | null;
  autorTexto?: string | null;
  estado: string;
  atualizadoEm: string;
}

export interface ListaProposicoesOut {
  itens: ProposicaoResumoOut[];
  total: number;
  pagina: number;
  tamanhoPagina: number;
}
