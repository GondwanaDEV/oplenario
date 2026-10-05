import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

// SONDA DE PROVA das precondicoes (nao e um dos 8 specs da T3). Abre no browser as 4 telas cujo
// estado o preparar.mjs alegou ter deixado pronto e afirma o que TEM de estar na tela. Existe para
// que "preparei" nao seja alegacao: se a precondicao nao vingou, isto reprova aqui, nao no spec.
const ids = JSON.parse(readFileSync(resolve(__dirname, ".artifacts/t3-ids.json"), "utf8"));

// [OPT-IN E2E_T3_SSE] As 3 sondas de SESSAO AO VIVO abaixo observam estado que e' MUTADO em paralelo pelos specs
// reais (E5 grupo A conduz a chamada -> "Registrar a chamada" some; E6/E5-B abrem votacao e votam). Sondas de
// precondicao correndo ao lado de quem muta a mesma sessao sao instaveis por construcao — por isso ficam fora do
// gate. As assercoes reais vivem nos specs E5/E6, que RODAM no CI desde 02/10/2026 (o placar passou a vir por
// snapshot). As sondas DETERMINISTICAS (E2/E7/E8/E1/E3/E4) seguem sempre ligadas.
const SSE = !!process.env.E2E_T3_SSE;

test("E5 — a sessao de chamada esta ABERTA e com o roster inteiro sem marcacao", async ({ page }) => {
  test.skip(!SSE, "sonda de sessao ao vivo (chamada mutada em paralelo por E5 grupo A) — opt-in E2E_T3_SSE");
  await page.goto(ids.e5.urlChamada, { timeout: 90_000 });
  await expect(page.getByRole("button", { name: "Registrar a chamada" })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "Todos presentes" })).toBeVisible();
});

test("E5 — /votar com :vereador oferece 'Confirmar presenca'", async ({ page }) => {
  test.skip(!SSE, "sonda de cockpit ao vivo (E5/E6 mutam a mesma sessao em paralelo) — opt-in E2E_T3_SSE");
  await page.goto(ids.e5.confirmarPresenca.url, { timeout: 90_000 });
  await expect(page.getByRole("button", { name: "Confirmar presença" })).toBeVisible({ timeout: 30_000 });
});

test("E6 — /votar com :presidente oferece os tres botoes de voto", async ({ page }) => {
  test.skip(!SSE, "sonda de cockpit ao vivo (E5/E6 mutam a mesma sessao em paralelo) — opt-in E2E_T3_SSE");
  await page.goto(ids.e6.url, { timeout: 90_000 });
  await expect(page.getByRole("button", { name: "Sim", exact: true })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "Não", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Abster", exact: true })).toBeVisible();
});

test("E2 — /expediente lista os modelos e o botao de gerar deixa de ser inalcancavel", async ({ page }) => {
  await page.goto(ids.e2.url, { timeout: 90_000 });
  await expect(page.getByText("Ofício padrão da Mesa")).toBeVisible({ timeout: 30_000 });
});

test("E8 — /notificacoes (a caixa, ADR-0020) com :vereador tem aviso nao-lido com 'Marcar como lido'", async ({ page }) => {
  await page.goto(ids.e8.url, { timeout: 90_000 });
  await expect(page.getByRole("button", { name: "Marcar como lido" }).first()).toBeVisible({ timeout: 30_000 });
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
  // O positivo vem ANTES do negativo: `toHaveCount(0)` é verdadeiro no primeiro instante (a página ainda em
  // "Carregando…"), então afirmar a ausência antes de a tela decidir não provava nada. O título "Assinar parecer" só
  // existe quando o parecer foi lido e o relator tem posse; o erro e o "não encontrado" têm outros títulos.
  await expect(page.getByRole("heading", { name: "Assinar parecer", level: 1, exact: true })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/n[ãa]o encontrad/i)).toHaveCount(0);
});
