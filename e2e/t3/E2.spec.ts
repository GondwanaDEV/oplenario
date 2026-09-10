import { test, expect, type Page } from "@playwright/test";
import { readFileSync, appendFileSync } from "node:fs";
import { resolve } from "node:path";

// e2e/t3/E2.spec.ts — GRUPO E2 (Expediente e protocolo, servidor) da Trilha 3.
// Cobre as 3 escritas do mapa (e2e/t3/mapa-E2.json): gerar documento a partir de modelo, editar
// rascunho, protocolar e numerar. Le os ids REAIS de e2e/t3/.artifacts/t3-ids.json (nunca crava UUID
// a mao) e da append de uma linha de prova por escrita bem-sucedida em
// e2e/t3/.artifacts/escritas-E2.json.
//
// NAO RODAR AQUI: este arquivo e' escrito por um agente que nao pode executar o spec (8 grupos
// escrevendo ao mesmo tempo contra a MESMA Casa compartilhada). Quem roda depois confere o SQL
// registrado contra o Postgres.

const ids = JSON.parse(readFileSync(resolve(__dirname, ".artifacts/t3-ids.json"), "utf8"));
const ARQ_PROVA = resolve(__dirname, ".artifacts/escritas-E2.json");
const ENTE = ids.ente as string;
const TOKEN_SECRETARIA = ids.tokens.secretaria.json as string;

const MODELO_OFICIO = ids.e2.modelos.find((m: { chave: string }) => m.chave === "oficio_padrao");
const MODELO_CERTIDAO = ids.e2.modelos.find((m: { chave: string }) => m.chave === "certidao_padrao");

function registrarProva(linha: {
  escrita: string;
  metodo: string;
  url: string;
  status: number;
  id: string | null;
  tabela: string;
  sql_de_prova: string;
}) {
  appendFileSync(ARQ_PROVA, JSON.stringify(linha) + "\n");
}

function authHeader(tokenJson: string) {
  return { Authorization: `Bearer ${tokenJson}` };
}

// ESTADO COMPARTILHADO ENTRE OS TESTES DESTE ARQUIVO. playwright.config.ts nao usa describe.parallel
// e o harness roda 1 ARQUIVO = 1 worker (fullyParallel:false) — os testes daqui executam em SERIE, na
// ORDEM ESCRITA, mesmo atravessando describes diferentes. Por isso e seguro guardar aqui o id do
// documento que o fluxo feliz gera e reusa-lo nos testes de erro que vem depois (via API direta, ja
// que a UI nao tem como reabrir um documento existente por id — ver achado abaixo).
//
// ARMADILHA MEDIDA (nao re-descobrir): o Playwright DESCARTA o worker apos QUALQUER teste que falha e
// abre um novo — o modulo e' reimportado e estas tres variaveis voltam a null. Consequencia pratica:
// se um teste anterior falha, os dois testes que dependem de `docId` (o PATCH em 'emitido' e o replay
// de protocolar) aparecem no placar como SKIP ("-"), nao como falha. Ler esse "-" como benigno mascara
// que a cobertura caiu de 8 para 6. O skip esta certo (e' honesto, nao verde fabricado); o que muda e'
// como se le o placar: 8 passed e a unica leitura que prova cobertura cheia.
let docId: string | null = null;
let docLockVersionAposEditar: number | null = null;
let docLockVersionAposProtocolar: number | null = null;

test.describe.serial("E2 - Expediente: fluxo feliz (gerar -> editar -> protocolar)", () => {
  // Uma unica `page` para as 3 escritas: sao literalmente "a mesma sessao de composicao" no
  // vocabulario do mapa ("Continuação do fluxo... mesma página"). O componente FormularioPreenchimento
  // e' remontado (key=documento?.id) so' quando passa de null->id; enquanto o id nao muda ele NAO
  // remonta, entao o estado local (corpo/assunto digitados) sobrevive entre "gerar" e "editar" sem
  // qualquer reload.
  let page: Page;

  test.beforeAll(async ({ browser }) => {
    // baseURL explicito: esta `page` nasce de browser.newPage() (fora do fixture `page` do test runner),
    // que NAO herda o `use.baseURL` de playwright.config.ts sozinho — sem isso, os `page.request.get(...)`
    // com caminho relativo abaixo (persistencia pós-"gerar"/"editar") quebrariam ao tentar resolver URL.
    page = await browser.newPage({ baseURL: ids.base.frontend });
  });

  test.afterAll(async () => {
    await page.close();
  });

  test("Gerar documento a partir de modelo (Ofício padrão da Mesa) — caminho feliz", async () => {
    // next dev compila sob demanda: primeiro goto desta rota nesta corrida pode passar de 20s.
    await page.goto(ids.e2.url, { waitUntil: "domcontentloaded", timeout: 90_000 });

    // passo 1 de 3 — Modelo. O modelo so' existe porque e2e/t3/fixtures.sql inseriu (achado do mapa:
    // nao ha rota POST de documento_modelo).
    const botaoModelo = page.getByRole("button", { name: MODELO_OFICIO.nome, exact: true });
    await expect(botaoModelo).toBeVisible({ timeout: 30_000 });
    await botaoModelo.click();
    await expect(botaoModelo).toHaveAttribute("aria-pressed", "true");

    // passo 2 de 3 — Preenchimento. 'oficio_padrao' tem DOIS placeholders de proposito
    // ({{destinatario}} e {{assunto_detalhado}}, fixtures.sql) — o caminho feliz preenche OS DOIS
    // (deixar um de fora e' exatamente o caso de erro "campo {{ }} nao preenchido", testado abaixo
    // com o outro modelo).
    await page.locator("#assunto").fill("Convite para audiência pública sobre mobilidade urbana");
    await page.locator("#dados-chave-0").fill("destinatario");
    await page.locator("#dados-valor-0").fill("Secretaria Municipal de Obras");
    await page.getByRole("button", { name: "+ Adicionar campo" }).click();
    await page.locator("#dados-chave-1").fill("assunto_detalhado");
    await page.locator("#dados-valor-1").fill("a reforma da praça central do bairro");

    // a ESCRITA: POST /api/legislativo/documentos. Casar por PATHNAME exato (nao substring) — senao
    // um match solto tambem pegaria .../documentos/:id/protocolo mais adiante nesta mesma suite.
    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.request().method() === "POST" && new URL(r.url()).pathname === "/api/legislativo/documentos",
      ),
      page.getByRole("button", { name: "Gerar documento" }).click(),
    ]);
    expect(resp.status()).toBe(201);
    const corpoResp = await resp.json();
    docId = corpoResp["id"];
    expect(docId).toBeTruthy();
    expect(corpoResp["estado"]).toBe("rascunho");
    expect(corpoResp["modelo-id"]).toBe(MODELO_OFICIO.id);

    // a) a TELA diz que gravou.
    await expect(page.locator(".comando-ctx span")).toContainText("Documento gerado");
    await expect(page.locator('article.documento[role="document"]')).toBeVisible();
    // o merge aparece no corpo (destacarMerge sublinha por correspondencia exata de string).
    await expect(page.locator("p.corpo")).toContainText("Secretaria Municipal de Obras");
    await expect(page.locator("p.corpo")).toContainText("a reforma da praça central do bairro");
    // carimbo ainda "a reservar" — so' protocolar reserva o numero (Global Constraint "sem dado falso").
    await expect(page.locator("div.carimbo")).toHaveClass(/reservar/);
    await expect(page.locator("div.carimbo .num")).toHaveText("a reservar");

    // c) "F5 e o dado ainda esta la" — ACHADO: nao ha rota /expediente/[id]. Um page.reload({ waitUntil: "domcontentloaded" }) real
    // aqui devolveria a tela ao passo 1 (Modelo) e perderia a composicao inteira da MEMORIA (o id do
    // documento nunca entra na URL, so' vive em useState). Isso nao e falha da ESCRITA — o dado esta
    // gravado no Postgres — e' a tela que nao tem como reencontra-lo sozinha. Por isso a prova de
    // persistencia aqui e' um GET direto no MESMO endpoint que useDocumentoDetalhe chamaria se a tela
    // soubesse reabrir por id (equivalente funcional ao reload, sem fingir uma affordance que nao
    // existe). O reload de VERDADE fica reservado pro teste de "protocolar" abaixo, onde a tela
    // realmente re-busca algo por GET a cada montagem (o Livro do Protocolo Geral).
    const respGet = await page.request.get(`/api/legislativo/documentos/${docId}`, {
      headers: authHeader(TOKEN_SECRETARIA),
    });
    expect(respGet.status()).toBe(200);
    const docPersistido = await respGet.json();
    expect(docPersistido["estado"]).toBe("rascunho");
    expect(docPersistido["assunto"]).toBe("Convite para audiência pública sobre mobilidade urbana");
    expect(docPersistido["corpo"]).toContain("Secretaria Municipal de Obras");

    registrarProva({
      escrita: "Gerar documento a partir de modelo",
      metodo: "POST",
      url: "/api/legislativo/documentos",
      status: resp.status(),
      id: docId,
      tabela: "legislativo.documento",
      sql_de_prova: `SELECT id, estado, assunto, corpo, modelo_id, lock_version FROM legislativo.documento WHERE ente_id='${ENTE}'::uuid AND id='${docId}'::uuid; -- espera 1 linha, estado='rascunho', modelo_id='${MODELO_OFICIO.id}', corpo com {{destinatario}}->'Secretaria Municipal de Obras' e {{assunto_detalhado}}->'a reforma da praça central do bairro' ja substituidos.`,
    });
  });

  test("Editar rascunho — salvar alteração de assunto e corpo (caminho feliz)", async () => {
    expect(docId, "depende do documento gerado no teste anterior").toBeTruthy();

    // Continuação da MESMA página (nenhum goto/reload) — e' a unica forma de "editar" alcancavel pela
    // interface: o componente ja esta na fase de edicao (documento !== null), so' o rotulo do passo da
    // seção "Preenchimento" mudou de "passo 2 de 3" pra rotularEstadoDocumento(documento.estado)
    // ("Rascunho") — prova de que a tela sabe que o documento já existe, sem precisar de reload.
    await expect(page.locator("div.bloco-cabeca:has(#preench-titulo) .passo")).toHaveText("Rascunho");

    const corpoAtual = await page.locator("#corpo").inputValue();
    const corpoEditado = `${corpoAtual}\n\nPS: revisado pela secretaria antes do protocolo (T3-E2).`;
    await page.locator("#corpo").fill(corpoEditado);
    const assuntoEditado = "Convite para audiência pública sobre mobilidade urbana (revisado)";
    await page.locator("#assunto").fill(assuntoEditado);

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.request().method() === "PATCH" && new URL(r.url()).pathname === `/api/legislativo/documentos/${docId}`,
      ),
      page.getByRole("button", { name: "Salvar rascunho" }).click(),
    ]);
    expect(resp.status()).toBe(200);
    const corpoResp = await resp.json();
    expect(corpoResp["estado"]).toBe("rascunho");
    docLockVersionAposEditar = corpoResp["lock-version"];
    expect(docLockVersionAposEditar).toBeGreaterThan(0); // incrementou em relacao ao pos-geracao (0).

    // a) a TELA diz que gravou.
    await expect(page.locator(".comando-ctx span")).toContainText("Rascunho salvo");
    await expect(page.locator("#corpo")).toHaveValue(corpoEditado);
    await expect(page.locator("#assunto")).toHaveValue(assuntoEditado);

    // c) persistencia — mesmo achado do teste anterior (sem rota por id, GET direto substitui o F5).
    const respGet = await page.request.get(`/api/legislativo/documentos/${docId}`, {
      headers: authHeader(TOKEN_SECRETARIA),
    });
    expect(respGet.status()).toBe(200);
    const docPersistido = await respGet.json();
    expect(docPersistido["corpo"]).toBe(corpoEditado);
    expect(docPersistido["assunto"]).toBe(assuntoEditado);
    expect(docPersistido["lock-version"]).toBe(docLockVersionAposEditar);

    registrarProva({
      escrita: "Editar rascunho",
      metodo: "PATCH",
      url: `/api/legislativo/documentos/${docId}`,
      status: resp.status(),
      id: docId,
      tabela: "legislativo.documento",
      sql_de_prova: `SELECT corpo, assunto, lock_version, atualizado_em FROM legislativo.documento WHERE ente_id='${ENTE}'::uuid AND id='${docId}'::uuid; -- espera assunto='${assuntoEditado}', corpo terminando em 'T3-E2).', lock_version=${docLockVersionAposEditar}.`,
    });
  });

  test("Protocolar e numerar — caminho feliz + linha nova no Livro do Protocolo Geral", async () => {
    expect(docId, "depende do documento gerado/editado nos testes anteriores").toBeTruthy();

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === "POST" &&
          new URL(r.url()).pathname === `/api/legislativo/documentos/${docId}/protocolo`,
      ),
      page.getByRole("button", { name: "Protocolar e numerar" }).click(),
    ]);
    expect(resp.status()).toBe(200);
    const corpoResp = await resp.json();
    expect(corpoResp["estado"]).toBe("emitido");
    expect(corpoResp["protocolo-numero"]).not.toBeNull();
    expect(corpoResp["protocolo-ano"]).not.toBeNull();
    docLockVersionAposProtocolar = corpoResp["lock-version"];
    const numeroProtocolo: number = corpoResp["protocolo-numero"];
    const anoProtocolo: number = corpoResp["protocolo-ano"];
    const numeroFormatado = `${anoProtocolo}/${String(numeroProtocolo).padStart(5, "0")}`;

    // a) a TELA diz que gravou.
    await expect(page.locator(".comando-ctx span")).toContainText("Documento protocolado");
    await expect(page.locator("div.carimbo")).not.toHaveClass(/reservar/);
    await expect(page.locator("div.carimbo .num")).toHaveText(numeroFormatado);
    // bloqueado=true: os botoes de escrita SOMEM do DOM (nao so' desabilitam) — mesma disciplina de
    // formulario-parecer.tsx. E' o mecanismo que torna "protocolar duas vezes" numa unica aba
    // estruturalmente inalcancavel (acha do mapa) — verificado aqui, exercitado por API mais abaixo.
    await expect(page.getByRole("button", { name: "Salvar rascunho" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Protocolar e numerar" })).toHaveCount(0);

    // c) F5 de VERDADE aqui (ao contrario de "gerar"/"editar" acima): o Livro do Protocolo Geral e'
    // buscado por GET a CADA montagem (useProtocoloLivro), independente do estado efêmero da
    // composicao — entao um reload real prova persistencia pela PROPRIA interface, sem precisar de
    // GET direto por fora.
    await page.reload({ waitUntil: "domcontentloaded", timeout: 90_000 });
    const linhaLivro = page.locator("table.protocolo tbody tr", { hasText: numeroFormatado });
    await expect(linhaLivro).toBeVisible({ timeout: 30_000 });
    await expect(linhaLivro).toContainText("Convite para audiência pública sobre mobilidade urbana (revisado)");

    registrarProva({
      escrita: "Protocolar e numerar",
      metodo: "POST",
      url: `/api/legislativo/documentos/${docId}/protocolo`,
      status: resp.status(),
      id: docId,
      tabela: "legislativo.documento + legislativo.protocolo_geral",
      sql_de_prova: `SELECT d.estado, d.protocolo_geral_id, p.numero, p.ano, p.objeto_tipo, p.objeto_id FROM legislativo.documento d JOIN legislativo.protocolo_geral p ON p.ente_id=d.ente_id AND p.id=d.protocolo_geral_id WHERE d.ente_id='${ENTE}'::uuid AND d.id='${docId}'::uuid; -- espera estado='emitido', p.numero=${numeroProtocolo}, p.ano=${anoProtocolo}, p.objeto_tipo='documento', p.objeto_id=d.id. Gapless: SELECT numero, numero = row_number() OVER (ORDER BY numero) AS esperado FROM legislativo.protocolo_geral WHERE ente_id='${ENTE}'::uuid AND ano=${anoProtocolo} ORDER BY numero; -- toda linha com numero=esperado.`,
    });
  });
});

test.describe("E2 - Expediente: casos de erro", () => {
  test("[ACHADO] Gerar documento — assunto vazio é barrado no CLIENTE, sem round-trip HTTP", async ({ page }) => {
    // Validacao client-side (aoClicarGerar, formulario-preenchimento.tsx:103-110) roda ANTES do POST —
    // o mapa documenta as 3 camadas (cliente + Malli na borda + logica de dominio), mas pela interface so'
    // a primeira e alcancavel sem burlar o form. Provamos que NENHUM POST sai (nao so' que a mensagem
    // aparece) — senao "afirmar que o botao existe" vira expect(true) disfarcado.
    await page.goto(ids.e2.url, { waitUntil: "domcontentloaded", timeout: 90_000 });
    const botaoModelo = page.getByRole("button", { name: MODELO_OFICIO.nome, exact: true });
    await expect(botaoModelo).toBeVisible({ timeout: 30_000 });
    await botaoModelo.click();

    let houvePost = false;
    page.on("request", (r) => {
      if (r.method() === "POST" && new URL(r.url()).pathname === "/api/legislativo/documentos") houvePost = true;
    });

    // #assunto fica vazio de proposito.
    await page.getByRole("button", { name: "Gerar documento" }).click();

    // NAO usar page.getByRole("alert") aqui: o Next injeta um <div id="__next-route-announcer__"
    // role="alert" em TODA pagina, entao o role sozinho casa 2 elementos e o expect morre em strict
    // mode antes de medir qualquer coisa. O alerta do formulario e' `p.form-erro[role=alert]`
    // (formulario-preenchimento.tsx:129 e :194) — so' um dos dois esta montado por vez (fase de
    // composicao vs. fase de edicao), entao `p.form-erro` e' unico em tela.
    await expect(page.locator('p.form-erro[role="alert"]')).toHaveText(
      "Preencha o assunto antes de gerar o documento.",
    );
    // janela curta pra dar tempo de QUALQUER request assincrona aparecer antes de afirmar a ausencia.
    await page.waitForTimeout(500);
    expect(houvePost).toBe(false);
  });

  test("Gerar documento — campo {{ }} sem valor correspondente é recusado com 400 genérico", async ({ page }) => {
    // 'certidao_padrao' tem TRES placeholders ({{interessado}}, {{referencia}}, {{data_referencia}}).
    // Preenchemos so' DOIS de proposito — o terceiro fica sem par em `dados`, e
    // legislativo.logic/renderizar-documento lanca :validacao/invalido (get dados chave) -> 400.
    await page.goto(ids.e2.url, { waitUntil: "domcontentloaded", timeout: 90_000 });
    const botaoModelo = page.getByRole("button", { name: MODELO_CERTIDAO.nome, exact: true });
    await expect(botaoModelo).toBeVisible({ timeout: 30_000 });
    await botaoModelo.click();

    await page.locator("#assunto").fill("Certidão de vinculação — teste de campo faltante (T3-E2)");
    await page.locator("#dados-chave-0").fill("interessado");
    await page.locator("#dados-valor-0").fill("João da Silva");
    await page.getByRole("button", { name: "+ Adicionar campo" }).click();
    await page.locator("#dados-chave-1").fill("referencia");
    await page.locator("#dados-valor-1").fill("Processo 123/2026");
    // {{data_referencia}} deliberadamente SEM par — nenhuma 3ª linha adicionada.

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.request().method() === "POST" && new URL(r.url()).pathname === "/api/legislativo/documentos",
      ),
      page.getByRole("button", { name: "Gerar documento" }).click(),
    ]);

    expect(resp.status()).toBe(400);
    // mensagem GENÉRICA por desenho (interceptors.clj/erro: corpo nunca vaza qual campo faltou) — a
    // tela so' repassa `corpoErro?.erro`, entao o texto exato e o mesmo do backend.
    // mesmo cuidado do teste acima: role=alert casa tambem o route-announcer do Next.
    await expect(page.locator('p.form-erro[role="alert"]')).toContainText("requisicao invalida");
    // POR QUE o 400 e' atribuivel ao placeholder faltante, e nao a borda Malli recusando por outro
    // motivo: o payload aqui tem exatamente o MESMO formato que o do fluxo feliz acima (modelo-id +
    // assunto nao-vazio + 2 pares chave/valor), que devolve 201. A unica diferenca e' que este modelo
    // tem 3 placeholders. Prova negativa medida no Postgres depois desta corrida:
    // SELECT count(*) FROM legislativo.documento WHERE assunto LIKE 'Certidão de vinculação%'; -> 0.
    // O 400 nao gravou linha nenhuma.
    // nenhum documento foi criado: a fase continua "composicao" (documento === null), o formulario de
    // Modelo/Assunto/Dados segue montado (nao vira o form de Corpo).
    await expect(page.locator("#corpo")).toHaveCount(0);
  });

  test(
    "[ACHADO] Editar rascunho após emitido — a UI remove o botão do DOM; o backend também recusa (PATCH direto)",
    async ({ request }) => {
      // Estruturalmente inalcancavel NUMA aba so: assim que estado vira 'emitido', bloqueado=true tira
      // "Salvar rascunho" do DOM (verificado acima, no teste de "Protocolar"). O mapa so' alcanca este
      // guard com DUAS ABAS na mesma sessao de rascunho — e isso exige reabrir por id, rota que NAO
      // existe (mesmo achado do teste "Gerar documento"). Por isso exercitamos o GUARD DO BACKEND
      // direto: o mesmo PATCH que a 2ª aba mandaria, contra o documento que o describe.serial ACIMA ja
      // deixou 'emitido'. Isto documenta o comportamento REAL (400, nao 500) sem fingir um clique que a
      // tela nao oferece.
      test.skip(!docId, "depende do documento protocolado no describe.serial anterior");

      const resp = await request.patch(`/api/legislativo/documentos/${docId}`, {
        headers: { ...authHeader(TOKEN_SECRETARIA), "Content-Type": "application/json" },
        data: {
          "lock-version": docLockVersionAposProtocolar,
          corpo: "tentativa de editar um documento ja emitido (T3-E2)",
          assunto: "não deveria gravar",
        },
      });
      expect(resp.status()).toBe(400);
      const corpo = await resp.json();
      expect(corpo.erro).toBe("requisicao invalida");

      // prova negativa: o corpo NÃO mudou no banco.
      const respGet = await request.get(`/api/legislativo/documentos/${docId}`, {
        headers: authHeader(TOKEN_SECRETARIA),
      });
      const docAtual = await respGet.json();
      expect(docAtual["corpo"]).not.toContain("não deveria gravar");
      expect(docAtual["estado"]).toBe("emitido");
    },
  );

  test("Editar rascunho — conflito de lock_version é recusado com 400 (duas edições concorrentes)", async ({
    page,
    request,
  }) => {
    // CORRIGIDO (REVISAO.md #E2/1): antes gerava+editava DUAS vezes inteiramente por API (zero clique) —
    // isso era T2, nao T3. Agora: gera o documento PELA INTERFACE (doc proprio, pra nao competir com o
    // que o describe.serial acima ja deixou 'emitido') e faz a 1ª edicao — a aba que "chegou primeiro",
    // lock 0->1 — TAMBEM pela interface. So' a 2ª edicao (a aba "atrasada", que nunca recebeu o resultado
    // da 1ª e ainda manda o lock ANTIGO) vai direto por API: nao ha rota para reabrir um documento
    // existente por id (achado do mapa, ja documentado acima), entao uma 2ª aba de verdade nao tem como
    // carregar o MESMO estado pre-edicao — reproduzi-la e' genuinamente inalcancavel por clique.
    await page.goto(ids.e2.url, { waitUntil: "domcontentloaded", timeout: 90_000 });
    const botaoModelo = page.getByRole("button", { name: MODELO_OFICIO.nome, exact: true });
    await expect(botaoModelo).toBeVisible({ timeout: 30_000 });
    await botaoModelo.click();
    await page.locator("#assunto").fill("Documento para teste de concorrência (T3-E2)");
    await page.locator("#dados-chave-0").fill("destinatario");
    await page.locator("#dados-valor-0").fill("Setor de Compras");
    await page.getByRole("button", { name: "+ Adicionar campo" }).click();
    await page.locator("#dados-chave-1").fill("assunto_detalhado");
    await page.locator("#dados-valor-1").fill("aquisição de material de expediente");

    const [respGerar] = await Promise.all([
      page.waitForResponse(
        (r) => r.request().method() === "POST" && new URL(r.url()).pathname === "/api/legislativo/documentos",
      ),
      page.getByRole("button", { name: "Gerar documento" }).click(),
    ]);
    expect(respGerar.status()).toBe(201);
    const docConcorrencia = await respGerar.json();
    const idConcorrencia = docConcorrencia["id"];
    expect(docConcorrencia["lock-version"]).toBe(0);

    // 1ª edição (a aba que "chegou primeiro"): PELA INTERFACE — lock 0 -> sucesso, vai pra 1.
    await page.locator("#assunto").fill("editado pela primeira aba");
    const [respEditar1] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === "PATCH" &&
          new URL(r.url()).pathname === `/api/legislativo/documentos/${idConcorrencia}`,
      ),
      page.getByRole("button", { name: "Salvar rascunho" }).click(),
    ]);
    expect(respEditar1.status()).toBe(200);
    expect((await respEditar1.json())["lock-version"]).toBe(1);

    // 2ª edição (a aba "atrasada"): ainda manda lock 0 -> 0 linhas afetadas no WHERE -> 400.
    // [NAO-E-T3] — sem rota de reabrir por id, uma 2ª aba real nao tem como existir com o lock velho;
    // simulada pelo mesmo PATCH que ela mandaria, direto por API.
    const respEditar2 = await request.patch(`/api/legislativo/documentos/${idConcorrencia}`, {
      headers: { ...authHeader(TOKEN_SECRETARIA), "Content-Type": "application/json" },
      data: { "lock-version": 0, assunto: "editado pela segunda aba (deveria falhar)" },
    });
    expect(respEditar2.status()).toBe(400);
    expect((await respEditar2.json()).erro).toBe("requisicao invalida");

    // prova: o assunto que VENCEU foi o da 1ª edição (pela interface), não o da 2ª.
    const respGet = await request.get(`/api/legislativo/documentos/${idConcorrencia}`, {
      headers: authHeader(TOKEN_SECRETARIA),
    });
    const docFinal = await respGet.json();
    expect(docFinal["assunto"]).toBe("editado pela primeira aba");
    expect(docFinal["lock-version"]).toBe(1);

    registrarProva({
      escrita: "Editar rascunho (caso de erro: conflito de lock_version)",
      metodo: "PATCH",
      url: `/api/legislativo/documentos/${idConcorrencia}`,
      status: respEditar2.status(),
      id: idConcorrencia,
      tabela: "legislativo.documento",
      sql_de_prova: `SELECT assunto, lock_version FROM legislativo.documento WHERE ente_id='${ENTE}'::uuid AND id='${idConcorrencia}'::uuid; -- espera assunto='editado pela primeira aba', lock_version=1 (a 2ª edicao com lock stale NAO gravou).`,
    });
  });

  test(
    "Protocolar duas vezes (documento fora de rascunho) — recusado com 400; numeração gapless preservada",
    async ({ request }) => {
      // CORRIGIDO (REVISAO.md #E2/1): antes criava e protocolava um documento NOVO inteiramente por API
      // (zero clique) so' pra testar o replay — isso era T2, nao T3. Agora: reusa o MESMO documento que o
      // describe.serial ACIMA ja protocolou PELA INTERFACE (docId + docLockVersionAposProtocolar, reais,
      // do clique em "Protocolar e numerar" mais acima) — so' a 2ª tentativa (o replay) vai por API.
      // [NAO-E-T3] genuinamente inalcancavel por clique: assim que o estado vira 'emitido', o botao
      // "Protocolar e numerar" some do DOM (verificado no proprio teste "Protocolar e numerar" acima,
      // linhas 223-224) — nao sobra o que clicar duas vezes numa aba so'. `db/documento.clj/emitir!` so'
      // aceita estado='rascunho'; a chamada de replay cai em 400, com ROLLBACK da tx inteira — o numero
      // que kernel/sequencial reservou nunca e commitado (repositorio.clj:685-690).
      test.skip(!docId, "depende do documento protocolado no describe.serial anterior");

      const respAntes = await request.get(`/api/legislativo/documentos/${docId}`, {
        headers: authHeader(TOKEN_SECRETARIA),
      });
      const docAntes = await respAntes.json();
      expect(docAntes["estado"]).toBe("emitido");
      const numeroOriginal = docAntes["protocolo-numero"];
      const anoOriginal = docAntes["protocolo-ano"];

      // 2ª tentativa: mesmo lock-version que o retorno da 1ª protocolagem real (um cliente ingênuo
      // replicando o clique) — o guard e' por ESTADO, não só por lock_version (achado do mapa: mesmo com
      // lock "certo", recusa).
      const respProtocolar2 = await request.post(`/api/legislativo/documentos/${docId}/protocolo`, {
        headers: { ...authHeader(TOKEN_SECRETARIA), "Content-Type": "application/json" },
        data: { "lock-version": docLockVersionAposProtocolar },
      });
      expect(respProtocolar2.status()).toBe(400);
      expect((await respProtocolar2.json()).erro).toBe("requisicao invalida");

      // prova de gapless: o numero/ano NÃO mudaram (2ª tentativa não reservou nem consumiu outro número).
      const respGet = await request.get(`/api/legislativo/documentos/${docId}`, {
        headers: authHeader(TOKEN_SECRETARIA),
      });
      const docFinal = await respGet.json();
      expect(docFinal["protocolo-numero"]).toBe(numeroOriginal);
      expect(docFinal["protocolo-ano"]).toBe(anoOriginal);

      registrarProva({
        escrita: "Protocolar e numerar (caso de erro: protocolar duas vezes / fora de rascunho)",
        metodo: "POST",
        url: `/api/legislativo/documentos/${docId}/protocolo`,
        status: respProtocolar2.status(),
        id: docId,
        tabela: "legislativo.documento + legislativo.protocolo_geral",
        sql_de_prova: `SELECT d.estado, p.numero, p.ano FROM legislativo.documento d JOIN legislativo.protocolo_geral p ON p.ente_id=d.ente_id AND p.id=d.protocolo_geral_id WHERE d.ente_id='${ENTE}'::uuid AND d.id='${docId}'::uuid; -- espera d.estado='emitido', p.numero=${numeroOriginal}, p.ano=${anoOriginal} (inalterado pela 2ª tentativa). Gapless: SELECT numero, numero = row_number() OVER (ORDER BY numero) AS esperado FROM legislativo.protocolo_geral WHERE ente_id='${ENTE}'::uuid AND ano=${anoOriginal} ORDER BY numero; -- nenhum buraco, nenhuma duplicata.`,
      });
    },
  );

  // "campo {{ }} não preenchido no momento de protocolar" (caso de erro listado no mapa) NÃO SE APLICA
  // a este endpoint — achado já registrado em mapa-E2.json: renderizar-documento só roda em gerar!;
  // protocolar-documento! não faz merge de novo (o corpo já está congelado como string final desde a
  // geração). Não escrevemos um teste pra isso: seria fingir uma condição de erro que o próprio backend
  // não pode produzir neste endpoint.
});
