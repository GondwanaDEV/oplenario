import { test, expect, request as pwRequest, type APIRequestContext } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

// E11 — As entregas de 05/10/2026, exercitadas PELA INTERFACE (docs/20, Trilha 3):
//   1. revogar acesso: o admin_ente revoga, com motivo, em /administracao; a linha fica como histórico; a pessoa
//      revogada perde o acesso na chamada seguinte (ADR-0005, adendo "Revogar acesso").
//   2. o portal do cidadão sem login: leis, vereadores e votações abrem sem UUID nem enum cru; a ficha pública da
//      matéria traz "Por onde a matéria passou".
//   3. o prazo do Executivo ao gerar o autógrafo: prazo no passado é recusado em palavras; prazo no futuro vira o
//      prazo do autógrafo.
//   4. o resultado da última votação encerrada sobrevive a recarregar o telão, sem o canal ao vivo.
//
// AUTO-SUFICIENTE (como E9 e E10): os dev-tokens vêm de `e2e/.artifacts/demo-ids.edn`; NÃO depende de
// `preparar.mjs`, nem de t3-ids.json. Cada grupo cria o PRÓPRIO mundo e não toca no de E1-E10:
//   - o grupo 1 concede acesso a pessoas NOVAS (CPF sorteado) e revoga só elas — nenhuma persona da demo, e nenhuma
//     de que E5-E9 dependam, é tocada;
//   - o grupo 3/4 cria a PRÓPRIA sessão, a PRÓPRIA matéria e a aprova pelo rito real (abrir votação + voto + encerrar,
//     as mesmas rotas de preparar.mjs/aprovarDeVerdade). Não consome a matéria aprovada de E7 (ID_APROVADA) nem o
//     objeto votado de E6, e encerra a própria sessão no fim para não deixar uma "sessão em curso" na Casa.
// A Casa é append-only: rodar de novo cria gente, matéria e sessão novas; nada aqui depende de ordem entre specs.
//
// PRECONDIÇÃO QUE O DEV-IDP ENTORTA (e que a spec trata de frente, não esconde): em modo dev o `idp-dev` não provisiona
// realm (`provisionar-realm!` lança), então POST /identidade/acessos devolve 500 DEPOIS de gravar o vínculo e os
// papéis. A prova de que o acesso foi concedido não é o status: é a leitura de GET /identidade/acessos e de GET /eu
// com a pessoa nova. Se o acesso não aparecer, a spec reprova nomeando isto.

const ARQ_DEMO = resolve(__dirname, "../.artifacts/demo-ids.edn");
const BACK = process.env.E2E_BACKEND_URL ?? "http://localhost:8888";

function lerDemoIds() {
  const edn = readFileSync(ARQ_DEMO, "utf8");
  const um = (re: RegExp) => {
    const m = edn.match(re);
    if (!m) throw new Error(`demo-ids.edn sem ${re}`);
    return m[1];
  };
  const bloco = um(/:identidades\s*\{([\s\S]*?)\}/);
  const daBloco = (k: string) => {
    const m = bloco.match(new RegExp(`:${k}\\s+#uuid\\s+"([0-9a-f-]{36})"`));
    if (!m) throw new Error(`demo-ids.edn sem identidade :${k}`);
    return m[1];
  };
  return {
    ente: um(/:ente\s+#uuid\s+"([0-9a-f-]{36})"/),
    legislatura: um(/:legislatura\s+#uuid\s+"([0-9a-f-]{36})"/),
    // o 1º vereador do cadastro semeado: quem vota (pela Mesa) na matéria própria do grupo 3/4
    vereador: um(/:vereadores\s*\[\s*\{\s*:id\s+#uuid\s+"([0-9a-f-]{36})"/),
    secretaria: daBloco("secretaria"),
    presidente: daBloco("presidente"),
  };
}

const demo = lerDemoIds();
const token = (identidadeId: string, papeis: string[]) =>
  JSON.stringify({ "identidade-id": identidadeId, "ente-id": demo.ente, papeis });
const TOK = {
  secretaria: token(demo.secretaria, ["secretario"]),
  // admin_ente SÓ (como o 1º administrador de uma Casa provisionada): a presidente tem o papel no banco
  admin: token(demo.presidente, ["admin_ente"]),
};
const cab = (t: string) => ({ Authorization: `Bearer ${t}`, "Content-Type": "application/json" });

// ---------------------------------------------------------------------------------------------- utilidades

// ---------------------------------------------------------------------------- a página abriu de verdade?
// As specs da Trilha 3 rodam em paralelo no MESMO banco: outra spec cria vereador, matéria e votação enquanto esta lê.
// Por isso (a) `aquecer` só faz o `next dev` compilar cada rota antes dos testes — qualquer resposta abaixo de 500
// serve (a ficha de uma matéria que não existe é 404 de verdade); (b) `abrir` confere o status do documento na hora,
// então 404/500 reprova em segundos dizendo qual; (c) toda comparação "a tela bate com a API" relê a API e recarrega
// a página até as duas concordarem (`toPass`), em vez de comparar duas leituras feitas em instantes diferentes.
const BASE = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const UUID_QUALQUER = "00000000-0000-0000-0000-000000000abc";

async function aquecer(rotas: string[]) {
  for (const rota of rotas) {
    let status = 0;
    const compilou = () => status >= 200 && status < 500;
    for (let i = 0; i < 10 && !compilou(); i++) {
      try {
        status = (await fetch(`${BASE}${rota}`, { redirect: "manual", signal: AbortSignal.timeout(90_000) })).status;
      } catch {
        status = 0;
      }
      if (!compilou()) await new Promise((ok) => setTimeout(ok, 1_000));
    }
    expect(compilou(), `aquecer ${rota}: o frontend nunca serviu a rota (último status ${status})`).toBe(true);
  }
}

async function abrir(page: import("@playwright/test").Page, url: string) {
  const resp = await page.goto(url, { waitUntil: "domcontentloaded", timeout: 90_000 });
  expect(resp?.status(), `o documento de ${url.split("?")[0]} abriu com status ${resp?.status()} (esperado 200)`).toBe(200);
}

test.beforeAll(async () => {
  test.setTimeout(240_000);
  const tk = encodeURIComponent(TOK.secretaria);
  await aquecer([
    `/portal/casa/${demo.ente}/leis`,
    `/portal/casa/${demo.ente}/vereadores`,
    `/portal/casa/${demo.ente}/votacoes`,
    `/portal/casa/${demo.ente}/materias/${UUID_QUALQUER}`,
    `/administracao?token=${encodeURIComponent(TOK.admin)}`,
    `/pos-aprovacao/${UUID_QUALQUER}?token=${tk}`,
    `/sessoes/${UUID_QUALQUER}/plenario?token=${tk}`,
  ]);
});


const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
// chave de cadastro/enum cru na tela: qualquer palavra com sublinhado no meio ("em_pauta", "PROJETO_LEI", "1_secretario").
// Texto para gente não tem sublinhado.
const ENUM_CRU = /\w+_\w+/;

async function textoVisivel(page: import("@playwright/test").Page): Promise<string> {
  return (await page.locator("body").innerText()).replace(/\s+/g, " ");
}

function semUuidNemEnumCru(texto: string, onde: string) {
  expect(texto.match(UUID)?.[0], `${onde}: UUID no texto visível`).toBeUndefined();
  expect(texto.match(ENUM_CRU)?.[0], `${onde}: enum/chave de cadastro crua no texto visível`).toBeUndefined();
}

// O detector tem de poder reprovar: sem isto, "sem UUID nem enum cru" seria uma asserção que nunca falha.
test("E11-0 o detector de UUID e enum cru reprova texto sujo e aceita texto limpo", () => {
  for (const sujo of [
    "Matéria 9d015b48-9453-4e10-98b0-d6fd3797938f em votação",
    "Situação: em_pauta",
    "Tipo PROJETO_LEI 12/2026",
    "Cargo 1_secretario",
  ]) {
    expect(() => semUuidNemEnumCru(sujo, "amostra"), `devia reprovar: ${sujo}`).toThrow();
  }
  expect(() => semUuidNemEnumCru("PL 12/2026 · Em Plenário · Aprovada em 05/10/2026", "amostra")).not.toThrow();
});

/** CPF com dígitos verificadores válidos (o backend confere). Sorteado: cada corrida cria gente nova. */
function cpfValido(): string {
  const d = Array.from({ length: 9 }, () => Math.floor(Math.random() * 10));
  for (const n of [9, 10]) {
    const soma = d.reduce((acc, v, i) => acc + v * (n + 1 - i), 0);
    d.push(((soma * 10) % 11) % 10);
  }
  return d.join("");
}

const carimbo = () => new Date().toISOString().replace(/[-:.TZ]/g, "").slice(4, 14);

// ================================================================================ 1. revogar acesso

type Acesso = {
  "identidade-id": string;
  nome: string;
  papel: string;
  "revogado-em": string | null;
  "revogado-por-nome": string | null;
  motivo: string | null;
};

async function acessosDaCasa(ctx: APIRequestContext): Promise<Acesso[]> {
  const r = await ctx.get(`${BACK}/identidade/acessos`, { headers: cab(TOK.admin) });
  expect(r.status(), "admin_ente lê GET /identidade/acessos").toBe(200);
  return ((await r.json()) as { acessos: Acesso[] }).acessos;
}

async function criarIdentidade(ctx: APIRequestContext, nome: string): Promise<string> {
  const r = await ctx.post(`${BACK}/identidade/identidades`, { headers: cab(TOK.admin), data: { cpf: cpfValido(), nome } });
  expect(r.status(), `criar a identidade de ${nome}`).toBe(201);
  return ((await r.json()) as { "identidade-id": string })["identidade-id"];
}

/** Concede o papel pelo backend e PROVA a concessão lendo a lista (ver o cabeçalho: o dev-idp devolve 500 depois de gravar). */
async function conceder(
  ctx: APIRequestContext,
  identidadeId: string,
  nome: string,
  papel: "auditor" | "juridico",
): Promise<void> {
  const corpo: Record<string, unknown> = {
    "identidade-id": identidadeId,
    tipo: "servidor",
    papeis: [papel],
    email: `e11.${carimbo()}@camara.gov.br`,
    ...(papel === "juridico" ? { qualificacao: "efetivo", oab: "CE 12345" } : {}),
  };
  const r = await ctx.post(`${BACK}/identidade/acessos`, { headers: cab(TOK.admin), data: corpo });
  expect([201, 500], `conceder ${papel} a ${nome}: só 201 (idp real) ou 500 (idp-dev, depois de gravar) são esperados; veio ${r.status()}`).toContain(r.status());
  const acessos = await acessosDaCasa(ctx);
  const achou = acessos.find((a) => a["identidade-id"] === identidadeId && a.papel === papel && a["revogado-em"] === null);
  expect(
    achou,
    `o acesso ${papel} de ${nome} NÃO aparece em GET /identidade/acessos depois do POST (status ${r.status()}): a concessão não gravou`,
  ).toBeTruthy();
}

async function papeisDaPessoa(ctx: APIRequestContext, identidadeId: string, papeis: string[]) {
  return ctx.get(`${BACK}/eu`, { headers: cab(token(identidadeId, papeis)) });
}

async function abrirAdministracao(page: import("@playwright/test").Page) {
  await abrir(page, `/administracao?token=${encodeURIComponent(TOK.admin)}`);
  await expect(page.getByRole("heading", { name: "Quem tem acesso" })).toBeVisible({ timeout: 60_000 });
}

test.describe("E11-1 revogar acesso (admin_ente em /administracao)", () => {
  test.setTimeout(150_000);

  test("controle interno: revoga com motivo, a linha fica como histórico, e a pessoa perde o acesso na chamada seguinte", async ({ page }) => {
    const ctx = await pwRequest.newContext();
    const nome = `Controle Interno E11 ${carimbo()}`;
    const identidade = await criarIdentidade(ctx, nome);
    await conceder(ctx, identidade, nome, "auditor");

    // antes: o acesso vale — a pessoa resolve na Casa e lê a trilha (é o que a revogação vai tirar)
    const antes = await papeisDaPessoa(ctx, identidade, ["auditor"]);
    expect(antes.status(), "antes de revogar, a pessoa resolve na Casa").toBe(200);
    expect(((await antes.json()) as { ator: { papeis: string[] } }).ator.papeis).toEqual(["auditor"]);
    const trilhaAntes = await ctx.get(`${BACK}/auditoria`, { headers: cab(token(identidade, ["auditor"])) });
    expect(trilhaAntes.status(), "antes de revogar, o controle interno lê a trilha").toBe(200);

    // o nome de quem revoga, para conferir "por <nome>" na linha de histórico
    const eu = await ctx.get(`${BACK}/meu/identidade`, { headers: cab(TOK.admin) });
    const nomeDoAdmin = ((await eu.json()) as { nome: string }).nome;

    await abrirAdministracao(page);
    const item = page.getByRole("list", { name: "Acessos concedidos" }).getByRole("listitem").filter({ hasText: nome });
    await expect(item).toContainText("Controle interno · Acesso ativo desde");

    // 1) sem motivo, não revoga: a tela diz o que falta e o acesso segue ativo
    await item.getByRole("button", { name: `Revogar acesso de ${nome} (Controle interno)` }).click();
    const form = item.getByRole("form", { name: `Revogar acesso de ${nome}` });
    await form.getByRole("button", { name: "Confirmar revogação" }).click();
    await expect(form.getByRole("alert")).toHaveText("Escreva o motivo da revogação.");
    const aindaAtivo = await papeisDaPessoa(ctx, identidade, ["auditor"]);
    expect(aindaAtivo.status(), "recusada na tela, a revogação não chegou ao servidor").toBe(200);

    // 2) com motivo, revoga
    const motivo = `Saiu da controladoria (E11 ${carimbo()})`;
    await form.getByLabel("Motivo da revogação").fill(motivo);
    await form.getByRole("button", { name: "Confirmar revogação" }).click();
    await expect(
      page.getByRole("status").filter({ hasText: `Acesso de ${nome} (Controle interno) revogado. A pessoa já não consegue entrar no sistema.` }),
    ).toBeVisible({ timeout: 30_000 });

    // 3) a linha FICA como histórico: quando, por quem, por quê — e o botão de revogar sai, o de dar de novo entra
    await expect(item).toContainText(`Revogado em`);
    await expect(item).toContainText(`por ${nomeDoAdmin}`);
    await expect(item).toContainText(`Motivo: ${motivo}`);
    await expect(item.getByRole("button", { name: `Revogar acesso de ${nome} (Controle interno)` })).toHaveCount(0);
    await expect(item.getByRole("button", { name: `Dar o acesso de novo a ${nome} (Controle interno)` })).toBeVisible();

    // 4) recarregar não apaga o histórico
    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "Quem tem acesso" })).toBeVisible({ timeout: 60_000 });
    const itemDepois = page.getByRole("list", { name: "Acessos concedidos" }).getByRole("listitem").filter({ hasText: nome });
    await expect(itemDepois).toContainText(`Motivo: ${motivo}`);

    // 5) o servidor: a lista guarda a revogação, e a MESMA pessoa (mesmo token) perde o acesso na chamada seguinte
    const acesso = (await acessosDaCasa(ctx)).find((a) => a["identidade-id"] === identidade && a.papel === "auditor");
    expect(acesso?.["revogado-em"], "a revogação ficou registrada").toBeTruthy();
    expect(acesso?.motivo).toBe(motivo);
    expect(acesso?.["revogado-por-nome"]).toBe(nomeDoAdmin);
    const depois = await papeisDaPessoa(ctx, identidade, ["auditor"]);
    expect(depois.status(), "sem papel nenhum na Casa, o vínculo se encerra e a pessoa deixa de entrar (401)").toBe(401);
    const trilhaDepois = await ctx.get(`${BACK}/auditoria`, { headers: cab(token(identidade, ["auditor"])) });
    expect(trilhaDepois.status(), "e a trilha deixa de abrir para ela").toBe(401);
    await ctx.dispose();
  });

  test("jurídico: revogar UM papel não derruba os outros da mesma pessoa", async ({ page }) => {
    const ctx = await pwRequest.newContext();
    const nome = `Juridico E11 ${carimbo()}`;
    const identidade = await criarIdentidade(ctx, nome);
    await conceder(ctx, identidade, nome, "auditor");
    await conceder(ctx, identidade, nome, "juridico");
    const antes = await papeisDaPessoa(ctx, identidade, ["auditor", "juridico"]);
    expect(((await antes.json()) as { ator: { papeis: string[] } }).ator.papeis.sort()).toEqual(["auditor", "juridico"]);

    await abrirAdministracao(page);
    const itens = page.getByRole("list", { name: "Acessos concedidos" }).getByRole("listitem").filter({ hasText: nome });
    const doJuridico = itens.filter({ hasText: "Jurídico ·" });
    await doJuridico.getByRole("button", { name: `Revogar acesso de ${nome} (Jurídico)` }).click();
    const motivo = `Fim do contrato (E11 ${carimbo()})`;
    await doJuridico.getByRole("form", { name: `Revogar acesso de ${nome}` }).getByLabel("Motivo da revogação").fill(motivo);
    await doJuridico.getByRole("button", { name: "Confirmar revogação" }).click();
    await expect(
      page.getByRole("status").filter({ hasText: `Acesso de ${nome} (Jurídico) revogado. A pessoa mantém os outros acessos que tinha.` }),
    ).toBeVisible({ timeout: 30_000 });

    // o jurídico vira histórico (e o convite de volta é por outro formulário: a OAB precisa ser reconferida); o auditor segue ativo
    await expect(doJuridico).toContainText(`Motivo: ${motivo}`);
    await expect(doJuridico).toContainText("Para dar o acesso de novo, use “Dar acesso ao jurídico”");
    await expect(itens.filter({ hasText: "Controle interno ·" })).toContainText("Acesso ativo desde");

    const depois = await papeisDaPessoa(ctx, identidade, ["auditor", "juridico"]);
    expect(depois.status(), "a pessoa segue entrando: ainda tem um papel").toBe(200);
    expect(((await depois.json()) as { ator: { papeis: string[] } }).ator.papeis, "só o jurídico saiu").toEqual(["auditor"]);
    await ctx.dispose();
  });
});

// ================================================================================ 2. o portal sem login

const ENTE = demo.ente;
const PORTAL = `/portal/casa/${ENTE}`;

async function portalJson<T>(ctx: APIRequestContext, rota: string): Promise<T> {
  const r = await ctx.get(`${BACK}/portal/casa/${ENTE}/${rota}`);
  expect(r.status(), `GET /portal/casa/…/${rota} é público`).toBe(200);
  return (await r.json()) as T;
}

test.describe("E11-2 o portal do cidadão, sem login", () => {
  test.setTimeout(300_000);

  test("/leis abre sem login: a lista bate com o servidor e a tela não mostra UUID nem enum cru", async ({ page }) => {
    const ctx = await pwRequest.newContext();
    let total = 0;
    await expect(async () => {
      ({ "normas-total": total } = await portalJson<{ "normas-total": number }>(ctx, "legislacao"));
      await abrir(page, `${PORTAL}/leis`);
      await expect(page.getByRole("heading", { level: 1, name: "Leis e normas" })).toBeVisible({ timeout: 30_000 });
      if (total > 0) await expect(page.getByText(total === 1 ? "1 norma publicada" : `${total} normas publicadas`)).toBeVisible({ timeout: 10_000 });
    }).toPass({ timeout: 120_000 });
    await expect(page).toHaveURL(new RegExp(`${PORTAL}/leis`));
    if (total > 0) {
      await expect(page.getByRole("list", { name: "Leis e normas publicadas" }).getByRole("listitem").first()).toBeVisible();
    } else {
      await expect(page.getByText("Esta Câmara ainda não publicou leis aqui.")).toBeVisible({ timeout: 30_000 });
    }
    semUuidNemEnumCru(await textoVisivel(page), "/leis");
    await ctx.dispose();
  });

  test("/vereadores abre sem login: quem exerce o mandato, com o nome parlamentar, sem UUID nem enum cru", async ({ page }) => {
    const ctx = await pwRequest.newContext();
    type V = { "nome-parlamentar": string | null; nome: string | null };
    let vereadores: V[] = [];
    const cartoes = page.getByRole("list", { name: "Vereadores em exercício" }).getByRole("listitem");
    // a E1 cria vereador e mandato em paralelo: a contagem só vale entre uma leitura da API e uma carga da página feitas juntas
    await expect(async () => {
      ({ vereadores } = await portalJson<{ vereadores: V[] }>(ctx, "vereadores"));
      expect(vereadores.length, "a Casa semeada tem vereadores em exercício").toBeGreaterThan(0);
      await abrir(page, `${PORTAL}/vereadores`);
      await expect(page.getByRole("heading", { level: 1, name: "Vereadores em exercício" })).toBeVisible({ timeout: 30_000 });
      await expect(cartoes).toHaveCount(vereadores.length, { timeout: 10_000 });
    }).toPass({ timeout: 120_000 });
    const primeiro = vereadores[0]["nome-parlamentar"];
    // a ordem da tela não é a da API, e outra spec cria vereador que entra antes: o nome tem de estar em ALGUM cartão
    if (primeiro) await expect(cartoes.filter({ hasText: primeiro }).first()).toBeVisible();
    semUuidNemEnumCru(await textoVisivel(page), "/vereadores");
    await ctx.dispose();
  });

  test("/votacoes abre sem login: a lista bate com o servidor, e abrir uma votação mostra o resultado em palavras", async ({ page }) => {
    const ctx = await pwRequest.newContext();
    const { total } = await portalJson<{ total: number }>(ctx, "votacoes");
    await abrir(page, `${PORTAL}/votacoes`);
    await expect(page.getByRole("heading", { level: 1, name: "Votações" })).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole("heading", { name: "Votações encerradas" })).toBeVisible();
    if (total > 0) {
      const lista = page.getByRole("list", { name: "Votações encerradas" });
      await expect(lista.getByRole("listitem").first()).toBeVisible({ timeout: 30_000 });
      semUuidNemEnumCru(await textoVisivel(page), "/votacoes (lista)");
      await lista.getByRole("listitem").first().getByRole("link").click();
      await expect(page.getByText(/^Para aprovar: /)).toBeVisible({ timeout: 30_000 });
      semUuidNemEnumCru(await textoVisivel(page), "/votacoes (votação aberta)");
    } else {
      await expect(page.getByText("Nenhuma votação encerrada em sessão pública por enquanto.")).toBeVisible({ timeout: 30_000 });
      semUuidNemEnumCru(await textoVisivel(page), "/votacoes (vazia)");
    }
    await ctx.dispose();
  });

  test("a ficha pública de uma matéria mostra \"Por onde a matéria passou\", etapa por etapa", async ({ page }) => {
    const ctx = await pwRequest.newContext();
    const { materias } = await portalJson<{ materias: { "proposicao-id": string }[] }>(ctx, "materias");
    // uma matéria com mais de uma movimentação, achada pela API pública (a ficha tem de mostrar o que o servidor entrega)
    type Mov = { movimentacoes: { etapa: string | null }[]; "movimentacoes-total": number };
    let alvo: { id: string; mov: Mov } | null = null;
    for (const m of materias.slice(0, 40)) {
      const mov = await portalJson<Mov>(ctx, `materias/${m["proposicao-id"]}/movimentacoes`);
      if (mov.movimentacoes.length >= 2 && mov.movimentacoes[0].etapa) {
        alvo = { id: m["proposicao-id"], mov };
        break;
      }
    }
    expect(alvo, "nenhuma matéria do portal tem 2 movimentações com etapa nomeada: a projeção do desfecho/etapa não rodou").not.toBeNull();

    const linha = page.getByRole("list", { name: "Movimentações da matéria, da mais recente para a mais antiga" }).getByRole("listitem");
    // outra spec pode estar tramitando esta mesma matéria: relê as movimentações e recarrega a ficha até baterem
    let mov = alvo!.mov;
    await expect(async () => {
      mov = await portalJson<Mov>(ctx, `materias/${alvo!.id}/movimentacoes`);
      await abrir(page, `${PORTAL}/materias/${alvo!.id}`);
      await expect(page.getByRole("heading", { name: "Por onde a matéria passou" })).toBeVisible({ timeout: 30_000 });
      await expect(linha).toHaveCount(mov.movimentacoes.length, { timeout: 10_000 });
      await expect(linha.first()).toContainText(mov.movimentacoes[0].etapa!, { timeout: 5_000 });
    }).toPass({ timeout: 120_000 });
    await expect(linha.first()).toContainText("Etapa atual");
    semUuidNemEnumCru(await textoVisivel(page), "ficha pública da matéria");
    await ctx.dispose();
  });
});

// ================================================== 3 e 4. o prazo do Executivo e o resultado depois de recarregar

function maisDias(n: number): string {
  const d = new Date(Date.now() + n * 86_400_000);
  return d.toISOString().slice(0, 10);
}
const emBr = (iso: string) => iso.split("-").reverse().join("/");

test.describe.serial("E11-3/4 sessão e matéria PRÓPRIAS: prazo do Executivo e resultado depois de recarregar", () => {
  test.setTimeout(180_000);
  let sessaoId: string;
  let proposicaoId: string;
  let ctx: APIRequestContext;

  async function sessao(): Promise<{ estado: string; lock: number }> {
    const r = await ctx.get(`${BACK}/sessoes/${sessaoId}`, { headers: cab(TOK.secretaria) });
    expect(r.status()).toBe(200);
    const d = (await r.json()) as { estado: string; "lock-version": number };
    return { estado: d.estado, lock: d["lock-version"] };
  }
  async function exigir(r: import("@playwright/test").APIResponse, oque: string) {
    expect(r.ok(), `${oque}: HTTP ${r.status()} ${await r.text()}`).toBe(true);
    return (await r.json()) as Record<string, unknown>;
  }

  test.beforeAll(async () => {
    ctx = await pwRequest.newContext();
    // sessão própria, aberta
    const criada = await exigir(
      await ctx.post(`${BACK}/sessoes`, {
        headers: cab(TOK.secretaria),
        data: {
          "sessao-legislativa-id": demo.legislatura,
          "tipo-sessao": "ordinaria",
          modalidade: "presencial",
          "agendada-para": new Date().toISOString(),
        },
      }),
      "criar a sessão própria",
    );
    sessaoId = criada.id as string;
    const s0 = await sessao();
    await exigir(
      await ctx.post(`${BACK}/sessoes/${sessaoId}/transicao`, { headers: cab(TOK.secretaria), data: { para: "aberta", "lock-version": s0.lock } }),
      "abrir a sessão própria",
    );

    // matéria própria, com texto (o autógrafo leva o texto deliberado)
    const prop = await exigir(
      await ctx.post(`${BACK}/legislativo/proposicoes`, {
        headers: cab(TOK.secretaria),
        data: {
          tipo: "projeto_lei",
          ano: new Date().getFullYear(),
          ementa: `Dispõe sobre a conferência E11 de ${carimbo()}.`,
          texto: "Art. 1º Fica instituída a conferência E11.\n\nArt. 2º Esta lei entra em vigor na data de sua publicação.",
        },
      }),
      "criar a matéria própria",
    );
    proposicaoId = prop.id as string;

    // aprovada de verdade, pelo rito real: abrir a votação, o voto de um vereador e encerrar
    const votacao = await exigir(
      await ctx.post(`${BACK}/sessoes/${sessaoId}/votacoes`, {
        headers: cab(TOK.secretaria),
        data: { "objeto-tipo": "proposicao", "objeto-id": proposicaoId, modalidade: "nominal", "quorum-tipo": "maioria_simples" },
      }),
      "abrir a votação",
    );
    await exigir(
      await ctx.post(`${BACK}/sessoes/${sessaoId}/votacoes/${votacao.id}/votos`, {
        headers: cab(TOK.secretaria),
        data: { voto: "sim", "vereador-id": demo.vereador },
      }),
      "registrar o voto",
    );
    const enc = await exigir(
      await ctx.post(`${BACK}/sessoes/${sessaoId}/votacoes/${votacao.id}/encerramento`, {
        headers: cab(TOK.secretaria),
        data: { "lock-version": (votacao["lock-version"] as number | undefined) ?? 0 },
      }),
      "encerrar a votação",
    );
    expect(enc.resultado, "a votação própria aprova (1 sim contra 0 não)").toBe("aprovada");

    // precondição PROVADA, não presumida: aprovada pelo ato e sem autógrafo
    const detalhe = await exigir(await ctx.get(`${BACK}/legislativo/proposicoes/${proposicaoId}`, { headers: cab(TOK.secretaria) }), "detalhe");
    expect(detalhe.aprovada, "a matéria própria está aprovada pelo ato").toBe(true);
    const pos = await exigir(await ctx.get(`${BACK}/legislativo/proposicoes/${proposicaoId}/pos-aprovacao`, { headers: cab(TOK.secretaria) }), "pós-aprovação");
    expect(pos.autografo ?? null, "a matéria própria nasce sem autógrafo").toBeNull();
  });

  test.afterAll(async () => {
    // não deixa uma "sessão em curso" na Casa para as outras specs (dashboard, cockpit)
    try {
      if (!sessaoId) return;
      const s = await sessao();
      if (s.estado === "aberta") {
        await ctx.post(`${BACK}/sessoes/${sessaoId}/transicao`, { headers: cab(TOK.secretaria), data: { para: "encerrada", "lock-version": s.lock } });
      }
    } finally {
      await ctx.dispose();
    }
  });

  test("autógrafo: prazo no passado é recusado em palavras; prazo no futuro vira o prazo do Executivo", async ({ page }) => {
    await abrir(page, `/pos-aprovacao/${proposicaoId}?token=${encodeURIComponent(TOK.secretaria)}`);
    const campo = page.getByLabel("Prazo de sanção ou veto do Executivo (último dia)");
    const gerar = page.getByRole("button", { name: "Gerar autógrafo e enviar ao Executivo" });
    await expect(campo).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText("Sem prazo informado, o sistema não acompanha o vencimento.")).toBeVisible();

    // 1) prazo no passado: a tela recusa em palavras e nada é gerado
    await campo.fill("2020-01-02");
    await gerar.click();
    await expect(page.getByRole("alert").filter({ hasText: "O prazo não pode ser uma data que já passou." })).toBeVisible();
    // a recusa também vale no servidor, para quem não passa pela tela
    const passado = await ctx.post(`${BACK}/legislativo/proposicoes/${proposicaoId}/autografo`, {
      headers: cab(TOK.secretaria),
      data: { "prazo-resposta-em": "2020-01-02T23:59:59Z" },
    });
    expect(passado.status(), "o servidor recusa prazo anterior a agora").toBe(400);
    const semAutografo = await (await ctx.get(`${BACK}/legislativo/proposicoes/${proposicaoId}/pos-aprovacao`, { headers: cab(TOK.secretaria) })).json();
    expect(semAutografo.autografo ?? null, "nenhum autógrafo nasceu das duas recusas").toBeNull();

    // 2) prazo no futuro: a frase diz até quando, avisa que não se altera, e gera
    const dia = maisDias(20);
    await campo.fill(dia);
    await expect(page.getByText(`O Executivo tem até ${emBr(dia)} para sancionar ou vetar.`)).toBeVisible();
    await expect(page.getByText("Depois de gerado, o prazo não pode ser alterado.")).toBeVisible();
    await gerar.click();
    await expect(page.getByRole("status").filter({ hasText: "Autógrafo gerado e enviado ao Executivo" })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("heading", { name: "Prazo do Executivo" })).toBeVisible();
    await expect(page.getByText("Restam para o Executivo sancionar ou vetar.", { exact: false })).toBeVisible();

    // 3) o servidor guardou o prazo escolhido (fim do dia da Casa: ao menos 19 dias adiante, no máximo 21)
    const pos = (await (await ctx.get(`${BACK}/legislativo/proposicoes/${proposicaoId}/pos-aprovacao`, { headers: cab(TOK.secretaria) })).json()) as {
      autografo: { "prazo-resposta-em": string | null } | null;
    };
    const prazo = pos.autografo?.["prazo-resposta-em"];
    expect(prazo, "o autógrafo guarda o prazo informado").toBeTruthy();
    const dias = (new Date(prazo!).getTime() - Date.now()) / 86_400_000;
    expect(dias).toBeGreaterThan(19);
    expect(dias).toBeLessThan(21.5);
  });

  test("telão: o resultado da votação encerrada continua na tela depois de recarregar, mesmo sem o canal ao vivo", async ({ page }) => {
    // Sem o canal ao vivo: só o servidor (GET /sessoes/:id/votacao-encerrada) pode devolver o resultado. É o
    // caso de quem abre ou recarrega o telão depois da janela de replay do canal.
    const canalBarrado: string[] = [];
    page.on("requestfailed", (req) => {
      if (req.url().endsWith(`/api/sessoes/${sessaoId}/plenario`)) canalBarrado.push(req.url());
    });
    await page.route(`**/api/sessoes/${sessaoId}/plenario`, (rota) => rota.abort());
    await abrir(page, `/sessoes/${sessaoId}/plenario?token=${encodeURIComponent(TOK.secretaria)}`);

    const resultado = async () => {
      await expect(page.getByRole("heading", { name: "Votação encerrada" })).toBeVisible({ timeout: 60_000 });
      await expect(page.getByText("aprovada", { exact: true })).toBeVisible();
      const contagem = page.getByLabel("Contagem de votos");
      await expect(contagem).toContainText("Sim");
      await expect(contagem).toContainText("1");
    };
    await resultado();
    await page.reload({ waitUntil: "domcontentloaded" });
    await resultado();
    // a prova de que o canal ao vivo estava mesmo barrado (senão o resultado poderia vir do replay do canal)
    expect(canalBarrado.length, "o pedido do canal ao vivo foi barrado nas duas cargas").toBeGreaterThanOrEqual(2);
  });
});
