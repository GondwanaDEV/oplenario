import { test, expect, request as pwRequest } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

// E9 — Autorizacao de LEITURA por papel (docs/20, achado das jornadas autenticadas T1/T2).
//
// Achado (exploratorio, promovido a spec M3): as rotas read-only do legislativo e dos paineis estavam
// atras do gate GROSSO so'-'secretario'. Vereador e presidente levavam 403 em /proposicoes, /tramitacao e
// no Dashboard da Mesa — e o presidente E a Mesa, ainda assim nao a via. O front mostrava o erro generico
// "Nao foi possivel carregar" em vez do dado. Conserto: leitura aberta a 'secretario' OU 'vereador'
// (commit feat(authz): leitura de proposicoes/tramitacao/paineis aberta a vereador).
//
// Rede M3 do conserto: prova ponta a ponta (FE -> BFF -> backend -> DB) que vereador e presidente LEEM
// (200) as rotas que antes davam 403, e que a tela nao cai mais em "Nao foi possivel carregar". A ESCRITA/
// acoes seguem restritas a 'secretario' (coberto pelos *_http_in_test do backend, nao repetido aqui).
//
// PORTAVEL e ROBUSTA: constroi os dev-tokens a partir de `e2e/.artifacts/demo-ids.edn` (saida confiavel de
// `demo/semear-tudo.sh`), sem depender do `preparar.mjs`/`fixtures.sql` (que cravam ids congelados dos
// specs E1-E8 e por isso deixam o job t3-e2e informativo). O dev-token so vale em APP_ENV=dev/test (idp-dev).

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
    identidades: {
      secretaria: daBloco("secretaria"),
      presidente: daBloco("presidente"),
      vereador: daBloco("vereador"),
    },
  };
}

const demo = lerDemoIds();
// Dev-token = o mesmo formato que preparar.mjs monta: {identidade-id, ente-id, papeis} como JSON cru no
// Bearer (idp-dev confia no claim sem verificar assinatura — SO em dev/test).
const token = (identidadeId: string, papeis: string[]) =>
  JSON.stringify({ "identidade-id": identidadeId, "ente-id": demo.ente, papeis });
const TOK = {
  secretaria: token(demo.identidades.secretaria, ["secretario"]),
  presidente: token(demo.identidades.presidente, ["vereador", "admin_ente"]),
  vereador: token(demo.identidades.vereador, ["vereador"]),
};
const bearer = (t: string) => ({ Authorization: `Bearer ${t}` });

// As rotas de LEITURA que o gate grosso negava ao vereador (todas :get, sem id — o cerne da regressao).
const LEITURAS = [
  "/legislativo/proposicoes",
  "/paineis/mesa",
  "/paineis/pendencias",
  "/paineis/tramitacao",
  "/paineis/sli/sessoes",
];

for (const papel of ["vereador", "presidente"] as const) {
  test.describe(`E9 leitura autorizada por papel — ${papel}`, () => {
    for (const rota of LEITURAS) {
      test(`${papel} LE ${rota} -> 200 (era 403 pelo gate grosso so'-secretario)`, async () => {
        const ctx = await pwRequest.newContext();
        const r = await ctx.get(`${BACK}${rota}`, { headers: bearer(TOK[papel]) });
        expect(r.status(), `${papel} GET ${rota} deve ser 200`).toBe(200);
        await ctx.dispose();
      });
    }
  });
}

// Sanidade de simetria: a secretaria (que sempre pode) continua lendo — a variante OU nao quebrou o
// papel original.
test("E9 secretaria continua lendo /legislativo/proposicoes -> 200", async () => {
  const ctx = await pwRequest.newContext();
  const r = await ctx.get(`${BACK}/legislativo/proposicoes`, { headers: bearer(TOK.secretaria) });
  expect(r.status()).toBe(200);
  await ctx.dispose();
});

// Regressao de TELA (FE->BFF->backend): a pagina de proposicoes aberta pelo vereador nao pode mais cair
// no erro generico nem num gate — o dado carrega. `?token=` e' consumido pelo BFF em modo dev.
test("E9 tela /proposicoes do vereador renderiza sem 'Nao foi possivel carregar'", async ({ page }) => {
  await page.goto(`/proposicoes?token=${encodeURIComponent(TOK.vereador)}`, {
    waitUntil: "domcontentloaded",
    timeout: 90_000,
  });
  await page.waitForTimeout(2_000);
  const txt = (await page.locator("body").innerText()).replace(/\s+/g, " ");
  expect(txt, "a tela do vereador nao pode mostrar o erro generico de fetch").not.toContain(
    "Não foi possível carregar",
  );
  expect(txt, "o vereador tem leitura — nao pode cair no gate de acesso").not.toContain("Acesso restrito");
});
