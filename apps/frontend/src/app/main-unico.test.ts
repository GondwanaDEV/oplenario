import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

// Nos grupos cujo layout já abre o `<main>`, a página que abre outro deixa dois marcos "principal" aninhados, e o
// leitor de tela anuncia a região duas vezes (era o caso do /votar e dos estados de carregar/erro das outras telas
// do vereador). A página usa `<div>`; o `<main>` é do layout.
const GRUPOS_COM_MAIN_NO_LAYOUT = ["(vereador)", "(cidadao)", "(operacao)"];

function arquivos(dir: string): string[] {
  return readdirSync(dir).flatMap((nome) => {
    const caminho = join(dir, nome);
    if (statSync(caminho).isDirectory()) return arquivos(caminho);
    return /\.tsx$/.test(nome) && !/\.test\.tsx$/.test(nome) ? [caminho] : [];
  });
}

describe("um só <main> por página", () => {
  for (const grupo of GRUPOS_COM_MAIN_NO_LAYOUT) {
    const raiz = join(__dirname, grupo);

    it(`${grupo}: o layout abre o <main>`, () => {
      expect(readFileSync(join(raiz, "layout.tsx"), "utf8")).toMatch(/<main[\s>]/);
    });

    it(`${grupo}: nenhuma página abre outro <main>`, () => {
      const comMain = arquivos(raiz)
        .filter((f) => !f.endsWith(join(raiz, "layout.tsx")))
        .filter((f) => /<main[\s>]/.test(readFileSync(f, "utf8")))
        .map((f) => f.slice(raiz.length + 1));
      expect(comMain).toEqual([]);
    });
  }
});
