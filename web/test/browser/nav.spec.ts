import { parseRgb } from "./color.ts";
import { expect, setSession, test } from "./fixtures.ts";

test("역할에 맞는 메뉴와 로그인한 사람의 이름을 보인다", async ({ context, page }, testInfo) => {
  await page.goto("/");
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
  await expect(page.getByRole("link", { name: "에이전트", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "에이전트 관리" })).toHaveCount(0);
  await expect(page.getByText("브라우저 테스트", { exact: true })).toBeVisible();
  await expect(page.getByTestId("admin-entry")).toBeVisible();
  await expect(page.getByTestId("admin-entry")).toHaveText("관리자");

  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  const me = await page.request.get("/api/me");
  expect(me.ok()).toBeTruthy();
  expect(await me.json()).toMatchObject({
    email: "member@example.com",
    displayName: "가족 사용자",
    role: "MEMBER",
  });
  await page.goto("/");
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
  await expect(page.getByRole("link", { name: "에이전트", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "에이전트 관리" })).toHaveCount(0);
  await expect(page.getByText("가족 사용자", { exact: true })).toBeVisible();
  await expect(page.getByTestId("admin-entry")).toHaveCount(0);
});

test("머리의 이름을 한 번만 읽고 좁은 화면에서도 한 줄을 유지한다", async ({ page }, testInfo) => {
  await page.goto("/");

  // 모바일 머리줄과 넓은 화면의 사이드바에서 홈 이름을 한 번만 읽는다.
  const home = page.getByRole("link", { name: "검사용 비서 홈", exact: true });
  await expect(home).toHaveCount(1);
  await expect(home).toHaveAttribute("href", "/");
  // 좁은 폭의 서랍은 닫혀 있으면 그리지 않는다. 열어서 본다.
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
  // 앱 이름은 검사용 서버가 실행할 때 준 값이다.
  await expect(home).toHaveCount(1);
  await expect(home).toHaveText("검사용 비서");
  await expect(page).toHaveTitle("검사용 비서");

  if (testInfo.project.name === "mobile") {
    await page.getByRole("button", { name: "사이드바 닫기" }).click();
    // 서랍을 닫으면 머리줄의 홈 링크가 다시 낭독기에 드러난다.
    await expect(home).toHaveCount(1);
    const header = page.locator("header");
    await expect(header.getByRole("link", { name: "검사용 비서 홈", exact: true })).toBeVisible();
    await expect(header).toHaveCSS("flex-wrap", "nowrap");
    const headerBox = await header.boundingBox();
    expect(headerBox).not.toBeNull();
    expect(headerBox?.height ?? Number.POSITIVE_INFINITY).toBeLessThanOrEqual(56);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  }
});

test("로그인 화면은 메뉴 없이 가운데 카드와 브랜드 단추만 보인다", async ({ context, page }) => {
  await context.clearCookies();
  await page.goto("/signin");

  await expect(page.getByRole("heading", { name: "검사용 비서", level: 1 })).toBeVisible();
  await expect(page).toHaveTitle("검사용 비서");
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
    primary: getComputedStyle(document.documentElement).getPropertyValue("--primary").trim(),
  }));
  expect(colors.background).not.toBe("rgba(0, 0, 0, 0)");
  expect(parseRgb(colors.background), `로그인 단추 바탕이 --primary(${colors.primary}) 이어야 한다`).toEqual(parseRgb(colors.primary));
});
