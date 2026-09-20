// View-model PURO da fila de moderação (sem React, testável isolado). O backend já entrega os pendentes
// com os denunciados primeiro; aqui a ordem é REAFIRMADA de forma estável (defesa em profundidade — uma
// tela que dependesse só da ordem do servidor mentiria no dia em que a rota mudasse) e derivamos o resumo
// (total + quantos denunciados) que o cabeçalho mostra.

import type { ItemFilaModeracao } from "./use-fila-moderacao";

export interface ResumoModeracao {
  total: number;
  denunciados: number;
}

// Ordenação ESTÁVEL: denunciados primeiro, mantendo a ordem relativa do servidor dentro de cada grupo
// (Array.prototype.sort é estável no ES2019+; o comparador só separa os dois grupos).
export function ordenarFila(itens: ItemFilaModeracao[]): ItemFilaModeracao[] {
  return [...itens].sort((a, b) => Number(b.denunciado) - Number(a.denunciado));
}

export function resumirFila(itens: ItemFilaModeracao[]): ResumoModeracao {
  return { total: itens.length, denunciados: itens.filter((i) => i.denunciado).length };
}
