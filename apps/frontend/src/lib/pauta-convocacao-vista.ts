// View-model puro da rota pauta-convocacao (Onda C Slice C2 — leitura da pauta + convocação derivada de
// uma sessão agendada; escopo READ PURO, ver
// docs/superpowers/specs/2026-07-11-onda-c-slice2-pauta-convocacao-design.md). Nenhuma função aqui faz
// rede — tudo determinístico sobre os dados já buscados pelos hooks (use-sli-sessoes.ts,
// use-sessao-pauta.ts, use-proposicoes.ts).

import type { SliSessaoOut } from "./use-mesa";
import type { ProposicaoResumoOut } from "./contrato-legislativo.gen";
import { formatarData, formatarDiaSemana, formatarHora } from "./formatar-data";
import { formatarNumeroProposicao } from "./proposicoes-vista";

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

// ---------- agrupamento da pauta por fase (os 5 valores fechados de logic/fases-pauta no backend;
// "Outras fases" é o catch-all honesto — tudo que não é Expediente/Ordem do Dia cai aqui, nunca descarta
// item em silêncio, mesmo princípio da coluna "Outros" de tramitacao-board-vista.ts) ----------

export interface GrupoPauta {
  chave: string;
  titulo: string;
  itens: PautaItemOut[];
}

function porOrdem(itens: PautaItemOut[]): PautaItemOut[] {
  return itens.slice().sort((a, b) => a.ordem - b.ordem);
}

export function agruparPautaPorFase(pauta: PautaOut | null): GrupoPauta[] {
  const itens = pauta?.itens ?? [];
  const expediente = itens.filter((i) => i.fase === "expediente");
  const ordemDoDia = itens.filter((i) => i.fase === "ordem_do_dia");
  const outras = itens.filter((i) => i.fase !== "expediente" && i.fase !== "ordem_do_dia");
  const grupos: GrupoPauta[] = [
    { chave: "expediente", titulo: "Expediente", itens: porOrdem(expediente) },
    { chave: "ordem-do-dia", titulo: "Ordem do Dia", itens: porOrdem(ordemDoDia) },
  ];
  if (outras.length > 0) {
    grupos.push({ chave: "outras", titulo: "Outras fases", itens: porOrdem(outras) });
  }
  return grupos;
}

// ---------- título de exibição de um item de pauta (tipo "proposicao" resolve via lookup na lista de
// proposições já buscada pro rail — mesma rede, sem 2º round-trip; os demais tipos usam texto_descricao
// direto) ----------

export interface TituloItemPauta {
  numero?: string;
  rotulo: string;
  indisponivel: boolean;
}

export function resolverTituloItem(item: PautaItemOut, proposicoesPorId: Map<string, ProposicaoResumoOut>): TituloItemPauta {
  if (item.tipoItem === "proposicao") {
    const prop = item.proposicaoId ? proposicoesPorId.get(item.proposicaoId) : undefined;
    if (prop) {
      return {
        numero: formatarNumeroProposicao(prop.tipo, prop.sequencial, prop.ano),
        rotulo: prop.ementa,
        indisponivel: false,
      };
    }
    return { rotulo: "Matéria fora da página carregada de proposições", indisponivel: true };
  }
  return { rotulo: item.textoDescricao ?? "—", indisponivel: false };
}

export function indexarProposicoesPorId(proposicoes: ProposicaoResumoOut[]): Map<string, ProposicaoResumoOut> {
  return new Map(proposicoes.map((p) => [p.id, p]));
}

// ---------- rail "Prontas, fora da pauta" — proposições do tenant em estado aguardando pauta que ainda não
// estão em nenhum item ATIVO da pauta atual (diff client-side, sem query nova). `truncado` avisa quando a
// página de /proposicoes buscada (teto 100, o máximo do backend) não cobre o total do tenant — nunca finge
// cobertura completa em silêncio. ----------

const ESTADOS_PRONTAS_PARA_PAUTA = new Set(["em_pauta", "aguardando_pauta"]);

export interface ProntasForaDaPauta {
  itens: ProposicaoResumoOut[];
  truncado: boolean;
}

export function derivarProntasForaDaPauta(
  proposicoes: ProposicaoResumoOut[],
  totalProposicoes: number,
  pauta: PautaOut | null,
): ProntasForaDaPauta {
  const idsNaPauta = new Set(
    (pauta?.itens ?? [])
      .filter((i) => i.tipoItem === "proposicao" && i.proposicaoId)
      .map((i) => i.proposicaoId as string),
  );
  const itens = proposicoes.filter((p) => ESTADOS_PRONTAS_PARA_PAUTA.has(p.estado) && !idsNaPauta.has(p.id));
  return { itens, truncado: totalProposicoes > proposicoes.length };
}

// ---------- convocação — leitura derivada (decisão assumida 3: não é entidade persistida). Contagens por
// grupo + nota de antecedência regimental ESTÁTICA (`[Regimento]`, conteúdo real = [GAP] regulatório,
// mesma disciplina do resto do projeto — nunca crava prazo sem fonte). ----------

export interface Convocacao {
  tituloEdital: string;
  data: string;
  diaSemana: string;
  hora: string;
  contagemExpediente: number;
  contagemOrdemDoDia: number;
}

export function derivarConvocacao(sessao: SessaoOut, grupos: GrupoPauta[]): Convocacao | null {
  if (!sessao.agendadaPara) return null;
  const expediente = grupos.find((g) => g.chave === "expediente")?.itens.length ?? 0;
  const ordemDoDia = grupos.find((g) => g.chave === "ordem-do-dia")?.itens.length ?? 0;
  return {
    tituloEdital: `Edital de convocação — ${formatarTituloSessao(sessao)}`,
    data: formatarData(sessao.agendadaPara),
    diaSemana: formatarDiaSemana(sessao.agendadaPara),
    hora: formatarHora(sessao.agendadaPara),
    contagemExpediente: expediente,
    contagemOrdemDoDia: ordemDoDia,
  };
}
