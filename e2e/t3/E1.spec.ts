import { test, expect, Page, Locator } from "@playwright/test";
import { mkdirSync, appendFileSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

// E1 — Cadastros (servidor): as 4 escritas de /cadastros/vereadores — criar vereador, editar vereador,
// criar mandato, registrar licenca. Ids vem do artefato VIVO gerado por preparar.mjs, nao de exemplo
// nenhum do mapa (o mapa-E1.json foi escrito antes da corrida final do preparar; o t3-ids.json e' quem
// tem os ids REAIS desta Casa). Lemos aqui, nao reinventamos.
const ids = JSON.parse(readFileSync(resolve(__dirname, ".artifacts/t3-ids.json"), "utf8"));
const ENTE_ID: string = ids.ente;
const e1 = ids.e1;
const TOKEN_URL: string = ids.tokens.secretaria.url;
const urlFicha = (vereadorId: string) =>
  `${ids.base.frontend}/cadastros/vereadores?v=${vereadorId}&token=${TOKEN_URL}`;

// [ACHADO — TRANSVERSAL, medido nesta corrida] NENHUMA mensagem de erro VINDA DO SERVIDOR aparece na
// tela em modo dev. Os 19 hooks de ESCRITA de apps/frontend/src/lib/use-*.ts declaram
//     const vivoRef = useRef(true);
//     useEffect(() => () => { vivoRef.current = false; }, []);
// e guardam TODO setState pos-await com `if (vivoRef.current)`. O efeito nunca RE-ARMA o ref (falta o
// `vivoRef.current = true;` no corpo do setup — os 8 hooks de LEITURA, ex. use-chamada.ts:143, fazem
// isso certo). Sob React StrictMode (ligado em dev; ver o proprio comentario de use-plenario.ts:241) o
// React roda cleanup+setup no mount, entao `vivoRef.current` nasce FALSE e fica false para sempre:
// `setEstado("erro")`/`setErro(msg)` viram no-op e o botao trava em "Salvando…" para sempre.
// Prova nos dois lados, sem inferencia: (a) o teste "mandato sobreposto" abaixo mede o DOM 1,5s depois
// do 409 e ve o botao ainda "Salvando…" e zero alerta; (b) o mesmo caso em RTL (sem StrictMode) passa —
// `npx vitest run '(interno)/cadastros/vereadores/page.test.tsx' -t 'mandato sobreposto'` => 1 passed.
// Escopo: e' o AMBIENTE DE DEV que expoe o defeito (StrictMode nao roda em build de producao), mas o
// defeito e' do hook, nao do StrictMode. Conserto de uma linha por hook:
//     useEffect(() => { vivoRef.current = true; return () => { vivoRef.current = false; }; }, []);
//
// [ACHADO DO MAPA] (nao um teste, um fato de dominio): nenhuma das 4 escritas deste grupo tem campo CPF —
// CPF so existe em "conceder acesso" (fora do escopo E1). Os casos de erro "CPF invalido"/"CPF duplicado"
// do plano generico NAO SE APLICAM a nenhuma escrita daqui. Ver mapa-E1.json:achados[0].

const ARTIFACTS_DIR = resolve(__dirname, ".artifacts");
const PROVA_PATH = resolve(ARTIFACTS_DIR, "escritas-E1.json");

// Append de uma linha de prova por escrita bem-sucedida — {escrita, metodo, url, status, id, tabela,
// sql_de_prova}. Quem roda a suite depois confere isto contra o banco; SQL sempre com ente_id no WHERE.
function registrarProva(linha: Record<string, unknown>) {
  mkdirSync(ARTIFACTS_DIR, { recursive: true });
  appendFileSync(PROVA_PATH, JSON.stringify(linha) + "\n");
}

function hoje(): string {
  return new Date().toISOString().slice(0, 10);
}

// Duplo-clique fisico: dispara 2 dispatchEvent("click") no MESMO elemento sem esperar entre eles —
// bypassa a checagem de actionability do Playwright (que já esperaria o botão desabilitar), pra chegar
// o mais perto possível do clique-duplo-rápido real que o achado do mapa pede pra verificar na prática.
async function duploClique(page: Page, botao: Locator) {
  await Promise.all([botao.dispatchEvent("click"), botao.dispatchEvent("click")]);
}

// Fabrica um vereador NOVO pela INTERFACE (a mesma tela ja' provada em "criar vereador — caminho feliz")
// e devolve o id. Existe porque TODA precondicao de escrita deste grupo e' consumivel: licenciar e' um
// caminho so' de ida pela interface (a volta e' POST /reassuncao, que nao tem botao em tela nenhuma) e
// registrar mandato num vereador que ja' tem um vigente da' 409. Alvo fabricado = spec repetivel sem
// re-rodar preparar.sh, que foi como as 4 falhas desta corrida se manifestaram na 2a rodada.
async function criarVereadorPelaTela(page: Page, rotulo: string): Promise<string> {
  const carimbo = Date.now();
  await page.goto(e1.urlLista, { waitUntil: "domcontentloaded", timeout: 90_000 });
  await page.getByRole("button", { name: "Novo vereador" }).click();
  const form = page.locator('form[aria-label="Novo vereador"]');
  await expect(form).toBeVisible();
  await form.locator("#nv-nome").fill(`E2E T3 ${rotulo} ${carimbo}`);
  await form.locator("#nv-parlamentar").fill(`${rotulo} ${carimbo}`);
  const [r] = await Promise.all([
    page.waitForResponse((x) => x.url().includes("/api/cadastros/vereadores") && x.request().method() === "POST"),
    form.getByRole("button", { name: "Criar vereador" }).click(),
  ]);
  expect(r.status()).toBe(201);
  return (await r.json()).id as string;
}

// Da' mandato VIGENTE (inicio = hoje, sem fim) ao vereador, tambem pela interface. Deixa a pagina JA' na
// ficha desse vereador, com o painel fechado.
async function darMandatoVigentePelaTela(page: Page, vereadorId: string): Promise<string> {
  await page.goto(urlFicha(vereadorId), { waitUntil: "domcontentloaded", timeout: 90_000 });
  await page.getByRole("button", { name: "Registrar mandato" }).click();
  const form = page.locator('form[aria-label="Registrar mandato"]');
  await expect(form).toBeVisible();
  await form.locator("#rm-inicio").fill(hoje());
  const [r] = await Promise.all([
    page.waitForResponse(
      (x) => x.url().includes(`/api/cadastros/vereadores/${vereadorId}/mandatos`) && x.request().method() === "POST",
    ),
    form.getByRole("button", { name: "Registrar mandato" }).click(),
  ]);
  expect(r.status()).toBe(201);
  await expect(page.locator(".ficha .chip")).toHaveText("Mandato ativo");
  return (await r.json()).id as string;
}

test.describe("E1 - Cadastros de vereadores", () => {
  // fullyParallel:false no playwright.config.ts já serializa os testes deste arquivo na ordem escrita —
  // importante aqui porque "licença sem mandato" (não-mutante) precisa rodar ANTES de "criar mandato"
  // (que dá mandato vigente ao MESMO vereador sem-mandato e destruiria a precondição do teste seguinte).

  // ============================== CRIAR VEREADOR ==============================
  test("criar vereador — caminho feliz + as 3 verificacoes", async ({ page }) => {
    const nome = `E2E T3 Vereador ${Date.now()}`;
    await page.goto(e1.urlLista, { waitUntil: "domcontentloaded", timeout: 90_000 }); // 1º acesso à rota: next dev compila sob demanda

    await page.getByRole("button", { name: "Novo vereador" }).click();
    const form = page.locator('form[aria-label="Novo vereador"]');
    await expect(form).toBeVisible();
    await form.locator("#nv-nome").fill(nome);
    await form.locator("#nv-parlamentar").fill("Nome Parlamentar E2E");

    const [resposta] = await Promise.all([
      page.waitForResponse((r) => r.url().includes("/api/cadastros/vereadores") && r.request().method() === "POST"),
      form.getByRole("button", { name: "Criar vereador" }).click(),
    ]);
    // b) a escrita SAIU. 201, nao 200 — `criar-vereador-handler` responde `json-resposta 201`
    // (apps/backend/src/oplenario/cadastros/diplomat/http/in.clj:61). A versao anterior deste teste exigia
    // 200 e reprovava com a escrita JA GRAVADA no banco: era defeito do TESTE, nao do produto.
    expect(resposta.status()).toBe(201);
    const corpo = await resposta.json();
    const novoId: string = corpo.id;
    expect(novoId).toBeTruthy();

    // a) a tela diz que gravou: painel fecha, ficha do vereador criado abre selecionada. nomeExibicao()
    // prioriza nomeParlamentar sobre nome (page.tsx) — como preenchemos os dois, e' "Nome Parlamentar
    // E2E ..." que aparece no h2 da ficha, NAO o `nome` cru.
    await expect(form).toBeHidden();
    await expect(page.locator(".ficha h2")).toContainText("Nome Parlamentar E2E");
    await expect(page).toHaveURL(new RegExp(`v=${novoId}`));

    registrarProva({
      escrita: "criar vereador",
      metodo: "POST",
      url: "/api/cadastros/vereadores",
      status: resposta.status(),
      id: novoId,
      tabela: "cadastros.vereador",
      sql_de_prova: `SELECT id, nome, nome_parlamentar, identidade_id FROM cadastros.vereador WHERE ente_id = '${ENTE_ID}' AND id = '${novoId}';`,
    });

    // c) F5: recarrega e o vereador continua lá, selecionado
    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.locator(".ficha h2")).toContainText("Nome Parlamentar E2E", { timeout: 15_000 });
  });

  test("criar vereador — campo obrigatorio vazio (nome em branco)", async ({ page }) => {
    await page.goto(e1.urlLista, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Novo vereador" }).click();
    const form = page.locator('form[aria-label="Novo vereador"]');
    await expect(form).toBeVisible();

    let disparouRequisicao = false;
    page.on("request", (r) => {
      if (r.url().includes("/api/cadastros/vereadores") && r.method() === "POST") disparouRequisicao = true;
    });

    await form.getByRole("button", { name: "Criar vereador" }).click();
    // TELA trata: erro de campo aparece e NENHUMA requisicao sai (preventDefault + !valido)
    await expect(page.locator("#nv-nome-erro")).toHaveText("Informe o nome do vereador.");
    await page.waitForTimeout(500);
    expect(disparouRequisicao).toBe(false);
  });

  test("criar vereador — duplo clique (guard sincrono enviandoRef)", async ({ page }) => {
    await page.goto(e1.urlLista, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Novo vereador" }).click();
    const form = page.locator('form[aria-label="Novo vereador"]');
    await form.locator("#nv-nome").fill(`E2E T3 Duplo Clique ${Date.now()}`);

    const posts: number[] = [];
    page.on("response", (r) => {
      if (r.url().includes("/api/cadastros/vereadores") && r.request().method() === "POST") posts.push(r.status());
    });
    await duploClique(page, form.getByRole("button", { name: /Criar vereador|Salvando/ }));
    await page.waitForTimeout(1500); // janela pro 2o clique responder SE tiver escapado do guard

    // achado do mapa: ha' 2 mecanismos client-side que podem bloquear a 2a chamada — o guard sincrono
    // (enviandoRef.current, checado antes de qualquer await) E o disabled={estado==='enviando'} do
    // re-render do botao. dispatchEvent nao espera actionability entre os 2 cliques, entao os 2
    // mecanismos ficam em corrida; esta asserção prova que a 2a chamada NAO saiu (nao duplicou o
    // vereador), sem distinguir qual dos dois barrou — se isto reprovar (posts.length > 1), o achado
    // documentado no mapa-E1.json vira FATO PROVADO, nao só leitura de codigo.
    expect(posts.length).toBe(1);
  });

  // ============================== EDITAR VEREADOR ==============================
  test("editar vereador — caminho feliz + as 3 verificacoes", async ({ page }) => {
    const novoNome = `Otavio Monteiro Editado ${Date.now()}`;
    await page.goto(e1.urlVereadorParaEditar, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Editar cadastro" }).click();
    const form = page.locator('form[aria-label="Editar cadastro"]');
    await expect(form).toBeVisible();
    await form.locator("#ev-nome").fill(novoNome);
    // nomeExibicao() (page.tsx) prioriza nomeParlamentar sobre nome — se so' preenchermos #ev-nome, o
    // h2 continua mostrando o nome_parlamentar antigo ('Otávio Monteiro' no banco). Preencher os dois
    // campos com novoNome e' o que de fato move o h2 (mesmo padrao do teste de "criar vereador" acima).
    await form.locator("#ev-parlamentar").fill(novoNome);

    const [resposta] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/cadastros/vereadores/${e1.vereadorParaEditarId}`) && r.request().method() === "PATCH",
      ),
      form.getByRole("button", { name: "Salvar alterações" }).click(),
    ]);
    expect(resposta.status()).toBe(200);
    const corpo = await resposta.json();
    expect(corpo.id).toBe(e1.vereadorParaEditarId);

    await expect(form).toBeHidden();
    await expect(page.locator(".ficha h2")).toHaveText(novoNome);

    registrarProva({
      escrita: "editar vereador",
      metodo: "PATCH",
      url: `/api/cadastros/vereadores/${e1.vereadorParaEditarId}`,
      status: resposta.status(),
      id: e1.vereadorParaEditarId,
      tabela: "cadastros.vereador",
      sql_de_prova: `SELECT nome, nome_parlamentar FROM cadastros.vereador WHERE ente_id = '${ENTE_ID}' AND id = '${e1.vereadorParaEditarId}';`,
    });

    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.locator(".ficha h2")).toHaveText(novoNome, { timeout: 15_000 });
  });

  test("editar vereador — nome esvaziado (obrigatorio)", async ({ page }) => {
    await page.goto(e1.urlVereadorParaEditar, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Editar cadastro" }).click();
    const form = page.locator('form[aria-label="Editar cadastro"]');
    await form.locator("#ev-nome").fill("");
    await form.getByRole("button", { name: "Salvar alterações" }).click();
    await expect(page.locator("#ev-nome-erro")).toHaveText("O nome não pode ficar em branco.");
  });

  test("editar vereador — nenhum campo alterado (erro geral)", async ({ page }) => {
    await page.goto(e1.urlVereadorParaEditar, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Editar cadastro" }).click();
    const form = page.locator('form[aria-label="Editar cadastro"]');
    // nao mexe em nada — clica direto
    await form.getByRole("button", { name: "Salvar alterações" }).click();
    await expect(form.getByText("Preencha ao menos um campo para salvar.")).toBeVisible();
  });

  test("editar vereador — duplo clique (guard sincrono enviandoRef)", async ({ page }) => {
    await page.goto(e1.urlVereadorParaEditar, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Editar cadastro" }).click();
    const form = page.locator('form[aria-label="Editar cadastro"]');
    await form.locator("#ev-parlamentar").fill(`Apelido ${Date.now()}`);

    const patches: number[] = [];
    page.on("response", (r) => {
      if (r.url().includes(`/api/cadastros/vereadores/${e1.vereadorParaEditarId}`) && r.request().method() === "PATCH") {
        patches.push(r.status());
      }
    });
    await duploClique(page, form.getByRole("button", { name: /Salvar alterações|Salvando/ }));
    await page.waitForTimeout(1500);
    // aqui o risco de dominio e' menor que criar-vereador (2 PATCHs com o MESMO corpo sao inofensivos),
    // mas o guard client-side deve mesmo assim bloquear a 2a chamada antes do 1o resolver.
    expect(patches.length).toBe(1);
  });

  // ============================== CRIAR MANDATO ==============================
  // "licenca sem mandato" mora conceitualmente na escrita de licenca, mas so' e' verificavel num vereador
  // SEM mandato vigente. Antes este teste usava `e1.vereadorSemMandatoId` e dependia de rodar antes de
  // "criar mandato" (que mutava o MESMO vereador): a ordem dos testes virava precondicao invisivel, e na
  // 2a corrida do spec ela ja' nao valia. Com alvo fabricado, este teste nao depende nem da ordem nem de
  // preparar.sh — `e1.vereadorSemMandatoId` deixou de ser usado no arquivo inteiro de proposito.
  test("registrar licenca — bloqueado pela tela quando o vereador nao tem mandato vigente", async ({ page }) => {
    const alvoId = await criarVereadorPelaTela(page, "Sem Mandato");
    await page.goto(urlFicha(alvoId), { waitUntil: "domcontentloaded", timeout: 90_000 });
    const botao = page.getByRole("button", { name: "Registrar licença" });
    await expect(botao).toBeVisible();
    // achado do mapa: a tela BLOQUEIA proativamente (disabled + title), nunca deixa o usuario chegar
    // organicamente no 409 :conflito/sem-mandato-vigente que o backend devolveria. Documentamos o
    // comportamento REAL da tela, nao fingimos que da' pra clicar.
    await expect(botao).toBeDisabled();
    await expect(botao).toHaveAttribute("title", "Requer um mandato vigente.");
  });

  test("criar mandato — caminho feliz + as 3 verificacoes", async ({ page }) => {
    // Alvo FABRICADO, nao o `e1.vereadorSemMandatoId` do artefato: aquele e' consumivel (depois da 1a
    // corrida ele ja' tem mandato, e a 2a rodada media a Casa errada — foi assim que a 2a corrida deste
    // spec reprovou aqui com chip "Licença" em vez de "Mandato ativo"). O artefato segue usado, mas so'
    // nos testes NAO-MUTANTES deste arquivo.
    const alvoId = await criarVereadorPelaTela(page, "Alvo de Mandato");
    await page.goto(urlFicha(alvoId), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Registrar mandato" }).click();
    const form = page.locator('form[aria-label="Registrar mandato"]');
    await expect(form).toBeVisible();
    // legislatura vigente ja vem fixa (display, nao input) — so' preenchemos o resto
    await form.locator("#rm-inicio").fill(hoje());

    const [resposta] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/cadastros/vereadores/${alvoId}/mandatos`) && r.request().method() === "POST",
      ),
      form.getByRole("button", { name: "Registrar mandato" }).click(),
    ]);
    // 201 (in.clj:84) — mesma correcao de teste do "criar vereador" acima.
    expect(resposta.status()).toBe(201);
    const corpo = await resposta.json();
    const mandatoId: string = corpo.id;
    expect(mandatoId).toBeTruthy();

    await expect(form).toBeHidden();
    // toContainText("Mandato") seria vácuo: .ficha-acoes já tem o botão literal "Registrar mandato"
    // ANTES da escrita. O chip de estado (estadoChip, cadastro-vereadores-vista.ts:72) é quem de fato
    // muda quando o mandato vira 'vigente' — é essa mudança que prova a escrita.
    await expect(page.locator(".ficha .chip")).toHaveText("Mandato ativo");
    // efeito colateral direto: o mandato recem-criado destrava "Registrar licença" nesta MESMA ficha
    await expect(page.getByRole("button", { name: "Registrar licença" })).toBeEnabled();

    registrarProva({
      escrita: "criar mandato",
      metodo: "POST",
      url: `/api/cadastros/vereadores/${alvoId}/mandatos`,
      status: resposta.status(),
      id: mandatoId,
      tabela: "cadastros.mandato",
      sql_de_prova: `SELECT id, vereador_id, legislatura_id, partido, estado, natureza, vigencia_inicio, vigencia_fim FROM cadastros.mandato WHERE ente_id = '${ENTE_ID}' AND id = '${mandatoId}'; -- esperado estado='vigente'`,
    });

    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.locator(".ficha .chip")).toHaveText("Mandato ativo", { timeout: 15_000 });
  });

  test("criar mandato — campo obrigatorio vazio (data de inicio)", async ({ page }) => {
    // caso de erro puramente de CAMPO — nao depende do estado de mandato do vereador. Alvo fabricado
    // mesmo assim, para nao herdar o estado que corridas anteriores deixaram no alvo do artefato.
    const alvoId = await criarVereadorPelaTela(page, "Campo Vazio");
    await page.goto(urlFicha(alvoId), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Registrar mandato" }).click();
    const form = page.locator('form[aria-label="Registrar mandato"]');
    await form.getByRole("button", { name: "Registrar mandato" }).click(); // sem preencher #rm-inicio
    await expect(page.locator("#rm-inicio-erro")).toHaveText("Data de início inválida (AAAA-MM-DD).");
  });

  test("criar mandato — fim antes do inicio (bloqueado nos dois lados, tela pega primeiro)", async ({ page }) => {
    const alvoId = await criarVereadorPelaTela(page, "Fim Antes");
    await page.goto(urlFicha(alvoId), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Registrar mandato" }).click();
    const form = page.locator('form[aria-label="Registrar mandato"]');
    await form.locator("#rm-inicio").fill("2024-06-10");
    await form.locator("#rm-fim").fill("2024-01-01");
    await form.getByRole("button", { name: "Registrar mandato" }).click();
    await expect(page.locator("#rm-fim-erro")).toHaveText("O fim não pode ser anterior ao início.");
  });

  test("criar mandato — mandato sobreposto (409 do backend, vereador ja tem mandato vigente)", async ({ page }) => {
    // vereadorParaEditarId e' um dos 17 vereadores da demo com mandato VIGENTE — registrar outro aqui
    // bate direto no :conflito/mandato-sobreposto (backend), o unico jeito de ver esse 409 pela tela.
    await page.goto(e1.urlVereadorParaEditar, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Registrar mandato" }).click();
    const form = page.locator('form[aria-label="Registrar mandato"]');
    await form.locator("#rm-inicio").fill(hoje());

    const [resposta] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/cadastros/vereadores/${e1.vereadorParaEditarId}/mandatos`) && r.request().method() === "POST",
      ),
      form.getByRole("button", { name: "Registrar mandato" }).click(),
    ]);
    // O BACKEND acerta: 409 com a mensagem em pt-BR, pronta pra ser exibida tal-qual.
    expect(resposta.status()).toBe(409);
    expect((await resposta.json()).erro).toBe("ja existe mandato vigente sobreposto para este vereador");

    // [ACHADO T3-B — CONSERTADO em 10/09/2026] Este teste NASCEU afirmando o defeito: 1,5s depois do 409
    // o form continuava com o botao "Salvando…" desabilitado e ZERO alerta na pagina, porque os 19 hooks
    // de ESCRITA faziam `useEffect(() => () => { vivoRef.current = false; }, [])` e nunca re-armavam o
    // ref — sob React StrictMode (dev) o cleanup roda no mount, o ref nasce false, e todo setEstado
    // pos-resposta vira no-op. Os 8 hooks de LEITURA ja armavam certo; a assimetria era o padrao.
    //
    // O autor do teste deixou escrito, aqui mesmo, o que fazer no dia do conserto: trocar as 3 asserçoes
    // do comportamento quebrado pela UNICA que o produto certo satisfaz. E isso que segue abaixo — e o
    // teste virou de vermelho-que-documenta para verde-que-prende. A prova de que o conserto e' real
    // esta justamente em ele ter REPROVADO na primeira corrida depois da mudanca do hook.
    // Gate estrutural que impede a omissao de voltar em qualquer hook novo:
    // apps/frontend/src/lib/vivo-ref-lint.test.ts (provado capaz de reprovar, nomeando o arquivo).
    await expect(form.getByText("ja existe mandato vigente sobreposto para este vereador")).toBeVisible({
      timeout: 15_000,
    });
    // E o botao volta a ser clicavel: o usuario pode corrigir a data e tentar de novo sem fechar o painel.
    const submit = form.getByRole("button", { name: /Registrar mandato|Salvando/ });
    await expect(submit).toBeEnabled({ timeout: 15_000 });
  });

  test("criar mandato — duplo clique", async ({ page }) => {
    await page.goto(e1.urlVereadorParaEditar, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Registrar mandato" }).click();
    const form = page.locator('form[aria-label="Registrar mandato"]');
    await form.locator("#rm-inicio").fill(hoje());

    const posts: number[] = [];
    page.on("response", (r) => {
      if (r.url().includes(`/api/cadastros/vereadores/${e1.vereadorParaEditarId}/mandatos`) && r.request().method() === "POST") {
        posts.push(r.status());
      }
    });
    await duploClique(page, form.getByRole("button", { name: /Registrar mandato|Salvando/ }));
    await page.waitForTimeout(1500);
    // aqui o guard client-side (enviandoRef OU o disabled do re-render, em corrida — nao distinguimos
    // qual) E o dominio (409 mandato-sobreposto) convergem: mesmo que os 2 cliques escapassem do guard
    // client-side, o 2o bateria em 409 sobreposto — nunca 2 mandatos vigentes.
    expect(posts.length).toBe(1);
  });

  // [ACHADO — PRODUTO, medido, NAO asseverado aqui de proposito] Um vereador pode acumular, PELA
  // INTERFACE e sem nenhum SQL, um mandato 'licenciado' e um 'vigente' cobrindo a MESMA data: basta
  // "Registrar mandato" -> "Registrar licença" -> "Registrar mandato" de novo. O EXCLUDE
  // `uq_mandato_vigente_sem_overlap` (mig 0059) so' barra dois VIGENTES sobrepostos, e o backend aceita
  // o 2o mandato com 201. A partir dai as duas leituras do sistema DISCORDAM sobre o mesmo vereador:
  //   - cadastro (lista e ficha): `listar` e `mandato-vigente` (cadastros/db/vereador.clj:222 e :114)
  //     ordenam so' por `vigencia_inicio DESC, id` — com as duas vigencias comecando no mesmo dia, quem
  //     ganha e' o UUID que vier primeiro. SORTEIO.
  //   - quorum/chamada/assiduidade: `mandato-vigente-lateral` (mesmo arquivo, :272) desempata com
  //     `CASE WHEN estado='vigente' THEN 0 ELSE 1` — 'vigente' SEMPRE vence.
  // Medido nesta Casa (vereador 1ace5d7e-..., 1 mandato 'vigente' + 2 'licenciado' na mesma data):
  //   psql  -> id 1265431e-... estado 'vigente' existe
  //   GET /cadastros/vereadores/1ace5d7e-...        -> mandato.estado = "licenciado"
  //   GET /cadastros/vereadores (lista)             -> estado-mandato = "licenciado"
  // Efeito de produto: a ficha mostra "Licença" e DESABILITA "Registrar licença" ("Requer um mandato
  // vigente") para alguem que TEM mandato vigente e conta no quorum da sessao — e a secretaria nao tem
  // como sair disso pela tela (a saida e' POST /reassuncao, sem botao).
  // NAO viro isto em teste verde por honestidade: como o desempate e' por UUID aleatorio, QUALQUER
  // asserçao sobre o chip seria ~50% flaky — um verde aqui seria sorteio, nao prova. Fica como fixme
  // com a evidencia dos dois lados; o conserto natural e' dar a `listar`/`mandato-vigente` o MESMO
  // desempate por estado que o LATERAL do roster ja' tem.
  test.fixme(
    "criar mandato — 'licenciado' + 'vigente' na mesma data: cadastro e quorum discordam (desempate por UUID)",
    async () => {
      /* ver o bloco acima: reproduzido e medido, mas inasseveravel em uma corrida — desempate aleatorio */
    },
  );

  // ============================== REGISTRAR LICENCA ==============================
  test("registrar licenca — caminho feliz + as 3 verificacoes", async ({ page }) => {
    // [PRECONDICAO CONSUMIDA — corrigido no TESTE] o alvo do artefato
    // (e1.vereadorComMandatoVigenteParaLicencaId, Thiago Bezerra) ja' esta 'licenciado' desde a PRIMEIRA
    // corrida deste spec: licenciar e' IRREVERSIVEL pela interface (a volta e' POST
    // /cadastros/vereadores/:id/reassuncao — rota que existe no backend, in.clj:215, e nao tem botao em
    // tela nenhuma) e o preparar.mjs nao re-arma o alvo. Conferido no banco:
    //   SELECT m.estado FROM cadastros.mandato m WHERE m.vereador_id='<alvo>'  =>  licenciado
    // Por isso a 2a corrida em diante reprovava em `toBeEnabled()` — precondicao gasta, NAO defeito de
    // produto (a tela desabilitar "Registrar licença" para quem nao tem mandato vigente e' o correto, e
    // esta provado no teste "bloqueado pela tela..." acima).
    // Em vez de contornar, o teste FABRICA o proprio alvo PELA INTERFACE: cria um vereador e registra um
    // mandato vigente nele (as duas escritas ja' provadas nos testes acima), e so' entao licencia. Fica
    // repetivel N vezes, e o alvo termina 'licenciado' — nunca entra no denominador de quorum de sessao
    // nenhuma, que e' exatamente a razao pela qual o artefato reservou um vereador nao-logavel.
    const alvoId = await criarVereadorPelaTela(page, "Alvo de Licenca");
    await darMandatoVigentePelaTela(page, alvoId);

    // --------- e agora sim a escrita sob teste, com o botao HABILITADO por mandato vigente de verdade
    const botao = page.getByRole("button", { name: "Registrar licença" });
    await expect(botao).toBeEnabled();
    await botao.click();
    const form = page.locator('form[aria-label="Registrar licença"]');
    await expect(form).toBeVisible();
    await form.locator("#rl-inicio").fill(hoje());
    await form.locator("#rl-motivo").fill("Licenca medica E2E T3");

    const [resposta] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/cadastros/vereadores/${alvoId}/licencas`) && r.request().method() === "POST",
      ),
      form.getByRole("button", { name: "Registrar licença" }).click(),
    ]);
    // 201 (in.clj:102) — mesma correcao de teste das outras duas criacoes deste arquivo.
    expect(resposta.status()).toBe(201);
    const corpo = await resposta.json();
    const licencaId: string = corpo.id;
    expect(licencaId).toBeTruthy();

    await expect(form).toBeHidden();
    // toContainText("Licença") seria vácuo: .ficha-acoes já tem o botão literal "Registrar licença"
    // ANTES da escrita. O que prova a escrita e' o chip de estado (estadoChip -> 'Licença' quando o
    // mandato vira 'licenciado') e o efeito colateral direto: o botao "Registrar licença" NAO some —
    // fica desabilitado (mandato ja nao esta 'vigente' pra licenciar de novo).
    await expect(page.locator(".ficha .chip")).toHaveText("Licença");
    await expect(page.getByRole("button", { name: "Registrar licença" })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Registrar licença" })).toHaveAttribute(
      "title",
      "Requer um mandato vigente.",
    );

    registrarProva({
      escrita: "registrar licenca",
      metodo: "POST",
      url: `/api/cadastros/vereadores/${alvoId}/licencas`,
      status: resposta.status(),
      id: licencaId,
      tabela: "cadastros.mandato_licenca",
      sql_de_prova: `SELECT ml.id, ml.mandato_id, ml.inicio, ml.fim, ml.motivo FROM cadastros.mandato_licenca ml WHERE ml.ente_id = '${ENTE_ID}' AND ml.id = '${licencaId}'; -- e conferir SELECT estado FROM cadastros.mandato WHERE ente_id='${ENTE_ID}' AND id = (SELECT mandato_id FROM cadastros.mandato_licenca WHERE id='${licencaId}'); -- esperado 'licenciado'`,
    });

    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.locator(".ficha .chip")).toHaveText("Licença", { timeout: 15_000 });
  });

  test("registrar licenca — campo obrigatorio vazio (inicio)", async ({ page }) => {
    // usa o MESMO vereador do teste anterior: agora ele esta 'licenciado', entao o botao de abrir o
    // painel some/desabilita de novo — a validacao de campo em si independe disso, entao verificamos
    // contra vereadorParaEditarId, que segue com mandato vigente ate aqui neste arquivo.
    await page.goto(e1.urlVereadorParaEditar, { waitUntil: "domcontentloaded", timeout: 90_000 });
    const botao = page.getByRole("button", { name: "Registrar licença" });
    await expect(botao).toBeEnabled();
    await botao.click();
    const form = page.locator('form[aria-label="Registrar licença"]');
    await form.getByRole("button", { name: "Registrar licença" }).click(); // sem preencher #rl-inicio
    await expect(page.locator("#rl-inicio-erro")).toHaveText("Data de início inválida (AAAA-MM-DD).");
  });

  // [ACHADO] mapa-E1.json: validarLicenca (client) bloqueia fim<inicio, mas
  // registrar-licenca->dominio (apps/backend/.../adapters/in/vereador.clj) NAO tem essa checagem —
  // so' valida formato de data e inicio<=hoje. Pela INTERFACE isto e' inalcancavel: o botao de submit
  // fica disabled (tocado && !valido) e o handler de submit (aoEnviar) retorna cedo antes de qualquer
  // fetch, entao nao ha' como o clique real do usuario contornar a validacao do cliente sem js custom
  // fora do form (o que deixaria de ser "pela interface"). Ja foi provado por HTTP direto na Trilha 2.
  // Documentado aqui, nao testado end-to-end nesta trilha.
  test.fixme(
    "registrar licenca — fim antes do inicio: backend NAO valida, mas a tela bloqueia antes de chegar la (inalcancavel pela interface)",
    async () => {
      /* ver comentario acima — acionavel so' por HTTP direto, fora do escopo desta trilha (so' interface) */
    },
  );

  test("registrar licenca — duplo clique", async ({ page }) => {
    // NAO usar vereadorParaEditarId (Otávio) aqui: este teste FAZ a escrita de verdade no 1o clique
    // (licencia o vereador), e Otávio é o alvo do roster de E5 (mexer no estado dele tira Otávio do
    // denominador de quórum da sessão de E5 — achado da revisão adversarial). Também não usar o
    // `vereadorSemMandatoId` do artefato, como fazia antes: aquilo amarrava este teste ao resultado do
    // "criar mandato — caminho feliz" (ordem como precondicao invisivel) e reprovava na 2a corrida.
    const alvoId = await criarVereadorPelaTela(page, "Duplo Licenca");
    await darMandatoVigentePelaTela(page, alvoId);
    const botao = page.getByRole("button", { name: "Registrar licença" });
    await expect(botao).toBeEnabled();
    await botao.click();
    const form = page.locator('form[aria-label="Registrar licença"]');
    await form.locator("#rl-inicio").fill(hoje());

    const posts: number[] = [];
    page.on("response", (r) => {
      if (
        r.url().includes(`/api/cadastros/vereadores/${alvoId}/licencas`) &&
        r.request().method() === "POST"
      ) {
        posts.push(r.status());
      }
    });
    await duploClique(page, form.getByRole("button", { name: /Registrar licença|Salvando/ }));
    await page.waitForTimeout(1500);
    // mais seguro que criar-vereador: mesmo se os 2 escapassem do guard client-side, a 2a bateria em
    // :conflito/sem-mandato-vigente (a 1a ja mudou o mandato pra 'licenciado') — o proprio dominio
    // absorve o double-submit sem duplicar a linha.
    expect(posts.length).toBe(1);
  });
});
