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
import { numerarNaFase } from "./posicao-na-fase";
import { formatarNumeroProposicao } from "./proposicoes-vista";
import { LEGENDA_SIM, PERGUNTA_VOTACAO, fraseDosPrecisos } from "./contas-vista";
import type { QuorumContas } from "./contrato-contas";

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
  /** A `ordem` gravada (uma só por pauta): serve para ordenar, não para ser lida. */
  ordem: number;
  /** O número que se lê: a posição do item dentro da fase, contando todos os itens ativos dela. */
  posicao: number;
  /** "PL 22/2026" quando a pauta traz o resumo; null sem ele (o seletor cai para fase + posição). */
  sigla: string | null;
}

/** Os itens da pauta que servem de objeto de votação: proposições com `proposicaoId` resolvido. Emenda/
 * parecer/requerimento entram por outra porta (fora desta fatia) — aqui o objeto é a matéria da pauta. */
export function candidatosObjeto(itens: ItemPautaObjeto[]): CandidatoObjeto[] {
  // a posição conta TODOS os itens da fase (o comunicado e a leitura também ocupam lugar), por isso vem antes do filtro
  const posicoes = numerarNaFase(itens);
  return itens
    .map((i, idx) => ({ i, posicao: posicoes[idx] }))
    .filter(({ i }) => i.tipoItem === "proposicao" && typeof i.proposicaoId === "string" && i.proposicaoId)
    .map(({ i, posicao }) => ({
      objetoId: i.proposicaoId as string,
      fase: i.fase,
      ordem: i.ordem,
      posicao,
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

// ---- matéria de contas (ADR-0021 B2) ----
//
// O PDL das contas do Prefeito vota "Rejeitar o parecer prévio do TCE?" (Sim = rejeitar), NOMINAL, com 2/3 dos MEMBROS
// (CF art. 31 §2). A regra é dado no backend (guarda avaliada ao abrir: 422 se o quórum ou a modalidade não forem os
// dela); aqui a Mesa só vê a regra travada e dita em palavras — sem chance de abrir com outra e tomar o 422.

export const QUORUM_DE_CONTAS: QuorumTipo = "maioria_qualificada_2_3";
export const MODALIDADE_DE_CONTAS: ModalidadeVotacao = "nominal";

export interface RegraVotacaoContas {
  quorumTipo: QuorumTipo;
  modalidade: ModalidadeVotacao;
  pergunta: string;
  legendaSim: string;
  precisos: string;
  nota: string;
}

export function regraDaVotacaoDeContas(quorum: QuorumContas | null | undefined): RegraVotacaoContas {
  return {
    quorumTipo: QUORUM_DE_CONTAS,
    modalidade: MODALIDADE_DE_CONTAS,
    pergunta: PERGUNTA_VOTACAO,
    legendaSim: LEGENDA_SIM,
    precisos: fraseDosPrecisos(quorum),
    nota: "Matéria de contas: quórum de 2/3 dos membros e votação nominal, fixados pela CF art. 31 §2. Sem os 2/3, o parecer do TCE prevalece.",
  };
}

// ---- emenda à Lei Orgânica (CF art. 29) ----
//
// A PELOM só é aprovada com 2/3 dos MEMBROS, em dois turnos com interstício de dez dias. A regra é dado no backend
// (`regra_votacao_materia`, chave `emenda_lom`: 422 ao abrir com outro quórum); aqui o painel só trava o quórum quando a
// pauta traz a espécie da matéria. A modalidade fica com a Mesa (a CF não a fixa; é cada LOM). Sem o resumo da matéria
// na pauta, o seletor fica livre e o 422 do backend diz a regra.

export const ESPECIE_EMENDA_LOM = "proposta_emenda_lom";
export const QUORUM_DE_EMENDA_LOM: QuorumTipo = "maioria_qualificada_2_3";

export interface RegraVotacaoPelaEspecie {
  quorumTipo: QuorumTipo;
  nota: string;
}

export function regraDaVotacaoPelaEspecie(especie: string | null | undefined): RegraVotacaoPelaEspecie | null {
  if (especie !== ESPECIE_EMENDA_LOM) return null;
  return {
    quorumTipo: QUORUM_DE_EMENDA_LOM,
    nota: "Emenda à Lei Orgânica: só é aprovada com 2/3 dos membros da Câmara, em dois turnos com pelo menos 10 dias entre eles (CF art. 29).",
  };
}
