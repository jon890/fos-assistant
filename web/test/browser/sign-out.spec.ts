import { expect, setSession, test } from "./fixtures.ts";
import { openAccountMenu } from "./helpers.ts";
import { TEST_EMAIL } from "./settings.ts";
import type { Page } from "../../node_modules/@playwright/test/index.js";

// 이름 메뉴의 로그아웃을 본다(docs/features/app-shell/spec.md 의 REQ6). 세션은 테스트마다 자기 context 에만 있다.

async function createConversation(page: Page, title: string): Promise<string> {
  const response = await page.request.post("/api/chat", { data: { text: title, agentCode: "browser" } });
  expect(response.ok()).toBeTruthy();
  return ((await response.json()) as { conversationId: string }).conversationId;
}

/** 기본 검사 사용자로 이 context 에 세션을 다시 넣는다. 세션 쿠키는 테스트마다 자기 context 에만 있다. */
const TEST_USER = { email: TEST_EMAIL, name: "브라우저 테스트" };

test("로그아웃하면 로그인 화면으로 가고 뒤로 가기와 저장한 주소로는 화면이 열리지 않는다", async ({ context, page }, testInfo) => {
  const title = `로그아웃 전 대화 ${Date.now()}`;
  const id = await createConversation(page, title);
  await page.goto("/new");
  await page.goto(`/chat/${id}`);

  await openAccountMenu(page, testInfo);
  await page.getByRole("menuitem", { name: "로그아웃" }).click();
  await expect(page).toHaveURL(/\/signin$/);

  await page.goBack();
  await expect(page).toHaveURL(/\/signin$/);
  await page.goto(`/chat/${id}`);
  await expect(page).toHaveURL(/\/signin$/);

  // 데이터는 지워지지 않는다. 같은 사용자로 다시 들어오면 대화가 목록에 그대로다.
  await setSession(context, TEST_USER);
  await page.goto("/new");
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
  await expect(page.getByRole("navigation", { name: "대화 목록" }).locator(`a[href="/chat/${id}"]`)).toBeVisible();
});

test("로그아웃은 다른 브라우저의 로그인을 풀지 않는다", async ({ browser, page }, testInfo) => {
  const other = await browser.newContext();
  try {
    await setSession(other, TEST_USER);
    await page.goto("/new");
    await openAccountMenu(page, testInfo);
    await page.getByRole("menuitem", { name: "로그아웃" }).click();
    await expect(page).toHaveURL(/\/signin$/);

    const otherPage = await other.newPage();
    await otherPage.goto("/new");
    await expect(otherPage).toHaveURL(/\/new$/);
  } finally {
    await other.close();
  }
});

for (const failure of ["500", "끊김"] as const) {
  test(`로그아웃 요청이 ${failure} 이면 안내가 보이고 화면은 그대로다`, async ({ page }, testInfo) => {
    await page.route("**/signout", (route) =>
      failure === "500" ? route.fulfill({ status: 500 }) : route.abort("internetdisconnected"),
    );
    await page.goto("/new");
    await openAccountMenu(page, testInfo);
    await page.getByRole("menuitem", { name: "로그아웃" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "로그아웃하지 못했어요" })).toBeVisible();
    await expect(page).toHaveURL(/\/new$/);
  });
}

test("bfcache 에서 돌아온 문서는 세션이 있으면 그대로 두고 없으면 다시 읽는다", async ({ context, page }) => {
  type Marked = Window & { beforeReload?: boolean };
  const restore = () => page.evaluate(() => window.dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true })));
  await page.goto("/new");
  await page.evaluate(() => {
    (window as Marked).beforeReload = true;
  });
  // 세션이 있으면 다시 읽지 않아 쓰던 글과 스크롤이 남는다.
  const sessionRead = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/auth/session");
  await restore();
  await sessionRead;
  expect(await page.evaluate(() => (window as Marked).beforeReload)).toBe(true);

  // 로그아웃된 브라우저에서 돌아온 문서는 다시 읽고 로그인 화면으로 간다.
  await context.clearCookies();
  const reloaded = page.waitForEvent("load");
  await restore();
  await reloaded;
  await expect(page).toHaveURL(/\/signin$/);
});

test("다른 출처에서 보낸 로그아웃 요청은 거절하고 로그인은 그대로다", async ({ page }) => {
  await page.goto("/new");
  for (const origin of ["https://other.example", "null"]) {
    const response = await page.request.post("/signout", { headers: { origin } });
    expect(response.status(), origin).toBe(403);
  }
  await page.goto("/new");
  await expect(page).toHaveURL(/\/new$/);
});
