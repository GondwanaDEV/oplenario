# Harness de browser-e2e (Portal do Cidadão) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Um teste de browser-e2e repetível que prova o pipeline FE→BFF→backend→DB→render do Portal do Cidadão, verde localmente e rodável em CI.

**Architecture:** Playwright em `apps/frontend/e2e/`, config própria (separada do vitest). Um `global-setup` semeia dados reais reusando `seed_demo.clj` (via container efêmero de Clojure) e captura o `ente_id` gerado. Os specs rodam contra a stack docker de pé (frontend :3000 → proxy BFF → backend :8888).

**Tech Stack:** Playwright (`@playwright/test`), Node 20 em container, Clojure via container efêmero, docker compose já existente.

## Global Constraints

- **Mandato Docker:** nunca rodar node/clj direto no host — Playwright e seed rodam em container (memória `oplenario-docker-mandato`).
- **Não tocar a suíte vitest** (`apps/frontend/vitest.config.ts`, unit/jsdom) — o Playwright é surface separada.
- **Seed = próprio código do projeto** (`seed_demo.clj`), zero SQL cru.
- **Frontend em modo dev** (`NEXT_PUBLIC_APP_ENV=dev`), stack já de pé nas portas: frontend :3000, backend :8888, Postgres :5544.
- **Verde-local é o critério #1;** CI é best-effort na mesma fatia.
- Português nos nomes de teste e mensagens, seguindo o resto do FE.
- **NUNCA mutar um mount vivo (guardrail aprendido, 2026-07-19):** os containers efêmeros (Playwright, seed) **não** podem bind-montar `apps/frontend`/`apps/backend` e mutá-los (`rm -rf node_modules`, escritas que o dev server observa). O compose monta `../frontend:/app` para o `next dev`; um `rm -rf node_modules` num container efêmero **derruba o frontend**. Montar só o necessário e manter deps em **volume de container**.

## REVISÃO — Task 1 (Opção A, aprovada 2026-07-19) — JÁ IMPLEMENTADA E VERDE

A Task 1 original (commit `c7bbf8f`) estava **verde-falso** e foi **reshapeada** (commit `1ceea06`). O harness agora é **auto-contido**:

- `apps/frontend/e2e/` é um **projeto Node isolado** com `package.json` próprio contendo **só `@playwright/test@1.49.0`** (sem `next` na árvore → **sem ERESOLVE**; o conflito de peer vinha de instalar o Playwright junto do `next` no mesmo package.json).
- `playwright.config.ts` vive **dentro** de `e2e/` (`testDir: "."`).
- `node_modules` do harness fica num **volume de container** (`oplenario_e2e_nm`); o host nunca é escrito.
- `apps/frontend/package.json`/lock **revertidos ao pristino** (sem `@playwright/test`).
- **Comando canônico** (da raiz do repo), verde de estado limpo (`1 passed`):
  ```bash
  docker run --rm --network host -e PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    -v "$PWD/apps/frontend/e2e:/e2e" -v oplenario_e2e_nm:/e2e/node_modules -w /e2e \
    mcr.microsoft.com/playwright:v1.49.0-noble \
    sh -c "npm ci --ignore-scripts && npx playwright test"
  ```
  (`--ignore-scripts`: o postinstall do Playwright trava neste ambiente e os browsers já vêm na imagem.)

**Tasks 2–4 herdam este comando/design:** a config já está em `e2e/`; specs importam de `./seed` como antes; o seed da Task 2 roda em container efêmero **sem mutar o mount** e grava o `ente_id` em `apps/frontend/e2e/.artifacts/` (gitignored). A Task 4 (CI) usa o mesmo comando canônico. Onde as steps abaixo disserem `-v "$PWD/apps/frontend:/work"` + `npm ci` no package.json do frontend, **substituir** pelo comando canônico acima.

---

### Task 1: Scaffolding do Playwright + smoke verde em container

De-risca a questão "browser-em-container contra a stack de pé" antes de qualquer seed.

**Files:**
- Create: `apps/frontend/e2e/smoke.spec.ts`
- Create: `apps/frontend/playwright.config.ts`
- Modify: `apps/frontend/package.json` (devDependency `@playwright/test`, script `e2e`)
- Create: `apps/frontend/e2e/README.md` (como rodar)

**Interfaces:**
- Produces: config Playwright com `baseURL` = `http://localhost:3000` (override por env `E2E_BASE_URL`); comando `npm run e2e` rodável dentro do container oficial de Playwright.

- [ ] **Step 1: Escrever o smoke spec (falha esperada — sem Playwright ainda)**

```ts
// apps/frontend/e2e/smoke.spec.ts
import { test, expect } from "@playwright/test";

test("o portal responde e o app monta", async ({ page }) => {
  const resp = await page.goto("/");
  expect(resp?.status()).toBeLessThan(400);
  await expect(page).toHaveTitle(/O Plenário/);
});
```

- [ ] **Step 2: Escrever a config**

```ts
// apps/frontend/playwright.config.ts
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 0,
  reporter: [["list"]],
  globalSetup: "./e2e/global-setup.ts", // adicionado na Task 2 (por ora ainda não existe — ver Step 3)
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "on-first-retry",
  },
});
```

- [ ] **Step 3: Remover a linha `globalSetup` por enquanto** (Task 2 a reintroduz — evita erro de arquivo ausente no smoke). Deixe a config sem `globalSetup` nesta task.

- [ ] **Step 4: Adicionar dependência e script**

```bash
cd apps/frontend
# editar package.json: em devDependencies adicionar "@playwright/test": "^1.49.0"
#                      em scripts adicionar "e2e": "playwright test"
```

- [ ] **Step 5: Rodar o smoke em container contra a stack de pé (deve PASSAR)**

Run:
```bash
docker run --rm --network host -v "$PWD/apps/frontend:/work" -w /work \
  mcr.microsoft.com/playwright:v1.49.0-noble \
  sh -c "npm ci && npx playwright test e2e/smoke.spec.ts"
```
Expected: `1 passed`. Se `--network host` não alcançar :3000 (macOS), usar `-e E2E_BASE_URL=http://host.docker.internal:3000` e `--add-host=host.docker.internal:host-gateway`. Documentar o que funcionou no `e2e/README.md`.

- [ ] **Step 6: Commit**

```bash
git add apps/frontend/e2e apps/frontend/playwright.config.ts apps/frontend/package.json apps/frontend/package-lock.json
git commit -m "test(e2e): scaffolding do Playwright + smoke verde em container"
```

---

### Task 2: `global-setup` semeia via `seed_demo.clj` e expõe o `ente_id`

**Files:**
- Create: `apps/frontend/e2e/global-setup.ts`
- Create: `apps/frontend/e2e/seed.ts` (helper: roda o seed e faz parse do `ente_id`)
- Modify: `apps/frontend/playwright.config.ts` (reintroduzir `globalSetup`)
- Modify (talvez): `apps/backend/deps.edn` (garantir alias `:seed` com `:extra-paths ["demo"]`)
- Test: `apps/frontend/e2e/seed-smoke.spec.ts`

**Interfaces:**
- Consumes: config da Task 1.
- Produces: arquivo `apps/frontend/e2e/.artifacts/ente.json` com `{ "enteId": "<uuid>" }`; helper `lerEnteId(): string` que os specs importam.

- [ ] **Step 1: Confirmar como o seed imprime o `ente_id` e o alias de execução**

Run (contra a stack de pé):
```bash
docker run --rm --network host -v "$PWD/apps/backend:/app" -w /app \
  -e DATABASE_URL=jdbc:postgresql://localhost:5544/oplenario \
  -e MINIO_ENDPOINT=http://localhost:9100 \
  clojure:temurin-21-tools-deps \
  clojure -Sdeps '{:aliases {:seed {:extra-paths ["demo"]}}}' -X:seed seed-demo/materias
```
Expected: stdout com a URL do portal contendo o `ente_id` (uuid). **Anotar o formato exato da linha** — o parser do Step 3 depende dele. Rodar também `seed-demo/encarregado` do mesmo jeito e confirmar 0 erro.

- [ ] **Step 2: Escrever o teste do helper (falha esperada)**

```ts
// apps/frontend/e2e/seed-smoke.spec.ts
import { test, expect } from "@playwright/test";
import { lerEnteId } from "./seed";

test("o global-setup semeou um ente e o portal carrega para ele", async ({ page }) => {
  const enteId = lerEnteId();
  expect(enteId).toMatch(/^[0-9a-f-]{36}$/);
  await page.goto(`/portal/casa/${enteId}`);
  await expect(page.getByRole("banner")).toContainText(/Câmara|Camara/);
});
```

- [ ] **Step 3: Escrever `seed.ts` (roda o seed em container, parseia o `ente_id`, persiste)**

```ts
// apps/frontend/e2e/seed.ts
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync, readFileSync } from "node:fs";
import { join } from "node:path";

const ARTIFACT = join(__dirname, ".artifacts", "ente.json");

function rodarSeed(fn: string): string {
  // ajustar a imagem/rede conforme o que funcionou na Task 1/Step 1
  return execFileSync("docker", [
    "run", "--rm", "--network", "host",
    "-v", `${join(__dirname, "../../backend")}:/app`, "-w", "/app",
    "-e", "DATABASE_URL=jdbc:postgresql://localhost:5544/oplenario",
    "-e", "MINIO_ENDPOINT=http://localhost:9100",
    "clojure:temurin-21-tools-deps",
    "clojure", "-Sdeps", '{:aliases {:seed {:extra-paths ["demo"]}}}',
    "-X:seed", `seed-demo/${fn}`,
  ], { encoding: "utf8" });
}

export async function semear(): Promise<string> {
  const out = rodarSeed("materias");
  rodarSeed("encarregado");
  // AJUSTAR o regex ao formato real capturado na Step 1
  const m = out.match(/portal\/casa\/([0-9a-f-]{36})/);
  if (!m) throw new Error(`nao achei o ente_id no output do seed:\n${out}`);
  mkdirSync(join(__dirname, ".artifacts"), { recursive: true });
  writeFileSync(ARTIFACT, JSON.stringify({ enteId: m[1] }), "utf8");
  return m[1];
}

export function lerEnteId(): string {
  return JSON.parse(readFileSync(ARTIFACT, "utf8")).enteId;
}
```

- [ ] **Step 4: Escrever `global-setup.ts`**

```ts
// apps/frontend/e2e/global-setup.ts
import { semear } from "./seed";

export default async function globalSetup() {
  const enteId = await semear();
  console.log(`[e2e] seed pronto — ente ${enteId}`);
}
```

- [ ] **Step 5: Reintroduzir `globalSetup: "./e2e/global-setup.ts"` na `playwright.config.ts`** e adicionar `.artifacts/` ao `apps/frontend/.gitignore`.

- [ ] **Step 6: Rodar (deve PASSAR)**

Run: mesmo comando de container da Task 1/Step 5, mas com o docker socket montado para o global-setup poder chamar `docker run` (seed):
```bash
docker run --rm --network host \
  -v "$PWD/apps/frontend:/work" -v "$PWD/apps/backend:/work/../backend" \
  -v /var/run/docker.sock:/var/run/docker.sock -w /work \
  mcr.microsoft.com/playwright:v1.49.0-noble \
  sh -c "npm ci && npx playwright test e2e/seed-smoke.spec.ts"
```
Expected: `1 passed`. (Se docker-in-docker complicar, alternativa documentada: global-setup roda o seed via `host.docker.internal` chamando um script no host — mas tentar docker.sock primeiro.)

- [ ] **Step 7: Commit**

```bash
git add apps/frontend/e2e apps/frontend/playwright.config.ts apps/frontend/.gitignore apps/backend/deps.edn
git commit -m "test(e2e): global-setup semeia via seed_demo e expoe o ente_id"
```

---

### Task 3: Spec completo do Portal — os 6 asserts do contrato

**Files:**
- Create: `apps/frontend/e2e/portal-cidadao.spec.ts`
- Delete: `apps/frontend/e2e/seed-smoke.spec.ts` (absorvido por este spec)

**Interfaces:**
- Consumes: `lerEnteId()` da Task 2.

- [ ] **Step 1: Escrever o spec completo (falha esperada até rodar verde)**

```ts
// apps/frontend/e2e/portal-cidadao.spec.ts
import { test, expect } from "@playwright/test";
import { lerEnteId } from "./seed";

test.describe("Portal do Cidadão — e2e", () => {
  const enteId = lerEnteId();

  test("renderiza nome real, matéria semeada e balcões, sem erro de console", async ({ page }) => {
    const erros: string[] = [];
    page.on("console", (m) => { if (m.type() === "error") erros.push(m.text()); });

    const materias = page.waitForResponse((r) =>
      r.url().includes(`/api/portal/casa/${enteId}/materias`) && r.status() === 200);
    await page.goto(`/portal/casa/${enteId}`);
    await materias; // assert #4: 200 na rede

    // #1 nome real no header e rodapé
    await expect(page.getByRole("banner")).toContainText(/Câmara|Camara/);
    await expect(page.getByRole("contentinfo")).toContainText(/Câmara|Camara/);

    // #2 matéria semeada: número, estado, URN, faixa de tramitação
    const materia = page.getByRole("article").filter({ hasText: /PL \d+\/\d{4}/ });
    await expect(materia).toBeVisible();
    await expect(materia).toContainText(/urn:lex:br/);
    await expect(materia.getByRole("img", { name: /Tramitação/ })).toBeVisible();

    // #3 balcões e-SIC + LGPD, com contato do Encarregado presente (sem "em breve" de erro)
    await expect(page.getByRole("heading", { name: /Acesso à informação/ })).toBeVisible();
    await expect(page.getByRole("heading", { name: /dados pessoais/i })).toBeVisible();
    await expect(page.getByText(/Não foi possível carregar o contato do Encarregado/)).toHaveCount(0);

    // #6 placeholders honestos existem (contrato white-label)
    await expect(page.getByText("Em breve").first()).toBeVisible();

    // #5 sem erro de console inesperado (o 404 do encarregado sumiu)
    expect(erros, `erros de console: ${erros.join(" | ")}`).toEqual([]);
  });
});
```

- [ ] **Step 2: Rodar (deve PASSAR)**

Run: comando de container da Task 2/Step 6 apontando `e2e/portal-cidadao.spec.ts`.
Expected: `1 passed`. Se o assert #3/#5 do encarregado falhar, verificar que `seed-demo/encarregado` de fato semeia para o MESMO `ente_id` de `materias` (podem gerar entes distintos — nesse caso ajustar o seed para encadear, ou o seed de materias já criar o encarregado; anotar como achado e corrigir na fatia).

- [ ] **Step 3: Remover o smoke absorvido e commit**

```bash
git rm apps/frontend/e2e/seed-smoke.spec.ts
git add apps/frontend/e2e/portal-cidadao.spec.ts
git commit -m "test(e2e): spec completo do Portal do Cidadao (6 asserts do contrato)"
```

---

### Task 4: CI — job de e2e (ou carry documentado)

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify (se carry): `apps/frontend/e2e/README.md`

- [ ] **Step 1: Adicionar job que sobe a compose, semeia e roda o Playwright**

Adicionar em `.github/workflows/ci.yml` uma job `e2e` que: (a) `docker compose -f apps/backend/docker-compose.yml up -d --build` (com as vars de porta do CI), (b) espera `/saude` responder 200, (c) roda o container de Playwright da Task 3 apontando para os serviços da compose. Usar o mesmo mecanismo de rede validado na Task 1.

- [ ] **Step 2: Rodar o workflow (push da branch) e conferir verde**

Run: `git push -u origin fe-e2e-portal-publico` e acompanhar a Action.
Expected: job `e2e` verde. **Se escorregar** (setup de browser/serviços pesado, timeout), reverter a job e registrar carry no `e2e/README.md` com o motivo e o comando local exato que roda verde — o critério #1 (verde local) já está cumprido nas Tasks 1-3.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml apps/frontend/e2e/README.md
git commit -m "ci(e2e): job de browser-e2e do Portal (ou carry documentado)"
```

---

## Self-Review

- **Cobertura do spec:** seção 4 (6 asserts) → Task 3; seed (seção 2) → Task 2; localização (seção 1) → Task 1; CI (seção 3) → Task 4. Critérios de aceitação todos mapeados. ✓
- **Placeholders:** os `AJUSTAR`/`anotar` são pontos de verificação-contra-a-realidade deliberados (formato do output do seed, rede do container), não lacunas de design — cada um tem comando concreto e fallback documentado. ✓
- **Consistência de tipos:** `semear()`/`lerEnteId()`/`.artifacts/ente.json` usados consistentemente entre Tasks 2 e 3. ✓
- **Risco real conhecido:** `seed-demo/materias` e `seed-demo/encarregado` podem criar entes distintos — Task 3/Step 2 tem o gancho para detectar e corrigir. Não bloqueia o design.
