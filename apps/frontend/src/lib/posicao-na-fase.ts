// O NÚMERO que a pessoa lê ao lado de um item da pauta: a posição dele DENTRO da fase, não a `ordem` gravada.
//
// A `ordem` do backend é uma só por pauta (max+1 entre TODOS os itens, até os retirados): serve para ordenar e
// para reordenar, não para ser lida — o primeiro item da Ordem do Dia aparecia como "5" porque havia quatro no
// Expediente, e um item retirado deixava um buraco. Aqui a posição é derivada na hora, só dos itens que vieram
// (a pauta traz só os ATIVOS), na ordem atual; nada é gravado e a `ordem` não muda. Pura e total: item sem fase
// numera junto com os demais sem fase.

export interface ItemComFaseEOrdem {
  id: string;
  fase: string;
  ordem: number;
}

/** A posição dentro da fase de CADA item, alinhada por índice com `itens` (`resultado[i]` é a posição de
 * `itens[i]`). Vale a ordem crescente de `ordem`; empate fica na ordem em que os itens chegaram — a pauta já os
 * entrega por `ordem` e criação. Serve a quem tem itens sem `id`. */
export function numerarNaFase(itens: readonly { fase: string; ordem: number }[]): number[] {
  const porOrdem = itens.map((it, i) => ({ it, i })).sort((a, b) => a.it.ordem - b.it.ordem || a.i - b.i);
  const contagem = new Map<string, number>();
  const posicoes = new Array<number>(itens.length);
  for (const { it, i } of porOrdem) {
    const n = (contagem.get(it.fase) ?? 0) + 1;
    contagem.set(it.fase, n);
    posicoes[i] = n;
  }
  return posicoes;
}

/** id do item -> posição (1, 2, 3…) dentro da fase dele. */
export function posicoesNaFase(itens: readonly ItemComFaseEOrdem[]): Map<string, number> {
  const posicoes = numerarNaFase(itens);
  return new Map(itens.map((it, i) => [it.id, posicoes[i]]));
}
