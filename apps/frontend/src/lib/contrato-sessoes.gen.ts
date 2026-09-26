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
  lockVersion: number;
}

export interface SessoesOut {
  sessoes: SessaoOut[];
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

export interface DecisaoMesaOut {
  id: string;
  presidenteId: string;
  questao: string;
  decisao: string;
  decididoEm: string;
  fundamentacao?: string | null;
  falaId?: string | null;
}

export interface IncidenteOut {
  id: string;
  tipo: "pedido_vista" | "urgencia" | "verificacao_votacao" | "votacao_em_bloco";
  resultado: "deferido" | "indeferido" | "prejudicado" | "retirado";
  descricao: string;
  ocorridoEm: string;
  objetoTipo?: "emenda" | "proposicao" | "votacao" | null;
  objetoId?: string | null;
  requerenteId?: string | null;
  deliberacao?: string | null;
}

export interface AtosMesaOut {
  sessaoId: string;
  decisoes: DecisaoMesaOut[];
  incidentes: IncidenteOut[];
}

export interface ProposicaoResumoPautaOut {
  tipo: string;
  ano: number;
  sequencial: number;
  ementa: string;
  autorTexto?: string | null;
}

export interface PautaItemOut {
  id: string;
  fase: "expediente" | "explicacoes_pessoais" | "grande_expediente" | "ordem_do_dia" | "tribuna_livre_cidadao";
  tipoItem: "comunicado" | "homenagem" | "leitura" | "proposicao";
  proposicaoId?: string | null;
  proposicao?: ProposicaoResumoPautaOut | null;
  textoDescricao?: string | null;
  ordem: number;
  lockVersion: number;
}

export interface EmApreciacaoOut {
  itemId: string;
  anunciadoEm: string;
}

export interface PautaOut {
  sessaoId: string;
  itens: PautaItemOut[];
  emApreciacao?: EmApreciacaoOut;
}

export interface ItemAnunciadoOut {
  id: string;
  itemId: string;
  anunciadoEm: string;
}

export interface GravacaoReciboOut {
  id: string;
  audioHash: string;
  lockVersion: number;
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
  presentesTotal: number;
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

export interface ComposicaoMembroOut {
  vereadorId: string;
  nomeParlamentar: string | null;
  cargoMesa: string | null;
  partido: string | null;
}

export interface ComposicaoSessaoOut {
  sessaoId: string;
  sessaoEstado: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
  dataDeComposicao: string;
  composicaoResolvidaEm: string;
  membros: ComposicaoMembroOut[];
}

export interface OradorAtualOut {
  falaId: string;
  oradorId: string;
  tipoFala: "aparte" | "comunicado" | "explicacao_pessoal" | "pela_ordem" | "principal" | "questao_de_ordem";
  fase: "expediente" | "explicacoes_pessoais" | "grande_expediente" | "ordem_do_dia" | "tribuna_livre_cidadao";
  iniciouEm: string;
  inscricaoId: string | null;
  tempoConcedidoSegundos: number | null;
  lockVersion: number;
}

export interface MarcoCronometroOut {
  tipo: "aparte_concedido" | "pausada" | "retomada" | "tempo_adicional_concedido";
  ocorridoEm: string;
  segundosAdicionais: number | null;
}

export interface InscritoTribunaOut {
  inscricaoId: string;
  vereadorId: string;
  origemInscricao: "automatica_por_autoria" | "intra_sessao_pedido" | "pre_sessao_app" | "pre_sessao_secretaria";
  fase: "expediente" | "explicacoes_pessoais" | "grande_expediente" | "ordem_do_dia" | "tribuna_livre_cidadao";
  ordem: number;
  lockVersion: number;
}

export interface TribunaOut {
  sessaoId: string;
  oradorAtual: OradorAtualOut | null;
  marcosCronometro: MarcoCronometroOut[];
  inscritos: InscritoTribunaOut[];
}

export interface FolhaMetadadosOut {
  id: string;
  versao: number;
  specVersao: string;
  htmlHash: string;
  pdfHash: string;
  geradaPor: string;
  geradaPorNome?: string;
  geradaEm: string;
  jaCongelada?: boolean;
}

export interface FolhasDaSessaoOut {
  sessaoId: string;
  folhas: FolhaMetadadosOut[];
}

export interface AssiduidadeSessaoOut {
  id: string;
  numero: number;
  tipo: "especial" | "extraordinaria" | "ordinaria" | "secreta" | "solene";
  estado: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
  dataDeReferencia: string;
  sigilosa: boolean;
  quorum: ChamadaQuorumOut;
}

export interface AssiduidadeVereadorOut {
  id: string;
  nome: string | null;
  nomeParlamentar: string | null;
  partido: string | null;
  partidoVariou: boolean;
}

export interface AssiduidadePorVereadorOut {
  vereadorId: string;
  sessoesComputadas: number;
  comparecimentos: number;
  ausenciasJustificadas: number;
  ausenciasComJustificativaPendente: number;
  ausenciasInjustificadas: number;
  sessoesLicenciado: number;
  percentual: number | null;
}

export interface AssiduidadeDetalheLinhaOut {
  sessaoId: string;
  vereadorId: string;
  estado: "ausente" | "ausente-justificado" | "ausente-justificativa-pendente" | "licenciado" | "presente-plenario" | "presente-remoto";
  sigilosa: boolean;
}

export interface AssiduidadeTotaisOut {
  sessoesConsideradas: number;
  vereadoresConsiderados: number;
  sessoesSigilosas: number;
  sessoesSemDataDeReferencia: number;
  criterioDeInclusao: string;
  notaDeMetodologia: string;
}

export interface AssiduidadeOut {
  sessoes: AssiduidadeSessaoOut[];
  vereadores: AssiduidadeVereadorOut[];
  porVereador: AssiduidadePorVereadorOut[];
  detalhe: AssiduidadeDetalheLinhaOut[];
  totais: AssiduidadeTotaisOut;
}
