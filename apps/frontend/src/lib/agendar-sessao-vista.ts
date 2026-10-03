// View-model PURO do formulário "Agendar sessão" (§22.6) — sem React, testável isolado. Fecha o GAP
// docs/20: POST /sessoes (agendar) só existia via API. Deriva as opções do formulário e a origem do
// `sessao-legislativa-id` (campo obrigatório do corpo) a partir das sessões existentes — não há endpoint de
// listagem de sessões legislativas, mas cada SessaoOut carrega a sua, e as sessões de um período
// compartilham o mesmo id. IO em `use-agendar-sessao.ts`.

import type { SessaoOut } from "./contrato-sessoes.gen";
import { FINALIDADES, QUADRIMESTRES, REFERENCIA_VALIDA, type Finalidade } from "./contrato-audiencia";
import { LIMITE_TEMA, tempoFalaEmSegundos } from "./audiencia-vista";

export const TIPOS_SESSAO: { valor: string; rotulo: string }[] = [
  { valor: "ordinaria", rotulo: "Ordinária" },
  { valor: "extraordinaria", rotulo: "Extraordinária" },
  { valor: "solene", rotulo: "Solene" },
  { valor: "secreta", rotulo: "Secreta" },
  { valor: "especial", rotulo: "Especial" },
  // ADR-0021 A1: não delibera, não exige quórum, aceita inscrição do cidadão; pede o bloco da audiência abaixo.
  { valor: "audiencia_publica", rotulo: "Audiência pública" },
];

export const TIPO_AUDIENCIA = "audiencia_publica";

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

// ---- o bloco da AUDIÊNCIA PÚBLICA (ADR-0021 A1): obrigatório se e só se o tipo é `audiencia_publica` ----

export interface FormAudiencia {
  comissaoId: string;
  tema: string;
  local: string;
  proposicaoId: string; // "" = sem matéria relacionada
  finalidade: Finalidade | "";
  /** Só em metas fiscais: o ano e o quadrimestre (`AAAA-Q1|Q2|Q3`), montados de dois campos. */
  referenciaAno: string;
  referenciaQuadrimestre: string; // "Q1" | "Q2" | "Q3" | ""
  /** Minutos; "" = o padrão do servidor (5 min). */
  tempoFalaMinutos: string;
}

export const FORM_AUDIENCIA_VAZIO: FormAudiencia = {
  comissaoId: "",
  tema: "",
  local: "",
  proposicaoId: "",
  finalidade: "tematica",
  referenciaAno: "",
  referenciaQuadrimestre: "",
  tempoFalaMinutos: "5",
};

/** O objeto `audiencia` do POST /sessoes, em kebab. Opcionais vazios ficam fora (o servidor usa o padrão). */
export type CorpoAudiencia = Record<string, string | number>;

export type ValidacaoAudiencia = { ok: true; corpo: CorpoAudiencia } | { ok: false; erro: string };

export function validarAudiencia(f: FormAudiencia): ValidacaoAudiencia {
  if (!f.comissaoId) return { ok: false, erro: "Escolha a comissão que promove a audiência." };
  const tema = f.tema.trim();
  if (!tema) return { ok: false, erro: "Escreva o tema da audiência." };
  if (tema.length > LIMITE_TEMA) return { ok: false, erro: `O tema passou de ${LIMITE_TEMA} caracteres.` };
  if (!FINALIDADES.some((x) => x.valor === f.finalidade)) return { ok: false, erro: "Escolha a finalidade da audiência." };
  const corpo: CorpoAudiencia = { "comissao-id": f.comissaoId, tema, finalidade: f.finalidade };
  if (f.finalidade === "metas_fiscais") {
    const ref = `${f.referenciaAno.trim()}-${f.referenciaQuadrimestre}`;
    if (!REFERENCIA_VALIDA.test(ref) || !QUADRIMESTRES.some((q) => q.valor === f.referenciaQuadrimestre)) {
      return { ok: false, erro: "Na audiência de metas fiscais, informe o ano (quatro dígitos) e o quadrimestre de referência." };
    }
    corpo.referencia = ref;
  }
  if (f.local.trim()) corpo.local = f.local.trim();
  if (f.proposicaoId) corpo["proposicao-id"] = f.proposicaoId;
  if (f.tempoFalaMinutos.trim()) {
    const t = tempoFalaEmSegundos(f.tempoFalaMinutos);
    if (!t.ok) return { ok: false, erro: t.mensagem };
    corpo["tempo-fala-segundos"] = t.segundos;
  }
  return { ok: true, corpo };
}
