import { test, expect } from "@playwright/test";
import { readFileSync, mkdirSync, appendFileSync } from "node:fs";
import { resolve } from "node:path";

// GRUPO E7 — "Pós-aprovação" (servidor). Lê e2e/t3/mapa-E7.json (o mapa das escritas, os achados e os
// casos de erro já medidos ao vivo) e e2e/t3/.artifacts/t3-ids.json (os ids REAIS deixados pelo
// preparar.mjs — nenhum UUID aqui é inventado). As 2 escritas do grupo:
//   1) Gerar autógrafo (secretaria)         — POST /legislativo/proposicoes/:id/autografo
//   2) Registrar resposta do Executivo      — POST /legislativo/autografos/:id/resposta
//
// O ACHADO CENTRAL do mapa é que (1) NÃO confere `proposicao.estado === 'aprovada'` em nenhuma ponta:
// nem o backend (controllers.clj gerar-autografo só guarda duplicidade + texto vigente), nem a tela (o
// gate condicional é só no LINK DE ENTRADA em ficha-materia/acoes-card.tsx — a rota /pos-aprovacao/:id
// é navegável direto por URL para qualquer proposição). O agente de precondições mediu ao vivo que hoje
// não existe nenhuma proposição 'aprovada' sem autógrafo no ente (as 6 já têm; T2 grupo B as esgotou) —
// então o CAMINHO FELIZ de (1) e o ACHADO são o MESMO clique, sobre e7.proposicaoParaAutografoId
// (8344f6b3, em_comissoes). Isso também é o que destrava (2) na mesma página: autógrafo + tramitação
// executiva 'aguardando' nascem juntos, na MESMA tx (repo/gerar-autografo-e-abrir-tramitacao!).
//
// Os 2 casos de erro "dois autógrafos" / "resposta duplicada" só são alcançáveis por CORRIDA (2 cliques/
// 2 abas) numa sessão de 1 usuário — a tela muda de branch assim que a 1a escrita entra e o botão some.
// Em vez de perseguir um timing de corrida real (não-determinístico, frágil pra quem for rodar depois —
// mesma lição do preâmbulo SSE de E5/E6), reproduzimos DETERMINISTICAMENTE o que a 2a aba mandaria:
// o MESMO POST que o botão dispara, direto no backend (mesmo idioma de E6.spec.ts, "reenviar o POST fora
// da tela" — E6 já estabeleceu esse padrão para o caso irmão "votar duas vezes").
const ids = JSON.parse(readFileSync(resolve(__dirname, ".artifacts/t3-ids.json"), "utf8"));
const mapa = JSON.parse(readFileSync(resolve(__dirname, "mapa-E7.json"), "utf8"));
void mapa; // mantido só pra quem lê o spec ir direto na fonte do achado sem procurar o arquivo.

const BACKEND: string = ids.base.backend;
const FRONTEND: string = ids.base.frontend;
const ENTE: string = ids.ente;
const TSEC_URL: string = ids.tokens.secretaria.url; // token url-encoded, pro browser (?token=)
const TSEC_JSON: string = ids.tokens.secretaria.json; // token cru, pro header Authorization: Bearer

// e7.proposicaoParaAutografoId — em_comissoes, texto vigente, SEM autógrafo. É o único alvo do mapa que
// sobrou exercitável: as 6 proposições 'aprovada' do ente já têm autógrafo (esgotadas por T2 grupo B) e
// não existe rota HTTP que transicione proposicao.estado (bloqueio "e7-aprovada-sem-autografo" em
// t3-ids.json). Usar este id é literalmente exercitar o achado do mapa, não um desvio dele.
const ID_ALVO: string = ids.e7.proposicaoParaAutografoId;

// Candidato do próprio mapa (e7.candidatosSemAutografo), livre de qualquer outro grupo (E1/E3/E4/E5/E6
// reservam 1511bc9e/3b59cdbb/c7aecac5/9c157f96/746ca24a/044ab363/eb8383d1/0539ac78 — nenhum deles é
// este). Usado só de LEITURA no teste 7 (nunca gera autógrafo nele) — prova que a tela nunca oferece
// "Registrar retorno" sem um autógrafo já existente, sem consumir mais nenhum candidato do grupo.
const ID_SEM_AUTOGRAFO: string = (
  ids.e7.candidatosSemAutografo as Array<{ id: string }>
).find((c) => c.id !== ID_ALVO)!.id;

const ID_INEXISTENTE = "00000000-0000-0000-0000-000000000000"; // formato válido, não existe no ente

function urlFicha(id: string) {
  return `${FRONTEND}/ficha-materia/${id}?token=${TSEC_URL}`;
}
function urlPosAprovacao(id: string) {
  return `${FRONTEND}/pos-aprovacao/${id}?token=${TSEC_URL}`;
}

const ARTEFATO_ESCRITAS = resolve(__dirname, ".artifacts/escritas-E7.json");

// Append de 1 linha JSON por escrita bem-sucedida (a prova via SQL fica pro Daouda rodar depois — eu só
// registro a query certa, com o ente_id no WHERE, como o briefing pediu). JSONL evita read-modify-write
// concorrente com os outros 7 grupos escrevendo na mesma Casa ao mesmo tempo (mesmo padrão de E6.spec.ts).
function registrarEscrita(linha: Record<string, unknown>) {
  mkdirSync(resolve(__dirname, ".artifacts"), { recursive: true });
  appendFileSync(ARTEFATO_ESCRITAS, JSON.stringify(linha) + "\n", "utf8");
}

test.describe.serial("E7 - Pós-aprovação (servidor)", () => {
  // ids capturados da resposta real de T2, usados pelos casos de erro determinísticos que vêm depois
  // (nenhum UUID inventado — tudo sai da resposta HTTP da própria corrida, mesma disciplina do preparar.mjs).
  let autografoId: string | undefined;
  let tramitacaoId: string | undefined;

  // -----------------------------------------------------------------------------------------------
  // [ACHADO] 0) "Ver pós-aprovação" não aparece na ficha da matéria alvo (estado 'em_comissoes', não
  // 'aprovada') — mas a rota /pos-aprovacao/:id é navegável direto por URL, sem checar proposicao.estado
  // em nenhuma ponta (acoes-card.tsx só usa o gate pra decidir se MOSTRA o link, não é autorização).
  // Teste só de LEITURA: documenta o "antes", pra o próximo teste (a escrita de verdade) não parecer um
  // desvio de fluxo, e sim exatamente o caminho que o mapa aponta como explorável hoje.
  // -----------------------------------------------------------------------------------------------
  test("[ACHADO] Ver pós-aprovação não aparece na ficha (estado != aprovada), mas a rota é navegável direto por URL", async ({
    page,
  }) => {
    await page.goto(urlFicha(ID_ALVO), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("heading", { name: "Ações" })).toBeVisible({ timeout: 30_000 });
    // [ACHADO] o único gate do mapa (o link de entrada) nem existe pra esta matéria — em_comissoes, não aprovada.
    await expect(page.getByRole("link", { name: "Ver pós-aprovação" })).toHaveCount(0);

    // mas a página de destino abre normalmente por URL direta, com o CTA de gerar autógrafo disponível:
    await page.goto(urlPosAprovacao(ID_ALVO), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(
      page.getByText("Nenhum autógrafo foi gerado ainda para esta matéria."),
    ).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Gerar autógrafo e enviar ao Executivo" })).toBeVisible();
  });

  // -----------------------------------------------------------------------------------------------
  // 1) Gerar autógrafo — caminho feliz. Sobre ID_ALVO (em_comissoes): isto GERA um autógrafo válido
  // pra uma matéria que nunca foi votada nem aprovada — é o próprio achado do mapa sendo exercitado, não
  // um efeito colateral dele.
  // -----------------------------------------------------------------------------------------------
  test("[ACHADO] Gerar autógrafo — caminho feliz, sobre matéria NÃO aprovada", async ({ page }) => {
    await page.goto(urlPosAprovacao(ID_ALVO), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("button", { name: "Gerar autógrafo e enviar ao Executivo" })).toBeVisible({
      timeout: 30_000,
    });

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/legislativo/proposicoes/${ID_ALVO}/autografo`) && r.request().method() === "POST",
      ),
      page.getByRole("button", { name: "Gerar autógrafo e enviar ao Executivo" }).click(),
    ]);

    // b) a escrita saiu de fato
    expect(resp.status()).toBe(201);
    const corpo = await resp.json();
    autografoId = corpo?.autografo?.id;
    const tramitacao = corpo?.["tramitacao-executiva"] ?? corpo?.tramitacaoExecutiva;
    tramitacaoId = tramitacao?.id;
    expect(autografoId).toBeTruthy();
    expect(tramitacao?.estado).toBe("aguardando");

    // a) a tela diz que gravou (role="status" é nameFrom:author — não recebe nome do conteúdo;
    // getByRole(..., {name}) nunca casaria aqui, por isso getByText no texto real do <p role="status">)
    await expect(page.getByText("Autógrafo gerado e enviado ao Executivo")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Autógrafo" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Prazo do Executivo" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Registrar retorno" })).toBeVisible();

    // c) F5 — o autógrafo persiste e o botão "Gerar autógrafo" some (a página mudou de branch de vez)
    await page.reload({ waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("heading", { name: "Autógrafo" })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Gerar autógrafo e enviar ao Executivo" })).toHaveCount(0);

    registrarEscrita({
      escrita: "Gerar autógrafo (sobre matéria não aprovada — achado do mapa)",
      metodo: "POST",
      url: `/legislativo/proposicoes/${ID_ALVO}/autografo`,
      status: resp.status(),
      id: autografoId,
      tabela: "legislativo.autografo",
      sql_de_prova:
        `SELECT a.id, a.numero, a.ano, a.proposicao_id, a.destinatario_texto, t.id AS tramitacao_id, t.estado ` +
        `FROM legislativo.autografo a JOIN legislativo.tramitacao_executiva t ON t.autografo_id = a.id AND t.ente_id = a.ente_id ` +
        `WHERE a.ente_id = '${ENTE}' AND a.proposicao_id = '${ID_ALVO}'; ` +
        `-- espera 1 linha, t.estado = 'aguardando' (autógrafo + tramitação abertos na mesma tx)`,
    });
  });

  // -----------------------------------------------------------------------------------------------
  // [ACHADO] 2) caso de erro "dois autógrafos" — bem tratado pelo backend (:validacao/invalido -> 400),
  // mas só alcançável por corrida de 2 cliques/2 abas (assim que o 1o existe, a tela muda de branch e o
  // botão "Gerar autógrafo" some — provado no teste anterior). Reenviamos o MESMO POST fora do clique,
  // exatamente como o mapa descreve o cenário — determinístico, sem depender de timing de corrida real.
  // -----------------------------------------------------------------------------------------------
  test("[ACHADO] Gerar autógrafo — segunda vez sobre a mesma matéria é recusada (400, dois autógrafos)", async () => {
    test.skip(!autografoId, "depende do autógrafo do teste anterior já ter sido gerado");

    // [NAO-E-T3] só alcançável por corrida de 2 cliques/2 abas — o botão some assim que o 1º autógrafo
    // existe (branch muda). Reenvia o mesmo POST que o botão dispara, fora do clique (padrão de E6.spec.ts).
    const r = await fetch(`${BACKEND}/legislativo/proposicoes/${ID_ALVO}/autografo`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${TSEC_JSON}` },
      body: JSON.stringify({}),
    });
    expect(r.status).toBe(400);
    const corpo = (await r.json()) as { erro?: string };
    expect(corpo.erro ?? "").toMatch(/ja tem autografo/i);
  });

  // -----------------------------------------------------------------------------------------------
  // 3) Registrar resposta do Executivo — caminho feliz. Destravado pelo teste 1 (mesma tx abriu a
  // tramitação executiva 'aguardando'); mesma página (/pos-aprovacao/:id), agora com o card "Prazo do
  // Executivo" e o botão "Registrar retorno".
  // -----------------------------------------------------------------------------------------------
  test("Registrar resposta do Executivo — caminho feliz (sancionado)", async ({ page }) => {
    await page.goto(urlPosAprovacao(ID_ALVO), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("heading", { name: "Prazo do Executivo" })).toBeVisible({ timeout: 30_000 });

    await page.getByRole("button", { name: "Registrar retorno" }).click();
    const radiogroup = page.getByRole("radiogroup", { name: "Resultado do Executivo" });
    await expect(radiogroup).toBeVisible();
    await radiogroup.getByRole("radio", { name: "Sancionado" }).check();

    // depois do clique acima o botão-gatilho (btn-contorno btn-mini) já sumiu — {!mostrarForm && ...} —
    // então "Registrar retorno" aqui só pode casar com o botão de submit do form (btn-primaria).
    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes(`/api/legislativo/autografos/${autografoId}/resposta`) && r.request().method() === "POST",
      ),
      page.getByRole("button", { name: "Registrar retorno" }).click(),
    ]);

    // b) a escrita saiu de fato
    expect(resp.status()).toBe(200);

    // a) a tela diz que gravou (mesmo motivo do teste anterior: role="status" é nameFrom:author)
    await expect(page.getByText("Retorno do Executivo registrado")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Desfecho" })).toBeVisible();
    await expect(
      page.getByText("A matéria foi sancionada e segue para promulgação/publicação."),
    ).toBeVisible();

    // c) F5 — o desfecho persiste e "Registrar retorno" some pra sempre (tramitação virou terminal)
    await page.reload({ waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("heading", { name: "Desfecho" })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Registrar retorno" })).toHaveCount(0);

    registrarEscrita({
      escrita: "Registrar resposta do Executivo (sancionado)",
      metodo: "POST",
      url: `/legislativo/autografos/${autografoId}/resposta`,
      status: resp.status(),
      id: tramitacaoId,
      tabela: "legislativo.tramitacao_executiva",
      sql_de_prova:
        `SELECT id, estado, veto_tipo, respondido_em, lock_version FROM legislativo.tramitacao_executiva ` +
        `WHERE ente_id = '${ENTE}' AND autografo_id = '${autografoId}'; ` +
        `-- espera estado = 'sancionado', respondido_em NOT NULL, lock_version incrementado`,
    });
  });

  // -----------------------------------------------------------------------------------------------
  // [ACHADO] 4) caso de erro "resposta duplicada / tramitação já respondida" — o mapa registra isto como
  // correlato de "dois autógrafos" achado por leitura de código (não listado no enunciado original, real
  // mesmo assim). AO CONTRÁRIO de "dois autógrafos", este NÃO depende de corrida: o teste anterior já
  // deixou a tramitação em estado TERMINAL ('sancionado'), então qualquer POST novo pro MESMO autógrafo
  // bate no guard "só se responde uma tramitação 'aguardando'" de forma determinística.
  // -----------------------------------------------------------------------------------------------
  test("[ACHADO] Registrar resposta — tramitação já respondida (terminal) é recusada (409)", async () => {
    test.skip(!autografoId, "depende do autógrafo gerado no teste 1");

    // [NAO-E-T3] a tramitação já está terminal ('sancionado') desde o teste anterior — a tela nunca
    // oferece "Registrar retorno" neste estado, então o guard só é alcançável via chamada direta.
    const r = await fetch(`${BACKEND}/legislativo/autografos/${autografoId}/resposta`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${TSEC_JSON}` },
      body: JSON.stringify({ "lock-version": 0, resultado: "sancionado" }),
    });
    expect(r.status).toBe(409);
    const corpo = (await r.json()) as { erro?: string };
    expect(corpo.erro ?? "").toMatch(/so se responde uma tramitacao/i);
  });

  // -----------------------------------------------------------------------------------------------
  // 5) caso de erro "resposta sem autógrafo" — TRATADO pelo backend (404), mas NÃO alcançável pela tela
  // em uso normal (autógrafo e tramitação nascem juntos, na mesma tx — não existe fluxo de UI que produza
  // um autógrafo-id sem tramitação correspondente). Documentado por chamada direta com id inventado, como
  // o próprio mapa registra (e já coberto do lado de T2 grupo B — aqui é a confirmação do lado de E7).
  // -----------------------------------------------------------------------------------------------
  test("Registrar resposta — autógrafo inexistente retorna 404 (não alcançável pela tela hoje)", async () => {
    // [NAO-E-T3] não alcançável pela tela em uso normal — autógrafo e tramitação nascem juntos, na
    // mesma tx (repo/gerar-autografo-e-abrir-tramitacao!); não há fluxo de UI com autógrafo sem tramitação.
    const r = await fetch(`${BACKEND}/legislativo/autografos/${ID_INEXISTENTE}/resposta`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${TSEC_JSON}` },
      body: JSON.stringify({ "lock-version": 0, resultado: "sancionado" }),
    });
    expect(r.status).toBe(404);
    const corpo = (await r.json()) as { erro?: string };
    expect(corpo.erro ?? "").toMatch(/tramitacao executiva nao encontrada/i);
  });

  // -----------------------------------------------------------------------------------------------
  // 6) GAP de fixture, não de UI — confirma que, SEM autógrafo, a tela nunca oferece "Registrar retorno"
  // (o card só renderiza dentro do branch `autografo &&`). Usa um candidato do próprio mapa
  // (candidatosSemAutografo) DIFERENTE de ID_ALVO — só leitura, nenhum autógrafo é gerado aqui, pra não
  // consumir mais um candidato do grupo à toa.
  // -----------------------------------------------------------------------------------------------
  test("Registrar resposta — sem autógrafo, a tela nunca oferece o botão", async ({ page }) => {
    await page.goto(urlPosAprovacao(ID_SEM_AUTOGRAFO), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(
      page.getByText("Nenhum autógrafo foi gerado ainda para esta matéria."),
    ).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Registrar retorno" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Prazo do Executivo" })).toHaveCount(0);
  });
});
