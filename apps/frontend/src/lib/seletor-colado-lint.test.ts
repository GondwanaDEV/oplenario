import { describe, it, expect } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { resolve, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";

// LINT ESTRUTURAL — nenhum seletor solto antes de um comentário que abre a regra seguinte.
//
// O defeito: em participacao.css sobrou um `.acomp` na linha de cima do comentário de "Denunciar". O CSS
// ignora o comentário e junta os dois pedaços num seletor só (`.acomp .cmt-denunciar`), que não casa com
// nada: o link "Denunciar" do comentário saiu em produção no tamanho do corpo, sem sublinhado — sem erro de
// build, sem aviso no console, sem teste vermelho. Este gate procura, no trecho antes de cada `{`, texto de
// seletor dos DOIS lados de um comentário. Lista de seletores quebrada com vírgula antes do comentário
// (`.a, /* nota */ .b {`) é legítima e passa.
describe("lint: nenhum seletor colado num comentário", () => {
  const src = resolve(dirname(fileURLToPath(import.meta.url)), "..");

  const varrer = (dir: string): string[] =>
    readdirSync(dir).flatMap((nome) => {
      const p = resolve(dir, nome);
      if (statSync(p).isDirectory()) return varrer(p);
      return nome.endsWith(".css") ? [p] : [];
    });

  // o trecho entre o fim da regra anterior (`}`, `{` ou `;`) e o `{` desta, com os comentários
  const preambulos = (css: string): { texto: string; linha: number }[] => {
    const out: { texto: string; linha: number }[] = [];
    let inicio = 0;
    let i = 0;
    while (i < css.length) {
      if (css.startsWith("/*", i)) {
        const fim = css.indexOf("*/", i + 2);
        i = fim < 0 ? css.length : fim + 2;
        continue;
      }
      const c = css[i];
      if (c === "{") out.push({ texto: css.slice(inicio, i), linha: css.slice(0, i).split("\n").length });
      if (c === "{" || c === "}" || c === ";") inicio = i + 1;
      i++;
    }
    return out;
  };

  const colado = (preambulo: string): boolean => {
    const semInicio = preambulo.replace(/^(\s|\/\*[\s\S]*?\*\/)*/, "");
    const m = /^([\s\S]*?)\/\*[\s\S]*?\*\/([\s\S]*)$/.exec(semInicio);
    if (!m) return false;
    const antes = m[1].trim();
    const depois = m[2].replace(/\/\*[\s\S]*?\*\//g, "").trim();
    return antes !== "" && depois !== "" && !antes.endsWith(",");
  };

  it("o detector pega o caso que foi a produção", () => {
    expect(colado(".acomp /* Denunciar comentário */\n.cmt-denunciar ")).toBe(true);
    expect(colado("\n/* comentário */\n.cmt-denunciar ")).toBe(false);
    expect(colado(".a,\n/* nota */\n.b ")).toBe(false);
  });

  it("nenhum CSS do front tem seletor solto antes de comentário", () => {
    const arquivos = varrer(src);
    let regras = 0;
    const infratores: string[] = [];
    for (const f of arquivos) {
      for (const p of preambulos(readFileSync(f, "utf8"))) {
        regras++;
        if (colado(p.texto)) infratores.push(`${relative(src, f)}:${p.linha}: ${p.texto.trim().replace(/\s+/g, " ").slice(0, 90)}`);
      }
    }
    expect(regras, "o lint nao encontrou regras CSS — o gate virou vacuo").toBeGreaterThan(1000);
    expect(infratores, `seletor colado num comentário (a regra nao casa com nada):\n${infratores.join("\n")}`).toEqual([]);
  });
});
