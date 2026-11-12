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

  const persona = page.getByRole("textbox", { name: /성격$/ });
  const primaryButton = persona.locator("..").getByRole("button", { name: "저장", exact: true });
  // 성격이 비어 있으면 저장 단추가 잠겨 muted 바탕이다. 글을 넣어 단추를 켠 뒤 강조 색을 본다.
  // 저장하지 않으므로 이 에이전트의 성격은 바뀌지 않는다.
  await persona.fill("단추 색을 보려고 넣은 글");
  await expect(primaryButton).toBeEnabled();
  const readColors = () => primaryButton.evaluate((button) => {
    const buttonStyles = getComputedStyle(button);
    const rootStyles = getComputedStyle(document.documentElement);
    return {
      background: buttonStyles.backgroundColor,
      primary: rootStyles.getPropertyValue("--primary").trim(),
    };
  });
  // 바탕색은 짧게 바뀌어 가므로 강조 색에 닿을 때까지 기다린다.
  await expect.poll(async () => {
    const colors = await readColors();
    return parseRgb(colors.background);
  }).toEqual(parseRgb((await readColors()).primary));
  expect((await readColors()).background).not.toBe("rgba(0, 0, 0, 0)");
  await expect(page.getByRole("region", { name: "공개와 삭제" }).getByRole("button", { name: "그룹 공개로 변경" }))
    .toHaveAttribute("type", "button");
  expect(externalFontRequests).toEqual([]);
});
