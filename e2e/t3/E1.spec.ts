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

// ACHADO DO MAPA (nao um teste, um fato de dominio): nenhuma das 4 escritas deste grupo tem campo CPF —
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
    // b) a escrita SAIU
    expect(resposta.status()).toBe(200);
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
  // "licenca sem mandato" mora conceitualmente na escrita de licenca, mas so' e' verificavel ANTES de
  // darmos um mandato ao vereadorSemMandatoId — por isso este teste roda aqui, antes do describe de
  // criar-mandato mutar este mesmo vereador.
  test("registrar licenca — bloqueado pela tela quando o vereador nao tem mandato vigente", async ({ page }) => {
    await page.goto(e1.urlVereadorSemMandato, { waitUntil: "domcontentloaded", timeout: 90_000 });
    const botao = page.getByRole("button", { name: "Registrar licença" });
    await expect(botao).toBeVisible();
    // achado do mapa: a tela BLOQUEIA proativamente (disabled + title), nunca deixa o usuario chegar
    // organicamente no 409 :conflito/sem-mandato-vigente que o backend devolveria. Documentamos o
    // comportamento REAL da tela, nao fingimos que da' pra clicar.
    await expect(botao).toBeDisabled();
    await expect(botao).toHaveAttribute("title", "Requer um mandato vigente.");
  });

  test("criar mandato — caminho feliz + as 3 verificacoes", async ({ page }) => {
    await page.goto(e1.urlVereadorSemMandato, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Registrar mandato" }).click();
    const form = page.locator('form[aria-label="Registrar mandato"]');
    await expect(form).toBeVisible();
    // legislatura vigente ja vem fixa (display, nao input) — so' preenchemos o resto
    await form.locator("#rm-inicio").fill(hoje());

    const [resposta] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/cadastros/vereadores/${e1.vereadorSemMandatoId}/mandatos`) && r.request().method() === "POST",
      ),
      form.getByRole("button", { name: "Registrar mandato" }).click(),
    ]);
    expect(resposta.status()).toBe(200);
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
      url: `/api/cadastros/vereadores/${e1.vereadorSemMandatoId}/mandatos`,
      status: resposta.status(),
      id: mandatoId,
      tabela: "cadastros.mandato",
      sql_de_prova: `SELECT id, vereador_id, legislatura_id, partido, estado, natureza, vigencia_inicio, vigencia_fim FROM cadastros.mandato WHERE ente_id = '${ENTE_ID}' AND id = '${mandatoId}'; -- esperado estado='vigente'`,
    });

    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.locator(".ficha .chip")).toHaveText("Mandato ativo", { timeout: 15_000 });
  });

  test("criar mandato — campo obrigatorio vazio (data de inicio)", async ({ page }) => {
    // o vereador ja tem mandato vigente agora (teste anterior) — tudo bem, este caso de erro nao
    // depende do estado previo do vereador, so' da validacao de campo.
    await page.goto(e1.urlVereadorSemMandato, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("button", { name: "Registrar mandato" }).click();
    const form = page.locator('form[aria-label="Registrar mandato"]');
    await form.getByRole("button", { name: "Registrar mandato" }).click(); // sem preencher #rm-inicio
    await expect(page.locator("#rm-inicio-erro")).toHaveText("Data de início inválida (AAAA-MM-DD).");
  });

  test("criar mandato — fim antes do inicio (bloqueado nos dois lados, tela pega primeiro)", async ({ page }) => {
    await page.goto(e1.urlVereadorSemMandato, { waitUntil: "domcontentloaded", timeout: 90_000 });
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
    expect(resposta.status()).toBe(409);
    await expect(form.getByText("ja existe mandato vigente sobreposto para este vereador")).toBeVisible();
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

  // ============================== REGISTRAR LICENCA ==============================
  test("registrar licenca — caminho feliz + as 3 verificacoes", async ({ page }) => {
    await page.goto(e1.urlVereadorParaLicenca, { waitUntil: "domcontentloaded", timeout: 90_000 });
    const botao = page.getByRole("button", { name: "Registrar licença" });
    await expect(botao).toBeEnabled(); // Thiago Bezerra tem mandato vigente hoje — precondicao do agente
    await botao.click();
    const form = page.locator('form[aria-label="Registrar licença"]');
    await expect(form).toBeVisible();
    await form.locator("#rl-inicio").fill(hoje());
    await form.locator("#rl-motivo").fill("Licenca medica E2E T3");

    const [resposta] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/cadastros/vereadores/${e1.vereadorComMandatoVigenteParaLicencaId}/licencas`) && r.request().method() === "POST",
      ),
      form.getByRole("button", { name: "Registrar licença" }).click(),
    ]);
    expect(resposta.status()).toBe(200);
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
      url: `/api/cadastros/vereadores/${e1.vereadorComMandatoVigenteParaLicencaId}/licencas`,
      status: resposta.status(),
      id: licencaId,
      tabela: "cadastros.mandato_licenca",
      sql_de_prova: `SELECT ml.id, ml.mandato_id, ml.inicio, ml.fim, ml.motivo FROM cadastros.mandato_licenca ml WHERE ente_id = '${ENTE_ID}' AND ml.id = '${licencaId}'; -- e conferir SELECT estado FROM cadastros.mandato WHERE ente_id='${ENTE_ID}' AND id = (SELECT mandato_id FROM cadastros.mandato_licenca WHERE id='${licencaId}'); -- esperado 'licenciado'`,
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
    // denominador de quórum da sessão de E5 — achado da revisão adversarial). vereadorSemMandatoId
    // recebeu mandato vigente no teste "criar mandato — caminho feliz" acima, neste mesmo arquivo, e
    // nenhum teste entre aquele e este voltou a mexer nele — alvo seguro pro guard.
    await page.goto(e1.urlVereadorSemMandato, { waitUntil: "domcontentloaded", timeout: 90_000 });
    const botao = page.getByRole("button", { name: "Registrar licença" });
    await expect(botao).toBeEnabled();
    await botao.click();
    const form = page.locator('form[aria-label="Registrar licença"]');
    await form.locator("#rl-inicio").fill(hoje());

    const posts: number[] = [];
    page.on("response", (r) => {
      if (
        r.url().includes(`/api/cadastros/vereadores/${e1.vereadorSemMandatoId}/licencas`) &&
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
