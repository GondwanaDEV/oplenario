"use client";

// O relator da comissão pede o PARECER JURÍDICO da matéria que relata (ADR-0019, Eixo 2 — a UI da fatia 1 que faltava):
// POST /api/meu/pareceres/:id/pedido-juridico {assunto?}. O backend confere a posse (só o relator deste parecer; outro
// caso é 404). O pedido não move a matéria e o parecer jurídico é opinativo — a tela diz isso.

import type { PedidoJuridicoOut, PareceresJuridicosDaMateriaOut } from "./contrato-juridico.gen";
import { numeroDoParecer, rotuloConclusao } from "./juridico-vista";
import { pedirJuridico, type Resultado } from "./use-juridico";
import { formatarData } from "./formatar-data";

export const RECIBO_PEDIDO_DO_RELATOR = "Pedido registrado — o jurídico da Casa verá na fila.";

export const CONFIRMACAO_PEDIDO_DO_RELATOR =
  "O jurídico da Casa recebe o pedido na fila dele. O pedido não move a matéria, e o parecer jurídico é opinativo.";

/** A frase da tela para o pedido do relator que não foi ok. O 400 traz o que corrigir (o tamanho do assunto). */
export function mensagemDeErroPedidoDoRelator(status: number, erroDoServidor?: string): string {
  if (status === 0) return "Falha de rede. Nada foi pedido; tente de novo em instantes.";
  if (status === 401) return "Sua sessão expirou. Entre de novo.";
  if (status === 403) return "Só o vereador relator pede o parecer jurídico por aqui.";
  if (status === 404) return "Este parecer não está com você como relator, ou não é sobre uma proposição.";
  if (status === 400) return erroDoServidor ?? "O assunto deve ter de 5 a 300 caracteres.";
  return "Não foi possível registrar o pedido agora. Tente de novo em instantes.";
}

const pedidoValido = (d: unknown) =>
  typeof (d as PedidoJuridicoOut)?.id === "string" && typeof (d as PedidoJuridicoOut)?.estado === "string";

export async function pedirParecerJuridicoDoRelator(
  token: string | null,
  parecerId: string,
  assunto: string,
): Promise<Resultado<PedidoJuridicoOut>> {
  const a = assunto.trim();
  const r = await pedirJuridico<PedidoJuridicoOut>(
    token,
    `/api/meu/pareceres/${encodeURIComponent(parecerId)}/pedido-juridico`,
    "pedir",
    { method: "POST", corpo: a ? { assunto: a } : {} },
    pedidoValido,
  );
  if (r.ok) return r;
  // o 400 já vem com a frase do servidor; os demais ganham a frase do relator (a genérica fala da secretaria)
  return r.status === 400 ? r : { ...r, mensagem: mensagemDeErroPedidoDoRelator(r.status) };
}

/** Uma linha por situação do parecer jurídico da matéria, para o relator ver antes de pedir de novo: os pedidos em aberto
 *  e o parecer assinado VIGENTE (o substituído fica na ficha, não aqui). Vazio = nada pedido nem assinado. */
export function linhasDaSituacaoJuridica(d: PareceresJuridicosDaMateriaOut): string[] {
  const linhas = d.pedidosAbertos.map((p) => `Pedido em aberto desde ${formatarData(p.criadoEm)}: ${p.assunto}`);
  for (const p of d.pareceres.filter((x) => !x.substituido)) {
    const n = numeroDoParecer(p) ?? "Parecer jurídico";
    const quem = p.assinatura ? `, assinado por ${p.assinatura.nome}` : "";
    linhas.push(`${n} (${rotuloConclusao(p.conclusao).toLowerCase()})${quem}`);
  }
  return linhas;
}
