import type { Page, Route, TestInfo } from "../../web/node_modules/@playwright/test/index.js";
import { expect, setSession, test, type FakeHermesControl } from "./fixtures.ts";
import { WEB_BASE_URL } from "./settings.ts";

test.describe("지금 화면", () => {
  function memberOf(projectName: string) {
    return { email: `now-member-${projectName}@example.com`, name: "지금 화면 보는 사용자" };
  }

  test.beforeEach(async ({ context, page }, testInfo) => {
    await setSession(context, memberOf(testInfo.project.name));
    expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  });

  /** 이 사용자가 가진 에이전트의 코드다. 없으면 하나 만든다. 씨 뿌린 에이전트는 관리자 것이라 쓰지 못한다. */
  async function ownAgent(page: Page): Promise<string> {
    const listed = await page.request.get("/api/agents");
    expect(listed.ok(), `에이전트 목록을 읽지 못했다: ${listed.status()}`).toBeTruthy();
    const mine = ((await listed.json()) as { code: string; ownedByMe: boolean }[]).find((agent) => agent.ownedByMe);
    if (mine) return mine.code;
    const created = await page.request.post("/api/agents", { data: { name: "장보기 비서" } });
    expect(created.status(), `에이전트를 만들지 못했다: ${created.status()}`).toBe(201);
    return ((await created.json()) as { code: string }).code;
  }

  /** Hermes 가 붐비게 해 turn 하나를 실패시킨다. 대화를 주지 않으면 새 대화가 생긴다. */
  async function failTurn(page: Page, hermes: FakeHermesControl, text: string, conversationId?: string) {
    const agentCode = await ownAgent(page);
    await hermes.busy();
    try {
      const response = await page.request.post("/api/chat", { data: { text, agentCode, conversationId } });
      expect(response.status(), `실패시킬 turn 이 429 가 아니다`).toBe(429);
    } finally {
      await hermes.clearBusy();
    }
  }

  async function openSidebar(page: Page, testInfo: TestInfo) {
    if (testInfo.project.name === "mobile") {
      await page.getByRole("button", { name: "사이드바 열기" }).click();
    }
    return page.getByRole("complementary", { name: "사이드바" });
  }

  test("실패한 turn 이 실패 카드의 첫 줄에 이유와 함께 보이고 사이드바에 수가 붙는다", async ({ page, hermes }, testInfo) => {
    const title = "주간 장보기 목록 정리";
    await failTurn(page, hermes, title);

    await page.goto("/now");
    await expect(page.getByRole("heading", { level: 1, name: "지금 볼 것" })).toBeVisible();
    await expect(page.locator('[data-testid^="now-card-"]').first()).toHaveAttribute("data-testid", "now-card-failures");
    const row = page.getByTestId("now-card-failures").getByTestId("now-item").filter({ hasText: title });
    await expect(row).toHaveCount(1);
    await expect(row).toHaveAttribute("data-attention", "NOW");
    await expect(row).toContainText("답을 만들지 못했고 아직 다시 보내지 않았어요");
    await expect(row.locator("time")).toHaveAttribute("title", /.+/);

    const sidebar = await openSidebar(page, testInfo);
    const count = sidebar.getByTestId("now-count");
    await expect(count).toBeVisible();
    expect(Number(await count.textContent()), "사이드바 「지금 볼 것」 의 수").toBeGreaterThanOrEqual(1);
  });

  test("실패한 대화에 다시 보내 성공하면 실패 카드에서 빠지고 이어서 하기에 보인다", async ({ page, hermes }) => {
    await page.goto("/now");
    const failures = page.getByTestId("now-card-failures");
    if ((await failures.getByTestId("now-item").count()) === 0) {
      await failTurn(page, hermes, "주간 장보기 목록 정리");
      await page.goto("/now");
    }
    const links = failures.getByTestId("now-item").getByRole("link");
    await expect(links.first()).toBeVisible();
    const hrefs = await links.evaluateAll((elements) => elements.map((element) => element.getAttribute("href") ?? ""));
    const conversationIds = hrefs.map((href) => /^\/chat\/([0-9a-f-]+)$/.exec(href)?.[1]);
    for (const conversationId of conversationIds) {
      expect(conversationId, `실패 항목의 링크가 대화로 가지 않는다: ${hrefs.join(", ")}`).toBeTruthy();
      const retried = await page.request.post("/api/chat", { data: { text: "다시 정리해 주세요", conversationId } });
      expect(retried.ok(), `다시 보내기가 실패했다: ${retried.status()}`).toBeTruthy();
    }

    await page.goto("/now");
    await expect(failures.getByTestId("now-item")).toHaveCount(0);
    await expect(failures).toContainText("실패한 일이 없어요");
    await expect(
      page.getByTestId("now-card-continue").locator(`a[href="/chat/${conversationIds[0]}"]`),
    ).toBeVisible();
  });

  test("좁은 폭은 카드가 한 열이고 넓은 폭은 두 열이다", async ({ page }, testInfo) => {
    const created = await page.request.post("/api/chat", {
      data: { text: "주간 식단 메모", agentCode: await ownAgent(page) },
    });
    expect(created.ok(), `대화를 만들지 못했다: ${created.status()}`).toBeTruthy();

    await page.goto("/now");
    const cards = page.locator('[data-testid^="now-card-"]');
    await expect(cards).toHaveCount(4);
    const first = await cards.nth(0).boundingBox();
    const second = await cards.nth(1).boundingBox();
    expect(first && second, "카드의 자리를 읽지 못했다").toBeTruthy();
    if (testInfo.project.name === "mobile") {
      expect(second!.x, "두 번째 카드가 첫 카드와 같은 열이 아니다").toBe(first!.x);
      expect(second!.y, "두 번째 카드가 첫 카드 아래에 있지 않다").toBeGreaterThanOrEqual(first!.y + first!.height);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    } else {
      expect(second!.y, "넓은 폭의 첫 두 카드가 한 줄에 있지 않다").toBe(first!.y);
      expect(second!.x).toBeGreaterThan(first!.x);
    }
  });

  test("「지금 볼 것」 은 사이드바의 「새 대화」 바로 아래에 있고 주요 화면 메뉴에는 없다", async ({ page }, testInfo) => {
    await page.goto("/usage");
    const sidebar = await openSidebar(page, testInfo);
    const link = sidebar.getByRole("link", { name: /^지금 볼 것/ });
    await expect(link).toBeVisible();
    const linkBox = await link.boundingBox();
    const newBox = await sidebar.getByTestId("new-conversation-link").boundingBox();
    const searchBox = await sidebar.getByRole("searchbox", { name: "대화 검색" }).boundingBox();
    expect(linkBox && newBox && searchBox, "사이드바 요소의 자리를 읽지 못했다").toBeTruthy();
    expect(linkBox!.y, "링크가 「새 대화」 아래에 있지 않다").toBeGreaterThanOrEqual(newBox!.y + newBox!.height);
    expect(linkBox!.y, "링크가 「대화 검색」 위에 있지 않다").toBeLessThan(searchBox!.y);
    await expect(
      sidebar.getByRole("navigation", { name: "주요 화면" }).getByRole("link", { name: /^지금 볼 것/ }),
    ).toHaveCount(0);
  });

  test("알림을 모두 읽어도 「지금 볼 것」 의 수는 그대로다", async ({ page }, testInfo) => {
    // 승인 대기 하나가 알림 한 줄과 지금 볼 것 한 항목을 함께 만든 상태다. 두 경로를 대역으로 둔다.
    const state = { unread: 1, readAt: null as string | null };
    await page.route("**/api/attention/summary", (route: Route) => route.fulfill({ json: { nowCount: 1 } }));
    await page.route("**/api/notifications?**", (route: Route) => {
      if (new URL(route.request().url()).pathname !== "/api/notifications") return route.fallback();
      return route.fulfill({
        json: {
          items: [
            {
              id: "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
              kind: "APPROVAL_REQUESTED",
              title: "승인을 기다리는 요청이 있어요",
              body: "장보기 메모 쓰기",
              targetType: null,
              targetId: null,
              createdAt: "2026-10-05T11:00:00Z",
              readAt: state.readAt,
            },
          ],
          nextCursor: null,
          unreadCount: state.unread,
        },
      });
    });
    await page.route("**/api/notifications/events", async () => {
      await new Promise(() => {});
    });
    await page.route("**/api/notifications/read-all", (route: Route) => {
      state.unread = 0;
      state.readAt = "2026-10-05T11:05:00Z";
      return route.fulfill({ status: 204 });
    });

    // 모바일 머리의 알림 단추도 `notification-count` 를 그리고 넓은 폭에서도 DOM 에 남으므로 사이드바 안에서만 찾는다.
    await page.goto("/now");
    let sidebar = await openSidebar(page, testInfo);
    await expect(sidebar.getByTestId("notification-count")).toHaveText("1");
    await expect(sidebar.getByTestId("now-count")).toHaveText("1");

    await page.goto("/notifications");
    await page.getByRole("button", { name: "모두 읽음" }).click();
    await expect(page.getByRole("button", { name: "모두 읽음" })).toBeDisabled();
    sidebar = await openSidebar(page, testInfo);
    await expect(sidebar.getByTestId("notification-count")).toHaveCount(0);
    await expect(sidebar.getByTestId("now-count")).toHaveText("1");
    await expect(sidebar.getByRole("link", { name: "지금 볼 것 1건" })).toBeVisible();

    await page.unrouteAll({ behavior: "ignoreErrors" });
  });

  // 이 파일의 검사들이 같은 사용자와 에이전트를 함께 쓰므로 검사마다 지우지 않고 끝에 한 번 지운다.
  test.afterAll(async ({ browser }, testInfo) => {
    const context = await browser.newContext({ baseURL: WEB_BASE_URL });
    try {
      await setSession(context, memberOf(testInfo.project.name));
      const listed = await context.request.get("/api/agents");
      const mine = ((await listed.json()) as { code: string; ownedByMe: boolean }[]).filter((agent) => agent.ownedByMe);
      for (const agent of mine) {
        const deleted = await context.request.delete(`/api/agents/${agent.code}`);
        expect(deleted.status(), `검사가 만든 에이전트 ${agent.code} 를 지우지 못했다`).toBe(204);
      }
    } finally {
      await context.close();
    }
  });
});
