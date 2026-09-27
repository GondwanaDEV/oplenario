// O compilador de CSS do Next funde `-webkit-backdrop-filter` e `backdrop-filter` numa regra e guarda so' a
// ULTIMA. Com a prefixada por ultimo, o Chrome/Android (que so' le' a padrao) perdia o desfoque da barra do topo e o
// texto da pagina aparecia atraves dela em todas as telas internas. Este teste impede a ordem errada de voltar.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

function cssDe(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? cssDe(p) : p.endsWith(".css") ? [p] : [];
  });
}

describe("desfoque das barras de vidro", () => {
  it("a declaração padrão vem depois da prefixada em toda regra", () => {
    const erradas: string[] = [];
    for (const arquivo of cssDe(join(__dirname))) {
      const semComentarios = readFileSync(arquivo, "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
      for (const regra of semComentarios.split("}")) {
        const prefixada = regra.lastIndexOf("-webkit-backdrop-filter");
        if (prefixada < 0) continue;
        const padrao = regra.replace(/-webkit-backdrop-filter/g, "#".repeat(23)).lastIndexOf("backdrop-filter");
        if (padrao < prefixada) erradas.push(`${arquivo}: ${regra.trim().split("{")[0].trim()}`);
      }
    }
    expect(erradas).toEqual([]);
  });
});
