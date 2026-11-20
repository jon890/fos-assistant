import { parseRgb } from "./color.ts";
import { expect, test } from "./fixtures.ts";
import { openAccountMenu } from "./helpers.ts";

test("이름 메뉴의 「화면 밝기」 에서 어둡게를 고르고 새로 고쳐도 유지한다", async ({ page }, testInfo) => {
  await page.goto("/new");
  await page.evaluate(() => localStorage.setItem("theme", "light"));
  await page.reload();

  await openAccountMenu(page, testInfo);
  await expect(page.getByRole("menuitemradio", { name: "밝게" })).toHaveAttribute("aria-checked", "true");
  await page.getByRole("menuitemradio", { name: "어둡게" }).click();
  await expect(page.locator("html")).toHaveClass(/\bdark\b/);

  await page.reload();
  await expect(page.locator("html")).toHaveClass(/\bdark\b/);
  await openAccountMenu(page, testInfo);
  await expect(page.getByRole("menuitemradio", { name: "어둡게" })).toHaveAttribute("aria-checked", "true");
});

// 외부 스크립트를 막는 시험은 SSR 그림을 확인하고 hydration을 기다리지 않는다.
const firstPaintTest = test.extend({ waitForShellReady: false });

firstPaintTest("저장한 어두움 모드를 첫 그림부터 적용한다", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("theme", "dark"));
  await page.route(/\/_next\/static\/.*\.js(?:\?.*)?$/, (route) => route.abort());

  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(page.locator("main")).toHaveAttribute("aria-busy", "true");
  const firstScreenshot = await page.screenshot();

  expect(firstScreenshot.byteLength).toBeGreaterThan(0);
  await expect(page.locator("html")).toHaveClass(/\bdark\b/);
  // 스크립트를 막았으므로 토큰 값도 같은 자리에서 함께 읽는다.
  const read = () =>
    page.locator("body").evaluate((body) => ({
      background: getComputedStyle(body).backgroundColor,
      token: getComputedStyle(document.documentElement).getPropertyValue("--background").trim(),
    }));
  await expect
    .poll(async () => {
      const { background, token } = await read();
      return token === "" ? "토큰이 비어 있다" : parseRgb(background).join(",") === parseRgb(token).join(",");
    }, { message: "어두움 모드의 body 바탕이 --background 이어야 한다" })
    .toBe(true);
  const { token } = await read();
  const light = await page.locator("html").evaluate((html) => {
    html.classList.remove("dark");
    const value = getComputedStyle(html).getPropertyValue("--background").trim();
    html.classList.add("dark");
    return value;
  });
  expect(parseRgb(token), "어두움의 --background 는 밝음의 값과 달라야 이 검사가 뜻이 있다").not.toEqual(parseRgb(light));
});
