import { defineConfig } from "@playwright/test";

// Harness isolado: este projeto vive por conta propria em e2e/ (raiz do repo) com seu
// proprio package.json (so @playwright/test), rodado dentro do container oficial do
// Playwright. Nao depende dos node_modules do frontend Next.js nem muta o mount vivo.
export default defineConfig({
  testDir: ".",
  // Task 2: NÃO semeia (o container do Playwright não tem CLI do docker) — só lê/valida o artefato
  // que `semear.sh` (host) já deixou em `.artifacts/demo-ids.edn` e normaliza p/ `.artifacts/ente.json`.
  globalSetup: "./global-setup.ts",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  // Task 2 (achado real): com o default de workers (paralelo entre arquivos de spec), o
  // `smoke.spec.ts` (rota "/") e o `portal-cidadao.spec.ts` (rota "/portal/casa/[ente]", nova) compilam
  // a FRIO ao mesmo tempo sob o `next dev`/turbopack — competem por CPU e o segundo estoura os 30s
  // de timeout. `workers: 1` serializa TODOS os arquivos (não só os testes dentro de um arquivo, que
  // já era o efeito de `fullyParallel: false`) — cada rota compila sem concorrência.
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "on-first-retry",
  },
});
