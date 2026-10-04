import { expect, test } from "./fixtures.ts";
import type { Page, Route } from "../../web/node_modules/@playwright/test/index.js";

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
    await route.fulfill({
      status: 200,
      contentType: "text/event-stream",
      body: `data: ${JSON.stringify({ type: "created", notificationId: TARGETED_ID, unreadCount: eventUnread })}\n\n`,
    });
  });
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("사건이 알린 수가 단추에 보이고 누르면 알림 화면에서 줄을 읽고 그 대화로 간다", async ({ page }, testInfo) => {
  const conversationId = await createConversation(page);
  const state = {
    items: [item(TARGETED_ID, "메모 쓰기", conversationId), item(UNTARGETED_ID, "파일 지우기", null)],
    unread: 2,
  };
  await routeNotifications(page, state, 3);
  let readUrl: string | null = null;
  await page.route("**/api/notifications/*/read", (route: Route) => {
    readUrl = route.request().url();
    return route.fulfill({ json: { ...state.items[0], readAt: "2026-10-01T12:05:00Z" } });
  });

  await page.goto("/");
  const scope =
    testInfo.project.name === "mobile" ? page.getByRole("banner") : page.getByRole("complementary", { name: "사이드바" });
  await expect(scope.getByTestId("notification-count")).toHaveText("3");

  await scope.getByRole("link", { name: "알림, 읽지 않은 알림 3개" }).click();
  await expect(page).toHaveURL(/\/notifications$/);
  const rows = page.getByTestId("notification-item");
  await expect(rows).toHaveCount(2);
  await expect(rows.first()).toHaveAttribute("data-read", "false");

  await rows.first().click();
  await expect(page).toHaveURL(new RegExp(`/chat/${conversationId}$`));
  expect(readUrl).toContain(`/api/notifications/${TARGETED_ID}/read`);
});

test("갈 곳이 없는 알림은 눌러도 화면을 옮기지 않고 읽음만 표시한다", async ({ page }) => {
  const state = { items: [item(UNTARGETED_ID, "파일 지우기", null)], unread: 1 };
  await routeNotifications(page, state, null);
  await page.route("**/api/notifications/*/read", (route: Route) =>
    route.fulfill({ json: { ...state.items[0], readAt: "2026-10-01T12:05:00Z" } }),
  );

  await page.goto("/notifications");
  const row = page.getByTestId("notification-item");
  await expect(row).toHaveAttribute("data-read", "false");
  await row.click();
  await expect(row).toHaveAttribute("data-read", "true");
  await expect(page).toHaveURL(/\/notifications$/);
});

test("알림이 없으면 빈 상태를 보인다", async ({ page }) => {
  await routeNotifications(page, { items: [], unread: 0 }, null);

  await page.goto("/notifications");
  await expect(page.getByText("아직 알림이 없어요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "모두 읽음" })).toBeDisabled();
});
