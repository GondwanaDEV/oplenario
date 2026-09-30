// Contrato do PARECER JURÍDICO e do CAMINHO DA COMISSÃO (ADR-0019, fatia 1). ESCRITO À MÃO a partir do contrato de
// API combinado com o backend (as rotas ainda não têm modelo Malli para o codegen): as formas abaixo são as do fio
// já passadas por `camelizarChaves` (kebab -> camel). Quando o backend gerar o contrato, este arquivo é substituído
// pelo gerado, sem mudar quem o importa.

export type EstadoPedido = "pendente" | "atendido" | "cancelado";
export type ConclusaoJuridica = "favoravel" | "contrario" | "com_ressalvas" | "orientacao";
export type QualificacaoJuridica = "efetivo" | "comissionado" | "contratado";
export type OrigemPedido = "secretaria" | "relator";

export interface AssinaturaJuridicaOut {
  nome: string;
  oab: string;
  qualificacao: QualificacaoJuridica | string;
  em: string;
}

/** O parecer do advogado. `numero`/`ano` só existem depois de assinado. Na LISTA da fila, `relatorio` e
 *  `fundamentacao` não vêm (só id/estado/conclusao/numero/ano/substituido). */
export interface ParecerJuridicoOut {
  id: string;
  numero: number | null;
  ano: number | null;
  estado: "rascunho" | "assinado";
  relatorio?: string | null;
  fundamentacao?: string | null;
  conclusao: ConclusaoJuridica | null;
  assinatura: AssinaturaJuridicaOut | null;
  substituiId?: string | null;
  substituido: boolean;
}

export interface MateriaDoPedidoOut {
  id: string;
  ref: string;
  ementa: string;
}

export interface PedidoJuridicoOut {
  id: string;
  proposicao: MateriaDoPedidoOut | null;
  assunto: string;
  prazo: string | null;
  estado: EstadoPedido;
  pedidoPor: string | null;
  emNomeDe: string | null;
  origem: OrigemPedido;
  criadoEm: string;
  parecer: ParecerJuridicoOut | null;
}

export interface PedidosJuridicosOut {
  pedidos: PedidoJuridicoOut[];
}

/** GET /legislativo/proposicoes/:id/pareceres-juridicos — só os ASSINADOS (o mais novo primeiro) e os pedidos abertos. */
export interface ParecerJuridicoDaMateriaOut extends ParecerJuridicoOut {
  pedidoId: string;
}

export interface PedidoAbertoOut {
  id: string;
  assunto: string;
  prazo: string | null;
  criadoEm: string;
}

export interface PareceresJuridicosDaMateriaOut {
  pareceres: ParecerJuridicoDaMateriaOut[];
  pedidosAbertos: PedidoAbertoOut[];
}

/** Portal (anônimo): só depois de a matéria ser deliberada. */
export interface ParecerJuridicoPublicoOut {
  numero: number | null;
  ano: number | null;
  conclusao: ConclusaoJuridica | null;
  relatorio: string;
  fundamentacao: string;
  assinatura: AssinaturaJuridicaOut;
}

export interface PareceresJuridicosPublicosOut {
  pareceres: ParecerJuridicoPublicoOut[];
}

// ---- caminho da comissão (secretaria) ----

export interface ComissaoOut {
  id: string;
  nome: string;
}

export interface ComissoesOut {
  comissoes: ComissaoOut[];
}

export interface DestinoDeComissao {
  comissaoId: string;
  relatorId: string | null;
}

export interface ParecerAbertoOut {
  id: string;
  comissaoId: string;
  comissaoNome: string;
  relatorId: string | null;
  relatorNome: string | null;
  estado: string;
  jaExistia: boolean;
}

export interface ParecerAbertosOut {
  pareceres: ParecerAbertoOut[];
}

export interface RelatorDesignadoOut {
  id: string;
  relatorId: string;
  relatorNome: string;
}
