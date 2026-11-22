import { expect, test } from "./fixtures.ts";
import type { Page, Route, TestInfo } from "../../node_modules/@playwright/test/index.js";

type Item = {
  id: string;
  kind: string;
  title: string;
  body: string;
  targetType: string | null;
  targetId: string | null;
  createdAt: string;
  readAt: string | null;
};

const TARGETED_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
const UNTARGETED_ID = "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee";

function item(id: string, body: string, conversationId: string | null): Item {
  return {
    id,
    kind: "APPROVAL_REQUESTED",
    title: "승인을 기다리는 요청이 있어요",
    body,
    targetType: conversationId === null ? null : "CONVERSATION",
    targetId: conversationId,
    createdAt: "2026-10-01T12:00:00Z",
    readAt: null,
  };
}

async function createConversation(page: Page): Promise<string> {
  const created = await page.request.post("/api/chat", {
    data: { text: `알림 검사 ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  return ((await created.json()) as { conversationId: string }).conversationId;
}

/**
 * 알림 API 를 대역한다. 목록은 `state` 를 돌려주고, 사건 연결은 처음 열릴 때 `created` 사건 하나를 보낸 뒤
 * 다시 열리면 붙잡아 둔다. 사건이 알린 수는 다시 읽어도 같도록 `state.unread` 에 맞춘다.
 */
async function routeNotifications(
  page: Page,
  state: { items: Item[]; unread: number },
  eventUnread: number | null,
  onEventSent?: () => void,
) {
  let eventsSent = false;
  await page.route("**/api/notifications?**", (route: Route) => {
    if (new URL(route.request().url()).pathname !== "/api/notifications") return route.fallback();
    return route.fulfill({ json: { items: state.items, nextCursor: null, unreadCount: state.unread } });
  });
  await page.route("**/api/notifications/events", async (route: Route) => {
    if (eventUnread === null || eventsSent) await new Promise(() => {});
    eventsSent = true;
    state.unread = eventUnread;
    onEventSent?.();
    await route.fulfill({
      status: 200,
      contentType: "text/event-stream",
      body: `data: ${JSON.stringify({ type: "created", notificationId: TARGETED_ID, unreadCount: eventUnread })}\n\n`,
    });
  });
}

/** 할 것의 수(`nowCount`)를 대역한다. 읽힌 횟수를 센다 */
async function routeNowCount(page: Page, count: () => number): Promise<{ reads: number }> {
  const seen = { reads: 0 };
  await page.route("**/api/attention/summary", (route: Route) => {
    seen.reads += 1;
    return route.fulfill({ json: { nowCount: count() } });
  });
  return seen;
}

async function openSidebar(page: Page, testInfo: TestInfo) {
  if (testInfo.project.name === "mobile") {
    await page.getByRole("button", { name: "사이드바 열기" }).click();
  }
  return page.getByRole("complementary", { name: "사이드바" });
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("할 것이 없고 새 알림만 있으면 점이 붙고, 「알림」 탭에서 줄을 누르면 읽음이 되고 그 대화로 간다", async ({ page }, testInfo) => {
  const conversationId = await createConversation(page);
  const state = {
    items: [item(TARGETED_ID, "메모 쓰기", conversationId), item(UNTARGETED_ID, "파일 지우기", null)],
    unread: 2,
  };
  await routeNowCount(page, () => 0);
  await routeNotifications(page, state, 3);
  let readUrl: string | null = null;
  await page.route("**/api/notifications/*/read", (route: Route) => {
    readUrl = route.request().url();
    return route.fulfill({ json: { ...state.items[0], readAt: "2026-10-01T12:05:00Z" } });
  });

  await page.goto("/new");
  // 종 모양 알림 단추는 어디에도 없다. 대화 제목 「알림 검사 …」 와 섞이지 않게 이름 전체로 찾는다.
  await expect(page.getByRole("link", { name: /^알림(,|$)/ })).toHaveCount(0);
  const sidebar = await openSidebar(page, testInfo);
  const now = sidebar.getByRole("link", { name: "지금 볼 것, 새 알림 있음" });
  await expect(now).toBeVisible();
  await expect(now.getByTestId("now-dot")).toBeVisible();
  await expect(now.getByTestId("now-count")).toHaveCount(0);

  await now.click();
  await expect(page).toHaveURL(/\/now$/);
  await expect(page.getByRole("tab", { name: /^할 것/ })).toHaveAttribute("aria-selected", "true");
  const alerts = page.getByRole("tab", { name: /^알림/ });
  await expect(alerts).toHaveAccessibleName("알림 읽지 않음 3개");
  await alerts.click();
  await expect(page).toHaveURL(/\/now\?tab=notifications$/);
  const rows = page.getByTestId("notification-item");
  await expect(rows).toHaveCount(2);
  await expect(rows.first()).toHaveAttribute("data-read", "false");

  await rows.first().click();
  await expect(page).toHaveURL(new RegExp(`/chat/${conversationId}$`));
  expect(readUrl).toContain(`/api/notifications/${TARGETED_ID}/read`);
});

test("「알림」 탭에서 「지금 볼 것」 으로 다시 오면 「할 것」 이 열리고, 「할 것」 만 열 때는 알림 목록을 읽지 않는다", async ({ page }, testInfo) => {
  await routeNotifications(page, { items: [item(UNTARGETED_ID, "파일 지우기", null)], unread: 1 }, null);
  const listReads: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    // 단추의 수는 `limit=1` 로 읽는다. 목록은 그보다 큰 쪽 크기로 읽는다.
    if (url.pathname === "/api/notifications" && url.searchParams.get("limit") !== "1") listReads.push(url.search);
  });

  const summary = await routeNowCount(page, () => 0);
  await page.goto("/now");
  await expect(page.getByRole("tab", { name: /^할 것/ })).toHaveAttribute("aria-selected", "true");
  // 서버가 그린 첫 그림만으로는 통과하므로, 브라우저가 화면을 살린 뒤에만 나가는 수 읽기를 먼저 기다린다.
  await expect.poll(() => summary.reads).toBeGreaterThanOrEqual(1);
  await expect(page.getByTestId("notification-item")).toHaveCount(0);
  expect(listReads, "「할 것」 탭만 열었는데 알림 목록을 읽었다").toEqual([]);

  await page.getByRole("tab", { name: /^알림/ }).click();
  await expect(page.getByTestId("notification-item")).toHaveCount(1);
  const sidebar = await openSidebar(page, testInfo);
  await sidebar.getByRole("link", { name: /^지금 볼 것/ }).click();
  await expect(page).toHaveURL(/\/now$/);
  await expect(page.getByRole("tab", { name: /^할 것/ })).toHaveAttribute("aria-selected", "true");
});

test("전의 알림 주소는 「알림」 탭이 열린 「지금 볼 것」 으로 넘어가고, 갈 곳이 없는 줄은 읽음만 표시한다", async ({ page }) => {
  const state = { items: [item(UNTARGETED_ID, "파일 지우기", null)], unread: 1 };
  await routeNotifications(page, state, null);
  await page.route("**/api/notifications/*/read", (route: Route) =>
    route.fulfill({ json: { ...state.items[0], readAt: "2026-10-01T12:05:00Z" } }),
  );

  await page.goto("/notifications");
  await expect(page).toHaveURL(/\/now\?tab=notifications$/);
  await expect(page.getByRole("heading", { level: 1, name: "지금 볼 것" })).toBeVisible();
  await expect(page.getByRole("tab", { name: /^알림/ })).toHaveAttribute("aria-selected", "true");
  const row = page.getByTestId("notification-item");
  await expect(row).toHaveAttribute("data-read", "false");
  await row.click();
  await expect(row).toHaveAttribute("data-read", "true");
  await expect(page).toHaveURL(/\/now\?tab=notifications$/);

  // 「할 것」 탭으로 돌아가면 주소에서 탭 값이 빠지고, 새로 고쳐도 「할 것」 이 열린다.
  await page.getByRole("tab", { name: /^할 것/ }).click();
  await expect(page).toHaveURL(/\/now$/);
  await page.reload();
  await expect(page.getByRole("tab", { name: /^할 것/ })).toHaveAttribute("aria-selected", "true");
});

test("알림이 없으면 「알림」 탭에 빈 상태를 보인다", async ({ page }) => {
  await routeNotifications(page, { items: [], unread: 0 }, null);

  await page.goto("/now?tab=notifications");
  await expect(page.getByText("아직 알림이 없어요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "모두 읽음" })).toBeDisabled();
  await expect(page.getByRole("tab", { name: /^알림/ })).toHaveAccessibleName("알림");
});

test("할 것이 있으면 줄과 탭에 그 수가 붙고 읽지 않은 알림의 수와 더하지 않는다", async ({ page }, testInfo) => {
  await routeNowCount(page, () => 3);
  await routeNotifications(page, { items: [item(UNTARGETED_ID, "파일 지우기", null)], unread: 2 }, null);

  await page.goto("/now");
  await expect(page.getByRole("tab", { name: /^할 것/ })).toHaveAccessibleName("할 것 3건");
  await expect(page.getByRole("tab", { name: /^알림/ })).toHaveAccessibleName("알림 읽지 않음 2개");
  const sidebar = await openSidebar(page, testInfo);
  const now = sidebar.getByRole("link", { name: "지금 볼 것 3건, 새 알림 있음" });
  await expect(now.getByTestId("now-count")).toHaveText("3");
  await expect(now.getByTestId("now-dot")).toHaveCount(0);
});

test("새 알림이 오면 화면을 옮기지 않아도 할 것의 수를 다시 읽는다", async ({ page }, testInfo) => {
  let nowCount = 0;
  const summary = await routeNowCount(page, () => nowCount);
  // 승인 요청이 알림 한 줄과 할 것 한 건을 함께 만든 상태를 사건이 오는 순간에 맞춘다.
  await routeNotifications(page, { items: [], unread: 0 }, 1, () => {
    nowCount = 1;
  });

  await page.goto("/new");
  const sidebar = await openSidebar(page, testInfo);
  await expect(sidebar.getByRole("link", { name: "지금 볼 것 1건, 새 알림 있음" })).toBeVisible();
  await expect(sidebar.getByTestId("now-count")).toHaveText("1");
  expect(summary.reads, "알림 사건 뒤에 할 것의 수를 다시 읽지 않았다").toBeGreaterThanOrEqual(2);
});

test("좁은 화면 위 막대에는 알림 단추 자리에 「지금 볼 것」 단추가 있다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile", "위 막대는 좁은 화면에만 보인다");
  await routeNowCount(page, () => 0);
  await routeNotifications(page, { items: [item(UNTARGETED_ID, "파일 지우기", null)], unread: 1 }, null);

  await page.goto("/new");
  const bar = page.getByRole("banner");
  const button = bar.getByRole("link", { name: "지금 볼 것, 새 알림 있음" });
  await expect(button).toBeVisible();
  await expect(button.getByTestId("now-dot")).toBeVisible();
  await button.click();
  await expect(page).toHaveURL(/\/now$/);
});

test("좁은 화면 위 막대의 「지금 볼 것」 단추에도 할 것의 수가 붙는다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile", "위 막대는 좁은 화면에만 보인다");
  await routeNowCount(page, () => 3);
  await routeNotifications(page, { items: [], unread: 0 }, null);

  await page.goto("/new");
  const button = page.getByRole("banner").getByRole("link", { name: "지금 볼 것 3건" });
  await expect(button.getByTestId("now-count")).toHaveText("3");
  await expect(button.getByTestId("now-dot")).toHaveCount(0);
});

test("단추 연결 앱 거절 알림을 누르면 서버 비밀값 화면으로 간다", async ({ page }) => {
  const rejected: Item = {
    ...item(TARGETED_ID, "GitHub 앱(커리어) 설정을 확인해 주세요", null),
    kind: "CONNECTOR_OAUTH_APP_REJECTED",
    title: "단추 연결 앱 설정을 확인해 주세요",
    targetType: "ADMIN_SECRETS",
  };
  const state = { items: [rejected], unread: 1 };
  await routeNotifications(page, state, null);
  await page.route("**/api/notifications/*/read", (route: Route) =>
    route.fulfill({ json: { ...rejected, readAt: "2026-10-01T12:05:00Z" } }),
  );

  await page.goto("/now?tab=notifications");
  await page.getByTestId("notification-item").click();
  await expect(page).toHaveURL(/\/admin\/secrets$/);
  await expect(page.getByRole("heading", { level: 1, name: "서버 비밀값" })).toBeVisible();
});
