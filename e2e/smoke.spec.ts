import { test, expect } from "@playwright/test";

test("o portal responde e o app monta", async ({ page }) => {
  const resp = await page.goto("/");
  expect(resp?.status()).toBeLessThan(400);
  await expect(page).toHaveTitle(/O Plenário/);
});
