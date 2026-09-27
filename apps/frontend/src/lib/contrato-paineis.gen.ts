// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.

export interface NotificacaoOut {
  id: string;
  categoria: string;
  assunto: string;
  corpo: string;
  objetoTipo: string;
  objetoId: string;
  criadoEm: string;
  lidaEm: string | null;
}

export interface MinhasNotificacoesOut {
  notificacoes: NotificacaoOut[];
  naoLidas: number;
  notificacoesTotal: number;
}

export interface MarcarLidaOut {
  id: string;
  lidaEm: string;
}

export interface OrcamentoIAOut {
  mensal: string;
  tetoDuro: string;
  moeda: string;
  definidoEm: string;
}

export interface CapacidadeIAOut {
  operacao: string;
  execucoes: number;
  indisponiveis: number;
  custo: string;
  aprovados: number;
  editados: number;
  descartados: number;
  errosReportados: number;
}

export interface DesfechosNotasOut {
  pendentes: number;
  aproveitadas: number;
  descartadas: number;
}

export interface DesfechosPropostasOut {
  aguardando: number;
  confirmadas: number;
  recusadas: number;
  expiradas: number;
}

export interface PainelIAOut {
  mes: string;
  orcamento: OrcamentoIAOut | null;
  consumoDisponivel: boolean;
  estado: "sem_orcamento" | "normal" | "aviso" | "segundo_plano_pausado" | "esgotada" | null;
  gasto: string | null;
  moeda: string | null;
  parcial: boolean;
  execucoes: number;
  porCapacidade: CapacidadeIAOut[];
  notasTecnicas: DesfechosNotasOut;
  propostas: DesfechosPropostasOut;
}
