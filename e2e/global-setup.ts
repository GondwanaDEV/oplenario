import * as fs from "node:fs";
import * as path from "node:path";

// Task 2 — decisão de arquitetura (ver seed-1-brief no repo, "Onde isto se encaixa"): este
// globalSetup NÃO semeia. O globalSetup roda DENTRO do container mcr.microsoft.com/playwright, que
// não tem CLI do docker — não dá pra disparar os 3 containers efêmeros de seed daqui. Quem semeia é
// `semear.sh`, no HOST, ANTES do Playwright subir. Este arquivo só lê e valida o artefato que
// `semear.sh` já deixou em `.artifacts/demo-ids.edn` (escrito por `seed-demo/base` via o mount
// `.artifacts:/demo-scratch`), extrai o `:ente` e o normaliza para `.artifacts/ente.json` — o formato
// que `seed.ts` (e os specs) sabem ler.
const ARTIFACTS_DIR = path.join(__dirname, ".artifacts");
const DEMO_IDS_PATH = path.join(ARTIFACTS_DIR, "demo-ids.edn");
const ENTE_JSON_PATH = path.join(ARTIFACTS_DIR, "ente.json");

export default async function globalSetup(): Promise<void> {
  if (!fs.existsSync(DEMO_IDS_PATH)) {
    throw new Error(
      `Artefato de seed ausente: ${DEMO_IDS_PATH}\n` +
        "Rode ./semear.sh (ou ./rodar.sh, que já o chama) antes dos testes — o globalSetup não semeia sozinho.",
    );
  }

  const edn = fs.readFileSync(DEMO_IDS_PATH, "utf-8");
  // EDN do Clojure: {:ente #uuid "..." :ident #uuid "..." :sessao #uuid "..."}. Sem parser EDN (decisão
  // do brief — dependência desnecessária p/ 1 campo): um regex sobre a chave `:ente` basta e falha alto
  // se não casar, em vez de degradar silenciosamente.
  const match = edn.match(/:ente\s+#uuid\s+"([0-9a-fA-F-]{36})"/);
  if (!match) {
    throw new Error(`Não foi possível extrair ":ente #uuid ..." de ${DEMO_IDS_PATH}. Conteúdo lido: ${edn}`);
  }

  const enteId = match[1];
  fs.writeFileSync(ENTE_JSON_PATH, JSON.stringify({ enteId }, null, 2));

  await aquecerRotas(enteId);
}

const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

/**
 * Aquece as rotas que os specs visitam, EM SÉRIE, antes de qualquer teste rodar.
 *
 * Por quê: o frontend roda em `next dev` (Turbopack), que compila cada rota sob demanda no 1º acesso.
 * Sem aquecimento, dois specs que abrem rotas diferentes ao mesmo tempo disparam duas compilações a
 * frio concorrentes, competem por CPU e estouram o `timeout` de 30s do teste. A defesa anterior era
 * `workers: 1` na config, que serializa a suíte INTEIRA para sempre — cura o sintoma e cobra o preço
 * em toda rodada futura. Aquecer aqui paga o custo da compilação uma vez, fora do relógio dos testes,
 * e devolve o paralelismo.
 *
 * Falha ALTO se o servidor não responder (o problema é a stack estar fora do ar, e o diagnóstico aqui
 * é muito melhor do que um `net::ERR_ABORTED` dentro de um teste). Status HTTP não-2xx NÃO derruba: os
 * specs é que julgam o conteúdo — aqui só interessa que a rota tenha compilado.
 */
async function aquecerRotas(enteId: string): Promise<void> {
  for (const rota of ["/", `/portal/casa/${enteId}`]) {
    const url = `${BASE_URL}${rota}`;
    try {
      // Janela larga de propósito: compilação a frio de uma rota nova pode passar de 20s numa máquina
      // carregada. Este tempo sai do setup, não do orçamento de nenhum teste.
      const resp = await fetch(url, { signal: AbortSignal.timeout(90_000) });
      console.log(`[e2e] rota aquecida: ${rota} (${resp.status})`);
    } catch (erro) {
      throw new Error(
        `Falha ao aquecer ${url} — a stack está de pé? ` +
          `(frontend em :3000, via 'docker compose up -d' em apps/backend)\nCausa: ${String(erro)}`,
      );
    }
  }
}
