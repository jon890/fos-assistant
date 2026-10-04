import { CONVERSATION_URL, expect, test } from "./fixtures.ts";
import type { Page, Request } from "../../web/node_modules/@playwright/test/index.js";

/** 사이드바가 그릴 만큼 대화가 많은 사용자를 흉내 낸다. 한 페이지(기본 30개)를 넘겨야 한다. */
const CONVERSATION_COUNT = 70;

// 알림 단위 SSE 가 열려 있으면 networkidle 이 오지 않는다. 빈 응답으로 끝낸다.
test.beforeEach(async ({ page }) => {
  await page.route("**/api/notifications/events", (route) =>
    route.fulfill({ status: 200, contentType: "text/event-stream", body: "" }),
  );
});

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

type Counts = { list: number; prefetch: number; other: number; total: number };

async function seedConversations(page: Page): Promise<string[]> {
  const ids: string[] = [];
  for (let index = 0; index < CONVERSATION_COUNT; index += 1) {
    const created = await page.request.post("/api/chat/conversations", { data: { agentCode: "browser" } });
    expect(created.ok()).toBeTruthy();
    ids.push(((await created.json()) as { conversationId: string }).conversationId);
  }
  return ids;
}

/** 이 페이지가 웹 서버로 보내는 요청을 센다. 목록 읽기와 대화 화면 미리 읽기를 따로 센다. */
function countRequests(page: Page): { snapshot(): Counts; reset(): void } {
  let counts: Counts = { list: 0, prefetch: 0, other: 0, total: 0 };
  page.on("request", (request: Request) => {
    const url = new URL(request.url());
    const isRsc = request.headers()["rsc"] === "1" || url.searchParams.has("_rsc");
    const isPrefetch = isRsc && request.headers()["next-router-prefetch"] === "1";
    if (url.pathname === "/api/chat/conversations" && request.method() === "GET") counts.list += 1;
    else if (isPrefetch && url.pathname.startsWith("/chat/")) counts.prefetch += 1;
    else counts.other += 1;
    counts.total += 1;
  });
  return {
    snapshot: () => ({ ...counts }),
    reset: () => {
      counts = { list: 0, prefetch: 0, other: 0, total: 0 };
    },
  };
}

test("대화가 많아도 사이드바는 첫 화면과 대화 전환에서 요청을 몰아 내지 않는다", async ({ page }, testInfo) => {
  test.setTimeout(120_000);
  const ids = await seedConversations(page);
  const counter = countRequests(page);

  await page.goto("/");
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
  const nav = page.getByRole("navigation", { name: "대화 목록" });
  await expect(nav.locator('a[href^="/chat/"]').first()).toBeVisible();
  await page.waitForLoadState("networkidle");
  const firstScreen = counter.snapshot();
  const rendered = await nav.locator('a[href^="/chat/"]').count();

  counter.reset();
  // 가장 나중에 만든 셋이 목록 맨 위에 있다. 첫 쪽에 든 줄만 눌러 본다.
  for (const id of ids.slice(-3)) {
    const link = nav.locator(`a[href="/chat/${id}"]`);
    await link.scrollIntoViewIfNeeded();
    await link.click();
    await expect(page).toHaveURL(new RegExp(`/chat/${id}$`));
    await page.waitForLoadState("networkidle");
    if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
  }
  const switching = counter.snapshot();

  // 새 대화에서 메시지 하나를 보내는 동안 나가는 목록 읽기를 센다.
  counter.reset();
  await page.goto("/");
  await page.getByRole("textbox", { name: "메시지" }).fill(`요청 수 검사 ${Date.now()}`);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
  // 대화 단위 SSE 가 열려 있어 networkidle 은 오지 않는다. 뒤따르는 목록 읽기가 나갈 만큼만 기다린다.
  await page.waitForTimeout(1500);
  const sending = counter.snapshot();

  console.log(`MEASURE ${testInfo.project.name} ${JSON.stringify({ rendered, firstScreen, switching, sending })}`);

  expect(CONVERSATION_URL.test(page.url())).toBeTruthy();
  // 링크마다 대화 화면을 미리 읽으면 보이는 수만큼 요청이 나간다.
  expect(firstScreen.prefetch).toBe(0);
  expect(switching.prefetch).toBe(0);
  // 한 페이지만 읽으므로 그려진 줄이 전체 대화 수보다 적다.
  expect(rendered).toBeLessThan(ids.length);
  expect(firstScreen.list).toBe(1);
  // 화면을 연 읽기 하나에 turn 이 시작될 때와 끝날 때가 더해진다. 그보다 많으면 목록 읽기가 겹쳐 나가는 것이다.
  expect(sending.list).toBeLessThanOrEqual(3);
});

test("목록 끝에 닿으면 다음 쪽을 읽고 검색은 아직 읽지 않은 대화도 찾는다", async ({ page }, testInfo) => {
  test.setTimeout(120_000);
  // 첫 쪽에서 밀려나도록 제목 있는 대화를 먼저 만들고 그 위에 빈 대화를 쌓는다.
  const title = `오래된 대화 ${testInfo.project.name} ${Date.now()}`;
  const old = await page.request.post("/api/chat", { data: { text: title, agentCode: "browser" } });
  expect(old.ok()).toBeTruthy();
  const oldId = ((await old.json()) as { conversationId: string }).conversationId;
  await seedConversations(page);

  const lists: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.pathname === "/api/chat/conversations" && request.method() === "GET") lists.push(url.search);
  });

  await page.goto("/");
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
  const nav = page.getByRole("navigation", { name: "대화 목록" });
  const links = nav.locator('a[href^="/chat/"]');
  await expect(links.first()).toBeVisible();
  await expect(nav.locator(`a[href="/chat/${oldId}"]`)).toHaveCount(0);

  // 끝까지 스크롤하면 다음 쪽이 붙는다.
  const firstCount = await links.count();
  await nav.evaluate((element) => element.scrollTo(0, element.scrollHeight));
  await expect.poll(() => links.count()).toBeGreaterThan(firstCount);
  expect(lists.some((search) => search.includes("cursor="))).toBeTruthy();

  // 검색은 읽지 않은 쪽까지 읽어서 찾는다.
  await page.getByRole("searchbox").fill(title);
  await expect(nav.locator(`a[href="/chat/${oldId}"]`)).toBeVisible({ timeout: 15_000 });
});
