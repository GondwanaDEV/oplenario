// Sobe o `next dev` e confere que a tabela de rotas dele está COMPLETA antes de deixar o servidor quieto.
//
// Por quê (medido em 05/10/2026, Next 16.2.9, 60 subidas a frio no CI, 4 ruins): na subida, o `next dev` monta a
// tabela de rotas a partir de uma leitura do diretório `app/`. Sob disputa de CPU essa tabela às vezes fica com a
// leitura incompleta: faltam as páginas do nível mais fundo (`/portal/casa/[ente]/materias/[proposicaoId]`,
// `leis/[normaId]`, `vereadores/[vereadorId]`, `audiencias/[sessao]`). O arquivo está no disco, mas o servidor
// responde 404 nessas rotas ATÉ algum arquivo de `app/` mudar — num CI nada muda, então o 404 dura a rodada inteira.
// Não aparece erro no log.
//
// O que este script faz: lê as páginas que existem no disco, compara com o que o Next escreveu em
// `.next/dev/types/routes.d.ts` (sai da mesma leitura que alimenta o roteador) e, se faltar rota, mexe só na data
// de modificação do arquivo da página que faltou. Isso faz o Next refazer a tabela. Repete até a tabela ficar
// completa e continuar completa por alguns segundos. Nenhum conteúdo de arquivo é alterado.
import { spawn } from "node:child_process";
import { existsSync, readdirSync, readFileSync, utimesSync } from "node:fs";
import { join } from "node:path";

const RAIZ = process.cwd();
const APP = ["src/app", "app"].map((d) => join(RAIZ, d)).find((d) => existsSync(d));
const TABELA = join(RAIZ, ".next", "dev", "types", "routes.d.ts");
const INTERVALO_MS = 2_000;
const LEITURAS_COMPLETAS_SEGUIDAS = 5;
const TENTATIVAS = 60;

const next = spawn(join(RAIZ, "node_modules", ".bin", "next"), ["dev", ...process.argv.slice(2)], { stdio: "inherit" });
for (const sinal of ["SIGINT", "SIGTERM"]) process.on(sinal, () => next.kill(sinal));
next.on("exit", (codigo, sinal) => process.exit(codigo ?? (sinal ? 1 : 0)));

/** Páginas no disco -> [{ rota, arquivo }]. Grupos `(x)` e slots `@x` não entram na URL; pasta `_x` é privada. */
export function paginasNoDisco(dir, segmentos = []) {
  const achadas = [];
  for (const item of readdirSync(dir, { withFileTypes: true })) {
    if (item.isDirectory()) {
      if (item.name.startsWith("_")) continue;
      const some = /^\(.*\)$/.test(item.name) || item.name.startsWith("@");
      achadas.push(...paginasNoDisco(join(dir, item.name), some ? segmentos : [...segmentos, item.name]));
    } else if (/^page\.(tsx|ts|jsx|js)$/.test(item.name)) {
      achadas.push({ rota: `/${segmentos.join("/")}`, arquivo: join(dir, item.name) });
    }
  }
  return achadas;
}

function rotasFaltando() {
  if (!existsSync(TABELA)) return null;
  const tabela = readFileSync(TABELA, "utf8");
  return paginasNoDisco(APP).filter(({ rota }) => !tabela.includes(`"${rota}"`));
}

const esperar = (ms) => new Promise((ok) => setTimeout(ok, ms));

async function conferirTabelaDeRotas() {
  let completas = 0;
  let refez = 0;
  for (let i = 0; i < TENTATIVAS && completas < LEITURAS_COMPLETAS_SEGUIDAS; i++) {
    await esperar(INTERVALO_MS);
    const faltando = rotasFaltando();
    if (faltando === null) continue; // o Next ainda não escreveu a tabela
    if (faltando.length === 0) {
      completas++;
      continue;
    }
    completas = 0;
    refez++;
    console.log(
      `[dev] tabela de rotas do next dev incompleta: faltam ${faltando.length} (ex.: ${faltando[0].rota}). Refazendo a leitura.`,
    );
    const agora = new Date();
    for (const { arquivo } of faltando) utimesSync(arquivo, agora, agora);
  }
  if (completas >= LEITURAS_COMPLETAS_SEGUIDAS) {
    console.log(`[dev] tabela de rotas completa (${paginasNoDisco(APP).length} páginas; leitura refeita ${refez}x).`);
  } else {
    const faltando = rotasFaltando();
    console.error(
      `[dev] ERRO: a tabela de rotas do next dev não ficou completa. ` +
        (faltando === null
          ? `O arquivo ${TABELA} não foi escrito.`
          : `Faltam ${faltando.length}: ${faltando.map((f) => f.rota).join(", ")}. Essas rotas vão responder 404.`),
    );
  }
}

if (APP) conferirTabelaDeRotas().catch((erro) => console.error("[dev] ERRO ao conferir a tabela de rotas:", erro));
