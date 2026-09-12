// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.

export interface Ente {
  enteId: string;
  municipioIbge: string;
  nomeOficial: string;
  nomeCurto?: string | null;
  brasaoRef?: string | null;
}

export interface Legislatura {
  enteId: string;
  id: string;
  numero: number;
  anoInicio: number;
  anoFim: number;
  vigente: boolean;
}

export interface Vereador {
  enteId: string;
  id: string;
  identidadeId?: string | null;
  nome: string;
  nomeParlamentar?: string | null;
}

export interface Mandato {
  enteId: string;
  id: string;
  vereadorId: string;
  legislaturaId: string;
  partido?: string | null;
  estado: "cassado" | "concluido" | "falecido" | "licenciado" | "renunciado" | "vigente";
  natureza: "suplencia" | "titular";
  vigenciaInicio: string;
  vigenciaFim?: string | null;
  fimEfetivo?: string | null;
}

export interface Comissao {
  enteId: string;
  id: string;
  nome: string;
  tipo: "cpi" | "especial" | "mesa" | "permanente" | "temporaria";
  legislaturaId?: string | null;
  vigenciaInicio: string;
  vigenciaFim?: string | null;
}

export interface ComissaoMembro {
  enteId: string;
  id: string;
  comissaoId: string;
  vereadorId: string;
  vigenciaInicio: string;
  vigenciaFim?: string | null;
}

export interface TramitacaoResumoOut {
  total: number;
  porEstado: Record<string, unknown>[];
}

export interface PendenciasResumoOut {
  abertas: number;
  vencidas: number;
  pendentes: number;
}

export interface SessoesResumoOut {
  emCurso: number;
  naoRealizadas: number;
  porSituacao: Record<string, unknown>[];
}

export interface PresencaResumoOut {
  mediaPercentual: number | null;
  sessoesConsideradas: number;
  membrosDaCasa: number;
}

export interface EsicCumprimentoOut {
  totalEncerrados: number;
  cumpridosNoPrazo: number;
  percentual: number | null;
}

export interface RelatorPendenteOut {
  id: string;
  proposicaoId: string;
  tipo: string | null;
  ano: number | null;
  sequencial: number | null;
  urnLex: string | null;
  ementa: string | null;
  criadoEm: string;
  indisponivel: boolean;
}

export interface RelatoresPendentesOut {
  itens: RelatorPendenteOut[];
  truncado: boolean;
}

export interface CardIndisponivelOut {
  indisponivel: true;
}

export interface MesaOut {
  complianceTce: Record<string, unknown>;
  tramitacao: TramitacaoResumoOut;
  pendencias: PendenciasResumoOut;
  sessoes: SessoesResumoOut;
  presencaResumo: PresencaResumoOut | CardIndisponivelOut;
  esicCumprimento: EsicCumprimentoOut | CardIndisponivelOut;
  relatoresPendentes: RelatoresPendentesOut | CardIndisponivelOut;
  lacunas: string[];
}

export interface ItemBoardOut {
  proposicaoId: string;
  tipo: string;
  ano: number;
  sequencial: number;
  urnLex: string;
  ementa: string;
  autorTipo?: string | null;
  autorTexto?: string | null;
  estado: string;
  transicionouEm: string;
}

export interface TotalPorEstadoOut {
  estado: string;
  total: number;
}

export interface TramitacaoBoardOut {
  itens: ItemBoardOut[];
  totaisPorEstado: TotalPorEstadoOut[];
}
