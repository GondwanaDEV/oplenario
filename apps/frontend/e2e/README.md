# E2E (Playwright) — Portal do Cidadão

Testes de browser end-to-end contra o frontend Next.js rodando na stack Docker do projeto.
Segue o mandato do projeto: **nunca rodar `npm`/`npx`/`node` direto no host** — tudo roda
dentro do container oficial do Playwright.

## Harness isolado (por quê)

Este diretório é um **projeto Node próprio e isolado** (`package.json` local com apenas
`@playwright/test`). Ele **não** compartilha dependências com o frontend Next.js e **nunca
muta o mount vivo** de `apps/frontend`. Isso resolve dois problemas reais descobertos na
montagem do harness:

1. **Conflito de peer dependency (ERESOLVE):** `next@16.2.9` declara `@playwright/test@^1.51.1`
   como peer opcional. Instalar o Playwright no mesmo `package.json` do frontend faz o
   `npm ci` falhar. Isolando o harness num `package.json` só com `@playwright/test`, não há
   `next` na árvore → sem conflito.
2. **Corrupção do dev server:** montar `apps/frontend` inteiro e rodar `rm -rf node_modules`
   corre com o `next dev` (que também monta `apps/frontend:/app` via compose) e **derruba o
   frontend**. Aqui montamos **só** `apps/frontend/e2e` e mantemos o `node_modules` do harness
   num **volume de container** (`oplenario_e2e_nm`) — o `node_modules` no host nunca é tocado.

## Pré-requisito

A stack tem que estar de pé (`oplenario-frontend-1` escutando em `:3000`):

```bash
cd apps/backend && docker compose up -d --build
```

## Rodar o smoke test

Da **raiz do repositório**:

```bash
docker run --rm --network host \
  -e PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
  -v "$PWD/apps/frontend/e2e:/e2e" \
  -v oplenario_e2e_nm:/e2e/node_modules \
  -w /e2e \
  mcr.microsoft.com/playwright:v1.49.0-noble \
  sh -c "npm ci --ignore-scripts && npx playwright test"
```

Notas sobre as flags (cada uma corrige uma armadilha medida neste ambiente):

- **`--network host`** — alcança o `:3000` do host. (Confirmado funcionando neste ambiente
  macOS/Docker Desktop; se algum ambiente não fizer bridge, use
  `-e E2E_BASE_URL=http://host.docker.internal:3000 --add-host=host.docker.internal:host-gateway`
  e remova `--network host`.)
- **`-v oplenario_e2e_nm:/e2e/node_modules`** — mantém o `node_modules` do harness num volume
  de container; o host nunca é escrito. Sem isso, o `npm ci` escreveria milhares de arquivos
  no bind mount do macOS (gRPC-FUSE) e **trava**.
- **`--ignore-scripts`** — pula os lifecycle scripts do install. O `postinstall` do Playwright
  tenta baixar browsers e **trava de forma determinística** neste ambiente; os browsers já vêm
  embutidos na imagem `v1.49.0-noble`, então não precisamos deles. `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`
  reforça isso.

Regenerar o `package-lock.json` (só quando mudar `package.json`): troque `npm ci` por
`npm install --ignore-scripts` no comando acima — o lock é escrito no `/e2e` montado (host) e
deve ser commitado.

## Versão do Playwright — pinada, não `^`

`@playwright/test` está pinado em **`1.49.0` exato** para casar com os binários de browser da
imagem `mcr.microsoft.com/playwright:v1.49.0-noble`. Se subir a versão do pacote, subir a tag
da imagem junto (e vice-versa) — os dois têm que casar, senão o teste falha em
`browserType.launch: Executable doesn't exist`.

## Próximas tasks (harness)

Task 2 reintroduz `globalSetup` (seed via `seed_demo.clj`, também em container efêmero, sem
mutar mount vivo) e persiste o `ente_id` em `.artifacts/`. Esta task (Task 1) só tem o smoke
de "o portal responde e o app monta" contra a stack de pé, sem dado semeado.
