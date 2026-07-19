import { defineConfig } from "@playwright/test";

// Harness isolado: este projeto vive por conta propria em apps/frontend/e2e/ com seu
// proprio package.json (so @playwright/test), rodado dentro do container oficial do
// Playwright. Nao depende dos node_modules do frontend Next.js nem muta o mount vivo.
export default defineConfig({
  testDir: ".",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "on-first-retry",
  },
});
