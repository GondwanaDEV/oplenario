import { describe, it, expect } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { resolve, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";

// LINT ESTRUTURAL — todo `var(--t-N)` usado no FE tem de existir na escala de tokens.css.
//
// O defeito: 52 declaracoes em 11 arquivos usavam `--t-14`, `--t-15`, `--t-22`, `--t-24` e `--t-32`,
// degraus que a escala (11 · 12 · 13 · 16 · 21 · 28 · 40 · 56, espelho de
// produto/design-system/o-plenario/sistema/tokens.css) nunca declarou. Propriedade custom indefinida
// torna a declaracao invalida no computed-value e o texto cai no tamanho HERDADO — sem erro de build,
// sem aviso no console, sem teste vermelho. O conserto mapeou cada uso para o degrau vizinho; este gate
// impede o proximo degrau inventado de passar calado.
describe("lint: todo --t-N usado existe na escala tipografica", () => {
  const src = resolve(dirname(fileURLToPath(import.meta.url)), "..");

  const varrer = (dir: string): string[] =>
    readdirSync(dir).flatMap((nome) => {
      const p = resolve(dir, nome);
      if (statSync(p).isDirectory()) return varrer(p);
      return /\.(css|tsx?)$/.test(nome) && !nome.endsWith(".test.ts") ? [p] : [];
    });

  it("nenhum var(--t-N) aponta para degrau inexistente", () => {
    const tokens = readFileSync(resolve(src, "app/tokens.css"), "utf8");
    const definidos = new Set([...tokens.matchAll(/^\s*--t-(\d+)\s*:/gm)].map((m) => m[1]));
    // Guarda contra o gate virar vacuo: se tokens.css mudar de lugar ou de forma, o Set fica vazio e
    // TODO uso vira "infrator" — falha alta, nunca verde silencioso.
    expect(definidos.size, "o lint nao achou a escala em app/tokens.css").toBeGreaterThanOrEqual(8);

    const arquivos = varrer(src);
    let usos = 0;
    const infratores: string[] = [];
    for (const f of arquivos) {
      for (const m of readFileSync(f, "utf8").matchAll(/var\(--t-(\d+)\)/g)) {
        usos++;
        if (!definidos.has(m[1])) infratores.push(`${relative(src, f)}: --t-${m[1]}`);
      }
    }
    expect(usos, "o lint nao encontrou usos de --t-N — o gate virou vacuo").toBeGreaterThan(500);
    expect(infratores, `degraus fora da escala (texto cai no tamanho herdado):\n${infratores.join("\n")}`).toEqual([]);
  });
});
