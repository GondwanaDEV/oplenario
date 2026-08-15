// Geometria do hemiciclo — pura, compartilhada pelo telão (`/sessoes/[id]/plenario`) e pela chamada
// (`/sessoes/[id]/chamada`). Antes vivia DUPLICADA nas duas telas, com a mesma constante mágica dos dois
// lados; o defeito abaixo apareceu nas duas de uma vez, e a correção precisava ser feita duas vezes.
//
// O DEFEITO QUE ESTE MÓDULO EXISTE PARA IMPEDIR (achado na verificação em browser, 15/08/2026):
// a versão anterior distribuía SEMPRE em três fileiras, com as proporções 0,256/0,349/resto calibradas
// no desenho de 43 assentos (o porte de Fortaleza). Para uma Casa pequena isso degenera: com 4 membros
// as fileiras viram [1, 1, 2] — dois assentos empilhados no MESMO eixo vertical (as duas fileiras de 1
// caem no ápice, t=0,5) e dois nos extremos da fileira de fora. Não é um arco; é um desenho quebrado.
// E é exatamente o mesmo tipo de mentira que o comentário do telão dizia ter corrigido ao trocar as 43
// cadeiras fixas pelo denominador real — a mentira só mudou de forma.
//
// A regra: o número de FILEIRAS sai do total, e não o contrário. Uma fileira só se justifica quando
// tem assentos que cheguem para desenhar um arco; abaixo disso, uma fileira só (no raio de fora) é a
// leitura honesta. As proporções de três fileiras são preservadas byte-a-byte para o porte grande —
// com 43 a saída continua sendo 11/15/17, igual à geometria original do desenho.

export type AssentoHemiciclo = { x: number; y: number };

/** Centro do arco no viewBox "0 0 240 130" que as duas telas usam. */
const CX = 120;
const CY = 116;

/** Quantos assentos por fileira, do raio interno para o externo. */
export function distribuirFileiras(total: number): number[] {
  const n = Math.max(1, Math.floor(total));
  // Uma fileira: até 6 assentos um arco único é mais legível do que fileiras de 1 ou 2.
  if (n <= 6) return [n];
  // Duas fileiras: a de dentro fica menor, como num plenário real.
  if (n <= 14) {
    const dentro = Math.max(1, Math.round(n * 0.42));
    return [dentro, n - dentro];
  }
  // Três fileiras: as proporções do desenho original (43 -> 11/15/17).
  const n1 = Math.max(1, Math.round(n * 0.256));
  const n2 = Math.max(1, Math.round(n * 0.349));
  return [n1, n2, Math.max(1, n - n1 - n2)];
}

/** Raios por fileira, casados com a contagem de fileiras. A de fora é sempre 90. */
function raiosPara(qtdFileiras: number): number[] {
  if (qtdFileiras === 1) return [90];
  if (qtdFileiras === 2) return [64, 90];
  return [46, 68, 90];
}

/** Posições dos assentos, na ordem do roster (fileira interna primeiro, esquerda -> direita). */
export function assentosHemiciclo(total: number): AssentoHemiciclo[] {
  const fileiras = distribuirFileiras(total);
  const raios = raiosPara(fileiras.length);
  const assentos: AssentoHemiciclo[] = [];
  fileiras.forEach((n, f) => {
    const r = raios[f];
    for (let k = 0; k < n; k++) {
      // Fileira de 1 assento fica no ápice; senão distribui de π (esquerda) a 0 (direita).
      const t = n === 1 ? 0.5 : k / (n - 1);
      const ang = Math.PI * (1 - t);
      assentos.push({ x: CX + r * Math.cos(ang), y: CY - r * Math.sin(ang) });
    }
  });
  return assentos;
}
