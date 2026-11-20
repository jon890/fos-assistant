import { randomUUID } from "node:crypto";
import { expect, test } from "./fixtures.ts";
import { WEB_BASE_URL } from "./settings.ts";
import type { Browser, BrowserContext } from "../../node_modules/@playwright/test/index.js";

// 푸시를 누르면 여는 `/n/<id>` 와 로그인 뒤 돌아오기를 본다(docs/features/attention/spec.md 의 REQ6).
// 화면 폭과 상관없는 서버 넘김이라 넓은 화면에서만 돈다.

test.beforeEach(({}, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "화면 폭과 상관없는 사례다");
});

/** 세션이 없는 context 다. Google 로 가는 요청은 막아 로그인 단추를 눌러도 밖으로 나가지 않는다. */
async function signedOut(browser: Browser): Promise<BrowserContext> {
  const context = await browser.newContext();
  await context.route(/accounts\.google\.com/, (route) => route.abort());
  return context;
}

test("로그인이 없으면 열기 주소를 next 로 붙여 로그인 화면으로 보낸다", async ({ browser }) => {
  const context = await signedOut(browser);
  try {
    const page = await context.newPage();
    const id = randomUUID();
    await page.goto(`/n/${id}`);
    await expect(page).toHaveURL(`${WEB_BASE_URL}/signin?next=%2Fn%2F${id}`);
  } finally {
    await context.close();
  }
});

test("없는 알림이나 모양이 틀린 주소는 알림 목록을 연다", async ({ page }) => {
  // 실제 Control Plane 이 없는 알림에 404 를 답한다.
  await page.goto(`/n/${randomUUID()}`);
  await expect(page).toHaveURL(/\/now\?tab=notifications$/);
  await page.goto("/n/not-a-public-id");
  await expect(page).toHaveURL(/\/now\?tab=notifications$/);
});

/** 로그인 화면에서 단추를 누른 뒤 Auth.js 가 남긴 돌아갈 주소 쿠키를 읽는다. */
async function callbackAfterSignIn(browser: Browser, next: string): Promise<string | null> {
  const context = await signedOut(browser);
  try {
    const page = await context.newPage();
    await page.goto(`/signin?next=${next}`);
    const leaving = page.waitForRequest(/accounts\.google\.com/);
    await page.getByRole("button", { name: "Google 계정으로 로그인" }).click();
    await leaving;
    const cookie = (await context.cookies()).find((item) => item.name.endsWith("authjs.callback-url"));
    return cookie ? decodeURIComponent(cookie.value) : null;
  } finally {
    await context.close();
  }
}

test("로그인 화면은 next 를 로그인 뒤 돌아갈 곳으로 넘기고 다른 출처는 첫 화면으로 바꾼다", async ({ browser }) => {
  const id = randomUUID();
  expect(await callbackAfterSignIn(browser, `%2Fn%2F${id}`)).toBe(`${WEB_BASE_URL}/n/${id}`);
  // 첫 화면은 끝의 `/` 가 붙거나 빠질 수 있다.
  const home = new RegExp(`^${WEB_BASE_URL.replace(/[.]/g, "\\.")}/?$`);
  expect(await callbackAfterSignIn(browser, "%2F%2Fevil.example")).toMatch(home);
  expect(await callbackAfterSignIn(browser, "https%3A%2F%2Fevil.example")).toMatch(home);
});
