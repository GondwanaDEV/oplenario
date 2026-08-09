// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.

export interface SessaoOut {
  id: string;
  sessaoLegislativaId: string;
  tipoSessao: "especial" | "extraordinaria" | "ordinaria" | "secreta" | "solene";
  numeroSequencial: number;
  estado: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
  modalidade: "hibrida" | "presencial" | "remota";
  delibera: boolean;
  transmitePublica: boolean;
  geraAtaRegimental: boolean;
  permiteVotoSecreto: boolean;
  permiteModalidadeRemota: boolean;
  agendadaPara?: string | null;
  abertaEm?: string | null;
  encerradaEm?: string | null;
  motivoNaoRealizada?: string | null;
}

export interface TransicaoSessaoOut {
  sessaoId: string;
  de: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
  para: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
}

export interface PresencaReciboOut {
  id: string;
  ocorridoEm: string;
  registradoEm: string;
}

export interface PresencaLoteReciboOut {
  recibos: PresencaReciboOut[];
}

export interface JustificativaAbertaOut {
  id: string;
  sessaoId: string;
  vereadorId: string;
  estado: "aprovada" | "indeferida" | "pendente";
  lockVersion: number;
}

export interface LinhaJustificativaOut {
  id: string;
  vereadorId: string;
  estado: "aprovada" | "indeferida" | "pendente";
  motivo: string;
  decididoPor: string | null;
  decididoEm: string | null;
  lockVersion: number;
}

export interface JustificativasOut {
  sessaoId: string;
  justificativas: LinhaJustificativaOut[];
}

export interface JustificativaDecididaOut {
  justificativaId: string;
  de: "aprovada" | "indeferida" | "pendente";
  para: "aprovada" | "indeferida" | "pendente";
}

export interface InscricaoReciboOut {
  id: string;
  ordem: number;
}

export interface DesistenciaInscricaoOut {
  inscricaoId: string;
  de: "desistencia" | "inscrita";
  para: "desistencia" | "inscrita";
}

export interface FalaReciboOut {
  falaId: string;
}

export interface CronometroEventoReciboOut {
  id: string;
}

export interface FalaEncerradaOut {
  falaId: string;
  tempoSegundos: number;
}

export interface DecisaoMesaReciboOut {
  id: string;
}

export interface IncidenteReciboOut {
  id: string;
}

export interface PautaItemOut {
  id: string;
  fase: "expediente" | "explicacoes_pessoais" | "grande_expediente" | "ordem_do_dia" | "tribuna_livre_cidadao";
  tipoItem: "comunicado" | "homenagem" | "leitura" | "proposicao";
  proposicaoId?: string | null;
  textoDescricao?: string | null;
  ordem: number;
}

export interface PautaOut {
  sessaoId: string;
  itens: PautaItemOut[];
}

export interface GravacaoReciboOut {
  id: string;
  audioHash: string;
}

export interface SegmentoOut {
  id: string;
  sessaoId?: string | null;
  iniciouEm: string;
  encerrouEm?: string | null;
  motivoInicio: "divisao_manual" | "inicio_sessao" | "reinicio_pos_falha";
  motivoFim?: "divisao_manual" | "falha_tecnica" | "fim_sessao" | null;
  fonteIngestao: "gravacao_local_pos_sessao" | "importacao_legado" | "rtmp_duplicado_ao_vivo" | "youtube_api_fallback";
  acessoRestrito: boolean;
  audioDisponivel: boolean;
}

export interface SegmentosOut {
  sessaoId: string;
  segmentos: SegmentoOut[];
}

export interface VinculoGravacaoOut {
  id: string;
  sessaoId: string;
}

export interface PautaItemAdicionadoOut {
  id: string;
  ordem: number;
}

export interface PautaItemReordenadoOut {
  id: string;
  de: number;
  para: number;
}

export interface PautaItemRemovidoOut {
  id: string;
}

export interface PresencaResumoOut {
  mediaPercentual: number | null;
  sessoesConsideradas: number;
  membrosDaCasa: number;
}

export interface LinhaChamadaOut {
  vereadorId: string;
  nome: string | null;
  nomeParlamentar: string | null;
  partido: string | null;
  cargoMesa: string | null;
  estado: "ausente" | "ausente-justificado" | "ausente-justificativa-pendente" | "licenciado" | "presente-plenario" | "presente-remoto";
  inconsistenciaCadastro: boolean;
  semAssento: boolean;
  desde: string | null;
  fonte: "autoatendimento" | "inferida_por_tribuna" | "inferida_por_voto" | "manual_secretaria" | "painel_eletronico" | null;
  registradoEm: string | null;
  justificativa: Record<string, unknown> | null;
}

export interface ChamadaQuorumOut {
  presentesPlenario: number;
  presentesRemoto: number;
  membrosDaCasa: number;
  presencasForaDoRoster: number;
}

export interface ChamadaConduzidaOut {
  id: string;
  conduzidaPor: string;
  membrosDaCasa: number;
  ocorridoEm: string;
  registradoEm: string;
}

export interface ChamadaOut {
  sessaoId: string;
  sessaoEstado: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
  instante: string;
  dataDeComposicao: string;
  composicaoResolvidaEm: string;
  semRegistroDePresenca: boolean;
  linhas: LinhaChamadaOut[];
  quorum: ChamadaQuorumOut;
  chamadasConduzidas: ChamadaConduzidaOut[];
}

export interface QuorumSessaoOut {
  sessaoId: string;
  sessaoEstado: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
  instante: string;
  dataDeComposicao: string;
  composicaoResolvidaEm: string;
  semRegistroDePresenca: boolean;
  quorum: ChamadaQuorumOut;
}
