import { expect, test } from "./fixtures.ts";

test("밝기 단추로 어두움 모드를 고르고 새로 고쳐도 유지한다", async ({ page }) => {
  await page.goto("/");
  await page.evaluate(() => localStorage.setItem("theme", "light"));
  await page.reload();

  await page.getByRole("button", { name: /밝기 모드: 밝음/ }).click();
  await expect(page.locator("html")).toHaveClass(/\bdark\b/);

  await page.reload();
  await expect(page.locator("html")).toHaveClass(/\bdark\b/);
  await expect(page.getByRole("button", { name: /밝기 모드: 어두움/ })).toBeVisible();
});

test("저장한 어두움 모드를 첫 그림부터 적용한다", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("theme", "dark"));
  await page.route(/\/_next\/static\/.*\.js(?:\?.*)?$/, (route) => route.abort());

  await page.goto("/", { waitUntil: "domcontentloaded" });
  const firstScreenshot = await page.screenshot();

  expect(firstScreenshot.byteLength).toBeGreaterThan(0);
  await expect(page.locator("html")).toHaveClass(/\bdark\b/);
  await expect
    .poll(() => page.locator("body").evaluate((body) => getComputedStyle(body).backgroundColor))
    .toBe("rgb(16, 18, 22)");
});
