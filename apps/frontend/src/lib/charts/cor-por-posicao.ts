// Cor de cada fatia de um gráfico cuja categoria é TEXTO LIVRE (o estado de uma proposição é definido por
// template, por Casa — invariante 4). Nenhum mapa nome->cor em código cobre o vocabulário de um tenant real:
// as 5 chaves fixas do gráfico "Carga por estágio" casavam com 2 estados reais e o resto caía num cinza.
//
// A cor vem da POSIÇÃO da fatia, numa rampa de 3 âncoras (--seg-1 -> --seg-2 -> --seg-3, definidas por tema em
// mesa.css: cobalto -> jade -> telha no claro; os mesmos matizes clareados no escuro). `color-mix` interpola em
// sRGB entre as duas âncoras vizinhas, então qualquer número de estágios ganha cores distintas e estáveis.

/** `color-mix(...)` da fatia `indice` (0-based) de `total`. Uma fatia só usa a primeira âncora. */
export function corPorPosicao(indice: number, total: number): string {
  if (total <= 1) return "var(--seg-1)";
  const t = Math.min(1, Math.max(0, indice / (total - 1)));
  const [de, para, p] = t <= 0.5 ? ["--seg-1", "--seg-2", t * 2] : ["--seg-2", "--seg-3", (t - 0.5) * 2];
  return `color-mix(in srgb, var(${para}) ${Math.round(p * 100)}%, var(${de}))`;
}
