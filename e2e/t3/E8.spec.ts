import { test, expect } from "@playwright/test";
import { readFileSync, mkdirSync, appendFileSync } from "node:fs";
import { resolve } from "node:path";

// GRUPO E8 — Notificações (vereador). Uma escrita só: POST /meu/notificacoes/:id/lida
// (paineis/diplomat/http/in.clj, gate = auth + posse — sem exigência de papel), tabela
// paineis.notificacao_caixa (lida_em: NULL -> now(), via COALESCE — IDEMPOTENTE por desenho).
//
// A canônica `identidades.vereador` de demo-ids.edn tem ZERO linhas nessa caixa por padrão (mapa-E8,
// achado 1: nenhum diplomat chama `publicar-norma!`, o único produtor de notificação in_app). O agente
// de precondições plantou 3 fixtures NÃO-LIDAS direto na tabela (e2e/t3/fixtures.sql, mesma forma que o
// projetor `projetar-inbox!` escreve — idempotency_key absorve replay do fixtures.sql, mas NÃO absorve
// "marcar como lida", que é irreversível). Por isso: TRÊS fixtures para UMA escrita — a suíte inteira só
// pode consumir 1 por corrida (t3-ids.json/e8.nota). Este spec usa exatamente a que o artefato já
// escolheu (`e8.naoLidaId`), nunca uma das outras duas — reservadas para reruns futuros.
//
// A "marcar duas vezes" (mapa-E8, achado 6) NÃO precisa de uma segunda fixture: é a MESMA linha, com um
// segundo POST fora do clique — exatamente o que o próprio achado descreve como único jeito de gerar uma
// segunda chamada genuína depois que o botão já sumiu da tela.

const ids = JSON.parse(readFileSync(resolve(__dirname, ".artifacts/t3-ids.json"), "utf8"));

const BACKEND: string = ids.base.backend;
const FRONTEND: string = ids.base.frontend;
const ENTE_ID: string = ids.ente;
const TOKEN_VEREADOR_JSON: string = ids.tokens.vereador.json;
const URL_NOTIFICACOES: string = ids.e8.url;
const NOTIFICACAO_ID: string = ids.e8.naoLidaId; // "3a89d4a4-..." — T3-FIXTURE 3, categoria "sistema"
const ASSUNTO_FIXTURE = "Aviso da Secretaria [T3-FIXTURE 3]";

const PROVA_PATH = resolve(__dirname, ".artifacts/escritas-E8.json");

function registrarProva(linha: Record<string, unknown>) {
  mkdirSync(resolve(__dirname, ".artifacts"), { recursive: true });
  // JSONL (uma linha por escrita) — evita read-modify-write concorrente com os outros 7 specs da trilha.
  appendFileSync(PROVA_PATH, JSON.stringify(linha) + "\n", "utf8");
}

test.describe.serial("E8 — Notificações (vereador)", () => {
  let lidaEmDaPrimeiraChamada: string | undefined;

  test("marcar como lida — caminho feliz", async ({ page }) => {
    // 1o goto desta rota nesta corrida do next dev: compilação sob demanda pode passar de 20s.
    await page.goto(URL_NOTIFICACOES, { waitUntil: "domcontentloaded", timeout: 90_000 });

    const cabecalho = page.getByRole("heading", { name: /Notificações/, level: 1 });
    await expect(cabecalho).toBeVisible({ timeout: 30_000 });

    // badge de não-lidas: existe (>=1) ANTES do clique — prova que a fixture chegou até a tela, não só
    // até o banco. O texto exato do número não é fixado aqui de propósito: outra corrida desta mesma
    // suíte pode já ter consumido as outras 2 fixtures antes desta rodar.
    const badge = cabecalho.locator(".badge");
    await expect(badge).toBeVisible({ timeout: 15_000 });
    const badgeAntes = await badge.textContent();
    expect(Number(badgeAntes)).toBeGreaterThanOrEqual(1);

    // o artigo da fixture escolhida, achado pelo texto literal do assunto (sem seletor inventado — a
    // tela não usa data-testid, ver notificacoes/page.tsx).
    const artigo = page.locator("article", { hasText: ASSUNTO_FIXTURE });
    await expect(artigo).toBeVisible({ timeout: 15_000 });
    await expect(artigo).toHaveClass(/nao-lida/);

    const botaoMarcar = artigo.getByRole("button", { name: "Marcar como lida" });
    await expect(botaoMarcar).toBeVisible();

    // b) a escrita SAIU — captura o POST real, não presume que o clique "deu certo".
    const [resposta] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes("/api/meu/notificacoes/") &&
          r.url().includes("/lida") &&
          r.request().method() === "POST",
      ),
      botaoMarcar.click(),
    ]);
    expect(resposta.status()).toBe(200);
    const corpoResposta = (await resposta.json()) as { id: string; "lida-em": string };
    expect(corpoResposta.id).toBe(NOTIFICACAO_ID);
    expect(corpoResposta["lida-em"]).toBeTruthy();
    lidaEmDaPrimeiraChamada = corpoResposta["lida-em"];

    // a) a tela diz que gravou — o botão vira "Lida", o ponto de não-lida some, e o badge decresce
    // (useMinhasNotificacoes.recarregar() refaz o GET depois do POST — page.tsx marcarLida()).
    await expect(artigo.getByText("Lida", { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(botaoMarcar).toBeHidden();
    await expect(artigo.locator(".nt-ponto")).toHaveCount(0);
    await expect(artigo).not.toHaveClass(/nao-lida/);
    if (badgeAntes && Number(badgeAntes) > 1) {
      await expect(badge).toHaveText(String(Number(badgeAntes) - 1), { timeout: 15_000 });
    } else {
      // se a fixture era a última não-lida do vereador, o badge inteiro some do heading (só renderiza
      // quando vista.naoLidas > 0 — page.tsx).
      await expect(badge).toHaveCount(0, { timeout: 15_000 });
    }

    // c) F5 — o estado sobrevive a reload (prova que não é otimismo só-de-cliente: lida_em é do servidor).
    await page.reload({ waitUntil: "domcontentloaded" });
    const artigoDepoisDoReload = page.locator("article", { hasText: ASSUNTO_FIXTURE });
    await expect(artigoDepoisDoReload.getByText("Lida", { exact: true })).toBeVisible({ timeout: 30_000 });
    await expect(artigoDepoisDoReload.getByRole("button", { name: "Marcar como lida" })).toHaveCount(0);

    registrarProva({
      escrita: "marcar notificação como lida (vereador)",
      metodo: "POST",
      url: `/api/meu/notificacoes/${NOTIFICACAO_ID}/lida`,
      status: resposta.status(),
      id: NOTIFICACAO_ID,
      tabela: "paineis.notificacao_caixa",
      sql_de_prova:
        `SELECT id, lida_em FROM paineis.notificacao_caixa ` +
        `WHERE id = '${NOTIFICACAO_ID}' AND ente_id = '${ENTE_ID}' AND lida_em IS NOT NULL; ` +
        `-- espera 1 linha`,
    });
  });

  test("caso de erro — marcar duas vezes não é erro (idempotente por desenho)", async () => {
    // mapa-E8, achado 6: NÃO existe caminho de clique normal para um 2o POST — o botão some assim que o
    // primeiro sucesso chega (provado no teste anterior). O único jeito real de gerar a segunda chamada
    // genuína é reenviar fora do clique, exatamente como o próprio achado descreve ("replay manual via
    // devtools/network"). Reusa a MESMA linha já marcada acima — não consome fixture nova.
    test.skip(!lidaEmDaPrimeiraChamada, "depende do POST do teste anterior já ter sido registrado");

    // [NAO-E-T3] POST direto por fetch, não por clique: o botão já sumiu da tela após o 1o sucesso
    // (comentário acima) — não há elemento de UI que dispare este 2o POST.
    const r = await fetch(`${BACKEND}/meu/notificacoes/${NOTIFICACAO_ID}/lida`, {
      method: "POST",
      headers: { Authorization: `Bearer ${TOKEN_VEREADOR_JSON}` },
    });
    // NÃO é 409: o backend usa SET lida_em = COALESCE(lida_em, now()) — 2a chamada devolve 200 de novo.
    expect(r.status).toBe(200);
    const corpo = (await r.json()) as { id: string; "lida-em": string };
    expect(corpo.id).toBe(NOTIFICACAO_ID);
    // o carimbo NÃO muda entre a 1a e a 2a resposta — é a prova real da idempotência (não só "não deu
    // erro", mas "o COALESCE de fato preservou o primeiro carimbo").
    expect(corpo["lida-em"]).toBe(lidaEmDaPrimeiraChamada);
  });

  test.fixme(
    "caso de erro — marcar notificação de outro destinatário (INATINGÍVEL pela interface)",
    async () => {
      // [ACHADO] mapa-E8, achado 5 + casos_de_erro[0]: a tela só renderiza `n.id` dos itens do PRÓPRIO
      // ator — GET /meu/notificacoes resolve o destinatário do :ator (auth), nunca de um parâmetro do
      // cliente (paineis/diplomat/http/in.clj). Não existe botão, link ou id de outra pessoa em lugar
      // nenhum do DOM desta tela: fabricar esse clique exigiria injetar um :id que a própria interface
      // nunca expõe, o que não é "exercitar a interface", é forjar uma chamada por fora dela.
      //
      // O backend recusa corretamente (marcar-lida-handler, in.clj:111-120): update-count 0 ->
      // controllers/marcar-lida devolve nil -> 404 uniforme {:erro "notificacao nao encontrada"},
      // indistinguível de id inexistente ou malformado (anti-oráculo deliberado, adapters/in/notificacao.clj).
      // Esse caminho já está coberto por chamada HTTP direta na Trilha 2 (fora da interface) — não
      // duplicado aqui. Este teste fica test.fixme() para documentar POR QUE não há caminho de UI, não
      // para fingir uma cobertura que a própria tela impede de existir.
    },
  );
});
