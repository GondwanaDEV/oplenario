# E2E (Playwright) — Portal do Cidadão

Testes de browser end-to-end contra o frontend Next.js rodando na stack Docker do
projeto. Segue o mandato do projeto: **nunca rodar `npm`/`npx`/`node` direto no host** —
tudo roda dentro do container oficial do Playwright.

## Pré-requisito

A stack tem que estar de pé (`oplenario-frontend-1` escutando em `:3000`):

```bash
cd apps/backend
docker compose up -d --build
```

## Rodar o smoke test

De dentro de `apps/frontend`:

```bash
docker run --rm --network host -v "$PWD:/work" -w /work \
  mcr.microsoft.com/playwright:v1.49.0-noble \
  sh -c "npm ci && npx playwright test e2e/smoke.spec.ts"
```

**`--network host` funciona neste projeto** (Linux/Docker Desktop deste ambiente resolveu
`localhost:3000` de dentro do container sem problema). Se em algum ambiente (notadamente
macOS com Docker Desktop, onde `--network host` normalmente **não** faz bridge para portas
do host) o container não alcançar `:3000`, use o fallback:

```bash
docker run --rm -e E2E_BASE_URL=http://host.docker.internal:3000 \
  --add-host=host.docker.internal:host-gateway \
  -v "$PWD:/work" -w /work \
  mcr.microsoft.com/playwright:v1.49.0-noble \
  sh -c "npm ci && npx playwright test e2e/smoke.spec.ts"
```

`playwright.config.ts` lê `baseURL` de `E2E_BASE_URL`, com default `http://localhost:3000`.

## Versão do Playwright — pinada, não `^`

`@playwright/test` está pinado em **`1.49.0` exato** (sem `^`) em `package.json`, para casar
com os binários de browser já embutidos na imagem `mcr.microsoft.com/playwright:v1.49.0-noble`.
**Não usar range `^1.49.0`**: sem lockfile prévio, `npm install` resolve para a última
versão publicada da série 1.x (testado: foi para `1.61.1`), que não bate com os binários
`chromium_headless_shell` da imagem `v1.49.0-noble` e o teste falha em
`browserType.launch: Executable doesn't exist`. Se subir a versão do pacote, subir a tag da
imagem junto (e vice-versa) — os dois têm que casar.

## Onde ficam os artefatos

`test-results/` e `playwright-report/` são gerados na raiz de `apps/frontend/` e estão no
`.gitignore` — não versionar.

## Próximas tasks (harness)

Task 2 reintroduz `globalSetup` (seed via API do backend antes da suíte). Esta task (Task 1)
deliberadamente **não** tem `globalSetup` configurado — só o smoke de "o portal responde e o
app monta" contra a stack já de pé, sem depender de dado semeado.
