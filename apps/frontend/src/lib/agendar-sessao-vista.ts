// View-model PURO do formulário "Agendar sessão" (§22.6) — sem React, testável isolado. Fecha o GAP
// docs/20: POST /sessoes (agendar) só existia via API. Deriva as opções do formulário e a origem do
// `sessao-legislativa-id` (campo obrigatório do corpo) a partir das sessões existentes — não há endpoint de
// listagem de sessões legislativas, mas cada SessaoOut carrega a sua, e as sessões de um período
// compartilham o mesmo id. IO em `use-agendar-sessao.ts`.

import type { SessaoOut } from "./contrato-sessoes.gen";

export const TIPOS_SESSAO: { valor: string; rotulo: string }[] = [
  { valor: "ordinaria", rotulo: "Ordinária" },
  { valor: "extraordinaria", rotulo: "Extraordinária" },
  { valor: "solene", rotulo: "Solene" },
  { valor: "secreta", rotulo: "Secreta" },
  { valor: "especial", rotulo: "Especial" },
];

export const MODALIDADES_SESSAO: { valor: string; rotulo: string }[] = [
  { valor: "presencial", rotulo: "Presencial" },
  { valor: "remota", rotulo: "Remota" },
  { valor: "hibrida", rotulo: "Híbrida" },
];

export interface SessaoLegislativaOpcao {
  id: string;
  sessoesCount: number;
  ultimoNumero: number;
}

/** As sessões legislativas presentes nas sessões existentes (distintas por id), com quantas sessões cada
 * uma tem e o maior número sequencial visto. Ordena pela mais "cheia"/recente primeiro — a primeira é o
 * palpite padrão do formulário (o período corrente). Puro. */
export function sessoesLegislativasDisponiveis(sessoes: SessaoOut[]): SessaoLegislativaOpcao[] {
  const por = new Map<string, SessaoLegislativaOpcao>();
  for (const s of sessoes) {
    const id = s.sessaoLegislativaId;
    if (!id) continue;
    const atual = por.get(id) ?? { id, sessoesCount: 0, ultimoNumero: 0 };
    atual.sessoesCount += 1;
    atual.ultimoNumero = Math.max(atual.ultimoNumero, s.numeroSequencial ?? 0);
    por.set(id, atual);
  }
  return [...por.values()].sort(
    (a, b) => b.sessoesCount - a.sessoesCount || b.ultimoNumero - a.ultimoNumero,
  );
}

export interface FormAgendar {
  sessaoLegislativaId: string;
  tipoSessao: string;
  modalidade: string; // "" = não informar (o backend usa o default)
  agendadaPara: string; // valor de <input datetime-local>, "" = sem data
}

export type ValidacaoAgendar = { ok: true } | { ok: false; erro: string };

export function validarAgendar(form: Pick<FormAgendar, "sessaoLegislativaId" | "tipoSessao">): ValidacaoAgendar {
  if (!form.sessaoLegislativaId) {
    return { ok: false, erro: "Escolha a sessão legislativa (o período) a que esta sessão pertence." };
  }
  if (!form.tipoSessao) {
    return { ok: false, erro: "Escolha o tipo da sessão." };
  }
  if (!TIPOS_SESSAO.some((t) => t.valor === form.tipoSessao)) {
    return { ok: false, erro: "Tipo de sessão inválido." };
  }
  return { ok: true };
}

/** Converte o valor de `<input type="datetime-local">` ("2026-05-21T14:00") em ISO-8601 (instante), ou null
 * quando vazio/ inválido — o `agendada-para` do corpo é opcional, então null = não enviar. */
export function agendadaParaIso(local: string): string | null {
  const t = local.trim();
  if (!t) return null;
  const d = new Date(t);
  if (Number.isNaN(d.getTime())) return null;
  return d.toISOString();
}
