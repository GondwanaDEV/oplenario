// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.

export interface PessoaOut {
  nome: string;
  cpfMascarado: string;
}

export interface RecursoPendenteOut {
  id: string;
  protocolo: string;
  recebidoEm: string;
}

export interface ItemEsicOut {
  id: string;
  protocolo: string;
  assunto: string;
  estado: "em_analise" | "indeferido" | "protocolado" | "respondido";
  recursoPendente: RecursoPendenteOut | null;
  aberto: boolean;
  recebidoEm: string;
  prazoVigente: string | null;
  diasRestantes: number | null;
  prorrogado: boolean;
}

export interface ItemOuvidoriaOut {
  id: string;
  protocolo: string;
  tipo: "denuncia" | "elogio" | "reclamacao" | "solicitacao" | "sugestao";
  assunto: string;
  identificacao: "anonima" | "identificada";
  estado: "arquivada" | "em_analise" | "protocolada" | "respondida";
  aberto: boolean;
  recebidoEm: string;
  prazoVigente: string | null;
  diasRestantes: number | null;
  prorrogado: boolean;
}

export interface ItemLgpdOut {
  id: string;
  protocolo: string;
  tipo: "acessar" | "com_quem_compartilhado" | "corrigir" | "eliminar" | "revogar_consentimento";
  estado: "em_analise" | "indeferida" | "protocolada" | "respondida";
  aberto: boolean;
  recebidoEm: string;
  prazoVigente: string | null;
  diasRestantes: number | null;
  prorrogado: boolean;
}

export interface FilaEsicOut {
  situacao: "abertos" | "respondidos" | "todos";
  itens: ItemEsicOut[];
}

export interface FilaOuvidoriaOut {
  situacao: "abertos" | "respondidos" | "todos";
  itens: ItemOuvidoriaOut[];
}

export interface FilaLgpdOut {
  situacao: "abertos" | "respondidos" | "todos";
  itens: ItemLgpdOut[];
}

export interface EventoOut {
  tipo: "resposta" | "recurso" | "decisao-recurso" | "prorrogacao" | "arquivamento";
  em: string;
  texto: string;
  por: string | null;
  protocolo?: string;
  deData?: string;
  paraData?: string;
}

export interface RecursoOut {
  id: string;
  protocolo: string;
  motivo: string | null;
  estado: "decidido" | "protocolado";
  recebidoEm: string;
  decididoEm: string | null;
  prazoVigente: string | null;
  diasRestantes: number | null;
  prorrogado: boolean;
}

export interface AcoesEsicOut {
  podeResponder: boolean;
  podeProrrogar: boolean;
  recursoPendenteId: string | null;
}

export interface AcoesOuvidoriaOut {
  podeResponder: boolean;
  podeArquivar: boolean;
  podeProrrogar: boolean;
}

export interface AcoesLgpdOut {
  podeResponder: boolean;
}

export interface DetalheEsicOut {
  id: string;
  protocolo: string;
  assunto: string;
  descricao: string;
  estado: "em_analise" | "indeferido" | "protocolado" | "respondido";
  requerente: PessoaOut | null;
  recurso: RecursoOut | null;
  historico: EventoOut[];
  acoes: AcoesEsicOut;
  aberto: boolean;
  recebidoEm: string;
  prazoVigente: string | null;
  diasRestantes: number | null;
  prorrogado: boolean;
}

export interface DetalheOuvidoriaOut {
  id: string;
  protocolo: string;
  tipo: "denuncia" | "elogio" | "reclamacao" | "solicitacao" | "sugestao";
  assunto: string;
  descricao: string;
  identificacao: "anonima" | "identificada";
  estado: "arquivada" | "em_analise" | "protocolada" | "respondida";
  historico: EventoOut[];
  acoes: AcoesOuvidoriaOut;
  aberto: boolean;
  recebidoEm: string;
  prazoVigente: string | null;
  diasRestantes: number | null;
  prorrogado: boolean;
}

export interface DetalheLgpdOut {
  id: string;
  protocolo: string;
  tipo: "acessar" | "com_quem_compartilhado" | "corrigir" | "eliminar" | "revogar_consentimento";
  detalhe: string | null;
  estado: "em_analise" | "indeferida" | "protocolada" | "respondida";
  titular: PessoaOut | null;
  historico: EventoOut[];
  acoes: AcoesLgpdOut;
  aberto: boolean;
  recebidoEm: string;
  prazoVigente: string | null;
  diasRestantes: number | null;
  prorrogado: boolean;
}
