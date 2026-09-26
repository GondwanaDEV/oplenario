// Lógica pura do requerimento COLETIVO (fatia 2c, feature 3.17 — desenho autoria-apoiamento.html). Coautoria é
// ato voluntário: cada coautor confirma com a própria assinatura, e quem não confirmou quando o autor protocola
// NÃO CONSTA. Aqui só o que a tela decide: como chamar cada estado do convite, o resumo de quantos confirmaram,
// quem vai ficar de fora se o autor protocolar agora, e o filtro da busca de colegas.

import type { ColegaOut, SubscricaoOut } from "./contrato-legislativo.gen";

export type EstadoSubscricao = SubscricaoOut["estado"];

const ROTULOS: Record<EstadoSubscricao, { texto: string; tom: "conf" | "pend" | "fora" }> = {
  pendente: { texto: "Aguarda confirmação", tom: "pend" },
  confirmada: { texto: "Subscrição confirmada", tom: "conf" },
  recusada: { texto: "Recusou", tom: "fora" },
  nao_consta: { texto: "Não consta", tom: "fora" },
};

export function rotuloSubscricao(estado: EstadoSubscricao): { texto: string; tom: "conf" | "pend" | "fora" } {
  return ROTULOS[estado] ?? { texto: estado, tom: "fora" };
}

/** "1 de 2 coautores confirmaram" — a frase-resumo da proposta. */
export function resumoSubscricoes(subs: readonly Pick<SubscricaoOut, "estado">[]): string {
  const total = subs.length;
  const conf = subs.filter((s) => s.estado === "confirmada").length;
  if (total === 0) return "Sem coautores convidados.";
  if (conf === 0) return total === 1 ? "O coautor ainda não confirmou" : `Nenhum dos ${total} coautores confirmou ainda`;
  const plural = total === 1 ? "coautor" : "coautores";
  return conf === 1 ? `1 de ${total} ${plural} confirmou` : `${conf} de ${total} ${plural} confirmaram`;
}

/** Quem ainda não respondeu — e por isso NÃO CONSTARÁ se o autor protocolar agora. */
export function pendentesAoProtocolar(subs: readonly SubscricaoOut[]): string[] {
  return subs.filter((s) => s.estado === "pendente").map((s) => s.vereadorNome);
}

/** Busca de colegas por nome ou partido, sem acento e sem caixa. Busca vazia = todos. */
export function filtrarColegas(colegas: readonly ColegaOut[], busca: string): ColegaOut[] {
  const norm = (t: string) =>
    t
      .normalize("NFD")
      .replace(/[̀-ͯ]/g, "")
      .toLowerCase()
      .trim();
  const q = norm(busca);
  if (!q) return [...colegas];
  return colegas.filter((c) => norm(`${c.nome} ${c.partido ?? ""}`).includes(q));
}

/** Iniciais para o avatar (duas letras: primeiro e último nome). */
export function iniciais(nome: string): string {
  const partes = nome.trim().split(/\s+/).filter(Boolean);
  if (partes.length === 0) return "?";
  const a = partes[0][0] ?? "";
  const b = partes.length > 1 ? partes[partes.length - 1][0] : "";
  return (a + b).toUpperCase();
}
