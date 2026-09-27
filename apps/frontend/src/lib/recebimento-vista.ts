// Lógica pura do RECEBIMENTO ASSINADO da tramitação (fatia 2b — pedido do stakeholder: "toda movimentação do
// documento assinada por quem recebe"). O rito da Casa marca quais estados exigem recebimento; a matéria que
// chega a um deles fica "em carga" até alguém receber e assinar, e nenhum ato de tramitação passa antes disso.
//
// Aqui só se decide o TEXTO: quem recebeu e quando (no histórico) e há quanto tempo a carga espera (no painel
// e na fila). Nome ausente = a pessoa não tem vínculo nesta Casa ou o nome não resolveu — a tela diz
// "Recebida", nunca inventa nome nem mostra o id (defeito #11 do ledger: UUID na tela).

import type { RecebimentoOut, RecebimentoPendenteOut } from "./contrato-legislativo.gen";
import { formatarData, formatarHora } from "./formatar-data";

/** "Recebida por Marina em 26/09/2026, 14:30 · assinada" — ou null quando a movimentação não tem recibo. */
export function textoRecebimento(r: RecebimentoOut | null | undefined): string | null {
  if (!r) return null;
  const quando = `${formatarData(r.recebidoEm)}, ${formatarHora(r.recebidoEm)}`;
  const quem = r.recebidoPorNome?.trim();
  return quem ? `Recebida por ${quem} em ${quando} · assinada` : `Recebida em ${quando} · assinada`;
}

/** Há quanto tempo a carga espera, em palavras curtas: "há 3 dias", "há 2 h", "agora há pouco". */
export function esperaDesde(desdeIso: string, agora: Date = new Date()): string {
  const desde = new Date(desdeIso).getTime();
  if (Number.isNaN(desde)) return "";
  const min = Math.floor((agora.getTime() - desde) / 60000);
  if (min < 60) return "agora há pouco";
  const h = Math.floor(min / 60);
  if (h < 24) return `há ${h} h`;
  const d = Math.floor(h / 24);
  return d === 1 ? "há 1 dia" : `há ${d} dias`;
}

export type VistaCarga = {
  movimentacaoId: string;
  estadoNome: string;
  chegouEm: string;
  espera: string;
  restrito: boolean;
};

export function vistaCarga(p: RecebimentoPendenteOut, agora: Date = new Date()): VistaCarga {
  return {
    movimentacaoId: p.movimentacaoId,
    estadoNome: p.estadoNome,
    chegouEm: `${formatarData(p.desde)}, ${formatarHora(p.desde)}`,
    espera: esperaDesde(p.desde, agora),
    restrito: p.restrito,
  };
}
