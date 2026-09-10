import { test, expect } from "@playwright/test";
import { readFileSync, appendFileSync } from "node:fs";
import { resolve } from "node:path";

// GRUPO E4 — "O parecer" (servidor + vereador). Le e2e/t3/mapa-E4.json (o mapa das escritas, os
// achados e os casos de erro ja medidos ao vivo) e e2e/t3/.artifacts/t3-ids.json (os ids REAIS
// deixados pelo preparar.mjs — nenhum UUID aqui e inventado). As 4 escritas do grupo:
//   1) Editar parecer (rascunho)               — PATCH /legislativo/pareceres/:id
//   2) Emitir parecer (secretaria)              — POST  /legislativo/pareceres/:id/emissao
//   3) Emitir "meu parecer" (vereador, 2 toques) — POST  /meu/pareceres/:id/emissao
//   4) Dar ciencia (vereador)                    — POST  /meu/ciencias
//
// Dois pareceres sustentam o grupo (ids resolvidos em runtime de t3-ids.json / e4 — nao ha UUID
// escrito a mao aqui):
//   ID_EDITAVEL  = e4.parecerEditavelId (em_elaboracao, relator = :presidente) — nunca transiciona a
//     terminal pelo gatilho 'emitir' (o template de demo so mapeia aguardando_assinatura->aprovado),
//     entao serve pra provar a emissao feliz E a dupla-emissao SEM crashar nada. E' REUSAVEL: nenhum
//     teste o leva a estado terminal.
//   ID_AGUARDANDO = e4.parecerAguardandoAssinaturaId (aguardando_assinatura, relator = :vereador) —
//     comeca com voto_relator NULO (situacao 'sem-voto' na tela de assinatura). So DEPOIS que a
//     secretaria emite sobre ele e' que ele fica com voto setado E vira 'aprovado' (terminal) NO
//     MESMO clique — e e' exatamente essa combinacao que reproduz o achado central do grupo: a tela
//     de assinatura nunca olha o estado terminal do parecer, entao reoferece "Revisar e assinar" e o
//     POST bate no trigger de imutabilidade (500 opaco). NAO aponto as duas emissoes pro MESMO
//     parecer: reusar o mesmo id pros dois lados destruiria a chance de mostrar a emissao feliz em
//     separado da colisao.
//
// ESTE PARECER E' CONSUMIDO EM CADA CORRIDA (vira terminal e o trigger trava qualquer UPDATE
// depois), e NAO EXISTE rota HTTP que crie parecer. Por isso e2e/t3/fixtures.sql ganhou um bloco
// que CLONA a linha da semente quando nao ha mais alvo em 'aguardando_assinatura' para o relator
// :vereador — e' o que torna E4 repetivel. Sempre rodar ./e2e/t3/preparar.sh antes de uma corrida
// cheia; sem isso, a 2a corrida seguida falha nos 2 ultimos testes por falta de alvo.
//
// [CORRECAO DO MAPA] mapa-E4.json afirma que a tela (interno)/parecer/[id] "abre normalmente pra um
// token 'vereador' e so a escrita falha com 403". FALSO, medido nesta sessao: o interceptor
// `exige-papel "secretario"` esta na rota :get tambem (in.clj:523), entao o GET ja e' 403 e o
// formulario nunca monta. O achado verdadeiro (ausencia de guard client-side) sobrevive, com outra
// forma observavel — ver o teste de papeis trocados abaixo.
const ids = JSON.parse(readFileSync(resolve(__dirname, ".artifacts/t3-ids.json"), "utf8"));
// mapa-E4.json (o dossiê original de achados) fica só como referência de leitura — cada achado
// citado nos comentários abaixo tem a linha correspondente lá, pra quem quiser conferir a fonte.

const BASE = ids.base.frontend as string;
const ENTE = ids.ente as string;
const TSEC = ids.tokens.secretaria.url as string; // papel 'secretario'
const TVER = ids.tokens.vereador.url as string; // papel 'vereador' — e' o relator de ID_AGUARDANDO
const TVER_JSON = ids.tokens.vereador.json as string; // o MESMO token, cru — vai no Authorization: Bearer
const BACKEND = ids.base.backend as string; // http://localhost:8888 — usado só onde a tela não alcança a rota

const ID_EDITAVEL: string = ids.e4.parecerEditavelId; // em_elaboracao, relator = :presidente
const ID_AGUARDANDO: string = ids.e4.parecerAguardandoAssinaturaId; // aguardando_assinatura, relator = :vereador
const ID_INEXISTENTE = "00000000-0000-0000-0000-000000000000"; // formato valido, nao existe no ente

function urlParecer(id: string, token: string) {
  return `${BASE}/parecer/${id}?token=${token}`;
}
function urlAssinar(id: string, token: string) {
  return `${BASE}/parecer/${id}/assinar?token=${token}`;
}
function urlVereadorHome(token: string) {
  return `${BASE}/vereador?token=${token}`;
}

const ARTEFATO_ESCRITAS = resolve(__dirname, ".artifacts/escritas-E4.json");

// Append de 1 linha JSON por escrita bem-sucedida (a prova via SQL fica pro Daouda rodar depois —
// eu so' registro a query certa, com o ente_id no WHERE, como o briefing pediu).
function registrarEscrita(linha: Record<string, unknown>) {
  appendFileSync(ARTEFATO_ESCRITAS, JSON.stringify(linha) + "\n");
}

test.describe.serial("E4 - O parecer (servidor + vereador)", () => {
  // ---------------------------------------------------------------------------------------------
  // 1) Editar parecer (rascunho) — caminho feliz, secretaria, ID_EDITAVEL
  // ---------------------------------------------------------------------------------------------
  test("Editar parecer (rascunho) — caminho feliz", async ({ page }) => {
    await page.goto(urlParecer(ID_EDITAVEL, TSEC), { waitUntil: "domcontentloaded", timeout: 90_000 });
    // [REVISAO #1] role="status" NAO recebe nome do conteudo (nameFrom: author) — o locator antigo
    // getByRole("status", {name: "Carregando…"}) nunca casava, entao toHaveCount(0) passava sempre,
    // inclusive com a pagina travada carregando. Espera o campo real do formulario em vez disso.
    await expect(page.getByLabel("Relatório")).toBeVisible({ timeout: 30_000 });

    const relatorio = "Relatório de teste E2E — grupo E4, T1";
    const analise = "Análise de teste E2E — grupo E4, T1";
    await page.getByLabel("Relatório").fill(relatorio);
    await page.getByLabel("Análise").fill(analise);

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/legislativo/pareceres/${ID_EDITAVEL}`) && r.request().method() === "PATCH",
        { timeout: 30_000 },
      ),
      page.getByRole("button", { name: "Salvar rascunho" }).click(),
    ]);

    // (a) a tela diz que gravou
    await expect(page.locator(".comando-ctx")).toContainText("Rascunho salvo");
    // (b) a escrita saiu de fato
    expect(resp.status()).toBe(200);

    // (c) F5 — o dado ainda esta la (mesmo token, mesma URL)
    await page.reload({ waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByLabel("Relatório")).toHaveValue(relatorio, { timeout: 30_000 });
    await expect(page.getByLabel("Análise")).toHaveValue(analise);

    registrarEscrita({
      escrita: "Editar parecer (rascunho)",
      metodo: "PATCH",
      url: `/legislativo/pareceres/${ID_EDITAVEL}`,
      status: resp.status(),
      id: ID_EDITAVEL,
      tabela: "legislativo.parecer_texto_versao",
      sql_de_prova: `SELECT id, numero_versao, estado_versao, texto_inline, criado_em FROM legislativo.parecer_texto_versao WHERE ente_id='${ENTE}' AND parecer_id='${ID_EDITAVEL}' ORDER BY numero_versao DESC, criado_em DESC LIMIT 1; -- espera texto_inline contendo '${relatorio}' e '${analise}'`,
    });
  });

  // ---------------------------------------------------------------------------------------------
  // 2) Emitir parecer (secretaria) — caminho feliz, ID_EDITAVEL fica NAO-terminal (em_elaboracao
  //    nao tem transicao de template pro gatilho 'emitir' — achado do mapa). Isso e' o que deixa o
  //    id livre pra T3 repetir a emissao sem crashar.
  // ---------------------------------------------------------------------------------------------
  test("Emitir parecer (secretaria) — caminho feliz, permanece não-terminal", async ({ page }) => {
    await page.goto(urlParecer(ID_EDITAVEL, TSEC), { waitUntil: "domcontentloaded", timeout: 90_000 });
    // [REVISAO #1] mesma correção do T1 — role="status" não recebe nome do conteúdo.
    await expect(page.getByLabel("Relatório")).toBeVisible({ timeout: 30_000 });

    // O input do radio é visualmente escondido (`.voto input { opacity:0; pointer-events:none }`,
    // formulario-parecer.css) — o clique REAL do usuário é no <label for="voto-...">, que o browser
    // encaminha pro input associado (delegação nativa de label, não afetada por pointer-events do
    // alvo). `.check()` direto no role=radio falharia a checagem de "receives events" do Playwright.
    await page.locator('label[for="voto-favoravel"]').click();

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/legislativo/pareceres/${ID_EDITAVEL}/emissao`) && r.request().method() === "POST",
        { timeout: 30_000 },
      ),
      page.getByRole("button", { name: "Emitir parecer" }).click(),
    ]);

    await expect(page.locator(".comando-ctx")).toContainText("Parecer emitido");
    expect(resp.status()).toBe(200);

    // F5: o voto persistiu E o botao "Emitir parecer" continua ali — prova viva de que o template
    // nao transicionou este parecer a terminal (achado do mapa: emissao a partir de em_elaboracao e'
    // best-effort e fica no mesmo estado).
    await page.reload({ waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("radio", { name: "Favorável", exact: true })).toBeChecked({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Emitir parecer" })).toBeVisible();

    registrarEscrita({
      escrita: "Emitir parecer (secretaria)",
      metodo: "POST",
      url: `/legislativo/pareceres/${ID_EDITAVEL}/emissao`,
      status: resp.status(),
      id: ID_EDITAVEL,
      tabela: "legislativo.pareceres",
      sql_de_prova: `SELECT estado, voto_relator, lock_version FROM legislativo.pareceres WHERE ente_id='${ENTE}' AND id='${ID_EDITAVEL}'; -- espera voto_relator='favoravel', estado ainda 'em_elaboracao' (nao transicionou)`,
    });
  });

  // ---------------------------------------------------------------------------------------------
  // [ACHADO] 3) Emitir parecer duas vezes NAO E BLOQUEADO quando a 1a emissao nao leva o parecer a
  // um estado terminal — a tela reoferece "Emitir parecer" e o backend aceita de novo, em silencio
  // (mapa: "NAO TRATADO quando a 1a emissao nao transiciona"). Documento o comportamento real, nao
  // finjo que ha' um 409.
  // ---------------------------------------------------------------------------------------------
  test("[ACHADO] Emitir parecer (secretaria) duas vezes — não é bloqueado", async ({ page }) => {
    await page.goto(urlParecer(ID_EDITAVEL, TSEC), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("button", { name: "Emitir parecer" })).toBeVisible({ timeout: 30_000 });
    // pre-condicao do achado: o botao de emitir AINDA esta disponivel, mesmo ja tendo emitido em T2.
    await expect(page.getByRole("radio", { name: "Favorável", exact: true })).toBeChecked();

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/legislativo/pareceres/${ID_EDITAVEL}/emissao`) && r.request().method() === "POST",
        { timeout: 30_000 },
      ),
      page.getByRole("button", { name: "Emitir parecer" }).click(),
    ]);

    // [ACHADO] isto DEVERIA ser um 409 (ja emitido) — o backend aceita de novo com 200.
    expect(resp.status()).toBe(200);
    await expect(page.locator(".comando-ctx")).toContainText("Parecer emitido");

    registrarEscrita({
      escrita: "Emitir parecer (secretaria) — 2a emissão [ACHADO: não bloqueado]",
      metodo: "POST",
      url: `/legislativo/pareceres/${ID_EDITAVEL}/emissao`,
      status: resp.status(),
      id: ID_EDITAVEL,
      tabela: "legislativo.pareceres",
      sql_de_prova: `SELECT estado, voto_relator, lock_version FROM legislativo.pareceres WHERE ente_id='${ENTE}' AND id='${ID_EDITAVEL}'; -- espera lock_version incrementado de novo (2a escrita real), estado ainda nao-terminal`,
    });
  });

  // ---------------------------------------------------------------------------------------------
  // [ACHADO] 4) Papéis trocados em (interno)/parecer/[id]. A versão anterior deste teste vinha do
  // mapa-E4.json, que afirma "o formulário abre normalmente pra um token 'vereador'; só a escrita
  // falha com 403". MEDIDO NA FONTE, isso é FALSO — e era a causa da falha de 30,7 s (o teste
  // esperava 30 s por um <textarea> que nunca ia renderizar):
  //
  //   $ curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer <TVER>" \
  //       http://localhost:8888/legislativo/pareceres/50a690c2-...  ->  403 {"erro":"autorizacao negada"}
  //
  // O gate `papel` (exige-papel "secretario") está na rota :get TAMBÉM, não só no :patch —
  // legislativo/diplomat/http/in.clj:523 e :525 recebem o MESMO interceptor. Logo o formulário
  // nunca chega a montar.
  //
  // O que continua verdadeiro do mapa (e é o achado real): NÃO existe guard de papel client-side em
  // (interno)/layout.tsx — ele só injeta AuthProvider+TemaProvider, sem nada como o GuardVereador de
  // (vereador)/layout.tsx. A consequência observável é dupla, e é isso que este teste afirma:
  //   (a) o vereador recebe o CHASSI INTERNO da secretaria (topo institucional, nav "Painéis da
  //       Mesa/Tramitação/...", e o ator hardcoded "Rita Campos · Servidora legislativa") em vez de
  //       um "Acesso restrito";
  //   (b) o 403 é renderizado pelo MESMO ramo de erro que um 404 — "Não foi possível carregar este
  //       parecer" — então "você não tem o papel" fica indistinguível de "este parecer não existe"
  //       (compare com o teste seguinte, que é um 404 de verdade e mostra a MESMA tela).
  // O gate de escrita do backend fica provado por HTTP direto, porque pela interface ele é
  // inalcançável: sem formulário, não há clique em "Salvar rascunho".
  // ---------------------------------------------------------------------------------------------
  test("[ACHADO] Papéis trocados em /parecer/:id — sem guard client-side, e o 403 vira a tela de 404", async ({
    page,
  }) => {
    // a promessa ANTES do goto: o GET do editor sai junto com a navegação.
    const esperaGet = page.waitForResponse(
      (r) => r.url().includes(`/api/legislativo/pareceres/${ID_EDITAVEL}`) && r.request().method() === "GET",
      { timeout: 30_000 },
    );
    await page.goto(urlParecer(ID_EDITAVEL, TVER), { waitUntil: "domcontentloaded", timeout: 90_000 });
    const respGet = await esperaGet;

    // [ACHADO] o GET do editor da secretaria é 403 para um token 'vereador' — o mapa dizia que a
    // tela abria; não abre.
    expect(respGet.status()).toBe(403);

    // (a) sem guard client-side: o chassi INTERNO renderiza mesmo assim, com o ator da secretaria.
    await expect(page.getByRole("navigation", { name: "Navegação interna" })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("Servidora legislativa")).toBeVisible();
    // contraste: o app do vereador teria mostrado isto (GuardVereador, (vereador)/layout.tsx:47).
    await expect(page.getByRole("heading", { name: "Acesso restrito" })).toHaveCount(0);

    // (b) o 403 cai no ramo genérico de erro — a mesma tela que o teste seguinte vê num 404 real.
    await expect(page.getByRole("heading", { name: "Não foi possível carregar este parecer" })).toBeVisible();

    // sem formulário, a escrita é inalcançável pela interface.
    await expect(page.getByLabel("Relatório")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Salvar rascunho" })).toHaveCount(0);

    // O gate de ESCRITA existe e é o mesmo — provado direto no backend, já que a tela não chega lá.
    // Medido também no banco fora do teste: `SELECT count(*) FROM legislativo.parecer_texto_versao
    // WHERE parecer_id='<ID_EDITAVEL>'` fica IGUAL antes e depois (403 nega antes do INSERT).
    const respPatch = await page.request.patch(`${BACKEND}/legislativo/pareceres/${ID_EDITAVEL}`, {
      headers: { Authorization: `Bearer ${TVER_JSON}`, "Content-Type": "application/json" },
      data: { relatorio: "Tentativa de escrita com papel trocado", analise: "não deve gravar" },
    });
    expect(respPatch.status()).toBe(403);
    expect(await respPatch.json()).toMatchObject({ erro: "autorizacao negada" });
  });

  // ---------------------------------------------------------------------------------------------
  // Editar parecer — parecer inexistente / outro tenant: TRATADO no backend (404 antes de escrever),
  // a tela cai no ramo de erro ja' no GET inicial e nunca chega a mostrar o formulario.
  // ---------------------------------------------------------------------------------------------
  test("Editar parecer — parecer inexistente cai em erro antes do formulário (404)", async ({ page }) => {
    await page.goto(urlParecer(ID_INEXISTENTE, TSEC), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("heading", { name: "Não foi possível carregar este parecer" })).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByLabel("Relatório")).toHaveCount(0);
  });

  // ---------------------------------------------------------------------------------------------
  // 5) Emitir "meu parecer" (vereador) — ANTES de qualquer emissão da secretaria sobre ID_AGUARDANDO,
  // voto_relator é NULO. `deriveEstadoAssinatura` cai em 'sem-voto': o aviso aparece e o CTA "Revisar
  // e assinar" nem é oferecido — TRATADO pela tela (mapa). Este teste é só leitura (nenhuma escrita
  // é possível por aqui hoje, e é exatamente isso que ele prova).
  // ---------------------------------------------------------------------------------------------
  test("Emitir 'meu parecer' (vereador) — sem voto do relator, CTA não é oferecido", async ({ page }) => {
    await page.goto(urlAssinar(ID_AGUARDANDO, TVER), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(
      page.getByText("Ainda falta registrar a conclusão (voto) do relator no editor"),
    ).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Revisar e assinar" })).toHaveCount(0);
  });

  // ---------------------------------------------------------------------------------------------
  // 6) Emitir parecer (secretaria) sobre ID_AGUARDANDO — aqui SIM o template tem a transição
  // aguardando_assinatura -> aprovado pro gatilho 'emitir' (achado do mapa), então este clique
  // TRANSICIONA o parecer a terminal de verdade, e' o caminho feliz "com transição real" (diferente
  // de T2, que ficava no lugar). Isso tambem prepara o terreno pro achado central em T6.
  // ---------------------------------------------------------------------------------------------
  test("Emitir parecer (secretaria) — caminho feliz com transição real a terminal", async ({ page }) => {
    await page.goto(urlParecer(ID_AGUARDANDO, TSEC), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("button", { name: "Emitir parecer" })).toBeVisible({ timeout: 30_000 });

    await page.locator('label[for="voto-contrario"]').click();

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/legislativo/pareceres/${ID_AGUARDANDO}/emissao`) && r.request().method() === "POST",
        { timeout: 30_000 },
      ),
      page.getByRole("button", { name: "Emitir parecer" }).click(),
    ]);

    await expect(page.locator(".comando-ctx")).toContainText("Parecer emitido");
    expect(resp.status()).toBe(200);

    // A pagina refaz o GET sozinha apos emitir (recarregar()) — com o parecer agora terminal,
    // `parecerEhTerminal` bloqueia: os botoes de escrita somem, so' "Pré-visualizar" resta.
    await expect(page.getByRole("button", { name: "Emitir parecer" })).toHaveCount(0, { timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Salvar rascunho" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Pré-visualizar" })).toBeVisible();

    // F5: o bloqueio persiste (nao e' so' estado de componente em memoria).
    await page.reload({ waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("button", { name: "Emitir parecer" })).toHaveCount(0, { timeout: 30_000 });

    registrarEscrita({
      escrita: "Emitir parecer (secretaria) — transição real a terminal",
      metodo: "POST",
      url: `/legislativo/pareceres/${ID_AGUARDANDO}/emissao`,
      status: resp.status(),
      id: ID_AGUARDANDO,
      tabela: "legislativo.pareceres",
      sql_de_prova: `SELECT estado, voto_relator, lock_version FROM legislativo.pareceres WHERE ente_id='${ENTE}' AND id='${ID_AGUARDANDO}'; -- espera voto_relator='contrario', estado='aprovado'. SELECT de_estado, para_estado, gatilho, ocorrido_em FROM legislativo.parecer_transicao_historico WHERE ente_id='${ENTE}' AND parecer_id='${ID_AGUARDANDO}' ORDER BY ocorrido_em DESC LIMIT 1; -- espera de_estado='aguardando_assinatura', para_estado='aprovado'`,
    });
  });

  // ---------------------------------------------------------------------------------------------
  // [ACHADO CENTRAL DO GRUPO] 7) Emitir "meu parecer" (vereador) sobre um parecer que JÁ é terminal
  // (T6 acabou de aprovar ID_AGUARDANDO). `deriveEstadoAssinatura` (assinatura-vista.ts) só olha
  // textoEstado/votoRelator — NUNCA dados.estado/parecerEhTerminal — então a tela volta a mostrar
  // "pronto-pra-revisar" e reoferece "Revisar e assinar" para um parecer já aprovado. Ao confirmar, o
  // UPDATE bate no trigger trg_pareceres_imut_estado (23514) — sem catch nesse caminho, o interceptor
  // global cai no ramo :else -> 500 opaco. Documento o comportamento REAL (o 500), não finjo que a
  // tela bloqueia isso (ela não bloqueia — é o achado).
  // ---------------------------------------------------------------------------------------------
  test("[ACHADO] Emitir 'meu parecer' — assinar parecer já terminal cai em 500 opaco", async ({ page }) => {
    await page.goto(urlAssinar(ID_AGUARDANDO, TVER), { waitUntil: "domcontentloaded", timeout: 90_000 });

    // [ACHADO] deveria estar bloqueado (parecer já 'aprovado'), mas o CTA aparece do mesmo jeito.
    await expect(page.getByRole("button", { name: "Revisar e assinar" })).toBeVisible({ timeout: 30_000 });
    await page.getByRole("button", { name: "Revisar e assinar" }).click();

    const dialog = page.getByRole("dialog", { name: "Confirmar assinatura" });
    await expect(dialog).toBeVisible();

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/meu/pareceres/${ID_AGUARDANDO}/emissao`) && r.request().method() === "POST",
        { timeout: 30_000 },
      ),
      dialog.getByRole("button", { name: "Confirmar com a biometria" }).click(),
    ]);

    // [ACHADO] 500 opaco — não é um 409 limpo de "já assinado"/"estado terminal".
    // TODO(achado central do grupo E4): quando o backend passar a rejeitar com 409 em vez de estourar
    // no trigger trg_pareceres_imut_estado, esta linha (e a asserção de texto abaixo) precisam mudar
    // junto — hoje o teste documenta o bug, não o comportamento correto.
    expect(resp.status()).toBe(500);
    await expect(dialog.getByText(/Não foi possível assinar/)).toBeVisible({ timeout: 10_000 });

    // Não há registrarEscrita aqui: a transação do backend reverte no trigger — nenhuma linha nova.
  });

  // ---------------------------------------------------------------------------------------------
  // 8) Dar ciência (vereador) — [GAP ESTRUTURAL, não é o achado da tela]. legislativo.ciencia_vereador
  // tem linhas históricas no banco, mas NENHUMA fica "pendente" pra identidade :vereador hoje (GET
  // /meu/painel confirmado ao vivo: `"ciencias": []`), e não existe rota HTTP que publique um evento
  // de ciência pendente (o único produtor seria publicar-norma!, sem chamador em nenhum diplomat —
  // mesmo achado que o agente de precondições registrou nos bloqueios). Sem esse evento, a seção
  // "Para sua ciência" nem renderiza (vista.ciencias.length > 0 é a guarda) — o botão "Dar ciência"
  // não é alcançável pela interface com o dado de hoje. Teste real (não fixme) que AFIRMA esse estado,
  // e teste .fixme() companheiro documentando o caminho feliz que ficaria pronto se a precondição
  // existisse.
  // ---------------------------------------------------------------------------------------------
  test("Dar ciência — hoje não há ciência pendente para :vereador (GAP de fixture, não de UI)", async ({ page }) => {
    await page.goto(urlVereadorHome(TVER), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("heading", { name: "Suas proposições" })).toBeVisible({ timeout: 30_000 });
    // a secao so' aparece quando ha' ao menos 1 ciencia pendente (vista.ciencias.length > 0) — hoje nao ha'.
    await expect(page.getByRole("heading", { name: "Para sua ciência" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Dar ciência" })).toHaveCount(0);
  });

  test.fixme(
    "Dar ciência — caminho feliz (bloqueado: sem rota HTTP que crie a precondição hoje)",
    async ({ page }) => {
      // Se um dia existir uma forma de publicar `parecer_publicado` pendente pra um vereador de
      // demo (produtor real seria publicar-norma!, sem chamador HTTP hoje — ver comentário acima),
      // o fluxo completo seria:
      await page.goto(urlVereadorHome(TVER), { waitUntil: "domcontentloaded", timeout: 90_000 });
      const card = page.locator("article.ciencia").first();
      await expect(card).toBeVisible({ timeout: 30_000 });
      const [resp] = await Promise.all([
        page.waitForResponse(
          (r) => r.url().includes("/api/meu/ciencias") && r.request().method() === "POST",
        ),
        card.getByRole("button", { name: "Dar ciência" }).click(),
      ]);
      expect(resp.status()).toBe(201);
      await expect(card).toHaveCount(0); // o card some da lista apos `recarregar()`.
      await page.reload({ waitUntil: "domcontentloaded", timeout: 90_000 });
      await expect(page.getByRole("heading", { name: "Para sua ciência" })).toHaveCount(0);
    },
  );
});
