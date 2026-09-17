import { expect, setSession, test } from "./fixtures.ts";

test("역할에 맞는 메뉴와 로그인한 사람의 이름을 보인다", async ({ context, page }) => {
  await page.goto("/");
  await expect(page.getByRole("link", { name: "에이전트 관리" })).toBeVisible();
  await expect(page.getByText("브라우저 테스트", { exact: true })).toBeVisible();

  await setSession(context, { email: "member@example.com", name: "가족 구성원" });
  await page.goto("/");
  await expect(page.getByRole("link", { name: "에이전트 관리" })).toHaveCount(0);
  await expect(page.getByText("가족 구성원", { exact: true })).toBeVisible();
});

test("머리의 이름을 한 번만 읽고 좁은 화면에서도 한 줄을 유지한다", async ({ page }, testInfo) => {
  await page.goto("/");

  const home = page.getByRole("link", { name: "우리집 비서 홈" });
  await expect(home).toHaveCount(1);
  await expect(home.locator("span")).toHaveText("우리집 비서");
  expect(await home.textContent()).not.toContain("비서우리집 비서");

  const header = page.locator("header");
  await expect(header).toHaveCSS("flex-wrap", "nowrap");
  if (testInfo.project.name === "mobile") {
    const headerBox = await header.boundingBox();
    expect(headerBox).not.toBeNull();
    expect(headerBox?.height ?? Number.POSITIVE_INFINITY).toBeLessThanOrEqual(56);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  }
});

test("로그인 화면은 메뉴 없이 가운데 카드와 브랜드 단추만 보인다", async ({ context, page }) => {
  await context.clearCookies();
  await page.goto("/signin");

  await expect(page.getByRole("navigation", { name: "주요 화면" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "대화" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "사용량" })).toHaveCount(0);

  const card = page.locator("main section > div");
  const box = await card.boundingBox();
  expect(box).not.toBeNull();
  const viewportWidth = page.viewportSize()?.width ?? 0;
  const leftMargin = box?.x ?? 0;
  const rightMargin = viewportWidth - ((box?.x ?? 0) + (box?.width ?? 0));
  expect(Math.abs(leftMargin - rightMargin)).toBeLessThanOrEqual(8);

  const button = page.getByRole("button", { name: "Google 계정으로 로그인" });
  const colors = await button.evaluate((element) => ({
    background: getComputedStyle(element).backgroundColor,
    brand: getComputedStyle(document.documentElement).getPropertyValue("--brand").trim(),
  }));
  expect(colors.background).not.toBe("rgba(0, 0, 0, 0)");
  expect(colors.background).toBe("rgb(176, 90, 60)");
  expect(colors.brand).toBe("#b05a3c");
});
