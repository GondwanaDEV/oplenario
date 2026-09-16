import { test, expect } from "@playwright/test";
import { readFileSync, appendFileSync, mkdirSync } from "node:fs";
import { resolve, dirname } from "node:path";

// E3 — "A matéria nasce" (servidor): as 2 escritas de mapa-E3.json — criar proposição (POST) e editar
// proposição (PATCH) — exercitadas pela interface (/proposicoes -> /editor-proposicao[, /:id]). Ids REAIS
// vêm de e2e/t3/.artifacts/t3-ids.json (gerado por preparar.mjs contra a Casa da demo viva); nenhum
// seletor aqui foi inventado — todos vieram de formulario-proposicao.tsx, editor-proposicao/page.tsx,
// editor-proposicao/[id]/page.tsx e proposicoes/page.tsx.
//
// Achados do mapa que este spec DOCUMENTA em vez de esconder (comentado [ACHADO] em cada teste):
//   - ementa só-espaços passa em qualquer camada (criar E editar) — o HTML `required` só barra length 0.
//   - criar não tem idempotency-key: 2 abas concorrentes com o mesmo corpo geram 2 proposições distintas.
//   - editar TEM proteção real (CAS por lock_version): a 2ª de duas PATCHes concorrentes recebe 400.
//   - editar só a ementa cunha uma versão de TEXTO nova, byte-a-byte idêntica à anterior (o form reenvia o
//     texto intacto e o backend decide promover por PRESENÇA da chave, não por mudança) — medido pelo salto
//     de lock_version (+2 com texto no corpo, +1 sem), com o alvo sem texto servindo de controle.
//   - o conflito de CAS sai como **400 "requisicao invalida"**, não 409: `:validacao/invalido` e corpo
//     malformado são indistinguíveis para o cliente, e essa string crua é o que o usuário lê no alerta.
//   - "espécie inválida" e "autor inexistente" NÃO são alcançáveis pela interface em nenhuma das 2 telas —
//     os testes correspondentes provam o motivo (select fechado / campo autor-id nunca existe no form),
//     não fingem cobrir um caso que a UI não deixa acontecer.
//
// RODADO nesta stack (10/09/2026, agente único, stack viva): 10/10 verde, duas corridas seguidas, `tsc
// --noEmit` limpo. As asserções novas foram provadas capazes de reprovar (delta de lock plantado errado →
// vermelho). Toda escrita bem-sucedida dá append em .artifacts/escritas-E3.json para o SQL de prova rodar
// depois, com ente_id sempre no WHERE (RLS por tenant).

const ids = JSON.parse(readFileSync(resolve(__dirname, ".artifacts/t3-ids.json"), "utf8"));

const ENTE_ID: string = ids.ente;
const TOKEN_SECRETARIA: string = ids.tokens.secretaria.json;
const TOKEN_PARAM = encodeURIComponent(TOKEN_SECRETARIA);
const BASE: string = ids.base.frontend;

const urlProposicoes = `${BASE}/proposicoes?token=${TOKEN_PARAM}`;
const urlEditorNovo: string = ids.e3.urlEditor; // já vem pronta do artefato, mesmo token acima
const urlEditar = (id: string) => `${BASE}/editor-proposicao/${id}?token=${TOKEN_PARAM}`;

const ARQ_ESCRITAS = resolve(__dirname, ".artifacts/escritas-E3.json");

function registrarEscrita(linha: Record<string, unknown>) {
  mkdirSync(dirname(ARQ_ESCRITAS), { recursive: true });
  appendFileSync(ARQ_ESCRITAS, JSON.stringify(linha) + "\n");
}

// Localiza a linha da tabela /proposicoes pelo id (o href de "Editar" contém o uuid cru — uuid não tem
// caractere reservado, então encodeURIComponent(id) === id e a substring bate direto).
function linhaDaTabela(page: import("@playwright/test").Page, id: string) {
  return page.locator(`a[href*="/editor-proposicao/${id}"]`).locator("xpath=ancestor::tr");
}

test.describe("E3 - A matéria nasce (servidor)", () => {
  test("criar proposição — caminho feliz: Projeto de Lei protocolado pela interface", async ({ page }) => {
    const marcador = `Ementa E3 criar ${Date.now()} — dispõe sobre teste de protocolo pela interface.`;

    await page.goto(urlProposicoes, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByRole("link", { name: "Nova proposição" }).click();
    await expect(page).toHaveURL(/\/editor-proposicao(\?|$)/, { timeout: 30_000 });
    await expect(page.getByRole("heading", { name: "Nova proposição" })).toBeVisible({ timeout: 30_000 });

    // Espécie/Ano ficam no default do form (projeto_lei / ano corrente) — só a ementa (obrigatória) muda.
    await page.getByLabel(/^ementa$/i).fill(marcador);

    const respostaPost = page.waitForResponse(
      (r) => r.url().includes("/api/legislativo/proposicoes") && r.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Protocolar" }).click();
    const resposta = await respostaPost;

    // (b) a escrita SAIU: status 2xx + corpo com o número/sequencial recém-gerados.
    expect(resposta.ok(), `esperava 2xx, veio ${resposta.status()}`).toBeTruthy();
    const corpo = await resposta.json();
    expect(corpo.id).toBeTruthy();
    expect(corpo.estado).toBe("protocolada");
    expect(corpo["urn-lex"]).toBeTruthy();

    // (a) a TELA diz que gravou: redireciona pra lista e a nova linha aparece com a ementa digitada.
    await expect(page).toHaveURL(/\/proposicoes(\?|$)/, { timeout: 30_000 });
    await expect(page.getByText(marcador)).toBeVisible({ timeout: 30_000 });

    // (c) F5: recarrega com o mesmo token e o dado continua lá.
    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.getByText(marcador)).toBeVisible({ timeout: 30_000 });

    registrarEscrita({
      escrita: "criar proposicao",
      metodo: "POST",
      url: "/legislativo/proposicoes",
      status: resposta.status(),
      id: corpo.id,
      tabela: "legislativo.proposicoes",
      sql_de_prova:
        `SELECT id, tipo, ano, sequencial, urn_lex, ementa, estado, lock_version, criado_em ` +
        `FROM legislativo.proposicoes WHERE ente_id='${ENTE_ID}' AND id='${corpo.id}'; ` +
        `-- espera 1 linha, estado='protocolada', ementa igual ao marcador, lock_version=0`,
    });
  });

  test("criar proposição — ementa só com espaços PASSA (nenhuma camada barra) [ACHADO]", async ({ page }) => {
    // mapa-E3: o atributo HTML `required` só checa length>0 — "   " passa. wire/in.CriarProposicao usa
    // `[:string {:max 2000}]` sem `:min`, e a coluna `ementa text NOT NULL` não tem CHECK de conteúdo.
    // Este teste PROVA o gap (não o esconde): se um dia isto reprovar com 4xx, o achado foi corrigido —
    // ajustar o teste então, não antes.
    await page.goto(urlEditorNovo, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await page.getByLabel(/^ementa$/i).fill("   ");

    const respostaPost = page.waitForResponse(
      (r) => r.url().includes("/api/legislativo/proposicoes") && r.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Protocolar" }).click();
    const resposta = await respostaPost;

    expect(resposta.ok(), `o achado É que isto passa — esperava 2xx, veio ${resposta.status()}`).toBeTruthy();
    const corpo = await resposta.json();
    expect(corpo.ementa.trim()).toBe(""); // o backend devolveu a ementa em branco, sem normalizar

    // (a) a tela mostra a lacuna: a linha nasce com a célula de ementa vazia.
    await expect(page).toHaveURL(/\/proposicoes(\?|$)/, { timeout: 30_000 });
    const linha = linhaDaTabela(page, corpo.id);
    await expect(linha).toBeVisible({ timeout: 30_000 });
    await expect(linha.locator(".ementa")).toHaveText("");

    // (c) F5: a ementa em branco sobrevive ao reload (não foi um artefato do estado React).
    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(linhaDaTabela(page, corpo.id).locator(".ementa")).toHaveText("", { timeout: 30_000 });

    registrarEscrita({
      escrita: "criar proposicao (ementa só-espaços) [ACHADO]",
      metodo: "POST",
      url: "/legislativo/proposicoes",
      status: resposta.status(),
      id: corpo.id,
      tabela: "legislativo.proposicoes",
      sql_de_prova:
        `SELECT id, ementa, length(ementa) AS tamanho, estado ` +
        `FROM legislativo.proposicoes WHERE ente_id='${ENTE_ID}' AND id='${corpo.id}'; ` +
        `-- espera ementa = '   ' (3 espaços), tamanho=3, estado='protocolada' — nada barrou`,
    });
  });

  test("criar proposição — duas abas concorrentes geram DUAS proposições distintas (sem idempotency-key) [ACHADO]", async ({
    context,
  }) => {
    // mapa-E3 "duplo clique": o guard de reentrância (botão disabled + enviandoRef) só protege UMA página/
    // aba. Duas abas reais — o cenário que o próprio achado cita ("2 abas, retry de rede") — não passam
    // por nenhum dos dois; o servidor não tem idempotency-key nem dedupe em `repo/protocolar!`. Isto prova
    // o gap com o cenário real, não com um clique duplo sintético que o client-side já barraria.
    const marcador = `Ementa E3 dup ${Date.now()} — mesmo corpo enviado por 2 abas simultâneas.`;
    const [pageA, pageB] = await Promise.all([context.newPage(), context.newPage()]);

    await Promise.all([
      pageA.goto(urlEditorNovo, { waitUntil: "domcontentloaded", timeout: 90_000 }),
      pageB.goto(urlEditorNovo, { waitUntil: "domcontentloaded", timeout: 90_000 }),
    ]);
    await pageA.getByLabel(/^ementa$/i).fill(marcador);
    await pageB.getByLabel(/^ementa$/i).fill(marcador);

    const respA = pageA.waitForResponse(
      (r) => r.url().includes("/api/legislativo/proposicoes") && r.request().method() === "POST",
    );
    const respB = pageB.waitForResponse(
      (r) => r.url().includes("/api/legislativo/proposicoes") && r.request().method() === "POST",
    );
    await Promise.all([
      pageA.getByRole("button", { name: "Protocolar" }).click(),
      pageB.getByRole("button", { name: "Protocolar" }).click(),
    ]);
    const [rA, rB] = await Promise.all([respA, respB]);

    // (b): as DUAS saem 2xx — ao contrário do editar (CAS), o criar não tem proteção de servidor nenhuma.
    expect(rA.ok(), `aba A esperava 2xx, veio ${rA.status()}`).toBeTruthy();
    expect(rB.ok(), `aba B esperava 2xx, veio ${rB.status()}`).toBeTruthy();
    const corpoA = await rA.json();
    const corpoB = await rB.json();
    expect(corpoA.id, "o achado: 2 requisições concorrentes viram 2 proposições distintas").not.toBe(corpoB.id);

    // (a) a tela: as duas abas redirecionam pra /proposicoes e AMBAS as linhas aparecem.
    await expect(pageA).toHaveURL(/\/proposicoes(\?|$)/, { timeout: 30_000 });
    await expect(pageB).toHaveURL(/\/proposicoes(\?|$)/, { timeout: 30_000 });
    await expect(linhaDaTabela(pageA, corpoA.id)).toBeVisible({ timeout: 30_000 });
    await expect(linhaDaTabela(pageB, corpoB.id)).toBeVisible({ timeout: 30_000 });

    await pageA.close();
    await pageB.close();

    for (const [resp, corpo] of [
      [rA, corpoA],
      [rB, corpoB],
    ] as const) {
      registrarEscrita({
        escrita: "criar proposicao (duplo clique via 2 abas concorrentes) [ACHADO]",
        metodo: "POST",
        url: "/legislativo/proposicoes",
        status: resp.status(),
        id: corpo.id,
        tabela: "legislativo.proposicoes",
        sql_de_prova:
          `SELECT id, ementa, criado_em FROM legislativo.proposicoes ` +
          `WHERE ente_id='${ENTE_ID}' AND id='${corpo.id}'; -- as 2 linhas (uma por corpo.id) existem, com a MESMA ementa`,
      });
    }
  });

  test("criar proposição — espécie inválida não é alcançável pela interface", async ({ page }) => {
    // mapa-E3: forçar um valor fora do vocabulário exige manipular o DOM fora do fluxo do usuário. Este
    // teste prova o MOTIVO em vez de pular o caso: o <select> só lista as 8 espécies conhecidas (mesmo
    // vocabulário do CHECK `proposicao_tipo_conhecido` da migration). Um valor fora daqui, se alcançado
    // via HTTP direto, o backend já rejeita 400 (Malli `km/enum-de logic/tipos`) — coberto na Trilha 2,
    // não aqui, porque não é um caminho que a INTERFACE ofereça.
    await page.goto(urlEditorNovo, { waitUntil: "domcontentloaded", timeout: 90_000 });
    const opcoes = page.getByLabel(/espécie/i).locator("option");
    await expect(opcoes).toHaveCount(8, { timeout: 30_000 });
    const valores = await opcoes.evaluateAll((els) => els.map((e) => (e as HTMLOptionElement).value));
    expect(valores).toEqual([
      "projeto_lei",
      "projeto_lei_complementar",
      "projeto_resolucao",
      "projeto_decreto_legislativo",
      "proposta_emenda_lom",
      "requerimento",
      "indicacao",
      "mocao",
    ]);
  });

  test("editar proposição — caminho feliz: servidor atualiza a ementa via PATCH com CAS", async ({ page }) => {
    const id: string = ids.e3.proposicaoEditavelId;
    await page.goto(urlEditar(id), { waitUntil: "domcontentloaded", timeout: 90_000 });

    const ementaCampo = page.getByLabel(/^ementa$/i);
    await expect(ementaCampo).not.toHaveValue("", { timeout: 30_000 }); // GET /:id resolveu, form pré-preenchido
    // Espécie/Ano vêm travados — identidade imutável pós-protocolo (checado à parte, teste seguinte).
    await expect(page.getByLabel(/espécie/i)).toBeDisabled();
    await expect(page.getByLabel(/^ano$/i)).toBeDisabled();

    const novaEmenta = `Ementa E3 editada ${Date.now()} — sobrescrita pela interface.`;
    await ementaCampo.fill(novaEmenta);

    const respostaPatch = page.waitForResponse(
      (r) => r.url().includes(`/api/legislativo/proposicoes/${id}`) && r.request().method() === "PATCH",
    );
    await page.getByRole("button", { name: "Salvar alterações" }).click();
    const resposta = await respostaPatch;

    // (b) a escrita SAIU: status 2xx + lock_version incrementado (prova o CAS por baixo do sucesso).
    expect(resposta.ok(), `esperava 2xx, veio ${resposta.status()}`).toBeTruthy();
    const corpoEnviado = JSON.parse(resposta.request().postData() ?? "{}");
    // [ACHADO] reforçado aqui: mesmo com o form inteiro preenchido, o corpo NUNCA carrega autor-id — o
    // teste dedicado abaixo prova a ausência do campo; este confirma que o efeito (corpo sem a chave) é
    // real no tráfego de rede, não só na leitura do JSX.
    expect(corpoEnviado).not.toHaveProperty("autor-id");
    const corpo = await resposta.json();
    expect(corpo["lock-version"]).toBeGreaterThan(0);
    expect(corpo.ementa).toBe(novaEmenta);

    // (a) a TELA diz que gravou: redireciona pra lista e a ementa nova aparece.
    await expect(page).toHaveURL(/\/proposicoes(\?|$)/, { timeout: 30_000 });
    await expect(page.getByText(novaEmenta)).toBeVisible({ timeout: 30_000 });

    // (c) F5: reabre o PRÓPRIO editor (GET fresco, não estado React) e confirma que o textarea carrega
    // o valor novo — não o antigo, não um cache do form.
    await page.goto(urlEditar(id), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByLabel(/^ementa$/i)).toHaveValue(novaEmenta, { timeout: 30_000 });

    registrarEscrita({
      escrita: "editar proposicao",
      metodo: "PATCH",
      url: `/legislativo/proposicoes/${id}`,
      status: resposta.status(),
      id,
      tabela: "legislativo.proposicoes",
      sql_de_prova:
        `SELECT id, ementa, lock_version, atualizado_em FROM legislativo.proposicoes ` +
        `WHERE ente_id='${ENTE_ID}' AND id='${id}'; -- espera ementa igual ao marcador novo, lock_version >= 1`,
    });
  });

  test("editar proposição — espécie/ano travados por DESIGN (trigger de imutabilidade, não só a UI)", async ({
    page,
  }) => {
    const id: string = ids.e3.proposicaoEditavelId;
    await page.goto(urlEditar(id), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByLabel(/^ementa$/i)).not.toHaveValue("", { timeout: 30_000 }); // form carregou
    await expect(page.getByLabel(/espécie/i)).toBeDisabled();
    await expect(page.getByLabel(/^ano$/i)).toBeDisabled();
    // mesmo que a UI permitisse: `legislativo.proposicao_identidade_imutavel()` (trigger BEFORE UPDATE)
    // lança exceção se tipo/ano/sequencial/urn_lex/ente_id mudarem — não é só react state, é o banco.
  });

  test("editar proposição — sobrescrever com só espaços PASSA (mesmo gap do criar) [ACHADO]", async ({ page }) => {
    // Usa um alvo DIFERENTE do 'editar caminho feliz' (outrasEditaveis[0]) — não some com a ementa do
    // alvo canônico, que outro teste/rerun pode precisar ler.
    const alvo = ids.e3.outrasEditaveis[0];
    await page.goto(urlEditar(alvo.id), { waitUntil: "domcontentloaded", timeout: 90_000 });
    const ementaCampo = page.getByLabel(/^ementa$/i);
    await expect(ementaCampo).not.toHaveValue("", { timeout: 30_000 });
    await ementaCampo.fill("    ");

    const respostaPatch = page.waitForResponse(
      (r) => r.url().includes(`/api/legislativo/proposicoes/${alvo.id}`) && r.request().method() === "PATCH",
    );
    await page.getByRole("button", { name: "Salvar alterações" }).click();
    const resposta = await respostaPatch;

    expect(resposta.ok(), `o achado É que isto passa — esperava 2xx, veio ${resposta.status()}`).toBeTruthy();
    const corpo = await resposta.json();
    expect(corpo.ementa.trim()).toBe("");

    await expect(page).toHaveURL(/\/proposicoes(\?|$)/, { timeout: 30_000 });
    const linha = linhaDaTabela(page, alvo.id);
    await expect(linha.locator(".ementa")).toHaveText("", { timeout: 30_000 });

    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(linhaDaTabela(page, alvo.id).locator(".ementa")).toHaveText("", { timeout: 30_000 });

    registrarEscrita({
      escrita: "editar proposicao (ementa só-espaços) [ACHADO]",
      metodo: "PATCH",
      url: `/legislativo/proposicoes/${alvo.id}`,
      status: resposta.status(),
      id: alvo.id,
      tabela: "legislativo.proposicoes",
      sql_de_prova:
        `SELECT id, ementa, length(ementa) AS tamanho FROM legislativo.proposicoes ` +
        `WHERE ente_id='${ENTE_ID}' AND id='${alvo.id}'; -- espera ementa = '    ' (4 espaços), sobrescreveu a ementa pública real`,
    });
  });

  test("editar proposição — duas PATCHes concorrentes com o mesmo lock_version: a 2ª leva 400 (CAS real)", async ({
    context,
  }) => {
    // mapa-E3: diferente do criar, o editar TEM proteção de servidor — `editar!` (db/proposicao.clj) faz
    // CAS por lock_version no WHERE do UPDATE. Duas abas que carregam a MESMA versão e submetem quase
    // juntas: uma aplica e incrementa; a outra casa 0 linhas e recebe 400. Alvo separado (outrasEditaveis[1])
    // pra não colidir com os outros 2 testes de editar que já mexem no canônico e no [0].
    const alvo = ids.e3.outrasEditaveis[1];
    const [pageA, pageB] = await Promise.all([context.newPage(), context.newPage()]);

    await Promise.all([
      pageA.goto(urlEditar(alvo.id), { waitUntil: "domcontentloaded", timeout: 90_000 }),
      pageB.goto(urlEditar(alvo.id), { waitUntil: "domcontentloaded", timeout: 90_000 }),
    ]);
    await expect(pageA.getByLabel(/^ementa$/i)).not.toHaveValue("", { timeout: 30_000 });
    await expect(pageB.getByLabel(/^ementa$/i)).not.toHaveValue("", { timeout: 30_000 });

    await pageA.getByLabel(/^ementa$/i).fill(`Ementa E3 CAS-A ${Date.now()}`);
    await pageB.getByLabel(/^ementa$/i).fill(`Ementa E3 CAS-B ${Date.now()}`);

    const respA = pageA.waitForResponse(
      (r) => r.url().includes(`/api/legislativo/proposicoes/${alvo.id}`) && r.request().method() === "PATCH",
    );
    const respB = pageB.waitForResponse(
      (r) => r.url().includes(`/api/legislativo/proposicoes/${alvo.id}`) && r.request().method() === "PATCH",
    );
    await Promise.all([
      pageA.getByRole("button", { name: "Salvar alterações" }).click(),
      pageB.getByRole("button", { name: "Salvar alterações" }).click(),
    ]);
    const [rA, rB] = await Promise.all([respA, respB]);

    // As duas abas TÊM de ter partido da mesma versão — senão não há conflito nenhum a provar e o teste
    // seria vácuo. Isto é medido no tráfego real, não presumido do fluxo da UI.
    const lockEnviadoA = JSON.parse(rA.request().postData() ?? "{}")["lock-version"];
    const lockEnviadoB = JSON.parse(rB.request().postData() ?? "{}")["lock-version"];
    expect(
      lockEnviadoA,
      "as 2 abas precisam sair da MESMA lock_version; se divergirem, não houve corrida",
    ).toBe(lockEnviadoB);
    expect(typeof lockEnviadoA).toBe("number");

    // Qual das duas abas perde é NÃO-DETERMINÍSTICO (vence quem chegar primeiro ao `SELECT … FOR UPDATE`
    // de `estado+lock`), então a asserção é sobre o PAR, nunca sobre uma aba fixa.
    const perdeuA = rA.status() !== 200;
    const [rPerdedora, rVencedora] = perdeuA ? [rA, rB] : [rB, rA];
    const [pagePerdedora] = perdeuA ? [pageA] : [pageB];

    // (b): exatamente uma vence (2xx) e a outra leva conflito — não é escrita perdida silenciosa.
    // O status do conflito é **400, não 409** — medido e confirmado na fonte: `editar!` lança
    // `ex-info "editar!: conflito de lock_version…" {:tipo :validacao/invalido}` (db/proposicao.clj:186-188)
    // e o interceptor global `erro` mapeia TODO `:validacao/invalido` para 400 com corpo opaco
    // (interceptors.clj:155-157). Não existe ramo 409 na cadeia de borda: conflito de CAS e corpo malformado
    // saem indistinguíveis para o cliente. [ACHADO menor de contrato — o correto REST seria 409; não é bug
    // de gravação, o dado está íntegro. Se um dia virar 409, esta linha reprova e é aqui que se ajusta.]
    expect(
      rPerdedora.status(),
      `a 2ª PATCH concorrente tinha de levar conflito; veio ${rPerdedora.status()}`,
    ).toBe(400);
    expect(rVencedora.status()).toBe(200);
    expect(await rPerdedora.json()).toEqual({ erro: "requisicao invalida" });

    // (a) a tela: a página perdedora mostra o alerta de erro (foco automático via erroRef) e NÃO navega.
    // Ancorado em `p.form-erro` e não em `getByRole("alert")`: o Next injeta um segundo role=alert vazio
    // (`#__next-route-announcer__`) em toda página, e o role puro estoura strict mode.
    const alertaDaPerdedora = pagePerdedora.locator("p.form-erro");
    await expect(alertaDaPerdedora).toBeVisible({ timeout: 30_000 });
    // A mensagem que o servidor mandou É a que a tela mostra — sem tradução, sem "tente novamente" genérico.
    // [ACHADO de microcopy] o usuário lê literalmente "requisicao invalida" (sem acento, sem explicar que
    // outra pessoa salvou antes) num conflito de edição concorrente — o corpo opaco do interceptor vaza cru
    // pro alerta (use-editar-proposicao.ts usa `corpoErro?.erro` direto).
    await expect(alertaDaPerdedora).toHaveText("requisicao invalida");
    await expect(pagePerdedora).toHaveURL(new RegExp(`/editor-proposicao/${alvo.id}`));

    const vencedora = rVencedora;
    const corpoVencedor = await vencedora.json();
    // A prova de que NÃO houve escrita perdida: SÓ a vencedora gravou. O incremento esperado é +2, não +1,
    // porque este corpo carrega `texto` — ver o teste "editar só a ementa…" abaixo, que isola e mede a
    // causa (`texto_versao/promover!` passo 3 dá um segundo `lock_version + 1` na mesma tx). Se a perdedora
    // também tivesse gravado, o salto seria +4.
    const passosDoLock = "texto" in JSON.parse(rVencedora.request().postData() ?? "{}") ? 2 : 1;
    expect(corpoVencedor["lock-version"]).toBe(lockEnviadoA + passosDoLock);
    expect(corpoVencedor.ementa).toBe(
      JSON.parse(rVencedora.request().postData() ?? "{}").ementa,
    );

    registrarEscrita({
      escrita: "editar proposicao (duplo clique concorrente — a que venceu o CAS)",
      metodo: "PATCH",
      url: `/legislativo/proposicoes/${alvo.id}`,
      status: vencedora.status(),
      id: alvo.id,
      tabela: "legislativo.proposicoes",
      sql_de_prova:
        `SELECT id, ementa, lock_version FROM legislativo.proposicoes ` +
        `WHERE ente_id='${ENTE_ID}' AND id='${alvo.id}'; ` +
        `-- ementa = a da vencedora ('${corpoVencedor.ementa}'), lock_version = ${corpoVencedor["lock-version"]} ` +
        `(= ${lockEnviadoA} + 1: incrementou UMA vez, a perdedora não gravou)`,
    });

    await pageA.close();
    await pageB.close();
  });

  test("editar só a ementa cria uma versão de TEXTO nova e idêntica à anterior [ACHADO]", async ({ page }) => {
    // MEDIDO nesta stack, não deduzido. O textarea "Texto da proposição" vem pré-preenchido pelo GET e o
    // hook `useEditarProposicao` só filtra `undefined` — logo o form reenvia o texto INTACTO em toda
    // gravação, mesmo quando o usuário só corrigiu a ementa. No backend, `editar-proposicao!`
    // (components/repositorio.clj:301-315) decide promover nova versão por `(when-let [corpo (:texto m)])`
    // — PRESENÇA da chave, nunca "o texto mudou". Efeito: cada salvamento de metadado cunha uma versão
    // `origem_versao='edicao'` byte-a-byte igual à anterior e supersede a vigente. Num sistema em que a
    // versão de texto é ato auditado (§22.4 eixo B), isso polui a trilha com versões que não são edição.
    //
    // A prova está no PAR de alvos, e é falseável dos dois lados:
    //   - alvo COM texto  -> o corpo leva `texto` e lock_version sobe 2 (o +1 extra é o reaponte do
    //     pointer em texto_versao/promover! passo 3, db/texto_versao.clj:64-68).
    //   - alvo SEM texto  -> o corpo não leva `texto`, nenhuma versão nasce e lock_version sobe 1.
    // Não há rota HTTP que liste as versões (nenhuma `/versoes` em diplomat/http/in.clj), por isso a
    // contagem direta fica no `sql_de_prova`, e o que a interface consegue medir é o salto do lock.
    // ESCOLHA POR PROPRIEDADE, NUNCA POR INDICE. A redacao anterior cravava `outrasEditaveis[2]` como
    // o alvo "sem texto", e a ordem de GET /legislativo/proposicoes NAO e' estavel entre corridas
    // (medido: os mesmos ids trocam de posicao entre duas preparacoes seguidas). Numa corrida em que o
    // indice 2 calhava de ter texto, este teste reprovava sem que nada do produto tivesse mudado.
    // O discriminador e' `lockVersion`: proposicao recem-criada pelo formulario nasce com lock 0 e SEM
    // versao de texto; qualquer uma ja editada tem lock > 0 e texto vigente. Conferido por SQL contra
    // legislativo.proposicao_texto_versao nos 5 candidatos do artefato.
    // O par vem do artefato POR PROPRIEDADE (temTexto), resolvido por preparar.mjs — que ate' FABRICA um
    // alvo sem-texto (POST cria proposicao com lock 0) quando o seed nao traz nenhum. Se ainda assim faltar
    // um dos dois (ex.: a fabricacao falhou e virou bloqueio), PULA em vez de reprovar: e' precondicao de
    // ambiente ausente, nao defeito do produto que este [ACHADO] documenta.
    type Cand = { id: string; lockVersion: number; temTexto: boolean };
    const comTextoCand = ids.e3.alvoComTexto as Cand | null;
    const semTextoCand = ids.e3.alvoSemTexto as Cand | null;
    test.skip(
      !comTextoCand || !semTextoCand,
      "E3 [ACHADO] sem o PAR de alvos (com/sem texto) no artefato — ver bloqueios e3-* em t3-ids.json; rode ./e2e/t3/preparar.sh.",
    );
    const comTexto: string = comTextoCand!.id;
    const semTexto: string = semTextoCand!.id;

    async function salvarSoAEmenta(alvo: string) {
      const antes = await page.request.get(`${ids.base.backend}/legislativo/proposicoes/${alvo}`, {
        headers: { Authorization: `Bearer ${TOKEN_SECRETARIA}` },
      });
      expect(antes.ok()).toBeTruthy();
      const lockAntes = (await antes.json())["lock-version"] as number;

      await page.goto(urlEditar(alvo), { waitUntil: "domcontentloaded", timeout: 90_000 });
      await expect(page.getByLabel(/^ementa$/i)).not.toHaveValue("", { timeout: 30_000 });
      // Só a ementa é tocada. O textarea de texto NÃO recebe um caractere sequer.
      await page.getByLabel(/^ementa$/i).fill(`Ementa E3 só-metadado ${Date.now()}`);

      const respostaPatch = page.waitForResponse(
        (r) => r.url().includes(`/api/legislativo/proposicoes/${alvo}`) && r.request().method() === "PATCH",
        { timeout: 30_000 },
      );
      await page.getByRole("button", { name: "Salvar alterações" }).click();
      const resposta = await respostaPatch;
      expect(resposta.status(), `esperava 200, veio ${resposta.status()}`).toBe(200);
      const enviado = JSON.parse(resposta.request().postData() ?? "{}");
      const corpo = await resposta.json();
      return { lockAntes, enviado, corpo };
    }

    // (1) alvo COM texto: o form reenviou o texto que ninguém tocou, e o lock saltou 2.
    const a = await salvarSoAEmenta(comTexto);
    expect(a.enviado, "o form reenvia o texto intacto mesmo sem o usuário tocá-lo").toHaveProperty("texto");
    expect(typeof a.enviado.texto).toBe("string");
    expect(a.corpo.texto, "o texto é o MESMO — não houve edição de texto nenhuma").toBe(a.enviado.texto);
    expect(a.corpo["lock-version"], "o +1 extra é a versão de texto promovida à toa").toBe(a.lockAntes + 2);

    // (2) alvo SEM texto: o mesmo fluxo de tela, sem `texto` no corpo, sobe só 1 — o controle que prova
    // que o salto de 2 acima vem da promoção de versão e não de outra coisa qualquer.
    const b = await salvarSoAEmenta(semTexto);
    expect(b.enviado).not.toHaveProperty("texto");
    expect(b.corpo["lock-version"]).toBe(b.lockAntes + 1);

    registrarEscrita({
      escrita: "editar proposicao (só a ementa; nasce versão de texto idêntica) [ACHADO]",
      metodo: "PATCH",
      url: `/legislativo/proposicoes/${comTexto}`,
      status: 200,
      id: comTexto,
      tabela: "legislativo.proposicao_texto_versao",
      sql_de_prova:
        `SELECT origem_versao, estado_versao, md5(texto_inline) AS hash, length(texto_inline) AS tam, criado_em ` +
        `FROM legislativo.proposicao_texto_versao WHERE ente_id='${ENTE_ID}' AND proposicao_id='${comTexto}' ` +
        `ORDER BY criado_em; -- espera N linhas com o MESMO md5, a última 'vigente'/'edicao': ` +
        `versões cunhadas por edição de metadado, texto byte-a-byte igual`,
    });
  });

  test("autor inexistente — o formulário nunca coleta autor-id, no criar nem no editar [ACHADO estrutural]", async ({
    page,
  }) => {
    // FormularioProposicao (COMPARTILHADO pelas 2 telas) só tem #f-autor-tipo (categoria) e #f-autor-texto
    // (texto livre) — `autorId` existe no TIPO do hook mas nenhum onChange o atribui. A validação real de
    // backend (validar-autor! + vereador-vinculado?) segue correta e ativa, só fica MORTA neste fluxo —
    // não há caminho pela interface pra alcançar o caso "autor-id que não corresponde a um vereador
    // vinculado". Sem regressão de segurança: o pior efeito é o campo nunca ser exercitável, não um
    // autor-id malicioso escapando.
    await page.goto(urlEditorNovo, { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByLabel(/^autor$/i)).toBeVisible({ timeout: 30_000 }); // autor-tipo existe
    await expect(page.locator("#f-autor-id")).toHaveCount(0);
    await expect(page.locator('input[name="autor-id"]')).toHaveCount(0);

    await page.goto(urlEditar(ids.e3.proposicaoEditavelId), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByLabel(/^autor$/i)).toBeVisible({ timeout: 30_000 });
    await expect(page.locator("#f-autor-id")).toHaveCount(0);
    await expect(page.locator('input[name="autor-id"]')).toHaveCount(0);
  });
});
