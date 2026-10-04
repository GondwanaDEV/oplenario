import { test, expect, request as pwRequest } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

// E10 — O balcão de atendimento ao cidadão (6.1 e-SIC): a cidadã pede pelo portal e a secretaria responde pela tela
// /atendimento. Antes desta fatia o backend tinha as rotas de ESCRITA do servidor, mas nenhuma de leitura e nenhuma
// tela: só o seed da demo respondia.
//
// AUTO-SUFICIENTE (como o E9): os dev-tokens vêm de `e2e/.artifacts/demo-ids.edn`, e a spec protocola o PRÓPRIO
// pedido antes de responder — não consome o pedido aberto da demo (que tem de seguir aberto para a apresentação e
// para o smoke) e pode rodar de novo na mesma Casa. O dev-token só vale em APP_ENV=dev/test (idp-dev).

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
    secretaria: daBloco("secretaria"),
    cidadao: daBloco("cidadao"),
  };
}

const demo = lerDemoIds();
const token = (identidadeId: string, papeis: string[]) =>
  JSON.stringify({ "identidade-id": identidadeId, "ente-id": demo.ente, papeis });
const TOK = { secretaria: token(demo.secretaria, ["secretario"]), cidadao: token(demo.cidadao, []) };
const cabecalhos = (t: string) => ({ Authorization: `Bearer ${t}`, "Content-Type": "application/json" });

test("E10 a secretaria abre /atendimento e responde um pedido de e-SIC", async ({ page }) => {
  const ctx = await pwRequest.newContext();
  const assunto = `Balcão E10 ${Date.now()}`;
  const pedido = await ctx.post(`${BACK}/portal/esic/pedidos`, {
    headers: cabecalhos(TOK.cidadao),
    data: { assunto, descricao: "Pedido da Trilha 3 para o balcão de atendimento." },
  });
  expect(pedido.status(), "a cidadã protocola pelo portal").toBe(201);
  const { protocolo } = (await pedido.json()) as { protocolo: string };

  await page.goto(`/atendimento?token=${encodeURIComponent(TOK.secretaria)}`, { waitUntil: "domcontentloaded", timeout: 90_000 });
  const link = page.getByRole("link", { name: new RegExp(`^Abrir ${protocolo}`) });
  await expect(link, "o pedido novo aparece na fila em aberto").toBeVisible({ timeout: 30_000 });
  await link.click();

  await expect(page.getByRole("heading", { name: assunto })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/CPF \*\*\*\.\d{3}\.\d{3}-\*\*/), "o CPF do requerente só aparece mascarado").toBeVisible();
  await page.getByRole("button", { name: "Responder", exact: true }).click();
  await page.getByLabel("Resposta ao cidadão").fill("Resposta da Trilha 3: segue a informação pedida.");
  await page.getByRole("button", { name: "Enviar a resposta" }).click();
  await expect(page.getByRole("status").filter({ hasText: `Resposta ao ${protocolo} registrada` })).toBeVisible({ timeout: 30_000 });

  // o servidor confirma: o pedido saiu dos abertos e está nos encerrados, respondido
  const fila = await ctx.get(`${BACK}/atendimento/esic?situacao=respondidos`, { headers: cabecalhos(TOK.secretaria) });
  expect(fila.status()).toBe(200);
  const itens = ((await fila.json()) as { itens: { protocolo: string; estado: string }[] }).itens;
  expect(itens.find((i) => i.protocolo === protocolo)?.estado).toBe("respondido");
  await ctx.dispose();
});

test("E10 quem não é da secretaria não lê o balcão -> 403", async () => {
  const ctx = await pwRequest.newContext();
  for (const rota of ["/atendimento/esic", "/atendimento/ouvidoria", "/atendimento/lgpd"]) {
    const r = await ctx.get(`${BACK}${rota}`, { headers: cabecalhos(TOK.cidadao) });
    expect(r.status(), `cidadã GET ${rota}`).toBe(403);
  }
  await ctx.dispose();
});

test("E10 a demo deixa ao menos um protocolo aberto em cada fila", async () => {
  const ctx = await pwRequest.newContext();
  for (const especie of ["esic", "ouvidoria", "lgpd"]) {
    const r = await ctx.get(`${BACK}/atendimento/${especie}`, { headers: cabecalhos(TOK.secretaria) });
    expect(r.status()).toBe(200);
    const { itens } = (await r.json()) as { itens: unknown[] };
    expect(itens.length, `fila ${especie} em aberto`).toBeGreaterThan(0);
  }
  await ctx.dispose();
});
