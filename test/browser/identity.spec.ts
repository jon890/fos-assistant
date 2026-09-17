import { expect, test } from "./fixtures.ts";
import { WEB_BASE_URL } from "./settings.ts";

function parseRgb(color: string): [number, number, number] {
  if (/^#[\da-f]{3}$/i.test(color)) {
    return color.slice(1).split("").map((channel) => Number.parseInt(channel.repeat(2), 16)) as [number, number, number];
  }
  if (/^#[\da-f]{6}$/i.test(color)) {
    return color.slice(1).match(/.{2}/g)!.map((channel) => Number.parseInt(channel, 16)) as [number, number, number];
  }
  const channels = color.match(/\d+(?:\.\d+)?/g)?.slice(0, 3).map(Number);
  if (!channels || channels.length !== 3) throw new Error(`RGB 색을 읽지 못했다: ${color}`);
  return channels as [number, number, number];
}

function luminance(color: string): number {
  const [red, green, blue] = parseRgb(color).map((channel) => {
    const value = channel / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

function contrast(foreground: string, background: string): number {
  const values = [luminance(foreground), luminance(background)].sort((left, right) => right - left);
  return (values[0] + 0.05) / (values[1] + 0.05);
}

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

  await page.goto("/admin/agents");

  const themeColors = await page.locator("html").evaluate((html) => {
    const readColors = () => {
      const styles = getComputedStyle(html);
      return {
        brand: styles.getPropertyValue("--brand").trim(),
        onBrand: styles.getPropertyValue("--on-brand").trim(),
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
  expect(themeColors.light.brand).not.toBe(themeColors.dark.brand);
  expect(contrast(themeColors.light.onBrand, themeColors.light.brand)).toBeGreaterThanOrEqual(4.5);
  expect(contrast(themeColors.dark.onBrand, themeColors.dark.brand)).toBeGreaterThanOrEqual(4.5);

  const primaryButton = page.getByRole("button", { name: "등록" });
  const primaryColors = await primaryButton.evaluate((button) => {
    const buttonStyles = getComputedStyle(button);
    const rootStyles = getComputedStyle(document.documentElement);
    return {
      background: buttonStyles.backgroundColor,
      brand: rootStyles.getPropertyValue("--brand").trim(),
    };
  });
  expect(primaryColors.background).not.toBe("rgba(0, 0, 0, 0)");
  expect(parseRgb(primaryColors.background)).toEqual(parseRgb(primaryColors.brand));
  await expect(page.getByRole("button", { name: /^(가족 공개|나만)으로 변경$/ })).toHaveAttribute("type", "button");
  expect(externalFontRequests).toEqual([]);
});
