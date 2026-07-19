import { test, expect } from "@playwright/test";
import { lerEnteId } from "./seed";

// Task 2: prova que o seed (seed_demo.clj, via semear.sh) rodou de verdade e que o globalSetup
// normalizou o ente_id em .artifacts/ente.json — o portal público carrega para esse ente real.
test("o seed criou um ente e o portal carrega para ele", async ({ page }) => {
  const enteId = lerEnteId();
  expect(enteId).toMatch(/^[0-9a-f-]{36}$/);
  await page.goto(`/portal/casa/${enteId}`);
  await expect(page.getByRole("banner")).toContainText(/Câmara|Camara/);
});
