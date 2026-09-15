import { defineConfig } from "@playwright/test";

// Harness isolado: este projeto vive por conta propria em e2e/ (raiz do repo) com seu
// proprio package.json (so @playwright/test), rodado dentro do container oficial do
// Playwright. Nao depende dos node_modules do frontend Next.js nem muta o mount vivo.
export default defineConfig({
  testDir: ".",
  // A suíte `t3/` (Trilha 3 — escritas internas autenticadas) é OPT-IN: exige `t3/preparar.sh` (fixtures
  // + preparar.mjs + t3-ids.json) rodado ANTES, e muta a Casa da demo. Fora do glob default (`rodar.sh` e
  // o job de browser-e2e do CI, que só provam o Portal do Cidadão anônimo). Rodar via `npx playwright
  // test t3/` depois do preparar.sh. Ver docs/20 (Onda T1) para o plano de integrá-la ao CI.
  testIgnore: ["**/t3/**"],
  // Task 2: NÃO semeia (o container do Playwright não tem CLI do docker) — só lê/valida o artefato
  // que `semear.sh` (host) já deixou em `.artifacts/demo-ids.edn` e normaliza p/ `.artifacts/ente.json`.
  globalSetup: "./global-setup.ts",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  // Sem `workers: 1` de propósito. O achado original da Task 2 era real — duas rotas compilando a FRIO
  // ao mesmo tempo sob o `next dev`/Turbopack competem por CPU e estouram os 30s de timeout — mas
  // serializar a suíte inteira curava o sintoma e cobrava o preço em toda rodada futura. A causa é
  // atacada na origem: o `global-setup.ts` aquece as rotas EM SÉRIE antes de qualquer teste, então a
  // compilação já está paga quando os workers sobem.
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "on-first-retry",
  },
});
