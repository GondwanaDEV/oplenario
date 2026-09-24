// View-model PURO da votação conduzida pela Mesa (§22.6 eixo C) — sem React, testável isolado. Duas ações
// da Mesa que só existiam via API: ABRIR uma votação sobre um objeto da pauta e ENCERRÁ-la. Este módulo
// deriva o que a tela oferece a partir de (estado da sessão + votação aberta, se houver); o IO/CAS mora em
// `use-votacao-mesa.ts`, os tipos de fio em `use-votacao-mesa.ts` (hand-modeled — o codegen ainda não cobre
// as saídas de votação).
//
// Regra da Mesa espelhada do backend: a votação só se conduz com a SESSÃO ABERTA (delibera). Uma votação
// simbólica (aclamação) NÃO apura voto individual — por isso o encerramento dela EXIGE o resultado
// declarado (aprovada/rejeitada); nominal/secreta apuram e não pedem resultado.

import type {
  ModalidadeVotacao,
  ObjetoTipoVotacao,
  QuorumTipo,
  VotacaoAbertaResumo,
} from "./use-votacao-mesa";
import { formatarNumeroProposicao } from "./proposicoes-vista";

export interface OpcaoRotulada<T extends string> {
  valor: T;
  rotulo: string;
  descricao?: string;
}

export const MODALIDADES: OpcaoRotulada<ModalidadeVotacao>[] = [
  { valor: "nominal", rotulo: "Nominal", descricao: "Voto a voto, cada vereador registrado nominalmente." },
  { valor: "simbolica", rotulo: "Simbólica", descricao: "Por aclamação — sem apuração individual; o resultado é declarado." },
  { valor: "secreta", rotulo: "Secreta", descricao: "Sem apuração individual (sigilo) — só a contagem final." },
];

export const QUORUNS: OpcaoRotulada<QuorumTipo>[] = [
  { valor: "maioria_simples", rotulo: "Maioria simples" },
  { valor: "maioria_absoluta", rotulo: "Maioria absoluta" },
  { valor: "maioria_qualificada_2_3", rotulo: "Qualificada (2/3)" },
  { valor: "maioria_qualificada_3_5", rotulo: "Qualificada (3/5)" },
];

const ROTULO_OBJETO: Record<ObjetoTipoVotacao, string> = {
  proposicao: "Proposição",
  emenda: "Emenda",
  parecer: "Parecer",
  requerimento: "Requerimento",
  redacao_final: "Redação final",
};

export function rotuloObjetoTipo(t: ObjetoTipoVotacao | string): string {
  return ROTULO_OBJETO[t as ObjetoTipoVotacao] ?? t;
}

export function rotuloModalidade(m: ModalidadeVotacao | string): string {
  return MODALIDADES.find((x) => x.valor === m)?.rotulo ?? m;
}

export function rotuloQuorum(q: QuorumTipo | string): string {
  return QUORUNS.find((x) => x.valor === q)?.rotulo ?? q;
}

/** A modalidade simbólica não apura voto individual — o encerramento precisa do resultado declarado. */
export function exigeResultadoAoEncerrar(modalidade: ModalidadeVotacao | string): boolean {
  return modalidade === "simbolica";
}

/** Item de pauta reduzido ao que o seletor de objeto precisa (campos já camelizados pelo hook). */
export interface ItemPautaObjeto {
  tipoItem: string;
  proposicaoId?: string | null;
  fase: string;
  ordem: number;
  /** Resumo que a pauta traz do item de proposição (docs/23 Fatia 4a) — dá a sigla ao seletor. */
  proposicao?: { tipo: string; ano: number; sequencial: number } | null;
}

export interface CandidatoObjeto {
  objetoId: string;
  fase: string;
  ordem: number;
  /** "PL 22/2026" quando a pauta traz o resumo; null sem ele (o seletor cai para fase + posição). */
  sigla: string | null;
}

/** Os itens da pauta que servem de objeto de votação: proposições com `proposicaoId` resolvido. Emenda/
 * parecer/requerimento entram por outra porta (fora desta fatia) — aqui o objeto é a matéria da pauta. */
export function candidatosObjeto(itens: ItemPautaObjeto[]): CandidatoObjeto[] {
  return itens
    .filter((i) => i.tipoItem === "proposicao" && typeof i.proposicaoId === "string" && i.proposicaoId)
    .map((i) => ({
      objetoId: i.proposicaoId as string,
      fase: i.fase,
      ordem: i.ordem,
      sigla: i.proposicao ? formatarNumeroProposicao(i.proposicao.tipo, i.proposicao.sequencial, i.proposicao.ano) : null,
    }))
    .sort((a, b) => a.ordem - b.ordem);
}

export type PainelVotacao =
  | { tipo: "indisponivel"; nota: string }
  | { tipo: "abrir" }
  | { tipo: "em-curso"; votacao: VotacaoAbertaResumo; exigeResultado: boolean };

/** Deriva o painel de votação a partir do estado da sessão + votação aberta (se houver). Puro. */
export function derivarPainelVotacao(dados: {
  sessaoEstado: string;
  votacaoAberta: VotacaoAbertaResumo | null;
}): PainelVotacao {
  if (dados.sessaoEstado !== "aberta") {
    return {
      tipo: "indisponivel",
      nota: "A votação só é conduzida com a sessão aberta. Abra a sessão para abrir votações.",
    };
  }
  if (dados.votacaoAberta) {
    return {
      tipo: "em-curso",
      votacao: dados.votacaoAberta,
      exigeResultado: exigeResultadoAoEncerrar(dados.votacaoAberta.modalidade),
    };
  }
  return { tipo: "abrir" };
}
