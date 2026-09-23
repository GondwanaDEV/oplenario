// Gate da ARQUITETURA de tema. Não mede cor — mede a mecânica que faz as cores
// chegarem na tela, e que é invisível a olho nu.
//
// Por que existe: até 22/09/2026 o tema escuro era declarado DUAS vezes (um bloco
// [data-tema="escuro"] e uma cópia manual sob @media). O arquivo pedia paridade à
// mão, e a paridade já tinha furado — `--merge` faltava na cópia, e só não deu
// defeito porque claro e escuro tinham o mesmo valor. Nenhum teste via isso.
//
// A troca para light-dark() eliminou a duplicação, mas trouxe uma precondição
// FRÁGIL E SILENCIOSA: o LightningCSS reescreve light-dark() para
// `var(--lightningcss-light,A) var(--lightningcss-dark,B)` e só emite essas
// auxiliares quando enxerga `color-scheme`. Sem as declarações, todo token de cor
// compila para lixo — e a build PASSA. Este teste é o que impede isso de voltar.

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const bruto = readFileSync(join(__dirname, "tokens.css"), "utf8");
// Sem os comentários: este arquivo DOCUMENTA o bloco @media que foi removido, e um
// teste que lesse a prosa acusaria a própria explicação como se fosse código.
const css = bruto.replace(/\/\*[\s\S]*?\*\//g, "");

describe("tokens.css — a mecânica do tema", () => {
  it("declara os TRÊS color-scheme que o light-dark() exige para compilar", () => {
    // Sem estes, o LightningCSS não emite --lightningcss-light/dark e as cores
    // viram lixo silenciosamente. Verificado contra o CSS compilado em 22/09/2026.
    expect(css).toMatch(/:root\s*\{[^}]*color-scheme:\s*light dark/);
    expect(css).toMatch(/\[data-tema="claro"\][^{]*\{[^}]*color-scheme:\s*light\s*;/);
    expect(css).toMatch(/\[data-tema="escuro"\][^{]*\{[^}]*color-scheme:\s*dark\s*;/);
  });

  it("NÃO reintroduz o bloco @media(prefers-color-scheme) duplicado", () => {
    // Era a fonte do drift. O color-scheme:light dark do :root já cobre o SO.
    expect(css).not.toMatch(/@media[^{]*prefers-color-scheme/);
  });

  it("todo light-dark() tem os dois lados preenchidos", () => {
    // Parser de parênteses BALANCEADOS, não regex: os argumentos contêm rgba(...),
    // e um /light-dark\\(([^)]*)\\)/ para no primeiro ")" do rgba e reporta 4 lados.
    const usos: string[] = [];
    for (let i = css.indexOf("light-dark("); i !== -1; i = css.indexOf("light-dark(", i + 1)) {
      let nivel = 0;
      const ini = i + "light-dark(".length;
      for (let j = ini; j < css.length; j++) {
        if (css[j] === "(") nivel++;
        else if (css[j] === ")") {
          if (nivel === 0) { usos.push(css.slice(ini, j)); break; }
          nivel--;
        }
      }
    }
    expect(usos.length).toBeGreaterThan(20); // não-vacuidade: o arquivo de fato usa

    for (const args of usos) {
      // vírgulas de TOPO (as de dentro do rgba não separam lados)
      const lados: string[] = [];
      let nivel = 0, atual = "";
      for (const c of args) {
        if (c === "(") nivel++;
        else if (c === ")") nivel--;
        if (c === "," && nivel === 0) { lados.push(atual.trim()); atual = ""; }
        else atual += c;
      }
      lados.push(atual.trim());
      expect(lados.length, `dois lados em light-dark(${args})`).toBe(2);
      expect(lados[0].length, `lado claro vazio em light-dark(${args})`).toBeGreaterThan(0);
      expect(lados[1].length, `lado escuro vazio em light-dark(${args})`).toBeGreaterThan(0);
    }
  });

  it("as CINCO cores da paleta chegam à tela — nenhuma fica declarada e órfã", () => {
    // Defeito real, encontrado em 22/09/2026 pelo cliente olhando a tela: `--verde-2`
    // (#C7D9C4) estava na paleta de origem e não era referenciada por NENHUM semântico.
    // Ou seja: o sistema dizia usar cinco cores e pintava com quatro. Nada acusava —
    // a paleta de origem é documentação, e documentação não quebra build.
    //
    // A ligação é por HEX cru, não por var(): os semânticos repetem o valor porque
    // light-dark() precisa de cor literal. Então a busca é pelo hex, fora da declaração
    // que o define.
    const paleta = [...css.matchAll(/(--(?:verde-\d|creme))\s*:\s*(#[0-9A-Fa-f]{6})\s*;/g)]
      .map((m) => ({ nome: m[1], hex: m[2].toUpperCase() }));
    expect(paleta.length, "a paleta de origem tem 5 cores").toBe(5);

    // Só o que está DENTRO de um light-dark() conta: é o que vira token semântico. Um hex
    // solto em outra declaração da própria paleta não é uso.
    const emSemanticos = [...css.matchAll(/light-dark\(([^;]*?)\)\s*;/g)]
      .map((m) => m[1].toUpperCase())
      .join(" | ");

    for (const { nome, hex } of paleta) {
      expect(emSemanticos.includes(hex), `${nome} (${hex}) não alimenta nenhum token semântico`).toBe(
        true,
      );
    }
  });

  it("nenhum token semântico é declarado duas vezes (a duplicação não voltou)", () => {
    // Sem a âncora de início de linha: uma declaração inline (`:root { --texto: #000 }`)
    // é uma duplicata igualmente real, e a versão ancorada deste teste não a via — furo
    // encontrado ao mutar o arquivo de propósito. `var(--x)` não casa: não tem ":".
    const nomes = [...css.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1]);
    const repetidos = nomes.filter((n, i) => nomes.indexOf(n) !== i);
    expect(repetidos).toEqual([]);
  });
});
