import { expect, test } from "./fixtures.ts";
import { contrast, parseRgb } from "./color.ts";
import { WEB_BASE_URL } from "./settings.ts";

test("Pretendard와 테마별 브랜드 색을 자체 글꼴 단추에 적용한다", async ({ page }) => {
  const externalFontRequests: string[] = [];
  const ownOrigin = new URL(WEB_BASE_URL).origin;
  await page.route("**/*", async (route) => {
    const request = route.request();
    if (request.resourceType() === "font" && new URL(request.url()).origin !== ownOrigin) {
      externalFontRequests.push(request.url());
    }
    await route.continue();
  });

  await page.goto("/agents/browser");

  const themeColors = await page.locator("html").evaluate((html) => {
    const readColors = () => {
      const styles = getComputedStyle(html);
      return {
        primary: styles.getPropertyValue("--primary").trim(),
        primaryForeground: styles.getPropertyValue("--primary-foreground").trim(),
      };
    };
    html.classList.remove("dark");
    const light = readColors();
    html.classList.add("dark");
    const dark = readColors();
    html.classList.remove("dark");
    return { light, dark };
  });

  const htmlFontFamily = await page.locator("html").evaluate((html) => getComputedStyle(html).fontFamily);
  expect(htmlFontFamily.split(",")[0].toLowerCase()).toContain("pretendard");
  expect(themeColors.light.primary).not.toBe(themeColors.dark.primary);
  expect(contrast(themeColors.light.primaryForeground, themeColors.light.primary)).toBeGreaterThanOrEqual(4.5);
  expect(contrast(themeColors.dark.primaryForeground, themeColors.dark.primary)).toBeGreaterThanOrEqual(4.5);

  const primaryButton = page.getByRole("button", { name: "저장", exact: true });
  const primaryColors = await primaryButton.evaluate((button) => {
    const buttonStyles = getComputedStyle(button);
    const rootStyles = getComputedStyle(document.documentElement);
    return {
      background: buttonStyles.backgroundColor,
      primary: rootStyles.getPropertyValue("--primary").trim(),
    };
  });
  expect(primaryColors.background).not.toBe("rgba(0, 0, 0, 0)");
  expect(parseRgb(primaryColors.background)).toEqual(parseRgb(primaryColors.primary));
  await expect(page.getByRole("button", { name: "그룹 공개로 변경" })).toHaveAttribute("type", "button");
  expect(externalFontRequests).toEqual([]);
});
