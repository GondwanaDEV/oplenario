// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.

export interface NormaOut {
  normaId: string;
  proposicaoId: string;
  tipoNorma: string;
  numero: number;
  ano: number;
  urn: string;
  ementa: string;
  publicadoEm: string;
  veiculoPublicacao: string;
}

export interface MateriaOut {
  proposicaoId: string;
  tipo: string;
  ano: number;
  sequencial: number;
  urnLex: string;
  ementa: string;
  autorTipo?: string | null;
  autorTexto?: string | null;
  estado: string;
}

export interface FichaOut {
  proposicaoId: string;
  tipo: string;
  ano: number;
  sequencial: number;
  urnLex: string;
  ementa: string;
  autorTipo?: string | null;
  autorTexto?: string | null;
  estado: string;
  norma?: NormaOut | null;
}

export interface EncarregadoOut {
  nome: string;
  rotulo: string;
  email: string;
}

export interface AcompanhamentoEsicOut {
  protocolo: string;
  estado: "em_analise" | "indeferido" | "protocolado" | "respondido";
  diasRestantes: number | null;
}

export interface AcompanhamentoOuvidoriaOut {
  protocolo: string;
  estado: "arquivada" | "em_analise" | "protocolada" | "respondida";
  diasRestantes: number | null;
}
