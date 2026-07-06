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

export interface ProposicaoDetalheOut {
  id: string;
  tipo: "indicacao" | "mocao" | "projeto_decreto_legislativo" | "projeto_lei" | "projeto_lei_complementar" | "projeto_resolucao" | "proposta_emenda_lom" | "requerimento";
  ano: number;
  sequencial: number;
  urnLex: string;
  ementa: string;
  autorTipo?: string | null;
  autorId?: string | null;
  autorTexto?: string | null;
  objetoIndicacao?: string | null;
  destinatarioId?: string | null;
  destinatarioTexto?: string | null;
  tipoRequerimento?: string | null;
  categoriaMocao?: string | null;
  estado: string;
  lockVersion: number;
  atualizadoEm: string;
  texto?: string | null;
}
