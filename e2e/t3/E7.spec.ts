import { test, expect } from "@playwright/test";
import { readFileSync, mkdirSync, appendFileSync } from "node:fs";
import { resolve } from "node:path";

// GRUPO E7 — "Pós-aprovação" (servidor). Lê e2e/t3/mapa-E7.json (o mapa das escritas e os casos de erro
// medidos ao vivo) e e2e/t3/.artifacts/t3-ids.json (os ids REAIS deixados pelo preparar.mjs — nenhum UUID
// aqui é inventado). As 2 escritas do grupo:
//   1) Gerar autógrafo (secretaria)         — POST /legislativo/proposicoes/:id/autografo
//   2) Registrar resposta do Executivo      — POST /legislativo/autografos/:id/resposta
//
// GUARDA-AUTOGRAFO-VOTACAO (commits 254a768/86b64fa/d93b077, ledger Fase 11 — T3-A). Este spec nasceu
// para EXERCITAR o achado T3-A: `POST .../autografo` gerava um ato jurídico numerado (o autógrafo, que vai
// ao Prefeito) para matéria que a Câmara nunca aprovou — o único gate ficava no LINK de entrada
// (ficha-materia/acoes-card.tsx), e /pos-aprovacao/:id era navegável direto por URL para qualquer
// proposição. Isso foi consertado em duas camadas: `controllers/gerar-autografo` guarda na BORDA (409,
// `:conflito/proposicao-nao-aprovada`) e `Repo/gerar-autografo-e-abrir-tramitacao!` REVERIFICA dentro da
// tx (fecha a janela TOCTOU). A pré-condição não é o rótulo `proposicao.estado` (texto livre, chave de
// template por câmara, sem CHECK, nenhuma rota HTTP o move) — é o ATO: existe votação ENCERRADA com
// resultado 'aprovada' sobre esta proposição (`db/votacao.clj/aprovada-em-votacao?`), exposto ao FE em
// `ProposicaoDetalheOut.aprovada` (booleano obrigatório). Com o conserto, os testes abaixo passaram a
// provar a GUARDA, não mais o defeito: a rota continua navegável por URL (isso não mudou — não era o
// achado), mas o botão fica INERTE (`disabled` + `aria-disabled` + `aria-describedby`, nunca escondido
// sem explicação — mesma disciplina "sem dado falso" do resto da tela) e o servidor recusa com 409.
//
// `preparar.mjs` (guarda-autografo-votacao) passou a produzir DOIS alvos distintos para este grupo:
//   - e7.proposicaoParaAutografoId — NUNCA aprovada (em_comissoes/em_pauta/etc., texto vigente, sem
//     autógrafo). Serve a prova da RECUSA: gerar autógrafo aqui tem de dar 409, sempre.
//   - e7.proposicaoAprovadaId — aprovada DE VERDADE nesta corrida, pelo RITO REAL (abrir votação +
//     registrar voto + encerrar — as mesmas 3 rotas que a sessão do E6 já usa; nominal, maioria_simples,
//     1 voto 'sim'). Esta é o único alvo em que o caminho feliz de gerar autógrafo é alcançável — e
//     alcançá-lo é o que destrava "registrar resposta do Executivo" (autógrafo + tramitação executiva
//     'aguardando' nascem juntos, na MESMA tx).
const ids = JSON.parse(readFileSync(resolve(__dirname, ".artifacts/t3-ids.json"), "utf8"));
const mapa = JSON.parse(readFileSync(resolve(__dirname, "mapa-E7.json"), "utf8"));
void mapa; // mantido só pra quem lê o spec ir direto na fonte do achado original sem procurar o arquivo.

const BACKEND: string = ids.base.backend;
const FRONTEND: string = ids.base.frontend;
const ENTE: string = ids.ente;
const TSEC_URL: string = ids.tokens.secretaria.url; // token url-encoded, pro browser (?token=)
const TSEC_JSON: string = ids.tokens.secretaria.json; // token cru, pro header Authorization: Bearer

// ID_ALVO — nunca aprovada (nem pelo rótulo morto, nem pelo ATO). Alvo fixo da prova de RECUSA: gerar
// autógrafo aqui tem de devolver 409 sempre, e nenhum autógrafo pode aparecer no banco depois.
const ID_ALVO: string = ids.e7.proposicaoParaAutografoId;

// ID_APROVADA — aprovada DE VERDADE nesta corrida (preparar.mjs abriu a votação, registrou o voto e
// encerrou com resultado='aprovada', e PROVOU via GET /legislativo/proposicoes/:id .aprovada===true antes
// de escrever o artefato — ver e2e/t3/preparar.mjs, seção "E7 — aprovando DE VERDADE"). É o único alvo do
// caminho feliz: gerar autógrafo aqui tem de devolver 201.
const ID_APROVADA: string = ids.e7.proposicaoAprovadaId;

// Candidato do próprio mapa (e7.candidatosSemAutografo), livre de ID_ALVO e ID_APROVADA — nenhum dos dois
// grupos de teste acima o toca. Usado só de LEITURA no último teste (nunca gera autógrafo nele): prova que
// a tela nunca oferece "Registrar retorno" sem um autógrafo já existente, qualquer que seja o estado de
// aprovação dele (a Casa é append-only e acumula entre corridas — este teste não presume se ele está
// aprovado ou não, só que SEM autógrafo o card de resposta ao Executivo não existe).
const ID_SEM_AUTOGRAFO: string = (
  ids.e7.candidatosSemAutografo as Array<{ id: string }>
).find((c) => c.id !== ID_ALVO && c.id !== ID_APROVADA)!.id;

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
  // ids capturados da resposta real do teste 2 (o caminho feliz), usados pelos testes determinísticos que
  // vêm depois (nenhum UUID inventado — tudo sai da resposta HTTP da própria corrida).
  let autografoId: string | undefined;
  let tramitacaoId: string | undefined;

  // PRECONDIÇÃO, checada contra a API antes de qualquer clique. O grupo CONSOME uma proposição por
  // corrida (autógrafo é UNIQUE por proposição, e a Casa é append-only: não há como desfazer). Sem esta
  // checagem, uma 2a corrida sobre os MESMOS ids falharia lá no teste do caminho feliz, por timeout de
  // locator — erro opaco que parece defeito de produto e não é. Falha (não pula) de propósito: verde
  // fabricado é pior que vermelho, e a ação corretiva é uma linha.
  test.beforeAll(async () => {
    for (const [rotulo, id] of [
      ["ID_ALVO (não aprovada)", ID_ALVO],
      ["ID_APROVADA (aprovada de verdade)", ID_APROVADA],
    ] as const) {
      const r = await fetch(`${BACKEND}/legislativo/proposicoes/${id}/pos-aprovacao`, {
        headers: { Authorization: `Bearer ${TSEC_JSON}` },
      });
      expect(r.status, `GET pos-aprovacao de ${rotulo} (${id}) devia ser 200`).toBe(200);
      const corpo = (await r.json()) as { autografo?: unknown };
      expect(
        corpo.autografo ?? null,
        `PRECONDIÇÃO ESTRAGADA: ${rotulo} (${id}) JÁ tem autógrafo (t3-ids.json está velho — este grupo ` +
          `consome 1 proposição por corrida). Rode ./e2e/t3/preparar.sh e rode o spec de novo.`,
      ).toBeNull();
    }

    // ID_APROVADA precisa estar REALMENTE aprovada (o ATO, não o rótulo) — prova, não suposição, mesma
    // leitura que o guard do backend usa (ProposicaoDetalheOut.aprovada).
    const rDetalhe = await fetch(`${BACKEND}/legislativo/proposicoes/${ID_APROVADA}`, {
      headers: { Authorization: `Bearer ${TSEC_JSON}` },
    });
    expect(rDetalhe.status, `GET detalhe de ID_APROVADA (${ID_APROVADA}) devia ser 200`).toBe(200);
    const detalhe = (await rDetalhe.json()) as { aprovada?: boolean };
    expect(
      detalhe.aprovada,
      `PRECONDIÇÃO ESTRAGADA: ID_APROVADA (${ID_APROVADA}) tem aprovada=${detalhe.aprovada}, não true — ` +
        `preparar.mjs deveria ter aprovado esta matéria pelo rito real antes de escrever t3-ids.json.`,
    ).toBe(true);
  });

  // -----------------------------------------------------------------------------------------------
  // 0) A rota /pos-aprovacao/:id continua navegável direto por URL para qualquer proposição — isso NÃO
  // mudou, e não era o achado (o achado era o botão funcionar sem checar aprovação). O que mudou: o botão
  // fica INERTE (disabled + aria-disabled + aria-describedby) e a explicação fica visível — provado pelo
  // papel/estado acessível, não por classe CSS. "Ver pós-aprovação" (o link de entrada da ficha) continua
  // ausente para matéria não aprovada — mas isso nunca foi autorização, só navegação.
  // -----------------------------------------------------------------------------------------------
  test("Ver pós-aprovação não aparece na ficha (não aprovada); a rota segue navegável por URL, mas o botão fica inerte", async ({
    page,
  }) => {
    await page.goto(urlFicha(ID_ALVO), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("heading", { name: "Ações", exact: true })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("link", { name: "Ver pós-aprovação" })).toHaveCount(0);

    // a página de destino abre normalmente por URL direta (nunca foi isso que o gate de entrada impedia)
    await page.goto(urlPosAprovacao(ID_ALVO), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("heading", { name: "Autógrafo" })).toBeVisible({ timeout: 30_000 });
    await expect(
      page.getByText("Esta matéria ainda não foi aprovada em votação pela Câmara."),
    ).toBeVisible();

    // o botão está lá (não escondido — "sem dado falso": some sem dizer o motivo seria pior), mas INERTE.
    // toBeDisabled() do Playwright reconhece tanto o atributo `disabled` nativo quanto `aria-disabled="true"`
    // — é a checagem pelo papel/estado acessível que o briefing pediu, não por classe CSS.
    const botao = page.getByRole("button", { name: "Gerar autógrafo e enviar ao Executivo" });
    await expect(botao).toBeVisible();
    await expect(botao).toBeDisabled();
    await expect(botao).toHaveAttribute("aria-disabled", "true");

    // a explicação está LIGADA ao botão via aria-describedby (não é só um texto solto na página)
    const describedBy = await botao.getAttribute("aria-describedby");
    expect(describedBy).toBe("pos-aprovacao-nao-aprovada");
    const explicacao = page.locator(`#${describedBy}`);
    await expect(explicacao).toBeVisible();
    await expect(explicacao).toHaveText(
      "O autógrafo é o ato que leva a matéria aprovada ao Executivo — só pode ser gerado depois que a Câmara aprovar esta matéria em votação.",
    );
  });

  // -----------------------------------------------------------------------------------------------
  // 1) A RECUSA. Como o botão está inerte, o POST não sai mais de um clique — reenviamos o mesmo POST que
  // o botão dispararia, fora da tela (mesmo idioma que este spec já usa para os casos de corrida
  // determinísticos, e que E6.spec.ts estabeleceu para o irmão "votar duas vezes"). Sobre ID_ALVO
  // (nunca aprovada): 409, e a prova de "nenhum autógrafo nasceu" é uma CONSULTA real ao backend
  // (GET pos-aprovacao), não a ausência de um elemento na tela.
  // -----------------------------------------------------------------------------------------------
  test("Gerar autógrafo — matéria NÃO aprovada é recusada (409), e nenhum autógrafo aparece no banco depois", async () => {
    const r = await fetch(`${BACKEND}/legislativo/proposicoes/${ID_ALVO}/autografo`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${TSEC_JSON}` },
      body: JSON.stringify({}),
    });
    expect(r.status).toBe(409);
    const corpo = (await r.json()) as { erro?: string };
    // a borda TRADUZ o :conflito/proposicao-nao-aprovada com a mensagem de domínio (diplomat/http/in.clj,
    // catch dedicado) — ao contrário do :validacao/invalido do guard de duplicidade (teste abaixo), que o
    // interceptor global troca por "requisicao invalida". Aqui a mensagem REAL chega no corpo.
    expect(corpo.erro).toBe("gerar-autografo: a materia nao foi aprovada em votacao pela Camara");

    // PROVA REAL: consulta ao backend, não ausência de elemento na tela. O guard de borda RODA PRIMEIRO
    // (antes da re-verificação na tx) — então nem a re-verificação chega a ser exercitada por este POST,
    // mas o resultado observável (nenhum autógrafo) é o mesmo dos dois pontos de guarda.
    const rProva = await fetch(`${BACKEND}/legislativo/proposicoes/${ID_ALVO}/pos-aprovacao`, {
      headers: { Authorization: `Bearer ${TSEC_JSON}` },
    });
    expect(rProva.status).toBe(200);
    const prova = (await rProva.json()) as { autografo?: unknown };
    expect(prova.autografo ?? null).toBeNull();
  });

  // -----------------------------------------------------------------------------------------------
  // 2) Gerar autógrafo — o CAMINHO FELIZ DE VERDADE, agora que ele existe: ID_APROVADA foi aprovada pelo
  // rito real (votação encerrada, resultado 'aprovada', provado no beforeAll). Isto também é o que
  // destrava "registrar resposta do Executivo" na mesma página (autógrafo + tramitação executiva
  // 'aguardando' nascem juntos, na MESMA tx).
  // -----------------------------------------------------------------------------------------------
  test("Gerar autógrafo — caminho feliz sobre matéria REALMENTE aprovada (201)", async ({ page }) => {
    await page.goto(urlPosAprovacao(ID_APROVADA), { waitUntil: "domcontentloaded", timeout: 90_000 });
    const botao = page.getByRole("button", { name: "Gerar autógrafo e enviar ao Executivo" });
    await expect(botao).toBeVisible({ timeout: 30_000 });
    // desta vez o botão está ATIVO — é a prova simétrica do teste 0 (mesmo papel acessível, estado oposto).
    await expect(botao).toBeEnabled();

    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.url().includes(`/api/legislativo/proposicoes/${ID_APROVADA}/autografo`) &&
          r.request().method() === "POST",
        { timeout: 30_000 },
      ),
      botao.click(),
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
      escrita: "Gerar autógrafo (caminho feliz — matéria realmente aprovada pelo rito real)",
      metodo: "POST",
      url: `/legislativo/proposicoes/${ID_APROVADA}/autografo`,
      status: resp.status(),
      id: autografoId,
      tabela: "legislativo.autografo",
      sql_de_prova:
        `SELECT a.id, a.numero, a.ano, a.proposicao_id, a.destinatario_texto, t.id AS tramitacao_id, t.estado ` +
        `FROM legislativo.autografo a JOIN legislativo.tramitacao_executiva t ON t.autografo_id = a.id AND t.ente_id = a.ente_id ` +
        `WHERE a.ente_id = '${ENTE}' AND a.proposicao_id = '${ID_APROVADA}'; ` +
        `-- espera 1 linha, t.estado = 'aguardando' (autógrafo + tramitação abertos na mesma tx)`,
    });
  });

  // -----------------------------------------------------------------------------------------------
  // 3) caso de erro "dois autógrafos" — bem tratado pelo backend (:validacao/invalido -> 400), mas só
  // alcançável por corrida de 2 cliques/2 abas (assim que o 1o existe, a tela muda de branch e o botão
  // "Gerar autógrafo" some — provado no teste anterior). Reenviamos o MESMO POST fora do clique, sobre a
  // MESMA ID_APROVADA (que já tem autógrafo desde o teste 2) — determinístico, sem depender de timing de
  // corrida real. A guarda de aprovação já passa (a matéria ESTÁ aprovada); quem recusa aqui é a
  // duplicidade, não a aprovação — os dois guards são independentes.
  // -----------------------------------------------------------------------------------------------
  test("Gerar autógrafo — segunda vez sobre a mesma matéria é recusada (400, dois autógrafos)", async () => {
    test.skip(!autografoId, "depende do autógrafo do teste anterior já ter sido gerado");

    const r = await fetch(`${BACKEND}/legislativo/proposicoes/${ID_APROVADA}/autografo`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${TSEC_JSON}` },
      body: JSON.stringify({}),
    });
    expect(r.status).toBe(400);
    const corpo = (await r.json()) as { erro?: string };

    // MEDIDO AO VIVO (achado de interface, não desta guarda): o corpo NÃO carrega a mensagem de domínio.
    // O controller lança "gerar-autografo: a proposicao ja tem autografo (UNIQUE por proposicao)"
    // (legislativo/controllers.clj, :tipo :validacao/invalido), mas o interceptor GLOBAL de erro
    // (oplenario/interceptors.clj) troca TODA :validacao/invalido por {"erro":"requisicao invalida"} — por
    // decisão explícita ("Não vaza detalhe de erro interno no corpo", docstring do mesmo ns). Ao contrário
    // do :conflito/proposicao-nao-aprovada (teste 1 acima), que a borda traduz com ex-message.
    expect(corpo.erro).toBe("requisicao invalida");
  });

  // -----------------------------------------------------------------------------------------------
  // 4) Registrar resposta do Executivo — caminho feliz. Destravado pelo teste 2 (mesma tx abriu a
  // tramitação executiva 'aguardando'); mesma página (/pos-aprovacao/:id), agora com o card "Prazo do
  // Executivo" e o botão "Registrar retorno".
  // -----------------------------------------------------------------------------------------------
  test("Registrar resposta do Executivo — grava (200), mas a tela nunca confirma", async ({ page }) => {
    await page.goto(urlPosAprovacao(ID_APROVADA), { waitUntil: "domcontentloaded", timeout: 90_000 });
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
        { timeout: 30_000 },
      ),
      page.getByRole("button", { name: "Registrar retorno" }).click(),
    ]);

    // b) a escrita saiu de fato
    expect(resp.status()).toBe(200);

    // a) [ACHADO DE INTERFACE, não desta guarda] a confirmação "Retorno do Executivo registrado" NUNCA
    // aparece na tela. MEDIDO nos dois lados: HTTP 200 acima + o card "Desfecho" troca de fato. CAUSA, na
    // fonte (conteudo-pos-aprovacao.tsx): o <p role="status">{mensagemStatus}</p> do "registrar" está
    // DENTRO do branch `{tramitacaoExecutiva?.estado === "aguardando" && (…)}`. No sucesso, o handler faz
    // setPosAprovacaoLocal(…estado 'sancionado') e setMensagemStatus(…) no MESMO tick — o branch inteiro
    // desmonta antes de pintar, e a mensagem morre com ele. É o mesmo defeito que o autor previu e
    // resolveu para a ação irmã "gerar" (mensagem içada para região compartilhada fora dos branches) mas
    // não aplicou ao "registrar" — não é escopo desta frente (T3-A é sobre a GUARDA, não esta confirmação),
    // documentado aqui só para não parecer regressão nova.
    await expect(page.getByRole("heading", { name: "Desfecho" })).toBeVisible();
    await expect(
      page.getByText("A matéria foi sancionada e segue para promulgação/publicação."),
    ).toBeVisible();
    // asserção do comportamento REAL (não do desejado): a confirmação não está em lugar nenhum da página.
    await expect(page.getByText("Retorno do Executivo registrado")).toHaveCount(0);

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
  // 5) caso de erro "resposta duplicada / tramitação já respondida" — o mapa registra isto como correlato
  // de "dois autógrafos", achado por leitura de código. AO CONTRÁRIO de "dois autógrafos", este NÃO
  // depende de corrida: o teste anterior já deixou a tramitação em estado TERMINAL ('sancionado'), então
  // qualquer POST novo pro MESMO autógrafo bate no guard "só se responde uma tramitação 'aguardando'" de
  // forma determinística.
  // -----------------------------------------------------------------------------------------------
  test("Registrar resposta — tramitação já respondida (terminal) é recusada (409)", async () => {
    test.skip(!autografoId, "depende do autógrafo gerado no teste 2");

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
  // 6) caso de erro "resposta sem autógrafo" — TRATADO pelo backend (404), mas NÃO alcançável pela tela em
  // uso normal (autógrafo e tramitação nascem juntos, na mesma tx — não existe fluxo de UI que produza um
  // autógrafo-id sem tramitação correspondente). Documentado por chamada direta com id inventado.
  // -----------------------------------------------------------------------------------------------
  test("Registrar resposta — autógrafo inexistente retorna 404 (não alcançável pela tela hoje)", async () => {
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
  // 7) GAP de fixture, não de UI — confirma que, SEM autógrafo, a tela nunca oferece "Registrar retorno"
  // (o card só renderiza dentro do branch `autografo &&`). Usa um candidato do próprio mapa
  // (candidatosSemAutografo) livre de ID_ALVO/ID_APROVADA — só leitura, nenhum autógrafo é gerado aqui.
  // Não presume o estado de aprovação deste candidato (a Casa é append-only e acumula aprovações reais
  // entre corridas — ver nota de ID_SEM_AUTOGRAFO acima): a única coisa que este teste prova é que, sem
  // autógrafo, o card de resposta ao Executivo não existe, qualquer que seja a matéria.
  // -----------------------------------------------------------------------------------------------
  test("Registrar resposta — sem autógrafo, a tela nunca oferece o botão", async ({ page }) => {
    await page.goto(urlPosAprovacao(ID_SEM_AUTOGRAFO), { waitUntil: "domcontentloaded", timeout: 90_000 });
    await expect(page.getByRole("heading", { name: "Autógrafo" })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Registrar retorno" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Prazo do Executivo" })).toHaveCount(0);
  });
});
