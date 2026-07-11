// View-model puro da rota pauta-convocacao (Onda C Slice C2 — leitura da pauta + convocação derivada de
// uma sessão agendada; escopo READ PURO, ver
// docs/superpowers/specs/2026-07-11-onda-c-slice2-pauta-convocacao-design.md). Nenhuma função aqui faz
// rede — tudo determinístico sobre os dados já buscados pelos hooks (use-sli-sessoes.ts,
// use-sessao-pauta.ts, use-proposicoes.ts).

import type { SliSessaoOut } from "./use-mesa";

// SessaoOut/PautaItemOut/PautaOut: hand-rolled (mesmo racional de SliSessaoOut em use-mesa.ts — a rota
// /sessoes ainda não tem codegen Malli→TS). Definidos aqui e reexportados por use-sessao-pauta.ts (Task 5)
// para não ter 2 declarações divergentes do mesmo shape.
export interface SessaoOut {
  id: string;
  sessaoLegislativaId: string;
  tipoSessao: string;
  numeroSequencial: number;
  estado: string;
  modalidade: string;
  delibera: boolean;
  transmitePublica: boolean;
  geraAtaRegimental: boolean;
  permiteVotoSecreto: boolean;
  permiteModalidadeRemota: boolean;
  agendadaPara: string | null;
  abertaEm: string | null;
  encerradaEm: string | null;
  motivoNaoRealizada: string | null;
}

export interface PautaItemOut {
  id: string;
  fase: string;
  tipoItem: string;
  proposicaoId?: string;
  textoDescricao?: string;
  ordem: number;
}

export interface PautaOut {
  sessaoId: string;
  itens: PautaItemOut[];
}

// ---------- seleção da sessão-alvo (decisão assumida 1: auto-seleciona a mais próxima "agendada"; troca
// manual se houver mais de uma) ----------

export function sessoesAgendadas(sessoes: SliSessaoOut[]): SliSessaoOut[] {
  return sessoes
    .filter((s) => s.situacao === "agendada" && s.agendadaPara)
    .slice()
    .sort((a, b) => (a.agendadaPara as string).localeCompare(b.agendadaPara as string));
}

export function selecionarSessaoAlvo(sessoes: SliSessaoOut[], sessaoIdEscolhida: string | null): SliSessaoOut | null {
  const agendadas = sessoesAgendadas(sessoes);
  if (agendadas.length === 0) return null;
  if (sessaoIdEscolhida) {
    const escolhida = agendadas.find((s) => s.sessaoId === sessaoIdEscolhida);
    if (escolhida) return escolhida;
  }
  return agendadas[0];
}

// ---------- rótulos de tipo de sessão (os 5 valores fechados de logic/tipos-sessao no backend;
// fail-closed — tipo fora do mapa cai no texto cru capitalizado, nunca é escondido) ----------

const TIPO_SESSAO_ROTULO: Record<string, string> = {
  ordinaria: "Ordinária",
  extraordinaria: "Extraordinária",
  solene: "Solene",
  secreta: "Secreta",
  especial: "Especial",
};

export function formatarTipoSessao(tipoSessao: string): string {
  return TIPO_SESSAO_ROTULO[tipoSessao] ?? tipoSessao.charAt(0).toUpperCase() + tipoSessao.slice(1);
}

export function formatarTituloSessao(sessao: SessaoOut): string {
  return `${sessao.numeroSequencial}ª Sessão ${formatarTipoSessao(sessao.tipoSessao)}`;
}
