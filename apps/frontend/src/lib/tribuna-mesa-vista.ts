// View-model PURO da tribuna conduzida pela Mesa (§22.6 eixo F) — sem React, testável isolado. A camada de
// INTENÇÃO da tribuna: a Mesa INSCREVE um orador na fila (por fase) e pode registrar a DESISTÊNCIA de uma
// inscrição. A camada de EXECUÇÃO (iniciar/encerrar fala + cronômetro) é outra frente, fora daqui — este
// painel só monta a fila. IO/CAS em `use-tribuna-mesa.ts`; tipos gerados em contrato-sessoes.gen.ts.
//
// Regra da Mesa: a inscrição é subordinada à FASE (reusa fases-pauta). A fila que o backend devolve
// (`inscritos`) já é a ATIVA (desistência é terminal e sai da lista) — aqui só resolvemos nome/rótulo e
// ordenamos por `ordem`.

import { nomeFase } from "./rotulos-sessao";
import type {
  ComposicaoMembroOut,
  InscritoTribunaOut,
  OradorAtualOut,
} from "./contrato-sessoes.gen";

/** As fases da pauta a que uma inscrição de tribuna pode se subordinar (espelha logic/fases-pauta). */
export const FASES_TRIBUNA: { valor: string; rotulo: string }[] = [
  "expediente",
  "grande_expediente",
  "ordem_do_dia",
  "explicacoes_pessoais",
  "tribuna_livre_cidadao",
].map((valor) => ({ valor, rotulo: nomeFase(valor) }));

export function nomeDoMembro(vereadorId: string, membros: ComposicaoMembroOut[]): string {
  const m = membros.find((x) => x.vereadorId === vereadorId);
  return m?.nomeParlamentar ?? `Vereador(a) ${vereadorId.slice(0, 8)}`;
}

export interface LinhaFila {
  inscricaoId: string;
  vereadorId: string;
  nome: string;
  fase: string;
  faseRotulo: string;
  ordem: number;
  lockVersion: number;
  ehOrador: boolean;
}

/** A fila de inscritos, ordenada por `ordem`, com nome resolvido pela composição e marcação de quem é o
 * orador atual (para a tela destacar quem está na tribuna agora). Puro. */
export function derivarFila(
  inscritos: InscritoTribunaOut[],
  membros: ComposicaoMembroOut[],
  oradorAtual: OradorAtualOut | null,
): LinhaFila[] {
  const oradorInscricaoId = oradorAtual?.inscricaoId ?? null;
  return [...inscritos]
    .sort((a, b) => a.ordem - b.ordem)
    .map((i) => ({
      inscricaoId: i.inscricaoId,
      vereadorId: i.vereadorId,
      nome: nomeDoMembro(i.vereadorId, membros),
      fase: i.fase,
      faseRotulo: nomeFase(i.fase),
      ordem: i.ordem,
      lockVersion: i.lockVersion,
      ehOrador: !!oradorInscricaoId && i.inscricaoId === oradorInscricaoId,
    }));
}

/** Rótulo do orador atual (quem está na tribuna agora), ou null quando ninguém fala. */
export function rotuloOradorAtual(
  oradorAtual: OradorAtualOut | null,
  membros: ComposicaoMembroOut[],
): string | null {
  if (!oradorAtual) return null;
  return nomeDoMembro(oradorAtual.oradorId, membros);
}

/** Os membros que a Mesa pode inscrever agora: todos os da composição que NÃO estão já na fila (evita
 * oferecer uma segunda inscrição do mesmo orador — o backend recusaria; a UI não convida ao erro). */
export function membrosInscriveis(
  membros: ComposicaoMembroOut[],
  inscritos: InscritoTribunaOut[],
): ComposicaoMembroOut[] {
  const jaNaFila = new Set(inscritos.map((i) => i.vereadorId));
  return membros.filter((m) => !jaNaFila.has(m.vereadorId));
}
