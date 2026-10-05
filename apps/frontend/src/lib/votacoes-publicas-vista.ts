// Vocabulário do portal de VOTAÇÕES em palavras de quem lê (cidadão, servidor): o backend transporta chaves
// (`maioria_qualificada_2_3`, `parecer`, `abstencao`) e a tela nunca as mostra cruas. Sem percentual nem ranking de
// vereador — é o que a Casa decidiu neste projeto: a página diz o que foi votado, o resultado e quem votou como.

import type { PlacarOut, VotacaoPublicaOut } from "@/lib/contrato-portal.gen";

const QUORUM: Record<string, string> = {
  maioria_simples: "maioria simples",
  maioria_absoluta: "maioria absoluta",
  maioria_qualificada_2_3: "dois terços dos membros",
  maioria_qualificada_3_5: "três quintos dos membros",
};

export function nomeQuorum(chave: string): string {
  return QUORUM[chave] ?? chave;
}

const OBJETO: Record<string, string> = {
  proposicao: "Matéria",
  emenda: "Emenda",
  parecer: "Parecer de comissão",
  requerimento: "Requerimento",
  redacao_final: "Redação final",
};

export function nomeObjeto(tipo: string): string {
  return OBJETO[tipo] ?? tipo;
}

const MODALIDADE: Record<VotacaoPublicaOut["modalidade"], string> = {
  nominal: "votação nominal",
  secreta: "votação secreta",
  simbolica: "votação simbólica",
};

export function nomeModalidade(m: VotacaoPublicaOut["modalidade"]): string {
  return MODALIDADE[m] ?? m;
}

// A emenda à Lei Orgânica vota em dois turnos (CF art. 29): a votação de turno chega com `turno`, e "Aprovada" sem o
// turno diria que a matéria foi aprovada quando só passou o 1º. Votação que não é turno chega sem o campo.
function noTurno(turno: number | null | undefined): string {
  return typeof turno === "number" && turno > 0 ? ` em ${turno}º turno` : "";
}

export function nomeResultado(r: VotacaoPublicaOut["resultado"], turno?: number | null): string {
  return (r === "aprovada" ? "Aprovada" : "Rejeitada") + noTurno(turno);
}

/** O ato do plenário no meio da frase da ficha pública: "a matéria foi aprovada em 1º turno", "a redação final foi
 * rejeitada". */
export function atoDaVotacao(v: Pick<VotacaoPublicaOut, "objetoTipo" | "resultado" | "turno">): string {
  const objeto = v.objetoTipo === "redacao_final" ? "a redação final foi" : "a matéria foi";
  return `${objeto} ${v.resultado === "aprovada" ? "aprovada" : "rejeitada"}${noTurno(v.turno)}`;
}

export function nomeVoto(v: "sim" | "nao" | "abstencao"): string {
  return v === "sim" ? "A favor" : v === "nao" ? "Contra" : "Abstenção";
}

function vezes(n: number, singular: string, plural: string): string {
  return `${n} ${n === 1 ? singular : plural}`;
}

/** "2 votos a favor, 1 voto contra e 0 abstenções" — o placar por extenso. */
export function placarEmPalavras(p: PlacarOut): string {
  return `${vezes(p.sim, "voto a favor", "votos a favor")}, ${vezes(p.nao, "voto contra", "votos contra")} e ${vezes(
    p.abstencoes,
    "abstenção",
    "abstenções",
  )}`;
}

/** O resultado em uma frase: "Aprovada: 2 votos a favor, …" — na simbólica (sem contagem), só o resultado. */
export function resultadoEmPalavras(
  v: Pick<VotacaoPublicaOut, "resultado" | "placar" | "modalidade" | "turno">,
): string {
  const r = nomeResultado(v.resultado, v.turno);
  if (v.placar) return `${r}: ${placarEmPalavras(v.placar)}`;
  return v.modalidade === "simbolica" ? `${r} por votação simbólica, sem contagem de votos` : r;
}
