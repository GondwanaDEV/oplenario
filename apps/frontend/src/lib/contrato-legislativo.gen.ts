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

export interface HistoricoTramitacaoItemOut {
  deEstado: string;
  paraEstado: string;
  gatilho: string;
  ocorridoEm: string;
}

export interface ApensacaoOut {
  apensadaId: string;
  apensadaEm: string;
  motivoApensacao?: string | null;
}

export interface EmendaResumoOut {
  id: string;
  numeroLocal: number;
  tipoEmenda: string;
  momentoApresentacao: string;
  autorTipo?: string | null;
  autorTexto?: string | null;
  estado: string;
}

export interface ParecerResumoOut {
  id: string;
  comissaoId: string;
  relatorId?: string | null;
  votoRelator?: string | null;
  estado: string;
}

export interface FichaMateriaOut {
  proposicao: ProposicaoDetalheOut;
  tramitacao: HistoricoTramitacaoItemOut[];
  apensadas: ApensacaoOut[];
  emendas: EmendaResumoOut[];
  pareceres: ParecerResumoOut[];
}

export interface ObjetoResumoOut {
  id: string;
  tipo: string;
  ano: number;
  sequencial: number;
  urnLex: string;
  ementa: string;
}

export interface ParecerEditorOut {
  id: string;
  objetoTipo: string;
  objetoId: string;
  comissaoId: string;
  relatorId?: string | null;
  votoRelator?: string | null;
  estado: string;
  templateId: string;
  lockVersion: number;
  criadoEm: string;
  objeto?: ObjetoResumoOut | null;
  relatorio?: string | null;
  analise?: string | null;
  textoEstado: "rascunho" | "vazio" | "vigente";
  textoNumeroVersao?: number | null;
  assinaturaAlgoritmo?: string | null;
  assinadoPor?: string | null;
  assinadoEm?: string | null;
}

export interface DocumentoOut {
  id: string;
  modeloId: string;
  tipoDocumento: string;
  assunto: string;
  corpo: string;
  estado: string;
  protocoloGeralId?: string | null;
  protocoloNumero?: number | null;
  protocoloAno?: number | null;
  lockVersion: number;
  criadoEm: string;
}

export interface DocumentoModeloOut {
  id: string;
  chave: string;
  nome: string;
  tipoDocumento: string;
}

export interface ListaModelosOut {
  itens: DocumentoModeloOut[];
}

export interface ProtocoloGeralOut {
  id: string;
  numero: number;
  ano: number;
  objetoTipo: string;
  objetoId?: string | null;
  sentido: string;
  assunto: string;
  protocoladoEm: string;
}

export interface LivroProtocoloOut {
  itens: ProtocoloGeralOut[];
}

export interface AutografoOut {
  id: string;
  proposicaoId: string;
  numero: number;
  ano: number;
  textoVersaoId?: string | null;
  destinatarioTexto: string;
  destinatarioId?: string | null;
  enviadoEm: string;
  prazoRespostaEm?: string | null;
}

export interface TramitacaoExecutivaOut {
  id: string;
  autografoId: string;
  estado: string;
  vetoTipo?: string | null;
  vetoRazoes?: string | null;
  vetoVotacaoId?: string | null;
  respondidoEm?: string | null;
  apreciadoEm?: string | null;
  lockVersion: number;
}

export interface PosAprovacaoOut {
  autografo?: AutografoOut | null;
  tramitacaoExecutiva?: TramitacaoExecutivaOut | null;
}

export interface ProposicaoResumoMeuPainelOut {
  id: string;
  tipo: string;
  ano: number;
  sequencial: number;
  urnLex: string;
  ementa: string;
  estado: string;
  atualizadoEm: string;
}

export interface ParecerResumoMeuPainelOut {
  id: string;
  objetoTipo: string;
  objetoId: string;
  comissaoId: string;
  estado: string;
  votoRelator: string | null;
  criadoEm: string;
}

export interface CienciaPendenteOut {
  parecerId: string;
  proposicaoId: string;
  tipo: string;
  ano: number;
  sequencial: number;
  urnLex: string;
  ementa: string;
}

export interface MeuPainelOut {
  vereadorId?: string | null;
  proposicoes: ProposicaoResumoMeuPainelOut[];
  pareceres: ParecerResumoMeuPainelOut[];
  ciencias: CienciaPendenteOut[];
}

export interface AcusarCienciaOut {
  id: string;
  cienteEm: string;
}
