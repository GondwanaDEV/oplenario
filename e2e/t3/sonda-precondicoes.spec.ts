import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

// SONDA DE PROVA das precondicoes (nao e um dos 8 specs da T3). Abre no browser as 4 telas cujo
// estado o preparar.mjs alegou ter deixado pronto e afirma o que TEM de estar na tela. Existe para
// que "preparei" nao seja alegacao: se a precondicao nao vingou, isto reprova aqui, nao no spec.
const ids = JSON.parse(readFileSync(resolve(__dirname, ".artifacts/t3-ids.json"), "utf8"));

// [QUARENTENA SSE — opt-in E2E_T3_SSE] As 3 sondas de SESSAO AO VIVO abaixo observam estado que (a) depende
// de evento SSE dentro da janela de 5min (o cockpit nao hidrata placar/presenca por snapshot — achado
// janela-sse-5min) e (b) e' MUTADO em paralelo pelos specs reais (E5 grupo A conduz a chamada -> "Registrar
// a chamada" some; E6/E5-B abrem votacao). Como sondas de precondicao correndo em paralelo com quem muta a
// mesma sessao, sao intrinsecamente instaveis no CI e derrubam a fila. As assercoes reais vivem nos specs
// E5/E6 (tambem opt-in). Rodam LOCALMENTE dentro da janela com E2E_T3_SSE=1. As sondas DETERMINISTICAS
// (E2/E7/E8/E1/E3/E4) seguem sempre ligadas — sao a prova barata de que a prep vingou.
const SSE = !!process.env.E2E_T3_SSE;

test("E5 — a sessao de chamada esta ABERTA e com o roster inteiro sem marcacao", async ({ page }) => {
  test.skip(!SSE, "sonda de sessao ao vivo (chamada mutada em paralelo por E5 grupo A) — opt-in E2E_T3_SSE");
  await page.goto(ids.e5.urlChamada, { timeout: 90_000 });
  await expect(page.getByRole("button", { name: "Registrar a chamada" })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "Todos presentes" })).toBeVisible();
});

test("E5 — /votar com :vereador oferece 'Confirmar presenca'", async ({ page }) => {
  test.skip(!SSE, "sonda de cockpit ao vivo (janela SSE 5min) — opt-in E2E_T3_SSE");
  await page.goto(ids.e5.confirmarPresenca.url, { timeout: 90_000 });
  await expect(page.getByRole("button", { name: "Confirmar presença" })).toBeVisible({ timeout: 30_000 });
});

test("E6 — /votar com :presidente oferece os tres botoes de voto", async ({ page }) => {
  test.skip(!SSE, "sonda de cockpit ao vivo (janela SSE 5min) — opt-in E2E_T3_SSE");
  await page.goto(ids.e6.url, { timeout: 90_000 });
  await expect(page.getByRole("button", { name: "Sim", exact: true })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "Não", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Abster", exact: true })).toBeVisible();
});

test("E2 — /expediente lista os modelos e o botao de gerar deixa de ser inalcancavel", async ({ page }) => {
  await page.goto(ids.e2.url, { timeout: 90_000 });
  await expect(page.getByText("Ofício padrão da Mesa")).toBeVisible({ timeout: 30_000 });
});

test("E8 — /notificacoes com :vereador tem item nao-lido com 'Marcar como lida'", async ({ page }) => {
  await page.goto(ids.e8.url, { timeout: 90_000 });
  await expect(page.getByRole("button", { name: "Marcar como lida" }).first()).toBeVisible({ timeout: 30_000 });
});

test("E7 — /pos-aprovacao oferece gerar autografo", async ({ page }) => {
  await page.goto(ids.e7.url, { timeout: 90_000 });
  await expect(page.getByRole("button", { name: /Gerar autógrafo/i })).toBeVisible({ timeout: 30_000 });
});

test("E1 — a lista de vereadores abre e oferece 'Novo vereador'", async ({ page }) => {
  await page.goto(ids.e1.urlVereadorSemMandato, { timeout: 90_000 });
  await expect(page.getByRole("button", { name: "Novo vereador" })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "Registrar mandato" })).toBeVisible();
});

test("E3 — o editor de proposicao abre", async ({ page }) => {
  await page.goto(ids.e3.urlEditor, { timeout: 90_000 });
  await expect(page.getByText("Editor de proposição")).toBeVisible({ timeout: 30_000 });
  await expect(page.locator("form.formulario-proposicao")).toBeVisible();
});

test("E4 — o editor de parecer da secretaria abre no parecer editavel", async ({ page }) => {
  await page.goto(ids.e4.urlParecerEditavel, { timeout: 90_000 });
  await expect(page.getByRole("button", { name: /Emitir parecer|Salvar/i }).first()).toBeVisible({ timeout: 30_000 });
});

test("E4 — a tela de assinatura abre para o relator (posse ok, nao 404)", async ({ page }) => {
  await page.goto(ids.e4.urlAssinarVereador, { timeout: 90_000 });
  await expect(page.getByText(/n[ãa]o encontrad/i)).toHaveCount(0);
  await expect(page.locator("main").first()).toBeVisible({ timeout: 30_000 });
});
