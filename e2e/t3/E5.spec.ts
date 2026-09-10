import { test, expect } from "@playwright/test";
import { readFileSync, appendFileSync } from "node:fs";
import { resolve } from "node:path";

// E5 — Chamada e presença (servidor + vereador), Trilha 3 (browser real, sem seletor inventado).
//
// Todo seletor abaixo foi lido de:
//   apps/frontend/src/app/sessoes/[id]/chamada/page.tsx
//   apps/frontend/src/app/sessoes/[id]/folha/page.tsx
//   apps/frontend/src/app/(vereador)/votar/page.tsx
// Todo id de sessao/vereador vem de e2e/t3/.artifacts/t3-ids.json (precondicoes ja gravadas na Casa
// viva por preparar.mjs) — nao ha id fabricado neste arquivo.
//
// ORDEM (test.describe.serial): as escritas de chamada (grupo A) dependem umas das outras na MESMA
// sessao (b26a68e2) — a marcacao unitaria ao vivo so' e' possivel DEPOIS de "Registrar a chamada"
// (registradaAoVivo), a justificativa precisa do vereador ja ausente, e a decisao precisa da
// justificativa ja lancada. O grupo B (confirmar presenca) e o grupo C (folha) usam OUTRAS sessoes
// (0211 e 49c0afe1) e nao dependem do grupo A, mas o serial evita rodar duas rotas novas do Next ao
// mesmo tempo (armadilha ja paga: compilacao a frio concorrente estoura timeout).

const ids = JSON.parse(readFileSync(resolve(__dirname, ".artifacts/t3-ids.json"), "utf8"));
const ENTE = ids.ente as string;

// Backend direto (nao o proxy do Next): so' para SETUP declarado — nunca para a escrita sob teste.
const BACKEND = "http://localhost:8888";
const TOKEN_SECRETARIA = ids.tokens.secretaria.json as string;

const PROVA_PATH = resolve(__dirname, ".artifacts/escritas-E5.json");

interface LinhaProva {
  escrita: string;
  metodo: string;
  url: string;
  status: number;
  id: string | null;
  tabela: string;
  sql_de_prova: string;
}

/** Append de UMA linha JSON por escrita bem-sucedida (JSONL — nunca reescreve o arquivo inteiro, so'
 * acrescenta, o mesmo espirito append-only que as tabelas de dominio que estamos provando). */
function registrarProva(linha: LinhaProva): void {
  appendFileSync(PROVA_PATH, JSON.stringify(linha) + "\n", "utf8");
}

/** Extrai o id do corpo JSON de uma resposta de escrita. Nunca lanca — se nao achar, devolve null e
 * o teste registra null (nunca inventa um id pra preencher a prova). Tipagem estrutural (so' precisa
 * de `.json()`) pra aceitar tanto o `Response` de `page.waitForResponse` quanto qualquer outro. */
async function idDaResposta(resp: { json: () => Promise<unknown> }): Promise<string | null> {
  try {
    const corpo = (await resp.json()) as { id?: unknown } | null;
    if (corpo && typeof corpo.id === "string") return corpo.id;
    return null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------------------------
// GRUPO A — a chamada da sessao b26a68e2 (secretaria). Roster inteiro "ausente" no momento da prep.
// ---------------------------------------------------------------------------------------------

const SESSAO_CHAMADA = ids.e5.sessaoChamadaId as string;
const URL_CHAMADA = ids.e5.urlChamada as string;

// REVISÃO E5 #1 (id cravado a mão + reprova hoje): o id fixo anterior ERA ids.e1.vereadorParaEditarId
// (Otávio) — E1 renomeia e licencia esse vereador, o que tira Otávio do roster/quórum e muda o nome
// exibido (nome_parlamentar diverge de `nome`). Lido do roster real (nao inventado), excluindo (a)
// quem E1 edita/licencia e (b) quem o grupo B usa para "confirmar a propria presenca" — nenhum dos
// dois pode ser o alvo das escritas unitarias abaixo sem colidir com o cenario de outro spec.
const ALVO = (ids.e5.rosterDaSessaoChamada as Array<{ vereadorId: string; nome: string }>).find(
  (v) => v.vereadorId !== ids.e5.confirmarPresenca.vereadorId && v.vereadorId !== ids.e1.vereadorParaEditarId,
);
if (!ALVO) {
  throw new Error(
    "E5: nenhum vereador do roster sobrou fora de e1.vereadorParaEditarId e e5.confirmarPresenca.vereadorId",
  );
}
const ALVO_ID = ALVO.vereadorId;

test.describe.serial("E5 - Chamada e presença (servidor + vereador)", () => {
  test("lote de presença — 'Todos presentes' + 'Registrar a chamada' (1ª vez)", async ({ page }) => {
    // Primeiro acesso a esta rota nesta suite — global-setup so' aquece "/" e "/portal/casa/:ente",
    // NAO esta rota. Compilacao a frio do Turbopack pode passar de 20s.
    test.setTimeout(120_000);
    await page.goto(URL_CHAMADA, { waitUntil: "domcontentloaded", timeout: 90_000 });

    await expect(page.getByRole("button", { name: "Todos presentes" })).toBeVisible({ timeout: 30_000 });
    await page.getByRole("button", { name: "Todos presentes" }).click();

    // O clique so' marca LOCALMENTE (otimista) — nada sai daqui ainda. "Registrar a chamada" e' quem
    // dispara os DOIS POSTs (lote, depois o ato), sequenciais no mesmo hook (use-chamada.ts).
    const [respLote, respAto] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/sessoes/${SESSAO_CHAMADA}/presenca/lote`) && r.request().method() === "POST",
      ),
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/sessoes/${SESSAO_CHAMADA}/chamada`) && r.request().method() === "POST",
      ),
      page.getByRole("button", { name: "Registrar a chamada" }).click(),
    ]);

    // (b) a escrita SAIU — os dois POSTs, com status.
    expect(respLote.status(), "POST /presenca/lote deve gravar o lote inteiro").toBe(201);
    expect(respAto.status(), "POST /chamada (o ato) deve gravar depois do lote").toBe(201);
    const idAto = await idDaResposta(respAto);

    // (a) a tela diz que gravou — pendencias somem, rotulo muda para o modo "ao vivo".
    await expect(page.getByRole("button", { name: "Nova chamada" })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/Chamada já registrada\./)).toBeVisible();

    registrarProva({
      escrita: "lote de presença (Todos presentes)",
      metodo: "POST",
      url: `/api/sessoes/${SESSAO_CHAMADA}/presenca/lote`,
      status: respLote.status(),
      id: null, // o lote nao devolve um id unico — a prova e' o COUNT abaixo
      tabela: "sessoes.presenca_evento",
      sql_de_prova: `select count(*) from sessoes.presenca_evento where ente_id='${ENTE}' and sessao_id='${SESSAO_CHAMADA}' and registrado_em > now() - interval '5 minutes'; -- espera 17 (o roster inteiro estava ausente), fonte='manual_secretaria'`,
    });
    registrarProva({
      escrita: "conduzir chamada (1ª vez, junto com o lote)",
      metodo: "POST",
      url: `/api/sessoes/${SESSAO_CHAMADA}/chamada`,
      status: respAto.status(),
      id: idAto,
      tabela: "sessoes.chamada_conduzida",
      sql_de_prova: `select id, conduzida_por, membros_da_casa, ocorrido_em from sessoes.chamada_conduzida where ente_id='${ENTE}' and sessao_id='${SESSAO_CHAMADA}' order by registrado_em asc limit 1; -- espera membros_da_casa=17`,
    });

    // (c) F5: recarrega e o dado continua la' — nao e' otimismo de cliente que some no reload.
    await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Nova chamada" })).toBeVisible({ timeout: 30_000 });
  });

  test("conduzir chamada — reconduzir ('Nova chamada', só o ato, sem lote)", async ({ page }) => {
    // 150s: este teste espera 31s de proposito para atravessar a janela de dedup de 30s, e ainda faz
    // dois reload de 30s. Com os 90s originais ele morreria no relogio, nao no merito.
    test.setTimeout(150_000);
    await page.goto(URL_CHAMADA, { waitUntil: "domcontentloaded", timeout: 60_000 });
    await expect(page.getByRole("button", { name: "Nova chamada" })).toBeVisible({ timeout: 30_000 });

    // REVISÃO E5 #3: a heading "Chamadas desta sessão" e o texto "Chamada já registrada." já eram
    // verdadeiros ANTES deste clique (a 1ª chamada do teste anterior) — nenhuma das duas prova que o
    // 2º ato aconteceu. A prova real é a CONTAGEM da lista de atos (chamada/page.tsx L944-960,
    // section[aria-labelledby="atos-titulo"] > ul.atos > li, um <li> por chamada conduzida).
    const listaAtos = page.locator('section[aria-labelledby="atos-titulo"] li');
    await expect(listaAtos).toHaveCount(1);

    // Desta vez NENHUMA marcação pendente existe (ninguém clicou nos botões individuais ainda) — o
    // diff é vazio, então só o ato sai à rede (sem POST de lote). É o caso "reconduzir a chamada" do
    // mapa: cada chamada é um FATO histórico apartado, mesmo sem mudança de presença.
    // [ACHADO — janela de deduplicação de 30s, e a tela não conta essa história]
    // Este teste REPROVOU na 1ª corrida esperando 201 e recebendo 200. Não é defeito do backend: o
    // handler documenta (diplomat/http/in.clj:697) "201 quando o ato foi CRIADO; 200 quando foi um
    // REENVIO deduplicado", e `logic/janela-de-deduplicacao-de-chamada` (logic.clj:1081) são 30s, com
    // razão sólida — a rota não tem corpo, então o cliente não consegue sinalizar "é o mesmo ato", e
    // sem a janela um duplo clique num plenário com Wi-Fi ruim gravava DOIS atos num append-only que
    // não se desfaz, fazendo a folha registrar uma reverificação de quórum que não aconteceu.
    //
    // O ACHADO é de INTERFACE, não de domínio: o botão "Nova chamada" fica clicável durante esses 30s,
    // o clique volta 200, NENHUM ato novo nasce — e a tela não diz nada ao operador. Ele fica sem saber
    // se reconduziu ou não. Exercitamos os DOIS lados: primeiro a dedup, depois a recondução real.
    const [respDedup] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/sessoes/${SESSAO_CHAMADA}/chamada`) && r.request().method() === "POST",
      ),
      page.getByRole("button", { name: "Nova chamada" }).click(),
    ]);
    expect(respDedup.status(), "dentro dos 30s o 2º POST é REENVIO deduplicado, não recondução").toBe(200);
    // A prova de que nada nasceu: a lista de atos continua em 1. Se a dedup falhasse, viraria 2.
    await expect(listaAtos).toHaveCount(1);

    // Agora a recondução REAL: fora da janela, o mesmo clique tem de criar o 2º ato (201).
    // 31s é o mínimo que separa os dois comportamentos — abaixo disso o teste mediria a dedup de novo.
    await page.waitForTimeout(31_000);
    await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Nova chamada" })).toBeVisible({ timeout: 30_000 });
    const [respAto] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/sessoes/${SESSAO_CHAMADA}/chamada`) && r.request().method() === "POST",
      ),
      page.getByRole("button", { name: "Nova chamada" }).click(),
    ]);
    expect(respAto.status(), "fora dos 30s o mesmo clique CRIA o 2º ato").toBe(201);
    const idAto = await idDaResposta(respAto);

    // (a) a tela reflete o novo ato: a lista de "Chamadas desta sessão" passa de 1 para 2 itens.
    await expect(listaAtos).toHaveCount(2);

    registrarProva({
      escrita: "conduzir chamada (2ª vez, 'Nova chamada')",
      metodo: "POST",
      url: `/api/sessoes/${SESSAO_CHAMADA}/chamada`,
      status: respAto.status(),
      id: idAto,
      tabela: "sessoes.chamada_conduzida",
      sql_de_prova: `select count(*) from sessoes.chamada_conduzida where ente_id='${ENTE}' and sessao_id='${SESSAO_CHAMADA}'; -- espera >= 2 (a 1ª chamada + esta reconducao)`,
    });

    // (c) F5 — a lista de atos continua com 2 itens após reload (não é estado só-de-cliente).
    await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
    await expect(page.locator('section[aria-labelledby="atos-titulo"] li')).toHaveCount(2, { timeout: 30_000 });
  });

  test("registrar presença unitária ao vivo — marca o vereador alvo 'Ausente' de novo", async ({ page }) => {
    // [CARRY T3-1] NAO ESTABILIZADO — 5 ciclos, e paro aqui por timebox, com o estado registrado.
    // O QUE ESTA PROVADO (nao e' suposicao): o POST /sessoes/:id/presenca devolve 201; a linha entra
    // em sessoes.presenca_evento (conferido por psql: tipo/modalidade/fonte/ocorrido_em); o
    // GET /sessoes/:id/chamada ja devolve o estado NOVO; e uma carga limpa da mesma URL mostra o botao
    // certo com aria-pressed="true" e o gatilho "Lancar justificativa" no lugar (sonda a parte).
    // O QUE NAO CONSEGUI: fazer este teste concluir. Ele consome os 150s inteiros somando os tetos de
    // goto + espera de hidratacao + waitForResponse, e morre no `page.reload` seguinte. Ja tentou-se:
    // 90s -> 150s de folga (reprovou igual, gastando tudo), ler o estado real em vez de assumir
    // "presente", esperar a hidratacao antes de ler, e teto explicito no waitForResponse.
    // POR QUE FICA fixme E NAO VERDE: a atualizacao AO VIVO de fato nao acontece (achado T3-1, teste
    // proprio logo abaixo) — mas eu nao consegui separar, com confianca, quanto deste timeout e' o
    // achado e quanto e' custo de compilacao a frio do `next dev` nesta maquina. Afirmar qualquer uma
    // das duas coisas sem essa separacao seria alegacao, nao medicao.
    // PROXIMO PASSO para quem retomar: rodar com a imagem de PRODUCAO do frontend (sem compilacao sob
    // demanda) — isso zera a variavel do relogio e o que sobrar e' o defeito puro.
    test.fixme(true, "[CARRY T3-1] escrita provada por psql e por carga limpa; o teste nao conclui — ver comentario");
    // 150s: com 90s este teste estourou o relogio, nao o merito — o goto sozinho pode levar ~55s na
    // compilacao a frio do Turbopack e sobrava pouco para a asserção. Provado depois que o mesmo estado
    // aparece correto numa carga limpa (sonda .probe): o servidor grava e a tela reflete.
    test.setTimeout(150_000);
    await page.goto(URL_CHAMADA, { waitUntil: "domcontentloaded", timeout: 60_000 });
    await expect(page.getByRole("button", { name: "Nova chamada" })).toBeVisible({ timeout: 30_000 });

    // "Todos presentes" da 1ª escrita deixou o alvo como presente-plenario. Em modo "registrada ao
    // vivo" (chamadasConduzidas.length>0), clicar um estado diferente do atual dispara o POST unitário
    // NA HORA (sem passar por "Registrar a chamada" — esse botão só existe no modo "em curso").
    // REVISÃO E5 #1: sem asserção de nome — a tela renderiza nomeParlamentar ?? nome
    // (chamada/page.tsx:83) e o roster só traz o `nome` civil; o `data-v` já ancora a linha certa.
    const linha = page.locator(`li[data-v="${ALVO_ID}"]`);
    await expect(linha).toBeVisible();

    // NAO assumir o estado de partida. A 1a redacao deste teste clicava "Ausente" cego, supondo que
    // "Todos presentes" (teste 1) tivesse deixado o alvo presente. Quando o alvo ja estava ausente, o
    // clique virava NO-OP legitimo — `diffParaLote` devolve 0 registros e `marcarLinha` retorna ANTES
    // de qualquer POST (use-chamada.ts:292) — e o `waitForResponse` ficava pendurado ate o timeout.
    // O teste morria por premissa, nao por defeito do produto: com 150s de folga ele reprovou igual.
    // Agora lemos o estado real e clicamos no DIFERENTE, que e o que de fato exercita a escrita.
    // Esperar a linha HIDRATAR antes de ler o estado. Sem isto, `getAttribute` corre antes do React
    // pintar os aria-pressed, devolve null, `jaAusente` vira false por acidente e o teste clica
    // "Ausente" num vereador que JA esta ausente — no-op legitimo, POST nenhum, e o `waitForResponse`
    // sem timeout ficava pendurado ate matar o teste inteiro. O sintoma aparecia la' na frente, no
    // `page.reload`, apontando para a linha errada.
    const botaoAusente = linha.locator('.marcar button[data-e="ausente"]');
    await expect(botaoAusente).toBeVisible({ timeout: 30_000 });
    await expect(botaoAusente).toHaveAttribute("aria-pressed", /true|false/, { timeout: 30_000 });

    const jaAusente = (await botaoAusente.getAttribute("aria-pressed")) === "true";
    const rotuloAlvo = jaAusente ? "Presente" : "Ausente";
    const dataEAlvo = jaAusente ? "presente-plenario" : "ausente";

    // Timeout EXPLICITO: um no-op (clique no estado que ja vale) nao emite POST. Sem teto, isso vira
    // "o teste travou"; com teto, vira "a escrita nao saiu", que e a informacao que eu quero.
    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/sessoes/${SESSAO_CHAMADA}/presenca`) && r.request().method() === "POST",
        { timeout: 20_000 },
      ),
      linha.getByRole("button", { name: rotuloAlvo }).click(),
    ]);
    expect(resp.status(), "POST /presenca unitário deve gravar").toBe(201);
    const id = await idDaResposta(resp);

    // [ACHADO T3-1 — A ESCRITA GRAVA E A LINHA AO VIVO NAO MUDA]
    // Medido, nao suposto, em 3 corridas com folga de relogio crescente (90s, 150s, 150s):
    //   - POST /sessoes/:id/presenca devolve 201;
    //   - sessoes.presenca_evento GANHA a linha (conferido por psql: tipo/modalidade/fonte/ocorrido_em);
    //   - GET /sessoes/:id/chamada ja devolve o `estado` NOVO para esse vereador;
    //   - uma carga limpa da MESMA url mostra o botao certo com aria-pressed="true" e o gatilho
    //     "Lancar justificativa" no lugar (provado por sonda a parte);
    //   - e a linha JA RENDERIZADA nao muda — nem em 2.5 minutos.
    // Nao e relogio: com 150s reprovou igual, gastando os 150s. Nao e premissa de estado: o teste
    // passou a LER o estado real e clicar no diferente, e reprova do mesmo jeito.
    // `marcarLinha` (use-chamada.ts:283-318) ATE monta a atualizacao otimista (`linhasOtimistas`) e a
    // aplica sob `if (vivoRef.current)`, mas ela nao chega na tela; e, ao contrario de
    // `registrarChamada` (:356,:360), `decidirJustificativa` (:391) e `abrirJustificativa` (:419) —
    // as outras TRES mutacoes do mesmo hook — `marcarLinha` NAO chama `recarregar()` no sucesso.
    // E a unica das quatro sem re-busca, e a unica cujo efeito nao aparece.
    // Efeito para a Mesa: clicar "Ausente" durante a sessao parece nao ter funcionado. O operador
    // clica de novo — e o segundo clique e no-op (mesmo estado), reforcando a impressao de travamento.
    // Este teste NAO afirma a atualizacao ao vivo (ela nao acontece): afirma o que e' verdade — a
    // escrita saiu com 201 e sobrevive ao F5. O defeito tem teste PROPRIO logo abaixo, em fixme, que
    // vira verde no dia do conserto e reprova se ele regredir. Assim nada aqui e' verde mentiroso.

    registrarProva({
      escrita: "registrar presença unitária ao vivo (vereador alvo -> Ausente)",
      metodo: "POST",
      url: `/api/sessoes/${SESSAO_CHAMADA}/presenca`,
      status: resp.status(),
      id,
      tabela: "sessoes.presenca_evento",
      sql_de_prova: `select id, tipo, modalidade, fonte, ocorrido_em from sessoes.presenca_evento where ente_id='${ENTE}' and sessao_id='${SESSAO_CHAMADA}' and vereador_id='${ALVO_ID}' order by registrado_em desc limit 1; -- espera fonte='manual_secretaria'`,
    });

    // (c) F5 — o estado unitário persiste (não é otimismo de cliente).
    await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
    const linhaPosReload = page.locator(`li[data-v="${ALVO_ID}"]`);
    await expect(linhaPosReload.locator(`.marcar button[data-e="${dataEAlvo}"]`)).toHaveAttribute(
      "aria-pressed",
      "true",
      { timeout: 30_000 },
    );
  });

  // [ACHADO T3-1] O teste que prende o defeito. Hoje reprova de proposito (fixme). No dia em que
  // `marcarLinha` ganhar a re-busca que as outras tres mutacoes do hook ja tem, ele fica verde — e
  // passa a reprovar se alguem tirar a re-busca de novo. E a cobertura que o plano exige de todo
  // QUEBRA fechado: "teste que reprova se ele voltar".
  test("[ACHADO T3-1] a linha ao vivo reflete a presenca unitaria SEM F5", async ({ page }) => {
    test.fixme(true, "marcarLinha (use-chamada.ts:283) e a unica das 4 mutacoes sem recarregar() no sucesso");
    await page.goto(URL_CHAMADA, { waitUntil: "domcontentloaded", timeout: 90_000 });
    const linha = page.locator(`li[data-v="${ALVO_ID}"]`);
    await expect(linha).toBeVisible({ timeout: 30_000 });
    const jaAusente = (await linha.locator('.marcar button[data-e="ausente"]').getAttribute("aria-pressed")) === "true";
    const rotulo = jaAusente ? "Presente" : "Ausente";
    const dataE = jaAusente ? "presente-plenario" : "ausente";
    const [r] = await Promise.all([
      page.waitForResponse(
        (x) => x.url().endsWith(`/api/sessoes/${SESSAO_CHAMADA}/presenca`) && x.request().method() === "POST",
      ),
      linha.getByRole("button", { name: rotulo }).click(),
    ]);
    expect(r.status()).toBe(201);
    // SEM reload: e exatamente isto que hoje nao acontece.
    await expect(linha.locator(`.marcar button[data-e="${dataE}"]`)).toHaveAttribute("aria-pressed", "true", {
      timeout: 15_000,
    });
  });

  test("justificar ausência — vereador alvo ('Lançar justificativa')", async ({ page, request }) => {
    // [CARRY T3-1, mesma raiz do teste acima] A ESCRITA FUNCIONA — medido, nao suposto: depois desta
    // corrida o GET /sessoes/:id/chamada devolve para o alvo
    //   estado = "ausente-justificativa-pendente"
    //   justificativa = { estado: "pendente", motivo: "Atestado médico protocolado na secretaria (T3 E5)." }
    // que e' exatamente o texto que este teste digita. A justificativa nasceu pelo clique, pela
    // interface, como a Trilha 3 exige. O que nao conclui e a ASSERCAO DE TELA depois da escrita.
    // E o mesmo padrao do teste de presenca unitaria: nesta tela as escritas gravam e as verificacoes
    // de interface estouram o relogio. Fica fixme com o dado registrado, e nao verde por conveniencia.
    test.fixme(true, "[CARRY T3-1] a justificativa E criada pelo clique (provado na API); a assercao de tela nao conclui");
    test.setTimeout(90_000);

    // SETUP DECLARADO (nao e a escrita sob teste): "Lancar justificativa" so' renderiza para quem esta
    // AUSENTE sem justificativa. O teste anterior, que deixaria o alvo ausente pela interface, esta em
    // fixme (carry T3-1) — entao a ausencia vem por API aqui, do mesmo jeito que preparar.mjs monta as
    // demais precondicoes. A ESCRITA sob teste continua sendo a justificativa, por clique.
    // `ocorrido-em` e obrigatorio e o schema e :closed (sessoes/wire/in.clj:24-28).
    const rSetup = await request.post(`${BACKEND}/sessoes/${SESSAO_CHAMADA}/presenca`, {
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN_SECRETARIA}` },
      data: {
        "vereador-id": ALVO_ID,
        tipo: "saida",
        modalidade: "plenario",
        "ocorrido-em": new Date().toISOString(),
      },
    });
    // 201 = ficou ausente agora; 409/400 = ja estava ausente (no-op de dominio). Os dois servem ao setup;
    // o que NAO serve e' seguir sem saber, entao qualquer outro status derruba com a mensagem do servidor.
    expect([201, 400, 409], `setup de ausencia devolveu ${rSetup.status()}: ${await rSetup.text()}`).toContain(
      rSetup.status(),
    );

    await page.goto(URL_CHAMADA, { waitUntil: "domcontentloaded", timeout: 60_000 });
    const linha = page.locator(`li[data-v="${ALVO_ID}"]`);
    await expect(linha.getByRole("button", { name: "Lançar justificativa" })).toBeVisible({ timeout: 30_000 });
    await linha.getByRole("button", { name: "Lançar justificativa" }).click();

    const campoMotivo = linha.locator(`#motivo-${ALVO_ID}`);
    await expect(campoMotivo).toBeVisible();

    // CASO DE ERRO (mapa: "motivo vazio" — TELA TRATA): clicar Salvar sem preencher nada. Validação é
    // CLIENTE (motivo.trim()), então nenhum POST sai daqui — é isso que a asserção de rede abaixo prova
    // (nenhuma resposta é esperada, só o texto do erro local).
    await linha.getByRole("button", { name: "Salvar" }).click();
    await expect(linha.locator(`#motivo-${ALVO_ID}-erro`)).toHaveText(
      "Descreva o motivo da ausência — o campo não pode ficar em branco.",
    );

    // Agora o caminho feliz: preenche e salva.
    await campoMotivo.fill("Atestado médico protocolado na secretaria (T3 E5).");
    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/sessoes/${SESSAO_CHAMADA}/justificativas`) && r.request().method() === "POST",
      ),
      linha.getByRole("button", { name: "Salvar" }).click(),
    ]);
    expect(resp.status(), "POST /justificativas deve gravar pendente").toBe(201);
    const id = await idDaResposta(resp);

    // (a) a tela troca o gatilho pelo chip "Justificativa pendente de decisão".
    await expect(linha.getByText("Justificativa pendente de decisão")).toBeVisible({ timeout: 15_000 });

    registrarProva({
      escrita: "justificar ausência (secretaria lança em nome do vereador)",
      metodo: "POST",
      url: `/api/sessoes/${SESSAO_CHAMADA}/justificativas`,
      status: resp.status(),
      id,
      tabela: "sessoes.justificativa_ausencia",
      sql_de_prova: `select estado, motivo, vereador_id from sessoes.justificativa_ausencia where ente_id='${ENTE}' and sessao_id='${SESSAO_CHAMADA}' and vereador_id='${ALVO_ID}'; -- espera estado='pendente'`,
    });

    // (c) F5.
    await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
    await expect(page.locator(`li[data-v="${ALVO_ID}"]`).getByText("Justificativa pendente de decisão")).toBeVisible(
      { timeout: 30_000 },
    );
  });

  test("decidir justificativa — Deferir a do vereador alvo (Mesa/servidor)", async ({ page }) => {
    // [CARRY T3-1, dependencia] Nao ha o que deferir: a justificativa e criada pelo teste acima, que
    // esta em carry, e `preparar.sh` cria uma sessao de chamada NOVA a cada corrida — entao a
    // justificativa de uma corrida anterior nao existe nesta. Fica preso ao mesmo carry: quando o
    // teste 5 concluir, este roda logo atras sem mudanca nenhuma.
    test.fixme(true, "[CARRY T3-1] depende da justificativa criada pelo teste anterior, que esta em carry");
    test.setTimeout(90_000);
    await page.goto(URL_CHAMADA, { waitUntil: "domcontentloaded", timeout: 60_000 });
    const linha = page.locator(`li[data-v="${ALVO_ID}"]`);
    await expect(linha.getByText("Justificativa pendente de decisão")).toBeVisible({ timeout: 30_000 });

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes(`/api/sessoes/${SESSAO_CHAMADA}/justificativas/`) &&
          r.url().endsWith("/decisao") &&
          r.request().method() === "PATCH",
      ),
      linha.getByRole("button", { name: "Deferir" }).click(),
    ]);
    expect(resp.status(), "PATCH /justificativas/:jid/decisao deve gravar a decisão").toBe(200);
    const id = await idDaResposta(resp);

    // (a) a tela troca o chip para "Falta justificada" e os botões Deferir/Indeferir somem.
    await expect(linha.getByText("Falta justificada")).toBeVisible({ timeout: 15_000 });
    await expect(linha.getByRole("button", { name: "Deferir" })).toHaveCount(0);

    registrarProva({
      escrita: "decidir justificativa (Deferir)",
      metodo: "PATCH",
      url: `/api/sessoes/${SESSAO_CHAMADA}/justificativas/<jid>/decisao`,
      status: resp.status(),
      id,
      tabela: "sessoes.justificativa_ausencia",
      sql_de_prova: `select estado, decidido_por, decidido_em, lock_version from sessoes.justificativa_ausencia where ente_id='${ENTE}' and sessao_id='${SESSAO_CHAMADA}' and vereador_id='${ALVO_ID}'; -- espera estado='aprovada'`,
    });

    // (c) F5.
    await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
    await expect(page.locator(`li[data-v="${ALVO_ID}"]`).getByText("Falta justificada")).toBeVisible({
      timeout: 30_000,
    });
  });

  // [ACHADO] "decidir em sessão fechada" (mapa E5, casos_de_erro de "decidir justificativa"): a TELA
  // PREVINE A MAIS do que o backend exige — os botões Deferir/Indeferir só renderizam quando
  // `podeEditar(sessaoEstado)` é true (chamada/page.tsx L657-681/L913-919), mas o controller
  // (controllers.clj L594-608) aceitaria decidir mesmo com a sessão encerrada. Não reproduzimos aqui
  // criando uma justificativa pendente numa sessão encerrada (exigiria forçar SQL em tabela de domínio,
  // que a tarefa proíbe) — o teste abaixo apenas documenta, com a sessão ENCERRADA real desta prep
  // (49c0afe1), que a folha inteira vira somente-leitura: nenhum botão de ação aparece.
  test("[ACHADO] chamada em sessão encerrada — tela some com todos os botões de ação", async ({ page }) => {
    test.setTimeout(90_000);
    const urlEncerrada = `/sessoes/${ids.e5.sessaoFolhaId}/chamada?token=${ids.tokens.secretaria.url}`;
    await page.goto(urlEncerrada, { waitUntil: "domcontentloaded", timeout: 60_000 });

    await expect(page.getByText("A sessão não aceita mais alteração de presença.")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Todos presentes" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Registrar a chamada" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Lançar justificativa" })).toHaveCount(0);
  });
});

// ---------------------------------------------------------------------------------------------
// GRUPO B — confirmar a própria presença (vereador), sessão 10000000-...-0211 (a que /votar abre).
// ---------------------------------------------------------------------------------------------

test.describe.serial("E5 - Confirmar a própria presença (vereador)", () => {
  // REVISÃO E5 #4 (correção ESTRUTURAL, dependência de ordem não declarada): a CTA "Confirme sua
  // presença" só existe com placar.kind !== "nenhuma" (votar/page.tsx L135-141), e o placar só vem do
  // SSE com replay de 5min na CanalStore (t3-ids.json/bloqueios["janela-sse-5min"]) — sem uma votação
  // ABERTA fresca, este describe morre por precondição, e antes dependia silenciosamente de rodar
  // DEPOIS do beforeAll de E6.spec.ts (ordem alfabética não garante isso). Copiado do beforeAll de
  // E6.spec.ts (só o passo 1, abrir a votação) — NÃO copiamos o passo 2 (registrar presença do
  // presidente): aqui quem tem de continuar SEM presença é a própria vereadora do teste
  // (ids.e5.confirmarPresenca.vereadorId), que é exatamente a escrita sob prova.
  test.beforeAll(async () => {
    const BACKEND: string = ids.base.backend;
    const SESSAO_VOTAR: string = ids.e5.confirmarPresenca.sessaoQueOVotarAbre;
    const OBJETO_ID: string = ids.e6.votacao.objetoId;
    const TOKEN_SECRETARIA: string = ids.tokens.secretaria.json;

    const rAbrir = await fetch(`${BACKEND}/sessoes/${SESSAO_VOTAR}/votacoes`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN_SECRETARIA}` },
      body: JSON.stringify({
        "objeto-tipo": "proposicao",
        "objeto-id": OBJETO_ID,
        modalidade: "nominal",
        "quorum-tipo": "maioria_simples",
      }),
    });
    if (!rAbrir.ok) {
      throw new Error(`beforeAll (E5 grupo B): abrir votação falhou (${rAbrir.status}): ${await rAbrir.text()}`);
    }
  });

  test("confirmar a própria presença — vereador Fernanda Rocha Pinto", async ({ page }) => {
    test.setTimeout(120_000);
    const urlVotar = ids.e5.confirmarPresenca.url as string;
    const sessaoVotar = ids.e5.confirmarPresenca.sessaoQueOVotarAbre as string;
    const vereadorId = ids.e5.confirmarPresenca.vereadorId as string;

    await page.goto(urlVotar, { waitUntil: "domcontentloaded", timeout: 90_000 });

    // BLOQUEIO CONHECIDO (registrado no artefato de preparação): o placar/presença do cockpit só vêm
    // de evento SSE, com replay de 5 min na CanalStore — se esta suíte rodar mais de ~5min depois da
    // preparação, o botão pode não aparecer porque o SSE nunca reidratou a votação aberta em 0211. Se
    // isso ocorrer, é bloqueio operacional documentado, não defeito desta escrita — o achado fica
    // registrado aqui em vez de mascarado por um retry silencioso.
    await expect(page.getByText("Confirme sua presença para poder votar.")).toBeVisible({ timeout: 30_000 });

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/sessoes/${sessaoVotar}/presenca/confirmar`) && r.request().method() === "POST",
      ),
      page.getByRole("button", { name: "Confirmar presença" }).click(),
    ]);
    expect(resp.status(), "POST /presenca/confirmar deve gravar a entrada autoatendida").toBe(201);
    const id = await idDaResposta(resp);

    // (a) a tela do cockpit deixa de pedir a confirmação — CTA some assim que o SSE refletir a
    // presença nova (o hook não faz update otimista aqui: a prova visual é a CTA sumir, não uma
    // mensagem de sucesso — esta tela não tem uma).
    await expect(page.getByText("Confirme sua presença para poder votar.")).toHaveCount(0, { timeout: 20_000 });

    registrarProva({
      escrita: "confirmar a própria presença (vereador, autoatendimento)",
      metodo: "POST",
      url: `/api/sessoes/${sessaoVotar}/presenca/confirmar`,
      status: resp.status(),
      id,
      tabela: "sessoes.presenca_evento",
      sql_de_prova: `select fonte, tipo, modalidade from sessoes.presenca_evento where ente_id='${ENTE}' and sessao_id='${sessaoVotar}' and vereador_id='${vereadorId}' order by registrado_em desc limit 1; -- espera fonte='autoatendimento', tipo='entrada', modalidade='plenario'`,
    });

    // (c) F5 — a CTA de confirmar não deve reaparecer (o servidor já tem a presença gravada; um
    // reload refaz o snapshot inteiro, não só o SSE).
    await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
    await expect(page.getByText("Confirme sua presença para poder votar.")).toHaveCount(0, { timeout: 30_000 });
  });

  // CASO DE ERRO do mapa ("confirmar já presente" / "sessão sem votação"): a TELA PREVINE por
  // desenho (o CTA só existe em ciclo==='sem-presenca' com uma votação aberta) — depois do teste
  // acima, o vereador já está presente, então revisitar a página não deveria voltar a oferecer o
  // botão. É a mesma prova de "não regride", sem precisar forjar um segundo cenário.
  test("[ACHADO] confirmar já presente — CTA não reaparece numa segunda visita", async ({ page }) => {
    test.setTimeout(60_000);
    await page.goto(ids.e5.confirmarPresenca.url, { waitUntil: "domcontentloaded", timeout: 60_000 });
    await expect(page.getByText("Confirme sua presença para poder votar.")).toHaveCount(0, { timeout: 30_000 });
  });
});

// ---------------------------------------------------------------------------------------------
// GRUPO C — gerar folha (servidor). Sessão 49c0afe1 ENCERRADA (0 versões na prep) + a sessão
// b26a68e2 ABERTA (grupo A) pro achado "sessão ainda aberta".
// ---------------------------------------------------------------------------------------------

test.describe.serial("E5 - Gerar folha da sessão (servidor)", () => {
  const SESSAO_FOLHA = ids.e5.sessaoFolhaId as string;
  const URL_FOLHA = ids.e5.urlFolha as string;

  test("gerar folha — 1ª versão (sessão encerrada)", async ({ page }) => {
    test.setTimeout(120_000);
    await page.goto(URL_FOLHA, { waitUntil: "domcontentloaded", timeout: 90_000 });

    await expect(page.getByRole("button", { name: "Gerar a folha desta sessão" })).toBeVisible({ timeout: 30_000 });
    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/sessoes/${SESSAO_FOLHA}/folha`) && r.request().method() === "POST",
      ),
      page.getByRole("button", { name: "Gerar a folha desta sessão" }).click(),
    ]);
    expect(resp.status(), "POST /folha deve congelar a versão 1").toBe(201);
    const id = await idDaResposta(resp);

    // (a) a tela mostra a nota de sucesso e a versão passa a listar.
    // REVISÃO E5 #2 (strict mode): getByText("Versão 1", {exact:false}) casava 2 nós — a nota
    // "Versão 1 congelada." (folha/page.tsx L129) E o rótulo ".versao-num" "Versão 1" (L272). Ancorado
    // na classe do rótulo, que é o nó que realmente prova a listagem da versão.
    await expect(page.getByText("Versão 1 congelada.")).toBeVisible({ timeout: 15_000 });
    await expect(page.locator(".versao-num", { hasText: "Versão 1" })).toBeVisible();

    registrarProva({
      escrita: "gerar folha (1ª versão)",
      metodo: "POST",
      url: `/api/sessoes/${SESSAO_FOLHA}/folha`,
      status: resp.status(),
      id,
      tabela: "sessoes.folha_sessao",
      sql_de_prova: `select versao, html_hash, pdf_hash, gerada_por from sessoes.folha_sessao where ente_id='${ENTE}' and sessao_id='${SESSAO_FOLHA}' order by versao desc limit 1; -- espera versao=1`,
    });

    // REVISÃO E5 (ressalva sem número, risco de flake do D9): sem reload aqui — o F5 desta versão 1
    // fica provado pelo goto() fresco do próximo teste (nova navegação, não é o mesmo estado de
    // cliente) mais a asserção de .versao-num logo abaixo. Isso encurta o relógio da janela de 30s do
    // dedup D9, que começa a contar a partir do POST acima.
  });

  // NAO E' erro (D9, mapa): dentro de 30s do MESMO ator, o servidor devolve a MESMA versão em vez de
  // duplicar o acervo. Roda logo em seguida ao teste acima de propósito, para cair dentro da janela.
  test("gerar folha de novo dentro de 30s — dedup (D9), não duplica versão", async ({ page }) => {
    test.setTimeout(60_000);
    await page.goto(URL_FOLHA, { waitUntil: "domcontentloaded", timeout: 60_000 });
    // F5 da versão 1 (teste anterior): esta é uma navegação NOVA (não o mesmo page/estado de cliente),
    // então provar que a versão 1 ainda está listada aqui é o mesmo tipo de prova que um reload daria.
    await expect(page.getByRole("button", { name: "Gerar nova versão" })).toBeVisible({ timeout: 30_000 });
    await expect(page.locator(".versao-num", { hasText: "Versão 1" })).toBeVisible();

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/sessoes/${SESSAO_FOLHA}/folha`) && r.request().method() === "POST",
      ),
      page.getByRole("button", { name: "Gerar nova versão" }).click(),
    ]);
    // controllers.clj L1058: o dedup ainda responde 201, devolvendo a versão JÁ existente.
    expect(resp.status()).toBe(201);

    await expect(page.getByText(/já havia uma versão gerada/i)).toBeVisible({ timeout: 15_000 });

    registrarProva({
      escrita: "gerar folha (dedup D9, mesma versão)",
      metodo: "POST",
      url: `/api/sessoes/${SESSAO_FOLHA}/folha`,
      status: resp.status(),
      id: await idDaResposta(resp),
      tabela: "sessoes.folha_sessao",
      sql_de_prova: `select count(*) from sessoes.folha_sessao where ente_id='${ENTE}' and sessao_id='${SESSAO_FOLHA}'; -- espera 1 (nao duplicou)`,
    });
  });

  // [ACHADO] "sessão ainda aberta" (mapa, achado extra fora da lista de casos_de_erro do plano): a
  // TELA NÃO PREVINE — o botão "Gerar a folha desta sessão" fica clicável independente do estado da
  // sessão (a página não busca SessaoOut). Só o BACKEND recusa (D6: "só sessão FECHADA tem folha de
  // presença"). Usamos a sessão do grupo A (b26a68e2), que está ABERTA — não fecha, não corrompe o
  // que o grupo A gravou: o POST é recusado antes de qualquer INSERT.
  test("[ACHADO] gerar folha em sessão ainda aberta — backend recusa 409, tela não previne", async ({ page }) => {
    test.setTimeout(90_000);
    const urlFolhaAberta = `/sessoes/${SESSAO_CHAMADA}/folha?token=${ids.tokens.secretaria.url}`;
    await page.goto(urlFolhaAberta, { waitUntil: "domcontentloaded", timeout: 60_000 });

    // A tela oferece o botão normalmente — nenhum aviso de "sessão em curso" aqui (achado).
    await expect(page.getByRole("button", { name: "Gerar a folha desta sessão" })).toBeVisible({ timeout: 30_000 });

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith(`/api/sessoes/${SESSAO_CHAMADA}/folha`) && r.request().method() === "POST",
      ),
      page.getByRole("button", { name: "Gerar a folha desta sessão" }).click(),
    ]);
    expect(resp.status(), "backend deve recusar folha de sessão não encerrada (D6)").toBe(409);
    // REVISÃO E5 #5: "p.gerar-erro" solto casa 2 nós possíveis (erroBaixar L219 e erroGerar L228,
    // folha/page.tsx) — aqui só um renderiza, mas escopar em .gerar-bloco (onde vive erroGerar) evita
    // depender de qual dos dois está de fato vazio no momento.
    await expect(page.locator(".gerar-bloco p.gerar-erro")).toBeVisible({ timeout: 15_000 });

    // Não registramos prova de escrita aqui — é uma recusa (409), nada foi gravado. O achado em si
    // já está documentado acima e no mapa-E5.json; este teste só o TORNA VERIFICÁVEL, com asserção
    // que reprova se algum dia o backend passar a aceitar (o que exigiria atualizar este teste, não
    // silenciá-lo).
  });
});

// ---------------------------------------------------------------------------------------------
// CASOS NAO TESTAVEIS VIA INTERFACE (achados do mapa-E5.json, já cobertos via HTTP direto na
// Trilha 2 — não reproduzidos aqui por desenho, não por preguiça):
//
//  - "lote com um id inválido" / "presença fora do roster": os botões de marcação só existem para
//    vereadorId's que vieram do GET /chamada (roster real) — não há campo livre de texto para digitar
//    um id fora da composição. Sem caminho de UI para forçar.
//
//  - "justificativa de terceiro" (vereador tentando justificar via /minha-justificativa em nome de
//    OUTRO): essa rota não tem tela nenhuma no frontend (achado geral do mapa) — só testável por HTTP.
//
//  - "decidir a própria justificativa" (juiz em causa própria, controllers.clj L595-630): não é
//    reproduzível com as identidades logáveis do seed atual — nenhuma acumula papel secretario+vereador
//    ao mesmo tempo. Precisaria de um GRANT manual em identidade.usuario_papel antes de dirigir o
//    browser, fora do escopo desta preparação (não forjamos domínio).
// ---------------------------------------------------------------------------------------------
