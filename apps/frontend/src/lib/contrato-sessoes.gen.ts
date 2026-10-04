// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.

export interface SessaoOut {
  id: string;
  sessaoLegislativaId: string;
  tipoSessao: "audiencia_publica" | "especial" | "extraordinaria" | "ordinaria" | "secreta" | "solene";
  numeroSequencial: number;
  estado: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
  modalidade: "hibrida" | "presencial" | "remota";
  delibera: boolean;
  transmitePublica: boolean;
  geraAtaRegimental: boolean;
  permiteVotoSecreto: boolean;
  permiteModalidadeRemota: boolean;
  exigeQuorum?: boolean;
  aceitaInscricaoCidadao?: boolean;
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

export interface MinhaPresencaOut {
  vereadorId: string;
  presente: boolean;
  ocorridoEm?: string;
  modalidade?: "plenario" | "remoto";
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

export interface PublicacaoResumoOut {
  versao: number;
  publicadaEm: string;
  alteradaDesde: boolean;
}

export interface PautaOut {
  sessaoId: string;
  itens: PautaItemOut[];
  emApreciacao?: EmApreciacaoOut;
  publicacao?: PublicacaoResumoOut;
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

export interface SugestaoSessaoOut {
  sessaoId: string;
  tipoSessao: "audiencia_publica" | "especial" | "extraordinaria" | "ordinaria" | "secreta" | "solene";
  numeroSequencial: number;
  estado: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
  inicio: string;
}

export interface GravacaoPendenteOut {
  id: string;
  iniciouEm: string;
  encerrouEm?: string | null;
  fonteIngestao: "gravacao_local_pos_sessao" | "importacao_legado" | "rtmp_duplicado_ao_vivo" | "youtube_api_fallback";
  acessoRestrito: boolean;
  audioHash?: string | null;
  lockVersion: number;
  sugestao?: SugestaoSessaoOut | null;
}

export interface GravacoesPendentesOut {
  segmentos: GravacaoPendenteOut[];
}

export interface TranscricaoPonteiroOut {
  id: string;
  segmentoId: string;
  situacao: "concluida" | "falhou";
  transcricaoId?: string | null;
  versao?: number | null;
  idioma?: string | null;
  duracaoS?: number | null;
  nTrechos?: number | null;
  coberturaAtribuida?: number | null;
  modeloAsr?: string | null;
  modeloDiarizacao?: string | null;
  categoriaErro?: string | null;
  detalheErro?: string | null;
  retentavel?: boolean | null;
  ocorridoEm: string;
}

export interface TranscricoesOut {
  sessaoId: string;
  itens: TranscricaoPonteiroOut[];
}

export interface TrechoTranscricaoOut {
  inicio: number;
  fim: number;
  texto: string;
  oradorId?: string | null;
  oradorNome?: string | null;
}

export interface TranscricaoConteudoOut {
  ponteiro: TranscricaoPonteiroOut;
  trechos: TrechoTranscricaoOut[];
}

export interface AtaVersaoOut {
  id: string;
  versao: number;
  origemRedacao: "gerada_automaticamente" | "redigida_externamente";
  conteudoSha256: string;
  motivoRetificacao?: string | null;
  rascunhoId?: string | null;
  modeloLlmId?: string | null;
  promptVersao?: string | null;
  proporcaoAlterada?: number | null;
  publicadaPorNome?: string | null;
  publicadaEm: string;
}

export interface AtaAtualOut {
  versao: AtaVersaoOut;
  texto: string;
}

export interface AtaRascunhoOut {
  solicitacaoId: string;
  situacao: "solicitado" | "pronto" | "falhou";
  solicitadoEm: string;
  rascunhoId?: string | null;
  modeloLlmId?: string | null;
  promptVersao?: string | null;
  incerteza?: "normal" | "revisar_com_atencao" | null;
  nCitacoes?: number | null;
  nCitacoesConferidas?: number | null;
  nParagrafosSemFonte?: number | null;
  nPontosAConfirmar?: number | null;
  categoriaErro?: string | null;
  detalheErro?: string | null;
  retentavel?: boolean | null;
  ocorridoEm: string;
}

export interface AtaSessaoOut {
  sessaoId: string;
  podeTerAta: boolean;
  atual?: AtaAtualOut | null;
  versoes: AtaVersaoOut[];
  rascunho?: AtaRascunhoOut | null;
}

export interface AtaReciboOut {
  id: string;
  versao: number;
  conteudoSha256: string;
}

export interface CitacaoRascunhoOut {
  fonteId: string;
  trecho?: string | null;
  inicio: number;
  fim: number;
  status: "conferida" | "sem_trecho" | "trecho_nao_encontrado" | "fonte_nao_lida";
  rotulo?: string | null;
}

export interface IncertezaRascunhoOut {
  nivel: "normal" | "revisar_com_atencao";
  motivos: string[];
}

export interface AtaRascunhoConteudoOut {
  rascunhoId: string;
  texto: string;
  textoLimpo: string;
  incerteza: IncertezaRascunhoOut;
  citacoes: CitacaoRascunhoOut[];
  paragrafosSemFonte: number[];
  pontosAConfirmar: string[];
  modeloLlmId: string;
  promptVersao: string;
  execucaoIa?: string | null;
}

export interface SolicitacaoRascunhoOut {
  solicitacaoId: string;
}

export interface SessaoAnteriorOut {
  id: string;
  tipoSessao: string;
  numeroSequencial: number;
  abertaEm?: string | null;
  encerradaEm?: string | null;
}

export interface AtaParaLerOut {
  versao: number;
  texto: string;
  conteudoSha256: string;
  origemRedacao: string;
  publicadaEm: string;
  publicadaPorNome?: string | null;
}

export interface LeituraAtaRegistradaOut {
  modo: "dispensada" | "presencial" | "voz_sintetizada";
  ataSessaoId: string;
  ataVersao: number;
  registradaEm: string;
  registradaPorNome?: string | null;
}

export interface LeituraAtaOut {
  sessaoId: string;
  podeRegistrar: boolean;
  anterior?: SessaoAnteriorOut | null;
  ata?: AtaParaLerOut | null;
  leitura?: LeituraAtaRegistradaOut | null;
}

export interface SessaoDoLivroOut {
  id: string;
  tipoSessao: string;
  numeroSequencial: number;
  abertaEm?: string | null;
  encerradaEm?: string | null;
  agendadaPara?: string | null;
}

export interface LeituraDoLivroOut {
  modo: "dispensada" | "presencial" | "voz_sintetizada";
  registradaEm: string;
  ataVersao: number;
}

export interface AtaDoLivroItemOut {
  sessao: SessaoDoLivroOut;
  versao: number;
  origemRedacao: "gerada_automaticamente" | "redigida_externamente";
  conteudoSha256: string;
  publicadaEm: string;
  leitura?: LeituraDoLivroOut | null;
}

export interface LivroAtasOut {
  atas: AtaDoLivroItemOut[];
}

export interface VersaoDoLivroOut {
  versao: number;
  origemRedacao: "gerada_automaticamente" | "redigida_externamente";
  conteudoSha256: string;
  motivoRetificacao?: string | null;
  publicadaEm: string;
  publicadaPorNome?: string | null;
}

export interface AtaDoLivroOut {
  sessao: SessaoDoLivroOut;
  versao: VersaoDoLivroOut;
  texto: string;
  vigente: boolean;
  versoes: VersaoDoLivroOut[];
  leitura?: LeituraDoLivroOut | null;
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
  exigeQuorum?: boolean;
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
  tipo: "audiencia_publica" | "especial" | "extraordinaria" | "ordinaria" | "secreta" | "solene";
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

export interface TempoRegimentalOut {
  fase: string | null;
  tipoFala: string;
  segundos: number;
  referenciaNormativa: string | null;
}

export interface TemposRegimentaisOut {
  itens: TempoRegimentalOut[];
}

export interface AvisoPautaOut {
  tipo: "antecedencia-nao-cumprida" | "pedido-juridico-pendente" | "sem-parecer-comissao";
  itemId?: string;
  proposicaoId?: string;
  proposicao?: ProposicaoResumoPautaOut;
  pareceresEmAndamento?: number;
  pedidosPendentes?: number;
  minimoHoras?: number;
  horasReais?: number;
}

export interface RegraPautaOut {
  quemPublica: "mesa" | "presidente" | "primeiro_secretario" | "secretaria";
  antecedenciaMinimaHoras: number | null;
  configurada: boolean;
  atualizadoEm?: string | null;
}

export interface AntecedenciaPautaOut {
  minimoHoras: number;
  horasReais: number;
  cumprida: boolean;
}

export interface VersaoPautaOut {
  versao: number;
  tipoVersao: "execucao_final" | "publicacao_inicial" | "republicacao";
  publicadaEm: string;
  itens: number;
  justificativa?: string | null;
  aTitulo?: "mesa" | "presidente" | "primeiro_secretario" | "secretaria" | null;
  publicadaPorNome?: string | null;
}

export interface PublicacaoPautaOut {
  sessaoId: string;
  regra: RegraPautaOut;
  podePublicar: boolean;
  motivo?: string | null;
  republicacao: boolean;
  itensNaPauta: number;
  ultima?: VersaoPautaOut | null;
  versoes: VersaoPautaOut[];
  alteradaDesdeAPublicacao: boolean;
  avisos: AvisoPautaOut[];
  avisosIndisponiveis: boolean;
  antecedencia?: AntecedenciaPautaOut | null;
}

export interface PautaPublicadaOut {
  sessaoId: string;
  versao: number;
  tipoVersao: "execucao_final" | "publicacao_inicial" | "republicacao";
  publicadaEm: string;
  itens: number;
  avisos: AvisoPautaOut[];
  aviso?: "antecedencia-nao-cumprida";
  antecedencia?: AntecedenciaPautaOut | null;
}

export interface PautaOficialResumoOut {
  versao: number;
  publicadaEm: string;
  itens: number;
}

export interface SessaoPautaPublicaOut {
  sessaoId: string;
  tipoSessao: "audiencia_publica" | "especial" | "extraordinaria" | "ordinaria" | "secreta" | "solene";
  numeroSequencial: number;
  estado: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
  agendadaPara?: string | null;
  abertaEm?: string | null;
  pautaOficial?: PautaOficialResumoOut | null;
}

export interface PautasPublicasOut {
  sessoes: SessaoPautaPublicaOut[];
}

export interface ItemPautaOficialOut {
  id: string;
  fase: "expediente" | "explicacoes_pessoais" | "grande_expediente" | "ordem_do_dia" | "tribuna_livre_cidadao";
  tipoItem: "comunicado" | "homenagem" | "leitura" | "proposicao";
  proposicaoId?: string;
  proposicao?: ProposicaoResumoPautaOut;
  textoDescricao?: string;
  ordem: number;
}

export interface VersaoPautaPublicaOut {
  versao: number;
  tipoVersao: "execucao_final" | "publicacao_inicial" | "republicacao";
  publicadaEm: string;
  justificativa?: string | null;
}

export interface PautaOficialVigenteOut {
  versao: number;
  tipoVersao: "execucao_final" | "publicacao_inicial" | "republicacao";
  publicadaEm: string;
  justificativa?: string | null;
  itens: ItemPautaOficialOut[];
}

export interface PautaOficialOut {
  sessao: SessaoPautaPublicaOut;
  vigente?: PautaOficialVigenteOut | null;
  versoes: VersaoPautaPublicaOut[];
}

export interface InscricaoOut {
  id: string;
  protocolo: string;
  ordem: number;
  nome: string;
  falaComo: "conselho_movimento" | "entidade" | "individual";
  entidade?: string | null;
  tema: string;
  origem: "portal_govbr" | "presencial_secretaria";
  estado: "ausente" | "desistiu" | "falando" | "falou" | "inscrita";
  chamadaEm?: string | null;
  encerradaEm?: string | null;
  tempoUsadoSegundos?: number | null;
}

export interface ComissaoAudienciaOut {
  id: string;
  nome: string | null;
}

export interface ProposicaoAudienciaOut {
  id: string;
  rotulo: string;
  ementa: string;
}

export interface AudienciaOut {
  sessaoId: string;
  numero: number;
  estado: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
  agendadaPara: string | null;
  modalidade: "hibrida" | "presencial" | "remota";
  comissao: ComissaoAudienciaOut;
  tema: string;
  local?: string | null;
  proposicao?: ProposicaoAudienciaOut | null;
  finalidade: "ldo" | "loa" | "metas_fiscais" | "ppa" | "tematica";
  referencia?: string | null;
  tempoFalaSegundos: number;
  inscricoesAbertas: boolean;
  inscricoes: InscricaoOut[];
}

export interface ResumoAudienciaOut {
  sessaoId: string;
  tema: string;
  comissaoNome: string | null;
  agendadaPara: string | null;
  estado: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
  local?: string | null;
  finalidade: "ldo" | "loa" | "metas_fiscais" | "ppa" | "tematica";
}

export interface AudienciasPublicasOut {
  proximas: ResumoAudienciaOut[];
  realizadas: ResumoAudienciaOut[];
}

export interface FalaCidadaOut {
  nome: string;
  falaComo: "conselho_movimento" | "entidade" | "individual";
  entidade?: string | null;
}

export interface AudienciaPublicaOut {
  sessaoId: string;
  tema: string;
  comissaoNome: string | null;
  agendadaPara: string | null;
  estado: "aberta" | "agendada" | "arquivada" | "encerrada" | "nao_realizada" | "suspensa";
  local?: string | null;
  finalidade: "ldo" | "loa" | "metas_fiscais" | "ppa" | "tematica";
  modalidade: "hibrida" | "presencial" | "remota";
  proposicao?: ProposicaoAudienciaOut | null;
  referencia?: string | null;
  tempoFalaSegundos: number;
  inscricoesAbertas: boolean;
  inscritos: number;
  ataPublicada: boolean;
  falaram: FalaCidadaOut[];
}

export interface InscricaoPortalReciboOut {
  protocolo: string;
  reciboEm: string;
  ordem: number;
}

export interface MinhaInscricaoOut {
  id: string;
  protocolo: string;
  sessaoId: string;
  tema: string;
  comissaoNome: string | null;
  agendadaPara: string | null;
  ordem: number;
  estado: "ausente" | "desistiu" | "falando" | "falou" | "inscrita";
  reciboEm: string;
}

export interface MinhasInscricoesOut {
  inscricoes: MinhaInscricaoOut[];
}
