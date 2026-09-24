// View-model puro da rota pauta-convocacao (Onda C Slice C2 — leitura da pauta + convocação derivada de
// uma sessão agendada; escopo READ PURO, ver
// docs/superpowers/specs/2026-07-11-onda-c-slice2-pauta-convocacao-design.md). Nenhuma função aqui faz
// rede — tudo determinístico sobre os dados já buscados pelos hooks (use-sli-sessoes.ts,
// use-sessao-pauta.ts, use-proposicoes.ts).

import type { SliSessaoOut } from "./use-mesa";
import type { ProposicaoResumoOut } from "./contrato-legislativo.gen";
import { formatarData, formatarDiaSemana, formatarHora } from "./formatar-data";
import { formatarNumeroProposicao } from "./proposicoes-vista";
import type { PautaItemOut, PautaOut, SessaoOut } from "./use-sessao-pauta";

export type { PautaItemOut, PautaOut, SessaoOut };

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

// ---------- fatia "truncamento-familia" sitio (a): aviso de corte de GET /paineis/sli/sessoes ----------
// O backend ordena o grupo não-encerrado (aberta/suspensa/agendada) por transicionou_em ASC — sob o teto de
// 200, quem cai fora são as sessões AGENDADAS MAIS NOVAS, exatamente as que esta tela existe para convocar.
// `sessoesTotal` é o campo AUTORITATIVO do servidor (regra 4): a comparação é sempre contra ele, nunca uma
// dedução client-side (ex.: comparar duas contagens já buscadas por outro motivo).

export function avisoCorteSessoes(sessoes: SliSessaoOut[], sessoesTotal: number): string | null {
  if (sessoesTotal <= sessoes.length) return null;
  return (
    `A Casa tem ${sessoesTotal} sessões registradas, mas só ${sessoes.length} aparecem aqui. ` +
    "Pode haver sessões agendadas mais recentes fora desta lista — confira antes de convocar."
  );
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

// ---------- agrupamento da pauta por fase (os 5 valores fechados de logic/fases-pauta no backend, na ordem
// do rito). Expediente e Ordem do Dia sempre aparecem (mesmo vazios: é onde a montagem começa); as demais
// fases ganham grupo próprio quando têm item — cada grupo é também o escopo do "subir/descer" (docs/23), então
// misturar fases num balaio faria a seta trocar um item de Explicações Pessoais com um da Tribuna Livre.
// "Outras fases" segue como catch-all honesto para fase FORA do enum — nunca descarta item em silêncio, mesmo
// princípio da coluna "Outros" de tramitacao-board-vista.ts. ----------

export interface GrupoPauta {
  chave: string;
  /** A fase do backend quando o grupo é de uma fase conhecida (é a fase default ao incluir nele). */
  fase: string | null;
  titulo: string;
  itens: PautaItemOut[];
}

/** As fases do rito, na ordem da sessão (espelha `logic/fases-pauta`). */
export const FASES_DO_RITO: { fase: string; chave: string; titulo: string; sempre: boolean }[] = [
  { fase: "expediente", chave: "expediente", titulo: "Expediente", sempre: true },
  { fase: "grande_expediente", chave: "grande-expediente", titulo: "Grande Expediente", sempre: false },
  { fase: "ordem_do_dia", chave: "ordem-do-dia", titulo: "Ordem do Dia", sempre: true },
  { fase: "explicacoes_pessoais", chave: "explicacoes-pessoais", titulo: "Explicações Pessoais", sempre: false },
  { fase: "tribuna_livre_cidadao", chave: "tribuna-livre", titulo: "Tribuna Livre", sempre: false },
];

function porOrdem(itens: PautaItemOut[]): PautaItemOut[] {
  return itens.slice().sort((a, b) => a.ordem - b.ordem);
}

export function agruparPautaPorFase(pauta: PautaOut | null): GrupoPauta[] {
  const itens = pauta?.itens ?? [];
  const conhecidas = new Set(FASES_DO_RITO.map((f) => f.fase));
  const grupos: GrupoPauta[] = [];
  for (const f of FASES_DO_RITO) {
    const daFase = itens.filter((i) => i.fase === f.fase);
    if (f.sempre || daFase.length > 0) {
      grupos.push({ chave: f.chave, fase: f.fase, titulo: f.titulo, itens: porOrdem(daFase) });
    }
  }
  const outras = itens.filter((i) => !conhecidas.has(i.fase));
  if (outras.length > 0) {
    grupos.push({ chave: "outras", fase: null, titulo: "Outras fases", itens: porOrdem(outras) });
  }
  return grupos;
}

/** Os vizinhos de um item DENTRO do grupo (o escopo das setas de reordenar). */
export function vizinhosNoGrupo(itens: PautaItemOut[], indice: number): { acima: PautaItemOut | null; abaixo: PautaItemOut | null } {
  return { acima: itens[indice - 1] ?? null, abaixo: itens[indice + 1] ?? null };
}

/** Rótulo dos tipos de item de texto (os não-`proposicao` de `logic/tipos-item-pauta`). */
export const TIPOS_ITEM_TEXTO: { valor: "leitura" | "comunicado" | "homenagem"; rotulo: string }[] = [
  { valor: "leitura", rotulo: "Leitura" },
  { valor: "comunicado", rotulo: "Comunicado" },
  { valor: "homenagem", rotulo: "Homenagem" },
];

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
    // O resumo que o próprio GET da pauta já traz (enriquecimento do Modo TV) vence o índice: cobre a matéria
    // que ficou fora da página de 100 proposições buscada para o rail.
    const prop = item.proposicao ?? (item.proposicaoId ? proposicoesPorId.get(item.proposicaoId) : undefined);
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

const ESTADOS_PRONTAS_PARA_PAUTA = new Set(["aguardando_pauta"]);

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
