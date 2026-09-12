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
