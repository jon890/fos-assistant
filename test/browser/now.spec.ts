import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import type { Locator, Page, Route, TestInfo } from "../../web/node_modules/@playwright/test/index.js";
import { isoToSeoulInput } from "../../web/src/lib/attention.ts";
import { expect, setSession, test, type FakeHermesControl } from "./fixtures.ts";
import { CONTROL_PLANE_BASE_URL, JWT_SECRET, WEB_BASE_URL } from "./settings.ts";

const HOUR_MS = 60 * 60 * 1000;

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

  /**
   * 지금 화면으로 가서 hydration 이 끝나기를 기다린다. 사이드바의 「지금 볼 것」 링크는 마운트된 뒤 수를 읽으므로
   * 그 응답이 온 시점이면 화면의 단추에 이벤트 핸들러가 붙어 있다. 그 전에 누른 클릭은 요청 없이 사라진다.
   */
  async function openNow(page: Page) {
    const summary = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/attention/summary");
    await page.goto("/now");
    await summary;
  }

  /** 상태를 바꾸는 단추를 누르고, 그 요청이 실제로 나가 성공했는지까지 확인한다. */
  async function clickSending(page: Page, target: Locator, method: string, pathname: RegExp) {
    const [response] = await Promise.all([
      page.waitForResponse(
        (candidate) => candidate.request().method() === method && pathname.test(new URL(candidate.url()).pathname),
      ),
      target.click(),
    ]);
    expect(response.ok(), `${method} ${response.url()} 가 실패했다: ${response.status()}`).toBeTruthy();
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

    await openNow(page);
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
    await openNow(page);
    const failures = page.getByTestId("now-card-failures");
    if ((await failures.getByTestId("now-item").count()) === 0) {
      await failTurn(page, hermes, "주간 장보기 목록 정리");
      await openNow(page);
    }
    const links = failures.getByTestId("now-item").getByRole("link");
    await expect(links.first()).toBeVisible();
    // 한 줄에 제목 링크와 「대화 열기」 가 함께 있어 같은 주소를 한 번씩만 다시 보낸다.
    const hrefs = [
      ...new Set(await links.evaluateAll((elements) => elements.map((element) => element.getAttribute("href") ?? ""))),
    ];
    const conversationIds = hrefs.map((href) => /^\/chat\/([0-9a-f-]+)$/.exec(href)?.[1]);
    for (const conversationId of conversationIds) {
      expect(conversationId, `실패 항목의 링크가 대화로 가지 않는다: ${hrefs.join(", ")}`).toBeTruthy();
      const retried = await page.request.post("/api/chat", { data: { text: "다시 정리해 주세요", conversationId } });
      expect(retried.ok(), `다시 보내기가 실패했다: ${retried.status()}`).toBeTruthy();
    }

    await openNow(page);
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

    await openNow(page);
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
    await openNow(page);
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

  /**
   * 에이전트가 제안한 할 일을 만든다. 제안은 에이전트의 제안 도구만 만들므로 test-support 경로로 넣는다.
   * 그 사용자의 메일로 서명한 Control Plane 토큰으로 부른다.
   */
  async function proposeFollowUp(email: string, conversationId: string, title: string): Promise<string> {
    const token = await new SignJWT({ name: "지금 화면 보는 사용자" })
      .setProtectedHeader({ alg: "HS256" })
      .setSubject(email)
      .setIssuedAt()
      .setExpirationTime("2m")
      .sign(new TextEncoder().encode(JWT_SECRET));
    const response = await fetch(`${CONTROL_PLANE_BASE_URL}/api/v1/test-support/follow-ups/proposed`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ conversationId, title }),
    });
    expect(response.status, `할 일을 제안하지 못했다: ${await response.clone().text()}`).toBe(200);
    const { id } = (await response.json()) as { id?: unknown };
    expect(typeof id, "제안한 할 일의 식별자가 없다").toBe("string");
    return id as string;
  }

  async function summaryCount(page: Page): Promise<number> {
    const response = await page.request.get("/api/attention/summary");
    expect(response.ok(), `지금 볼 것의 수를 읽지 못했다: ${response.status()}`).toBeTruthy();
    return ((await response.json()) as { nowCount: number }).nowCount;
  }

  /** 항목의 열쇠로 그 줄을 찾는다. 숨기거나 미룬 줄은 제목이 사라져 글로 찾지 못한다. */
  async function rowKey(row: Locator): Promise<string> {
    const key = await row.getAttribute("data-item-key");
    expect(key, "항목의 열쇠가 없다").toBeTruthy();
    return key!;
  }

  function conversationIdOf(href: string | null): string {
    const id = /^\/chat\/([0-9a-f-]+)$/.exec(href ?? "")?.[1];
    expect(id, `대화로 가는 링크가 아니다: ${href}`).toBeTruthy();
    return id!;
  }

  /** 수가 0 보다 크면 배지에 그 수가, 0 이면 배지가 없다. */
  async function expectBadge(badge: Locator, count: number) {
    if (count > 0) await expect(badge).toHaveText(String(count));
    else await expect(badge).toHaveCount(0);
  }

  test("숨긴 실패는 실패 카드에서 빠지고 상태가 바뀌면 다시 보인다", async ({ page, hermes }, testInfo) => {
    const title = "주간 장보기 영수증 정리";
    await failTurn(page, hermes, title);

    await openNow(page);
    const failures = page.getByTestId("now-card-failures");
    const row = failures.getByTestId("now-item").filter({ hasText: title });
    await expect(row).toHaveCount(1);
    const conversationId = conversationIdOf(await row.getByRole("link", { name: title }).getAttribute("href"));
    const key = await rowKey(row);
    // 앞 검사가 남긴 항목이 있을 수 있어 숨기기 전의 수를 읽고, 숨긴 뒤 그 수에서 하나 줄었는지 본다.
    const before = await summaryCount(page);
    expect(before, "실패를 만들었는데 지금 볼 것의 수가 0 이다").toBeGreaterThanOrEqual(1);
    const cardCount = failures.getByTestId("card-now-count");
    const cardBefore = Number(await cardCount.textContent());
    expect(cardBefore, "실패 카드 머리의 수").toBeGreaterThanOrEqual(1);
    // 숫자 배지는 낭독기에서 숨기고 옆의 sr-only 글이 수를 읽어 준다.
    await expect(failures.getByText(`지금 볼 것 ${cardBefore}건`)).toHaveCount(1);

    const controlled = failures.locator(`[data-item-key="${key}"]`);
    await controlled.getByRole("button", { name: "이 항목 제어" }).click();
    await clickSending(page, page.getByRole("menuitem", { name: "숨기기" }), "POST", /^\/api\/attention\/hide$/);
    await expect(controlled).toContainText("숨겼어요");
    await expect(controlled.getByRole("button", { name: "되돌리기" })).toBeVisible();
    // 카드 머리의 수는 화면을 다시 읽지 않고 숨긴 만큼 줄인다.
    await expectBadge(cardCount, cardBefore - 1);
    await expect(failures.getByText(`지금 볼 것 ${cardBefore - 1}건`)).toHaveCount(cardBefore - 1 > 0 ? 1 : 0);
    expect(await summaryCount(page), "숨긴 뒤 지금 볼 것의 수").toBe(before - 1);
    // 경로가 그대로여도 사이드바의 수를 다시 읽는다.
    let sidebar = await openSidebar(page, testInfo);
    await expectBadge(sidebar.getByTestId("now-count"), before - 1);

    await openNow(page);
    await expect(failures.getByTestId("now-item").filter({ hasText: title })).toHaveCount(0);
    // 제어는 카드마다 걸려 같은 대화가 이어서 하기 카드에는 보인다.
    await expect(page.getByTestId("now-card-continue").locator(`a[href="/chat/${conversationId}"]`)).toBeVisible();
    sidebar = await openSidebar(page, testInfo);
    await expectBadge(sidebar.getByTestId("now-count"), before - 1);

    await failTurn(page, hermes, "다시 정리해 주세요", conversationId);
    await openNow(page);
    await expect(failures.locator(`a[href="/chat/${conversationId}"]`).first()).toBeVisible();
  });

  test("숨겼다 되돌리면 카드 머리의 수가 다시 늘어난다", async ({ page, hermes }) => {
    const title = "주간 장보기 쿠폰 정리";
    await failTurn(page, hermes, title);

    await openNow(page);
    const failures = page.getByTestId("now-card-failures");
    const row = failures.getByTestId("now-item").filter({ hasText: title });
    await expect(row).toHaveCount(1);
    const controlled = failures.locator(`[data-item-key="${await rowKey(row)}"]`);
    const cardCount = failures.getByTestId("card-now-count");
    const cardBefore = Number(await cardCount.textContent());
    expect(cardBefore, "실패 카드 머리의 수").toBeGreaterThanOrEqual(1);

    await controlled.getByRole("button", { name: "이 항목 제어" }).click();
    await clickSending(page, page.getByRole("menuitem", { name: "숨기기" }), "POST", /^\/api\/attention\/hide$/);
    await expect(controlled).toContainText("숨겼어요");
    await expectBadge(cardCount, cardBefore - 1);

    await clickSending(
      page,
      controlled.getByRole("button", { name: "되돌리기" }),
      "POST",
      /^\/api\/attention\/restore$/,
    );
    await expect(controlled).toContainText(title);
    await expect(cardCount).toHaveText(String(cardBefore));
  });

  test("네 카드가 모두 비어도 빈 화면 아래에서 할 일을 더할 수 있다", async ({ context, page }, testInfo) => {
    // 아무것도 만들지 않은 사용자라 네 카드가 모두 빈다. 이 검사는 저장하지 않아 다음 실행에도 빈 채로 남는다.
    await setSession(context, { email: `now-empty-${testInfo.project.name}@example.com`, name: "빈 화면 사용자" });
    expect((await page.request.get("/api/me")).ok()).toBeTruthy();

    await openNow(page);
    await expect(page.getByText("지금 확인할 것이 없어요")).toBeVisible();
    await expect(page.locator('[data-testid^="now-card-"]')).toHaveCount(0);
    await page.getByRole("button", { name: "할 일 더하기" }).click();
    const dialog = page.getByRole("dialog", { name: "할 일 더하기" });
    await expect(dialog.getByLabel("제목", { exact: true })).toBeVisible();
    await dialog.getByRole("button", { name: "취소" }).click();
    await expect(dialog).toHaveCount(0);
  });

  test("직접 더한 할 일은 내 차례에 보이고 미뤘다 되돌린 뒤 끝낼 수 있다", async ({ page }) => {
    const title = "장보기 예약 확인";
    await openNow(page);
    const needsMe = page.getByTestId("now-card-needs_me");
    await needsMe.getByRole("button", { name: "할 일 더하기" }).click();
    const dialog = page.getByRole("dialog", { name: "할 일 더하기" });
    await dialog.getByLabel("제목", { exact: true }).fill(title);
    await dialog
      .getByLabel("기한", { exact: true })
      .fill(isoToSeoulInput(new Date(Date.now() + 3 * HOUR_MS).toISOString()));
    await clickSending(page, dialog.getByRole("button", { name: "저장" }), "POST", /^\/api\/follow-ups$/);
    await expect(dialog).toHaveCount(0);

    const row = needsMe.getByTestId("now-item").filter({ hasText: title });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText("기한이 다가왔어요");
    await expect(row.getByText("지금", { exact: true })).toBeVisible();
    await expect(row).toContainText("직접 더함");

    const controlled = needsMe.locator(`[data-item-key="${await rowKey(row)}"]`);
    await controlled.getByRole("button", { name: "이 항목 제어" }).click();
    await clickSending(
      page,
      page.getByRole("menuitem", { name: "내일 아침으로 미루기" }),
      "POST",
      /^\/api\/attention\/snooze$/,
    );
    await expect(controlled).toContainText("미뤘어요");
    await clickSending(
      page,
      controlled.getByRole("button", { name: "되돌리기" }),
      "POST",
      /^\/api\/attention\/restore$/,
    );
    await expect(controlled).toContainText(title);

    await openNow(page);
    await expect(row).toHaveCount(1);
    await clickSending(page, row.getByRole("button", { name: "끝냄" }), "POST", /^\/api\/follow-ups\/[^/]+\/done$/);
    await expect(row).toHaveCount(0);
  });

  test("에이전트가 제안한 할 일은 건수에 세지 않고 고쳐서 받아들이거나 거절할 수 있다", async ({
    page,
    hermes,
  }, testInfo) => {
    const email = memberOf(testInfo.project.name).email;
    await openNow(page);
    const failed = page.getByTestId("now-card-failures").getByTestId("now-item").getByRole("link");
    if ((await failed.count()) === 0) {
      await failTurn(page, hermes, "주간 장보기 영수증 정리");
      await openNow(page);
    }
    const conversationId = conversationIdOf(await failed.first().getAttribute("href"));

    const title = "학교 상담 신청서 내기";
    const before = await summaryCount(page);
    await proposeFollowUp(email, conversationId, title);
    expect(await summaryCount(page), "제안이 지금 볼 것의 수에 들어갔다").toBe(before);

    await openNow(page);
    const needsMe = page.getByTestId("now-card-needs_me");
    const row = needsMe.getByTestId("now-item").filter({ hasText: title });
    await expect(row).toHaveCount(1);
    await expect(row).toHaveAttribute("data-attention", "LATER");
    await expect(row).toContainText("에이전트가 할 일로 제안했어요");
    await expect(row).toContainText("대화에서");

    const due = isoToSeoulInput(new Date(Date.now() + 72 * HOUR_MS).toISOString());
    await row.getByRole("button", { name: "고치기" }).click();
    const dialog = page.getByRole("dialog", { name: "할 일 고치기" });
    await expect(dialog.getByLabel("제목", { exact: true })).toHaveValue(title);
    await dialog.getByLabel("기한", { exact: true }).fill(due);
    await clickSending(page, dialog.getByRole("button", { name: "저장" }), "PATCH", /^\/api\/follow-ups\/[^/]+$/);
    await expect(dialog).toHaveCount(0);

    await openNow(page);
    await row.getByRole("button", { name: "고치기" }).click();
    await expect(dialog.getByLabel("기한", { exact: true })).toHaveValue(due);
    await dialog.getByRole("button", { name: "취소" }).click();
    await expect(dialog).toHaveCount(0);

    await clickSending(
      page,
      row.getByRole("button", { name: "받아들이기" }),
      "POST",
      /^\/api\/follow-ups\/[^/]+\/accept$/,
    );
    await expect(row).toContainText("챙기고 있는 할 일이에요");
    for (const name of ["끝냄", "그만둠", "고치기"]) {
      await expect(row.getByRole("button", { name, exact: true })).toBeVisible();
    }
    await expect(row.getByRole("button", { name: "받아들이기" })).toHaveCount(0);
    await expect(row.getByRole("button", { name: "거절" })).toHaveCount(0);

    if (testInfo.project.name === "mobile") {
      await row.getByRole("button", { name: "이 항목 제어" }).click();
      await expect(page.getByRole("menuitem", { name: "일주일 뒤로 미루기" })).toBeVisible();
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth),
        "메뉴나 단추가 좁은 폭 밖으로 넘친다",
      ).toBeLessThanOrEqual(390);
      await page.keyboard.press("Escape");
    }

    const second = "주말 장보기 목록 공유";
    await proposeFollowUp(email, conversationId, second);
    await openNow(page);
    const rejected = needsMe.getByTestId("now-item").filter({ hasText: second });
    await expect(rejected).toHaveCount(1);
    await clickSending(
      page,
      rejected.getByRole("button", { name: "거절" }),
      "POST",
      /^\/api\/follow-ups\/[^/]+\/reject$/,
    );
    await expect(rejected).toHaveCount(0);
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
