import { expect, test } from "./fixtures.ts";
import type {
  Page,
  Route,
} from "../../web/node_modules/@playwright/test/index.js";

const ACTION_ID = "0f0e0d0c-0b0a-4908-8706-050403020100";
const TOOL_NAME = "write_note";

type Action = Record<string, unknown> & { status: string };

function action(overrides: Record<string, unknown> = {}): Action {
  return {
    actionId: ACTION_ID,
    connectorId: "demo-notes",
    toolName: TOOL_NAME,
    title: "메모 쓰기",
    risk: "WRITE",
    status: "PENDING",
    argsJson: JSON.stringify({ title: "장보기", count: 3 }),
    resultText: null,
    errorCode: null,
    createdAt: "2026-09-30T12:00:00Z",
    expiresAt: "2026-10-01T12:00:00Z",
    grantAllowed: true,
    hiddenArgs: false,
    ...overrides,
  };
}

/** 새 대화를 만들어 질문 하나와 답 하나를 저장하고 그 공개 식별자를 돌려준다. */
async function createConversation(page: Page, label: string): Promise<string> {
  const created = await page.request.post("/api/chat", {
    data: { text: `${label} ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  return ((await created.json()) as { conversationId: string }).conversationId;
}

/** 대화 단위 SSE 를 사건 없이 붙잡아 둔다. 다시 연결하며 승인 줄을 다시 읽지 않게 한다. */
async function holdEvents(page: Page, conversationId: string) {
  await page.route(
    `**/api/chat/conversations/${conversationId}/events`,
    async () => {
      await new Promise(() => {});
    },
  );
}

/** 승인 줄 읽기를 대역한다. `current` 가 주는 목록을 돌려주고 읽은 횟수를 센다. */
async function routeActions(
  page: Page,
  conversationId: string,
  current: () => Action[],
) {
  const reads = { count: 0 };
  await page.route(
    `**/api/chat/conversations/${conversationId}/connector-actions`,
    (route: Route) => {
      reads.count += 1;
      return route.fulfill({ json: current() });
    },
  );
  return reads;
}

/** 승인 줄 하나가 있는 대화를 연다. */
async function openWith(page: Page, label: string, first: Action) {
  const conversationId = await createConversation(page, label);
  const state = { actions: [first] };
  await holdEvents(page, conversationId);
  const reads = await routeActions(page, conversationId, () => state.actions);
  await page.goto(`/chat/${conversationId}`);
  return { conversationId, state, reads };
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("승인 줄이 없는 대화에는 카드 자리가 없다", async ({ page }) => {
  const conversationId = await createConversation(page, "승인 줄 없음 검사");
  await holdEvents(page, conversationId);
  const reads = await routeActions(page, conversationId, () => []);
  await page.goto(`/chat/${conversationId}`);

  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect.poll(() => reads.count).toBeGreaterThanOrEqual(1);
  await expect(page.getByTestId("approval-list")).toHaveCount(0);
});

test("기다리는 승인 줄은 제목과 위험도와 인자의 키와 값을 보이고 내부 값은 보이지 않는다", async ({
  page,
}) => {
  await openWith(page, "승인 카드 표시 검사", action());

  const card = page.getByTestId("approval-card");
  await expect(card).toHaveAttribute("data-status", "PENDING");
  await expect(card).toContainText("메모 쓰기");
  await expect(card).toContainText("쓰기");
  await expect(card).toContainText(
    "에이전트가 이 동작을 하려고 해요. 내용을 확인해 주세요.",
  );
  const args = card.getByTestId("approval-args");
  await expect(args.locator("dt")).toHaveText(["title", "count"]);
  await expect(args.locator("dd")).toHaveText(["장보기", "3"]);
  await expect(card).not.toContainText(TOOL_NAME);
  await expect(card).not.toContainText(ACTION_ID);
});

test("비밀처럼 보이는 키의 값도 화면이 가리지 않고 서버가 준 그대로 보인다", async ({
  page,
}) => {
  await openWith(
    page,
    "승인 카드 가림 없음 검사",
    action({
      argsJson: JSON.stringify({
        password_hint: "첫 반려동물 이름",
        api_token: "[가림]",
      }),
    }),
  );

  const args = page.getByTestId("approval-args");
  await expect(args.locator("dd")).toHaveText(["첫 반려동물 이름", "[가림]"]);
});

test("상시 허락을 줄 수 없는 줄은 긴 본문 뒤의 인자까지 스크롤 영역 없이 모두 펼친다", async ({
  page,
}) => {
  await openWith(
    page,
    "승인 카드 펼침 검사",
    action({
      grantAllowed: false,
      argsJson: JSON.stringify({
        subject: "회의록",
        body: Array.from({ length: 40 }, (_, index) => `줄 ${index}`).join(
          "\n",
        ),
        to: "friend@example.com",
        bcc: "other@example.com",
      }),
    }),
  );

  const args = page.getByTestId("approval-args");
  await expect(args.locator("dt")).toHaveText(["subject", "body", "to", "bcc"]);
  await expect(args.getByText("friend@example.com")).toBeVisible();
  await expect(args.getByText("other@example.com")).toBeVisible();
  // 펼친 카드가 화면보다 길어도 첫 인자와 끝 인자에 모두 닿을 수 있다.
  for (const reachable of [
    args.locator("dt").first(),
    args.getByText("other@example.com"),
    page.getByTestId("approval-approve"),
  ]) {
    await reachable.scrollIntoViewIfNeeded();
    await expect(reachable).toBeInViewport();
  }
  expect(
    await args.evaluate((element) => ({
      scrolls: element.scrollHeight > element.clientHeight,
      overflowY: getComputedStyle(element).overflowY,
      maxHeight: getComputedStyle(element).maxHeight,
    })),
  ).toEqual({ scrolls: false, overflowY: "visible", maxHeight: "none" });
});

test("상시 허락을 줄 수 없는 줄은 JSON 으로 읽히지 않는 긴 원문도 모두 펼친다", async ({
  page,
}) => {
  const raw = `{"body":"${Array.from({ length: 40 }, (_, index) => `줄 ${index}`).join("\n")}`;
  await openWith(
    page,
    "승인 카드 원문 펼침 검사",
    action({ grantAllowed: false, argsJson: raw }),
  );

  const args = page.getByTestId("approval-args");
  await expect(args).toHaveText(raw);
  expect(
    await args.evaluate(
      (element) => element.scrollHeight === element.clientHeight,
    ),
  ).toBe(true);
});

test("390px 폭에서 펼친 긴 인자도 가로로 넘치지 않는다", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 });
  await openWith(
    page,
    "승인 카드 펼침 폭 검사",
    action({
      grantAllowed: false,
      argsJson: JSON.stringify({
        ["k".repeat(120)]: "a".repeat(600),
        body: Array.from({ length: 40 }, (_, index) => `줄 ${index}`).join(
          "\n",
        ),
        to: "friend@example.com",
      }),
    }),
  );

  const args = page.getByTestId("approval-args");
  await expect(args.getByText("friend@example.com")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  expect(
    await args.evaluate(
      (element) =>
        element.scrollWidth <= element.clientWidth &&
        element.scrollHeight === element.clientHeight,
    ),
  ).toBe(true);
});

test("인자에 든 HTML 과 마크다운은 글자 그대로 보인다", async ({ page }) => {
  await openWith(
    page,
    "승인 카드 원문 검사",
    action({ argsJson: JSON.stringify({ body: "<b>굵게</b> **굵게**" }) }),
  );

  const args = page.getByTestId("approval-args");
  await expect(args.locator("dd")).toHaveText("<b>굵게</b> **굵게**");
  await expect(args.locator("b, strong")).toHaveCount(0);
});

test("JSON 으로 읽히지 않는 인자는 원문 글 그대로 보인다", async ({ page }) => {
  const raw = '{"body":"<b>굵게</b> **잘린 글…';
  await openWith(page, "승인 카드 잘린 인자 검사", action({ argsJson: raw }));

  const args = page.getByTestId("approval-args");
  await expect(args).toHaveText(raw);
  await expect(args.locator("b, strong, dt")).toHaveCount(0);
});

test("승인을 누르면 허락 없이 승인하고 실행이 끝난 카드는 사라진다", async ({
  page,
}) => {
  const { state } = await openWith(page, "승인 누르기 검사", action());
  let body: unknown;
  await page.route(
    `**/api/connector-actions/${ACTION_ID}/approve`,
    (route: Route) => {
      body = route.request().postDataJSON();
      state.actions = [action({ status: "SUCCEEDED" })];
      return route.fulfill({ json: state.actions[0] });
    },
  );

  await page.getByTestId("approval-approve").click();

  await expect(page.getByTestId("approval-list")).toHaveCount(0);
  expect(body).toEqual({ grant: null });
});

test("승인했지만 실행하지 않고 끝난 줄도 카드가 사라진다", async ({ page }) => {
  const { state } = await openWith(page, "승인 미실행 검사", action());
  await page.route(
    `**/api/connector-actions/${ACTION_ID}/approve`,
    (route: Route) => {
      state.actions = [
        action({ status: "REJECTED", errorCode: "not_executable" }),
      ];
      return route.fulfill({ json: state.actions[0] });
    },
  );

  await page.getByTestId("approval-approve").click();

  await expect(page.getByTestId("approval-list")).toHaveCount(0);
  await expect(page.getByText("not_executable")).toHaveCount(0);
});

test("승인하고 묻지 않기에서 고른 기간으로 승인한다", async ({ page }) => {
  const { state } = await openWith(page, "묻지 않기 검사", action());
  let body: unknown;
  await page.route(
    `**/api/connector-actions/${ACTION_ID}/approve`,
    (route: Route) => {
      body = route.request().postDataJSON();
      state.actions = [action({ status: "SUCCEEDED" })];
      return route.fulfill({ json: state.actions[0] });
    },
  );

  await page.getByTestId("approval-grant").click();
  await expect(page.getByRole("menuitem")).toHaveText([
    "1시간 동안",
    "오늘 하루",
    "30일 동안",
  ]);
  await page.getByRole("menuitem", { name: "오늘 하루" }).click();

  await expect(page.getByTestId("approval-list")).toHaveCount(0);
  expect(body).toEqual({ grant: "TODAY" });
});

test("허락을 줄 수 없는 동작에는 묻지 않기 단추가 없다", async ({ page }) => {
  await openWith(page, "허락 불가 검사", action({ grantAllowed: false }));

  await expect(page.getByTestId("approval-approve")).toBeVisible();
  await expect(page.getByTestId("approval-grant")).toHaveCount(0);
});

test("가려진 내용이 있는 줄은 경고를 보이고 승인 단추 없이 거절만 남긴다", async ({
  page,
}) => {
  await openWith(
    page,
    "가려진 인자 검사",
    action({
      grantAllowed: true,
      hiddenArgs: true,
      argsJson: JSON.stringify({ body: "회의록입니다 [가림]" }),
    }),
  );

  const card = page.getByTestId("approval-card");
  await expect(card.getByTestId("approval-hidden-args")).toHaveText(
    "가려진 내용이 있어 승인할 수 없어요. 에이전트에게 그 부분을 빼거나 다시 쓰게 해 주세요.",
  );
  await expect(card.getByTestId("approval-approve")).toHaveCount(0);
  await expect(card.getByTestId("approval-grant")).toHaveCount(0);
  await expect(card.getByTestId("approval-reject")).toBeEnabled();
});

test("가려진 내용이 없는 줄에는 경고가 없고 승인 단추가 있다", async ({
  page,
}) => {
  await openWith(
    page,
    "가려지지 않은 인자 검사",
    action({ hiddenArgs: false }),
  );

  await expect(page.getByTestId("approval-approve")).toBeVisible();
  await expect(page.getByTestId("approval-hidden-args")).toHaveCount(0);
});

test("거절을 누르면 거절 요청이 가고 카드가 사라진다", async ({ page }) => {
  const { state } = await openWith(page, "거절 누르기 검사", action());
  let rejected = 0;
  await page.route(
    `**/api/connector-actions/${ACTION_ID}/reject`,
    (route: Route) => {
      rejected += 1;
      state.actions = [action({ status: "REJECTED" })];
      return route.fulfill({ json: state.actions[0] });
    },
  );

  await page.getByTestId("approval-reject").click();

  await expect(page.getByTestId("approval-list")).toHaveCount(0);
  expect(rejected).toBe(1);
});

test("이미 처리된 요청을 승인하면 문구를 보이고 목록을 다시 읽는다", async ({
  page,
}) => {
  const { reads } = await openWith(page, "이미 처리됨 검사", action());
  await page.route(
    `**/api/connector-actions/${ACTION_ID}/approve`,
    (route: Route) =>
      route.fulfill({
        status: 409,
        json: { code: "CONNECTOR_ACTION_NOT_PENDING", message: "raw upstream" },
      }),
  );
  await expect(page.getByTestId("approval-card")).toBeVisible();
  const before = reads.count;

  await page.getByTestId("approval-approve").click();

  await expect(page.getByTestId("approval-card").getByRole("alert")).toHaveText(
    "이미 처리된 요청이에요.",
  );
  await expect(page.getByText("raw upstream")).toHaveCount(0);
  await expect.poll(() => reads.count).toBeGreaterThan(before);
});

test("실행하는 중인 줄은 단추 없이 상태만 보인다", async ({ page }) => {
  await openWith(page, "실행 중 검사", action({ status: "EXECUTING" }));

  const card = page.getByTestId("approval-card");
  await expect(card.getByRole("status")).toHaveText("실행하는 중이에요");
  await expect(card.getByRole("button")).toHaveCount(0);
});

test("실행했는지 모르는 줄은 승인 단추 없이 안내를 보이고 닫을 수 있다", async ({
  page,
}) => {
  await openWith(page, "알 수 없음 검사", action({ status: "UNKNOWN" }));

  const card = page.getByTestId("approval-card");
  await expect(card).toContainText("실행했는지 알 수 없어요");
  await expect(card.getByTestId("approval-approve")).toHaveCount(0);
  await expect(card.getByTestId("approval-reject")).toHaveCount(0);
  await expect(card.getByTestId("approval-grant")).toHaveCount(0);

  await card.getByTestId("approval-dismiss").click();
  await expect(page.getByTestId("approval-list")).toHaveCount(0);
});

test("열린 대화에 승인 사건이 오면 목록을 다시 읽어 카드가 나타난다", async ({
  page,
}) => {
  const conversationId = await createConversation(page, "승인 사건 검사");
  let created = false;
  let markFirstRead!: () => void;
  const firstRead = new Promise<void>((resolve) => {
    markFirstRead = resolve;
  });
  await page.route(
    `**/api/chat/conversations/${conversationId}/connector-actions`,
    async (route: Route) => {
      await route.fulfill({ json: created ? [action()] : [] });
      markFirstRead();
    },
  );
  let eventRequests = 0;
  await page.route(
    `**/api/chat/conversations/${conversationId}/events`,
    async (route: Route) => {
      eventRequests += 1;
      if (eventRequests > 1) {
        await new Promise(() => {});
        return;
      }
      // 빈 목록을 먼저 읽게 한 뒤에 사건을 보낸다. 그래야 사건으로 다시 읽은 것을 본다.
      await firstRead;
      created = true;
      await route.fulfill({
        status: 200,
        headers: {
          "Content-Type": "text/event-stream; charset=utf-8",
          "Cache-Control": "no-cache",
        },
        body: `data: ${JSON.stringify({ type: "approval", conversationId, detail: ACTION_ID })}\n\n`,
      });
    },
  );

  await page.goto(`/chat/${conversationId}`);

  await expect(page.getByTestId("approval-card")).toContainText("메모 쓰기");
});

test("390px 폭에서 긴 인자도 가로로 넘치지 않는다", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 });
  await openWith(
    page,
    "승인 카드 폭 검사",
    action({
      grantAllowed: true,
      title: `메모 쓰기 ${"가".repeat(80)}`,
      argsJson: JSON.stringify({
        ["k".repeat(120)]: "a".repeat(600),
        nested: { deep: "b".repeat(300) },
        lines: Array.from({ length: 40 }, (_, index) => `줄 ${index}`),
      }),
    }),
  );

  const args = page.getByTestId("approval-args");
  await expect(args).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  expect(
    await args.evaluate(
      (element) => element.scrollWidth <= element.clientWidth,
    ),
  ).toBe(true);
  // 상시 허락을 줄 수 있는 줄은 길면 카드 안에서 세로로 스크롤한다.
  expect(
    await args.evaluate(
      (element) => element.scrollHeight > element.clientHeight,
    ),
  ).toBe(true);
});

/** 라벨 만들기 승인 줄이다. 운영에서 본 모양처럼 값이 빈 인자와 원 이름 인자를 함께 싣는다. */
function labelAction(index: number, name: string, overrides: Record<string, unknown> = {}): Action {
  return action({
    actionId: `0f0e0d0c-0b0a-4908-8706-0504030201${String(index).padStart(2, "0")}`,
    connectorId: "gmail",
    toolName: "create_label",
    title: "라벨 만들기",
    argsJson: JSON.stringify({
      name,
      label_list_visibility: "labelShow",
      message_list_visibility: "show",
      background_color: "",
      text_color: "",
    }),
    ...overrides,
  });
}

const LABELS = ["업무", "가족", "영수증", "뉴스레터"];

/** 승인 줄 여럿이 있는 대화를 연다. `turns` 만큼 질문을 더 보내 메시지 목록이 넘치게 한다. */
async function openWithMany(page: Page, label: string, actions: Action[], turns = 0) {
  const conversationId = await createConversation(page, label);
  for (let index = 0; index < turns; index += 1) {
    const sent = await page.request.post("/api/chat", {
      data: { conversationId, text: `${label} ${index}번째 물음`, agentCode: "browser" },
    });
    expect(sent.ok()).toBeTruthy();
  }
  const state = { actions };
  await holdEvents(page, conversationId);
  const reads = await routeActions(page, conversationId, () => state.actions);
  await page.goto(`/chat/${conversationId}`);
  return { conversationId, state, reads };
}

/** 승인과 거절 요청을 대역한다. 받은 줄은 끝난 상태로 바꿔 돌려주고, 요청의 순서와 본문을 남긴다. */
async function routeDecisions(page: Page, state: { actions: Action[] }) {
  const calls: { actionId: string; kind: string; body: unknown }[] = [];
  await page.route("**/api/connector-actions/*/*", (route: Route) => {
    const [actionId, kind] = new URL(route.request().url()).pathname.split("/").slice(-2);
    calls.push({ actionId, kind, body: route.request().postDataJSON() });
    const status = kind === "approve" ? "SUCCEEDED" : "REJECTED";
    state.actions = state.actions.map((item) => (item.actionId === actionId ? { ...item, status } : item));
    return route.fulfill({ json: state.actions.find((item) => item.actionId === actionId) });
  });
  return calls;
}

for (const [project, size] of [
  ["desktop", { width: 1040, height: 600 }],
  ["mobile", { width: 390, height: 844 }],
] as const) {
  test(`${size.width}px 에서 승인 줄이 넷 이상이어도 메시지 목록이 보이고 스크롤된다`, async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== project, `${project} 에서만 돈다`);
    await page.setViewportSize(size);
    await openWithMany(
      page,
      "승인 여럿 화면 검사",
      [...LABELS.map((name, index) => labelAction(index, name)), action({ title: "메모 쓰기" })],
      4,
    );

    const dock = page.getByTestId("approval-list");
    await expect(dock).toBeVisible();
    await expect(page.getByTestId("assistant-message")).toHaveCount(5);
    const list = page.getByTestId("message-scroll");
    const measured = await list.evaluate((element) => ({
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight,
    }));
    // 메시지 목록이 화면 높이의 4분의 1 이상을 갖고, 그 안이 넘쳐 스크롤된다.
    expect(measured.clientHeight).toBeGreaterThanOrEqual(size.height / 4);
    expect(measured.scrollHeight).toBeGreaterThan(measured.clientHeight);
    const dockBox = await dock.boundingBox();
    expect(dockBox!.height).toBeLessThanOrEqual(size.height * 0.4 + 1);

    await list.evaluate((element) => {
      element.scrollTop = 0;
    });
    await expect(page.getByTestId("assistant-message").first()).toBeInViewport();
    await expect(page.getByRole("textbox", { name: "메시지" })).toBeInViewport();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });
}

test("같은 도구의 승인 줄은 한 묶음으로 접혀 보이고 펼치면 건마다 인자가 보인다", async ({ page }) => {
  await openWithMany(
    page,
    "승인 묶음 검사",
    [...LABELS.map((name, index) => labelAction(index, name)), action({ title: "메모 쓰기" })],
  );

  const group = page.getByTestId("approval-group");
  await expect(group).toHaveCount(1);
  await expect(group).toHaveAttribute("data-count", "4");
  await expect(group).toContainText("라벨 만들기");
  await expect(group).toContainText("4건");
  await expect(group.getByTestId("approval-group-summary")).toHaveText("업무, 가족, 영수증, 뉴스레터");
  await expect(group.getByTestId("approval-card")).toHaveCount(0);
  await expect(group.getByTestId("approval-approve-all")).toHaveCount(0);
  // 다른 도구의 줄은 묶음 밖에 한 장으로 남는다.
  await expect(page.getByTestId("approval-list").getByTestId("approval-card")).toHaveCount(1);

  const toggle = group.getByTestId("approval-group-toggle");
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  const items = group.getByTestId("approval-card");
  await expect(items).toHaveCount(4);
  for (const [index, name] of LABELS.entries()) {
    await expect(items.nth(index).getByTestId("approval-args").locator("dd").first()).toHaveText(name);
  }
  await expect(group.getByTestId("approval-group-summary")).toHaveCount(0);

  await toggle.click();
  await expect(group.getByTestId("approval-card")).toHaveCount(0);
});

test("묶음 안에서 건마다 승인하고 거절하며 펼친 상태가 남는다", async ({ page }) => {
  const { state } = await openWithMany(
    page,
    "묶음 한 건씩 검사",
    LABELS.map((name, index) => labelAction(index, name)),
  );
  const ids = state.actions.map((item) => item.actionId);
  const calls = await routeDecisions(page, state);
  const group = page.getByTestId("approval-group");
  await group.getByTestId("approval-group-toggle").click();

  await group.getByTestId("approval-card").nth(1).getByTestId("approval-approve").click();
  await expect(group).toHaveAttribute("data-count", "3");
  await expect(group.getByTestId("approval-card")).toHaveCount(3);
  await group.getByTestId("approval-card").nth(1).getByTestId("approval-reject").click();
  await expect(group).toHaveAttribute("data-count", "2");

  expect(calls).toEqual([
    { actionId: ids[1], kind: "approve", body: { grant: null } },
    { actionId: ids[2], kind: "reject", body: null },
  ]);
  await expect(group.getByTestId("approval-args").locator("dd").first()).toHaveText("업무");
  await expect(group.getByTestId("approval-args").nth(1).locator("dd").first()).toHaveText("뉴스레터");
});

test("모두 승인은 펼친 묶음에서 건마다 차례로 승인하고 카드를 치운다", async ({ page }) => {
  const { state } = await openWithMany(
    page,
    "모두 승인 검사",
    LABELS.map((name, index) => labelAction(index, name)),
  );
  const ids = state.actions.map((item) => item.actionId);
  const calls = await routeDecisions(page, state);
  const group = page.getByTestId("approval-group");
  await group.getByTestId("approval-group-toggle").click();

  await group.getByTestId("approval-approve-all").click();

  await expect(page.getByTestId("approval-list")).toHaveCount(0);
  expect(calls).toEqual(ids.map((actionId) => ({ actionId, kind: "approve", body: { grant: null } })));
});

for (const [name, overrides] of [
  ["상시 허락을 닫은 도구가", { grantAllowed: false }],
  ["되돌리기 어려운 도구가", { risk: "DESTRUCTIVE" }],
  ["위험도를 모르는 줄이", { risk: null }],
  ["가려진 인자가 있는 줄이", { hiddenArgs: true }],
] as const) {
  test(`${name} 섞인 묶음에는 모두 승인이 없다`, async ({ page }) => {
    await openWithMany(page, `모두 승인 제외 검사 ${name}`, [
      labelAction(0, "업무"),
      labelAction(1, "가족", overrides),
    ]);
    const group = page.getByTestId("approval-group");
    await group.getByTestId("approval-group-toggle").click();

    await expect(group.getByTestId("approval-card")).toHaveCount(2);
    await expect(group.getByTestId("approval-approve-all")).toHaveCount(0);
  });
}

test("값이 빈 인자는 숨기고 짧은 인자 둘만 위에 보이며 나머지는 자세히에 접는다", async ({ page }) => {
  await openWith(page, "빈 인자 숨김 검사", labelAction(0, "업무"));

  const card = page.getByTestId("approval-card");
  await expect(card.getByTestId("approval-args").locator("dt")).toHaveText(["name", "label_list_visibility"]);
  await expect(card.getByTestId("approval-args").locator("dd")).toHaveText(["업무", "labelShow"]);
  const more = card.getByTestId("approval-args-more");
  await expect(more.locator("summary")).toHaveText("자세히 (3개)");
  await expect(card.getByTestId("approval-args-folded")).toBeHidden();
  await expect(card.getByTestId("approval-args-empty")).toBeHidden();

  await more.locator("summary").click();
  await expect(card.getByTestId("approval-args-folded").locator("dt")).toHaveText(["message_list_visibility"]);
  await expect(card.getByTestId("approval-args-empty")).toHaveText("비어 있는 항목: background_color, text_color");
});

test("상시 허락을 닫은 줄은 빈 인자를 빼고 나머지를 모두 펼치며 빈 인자의 이름도 보인다", async ({ page }) => {
  await openWith(page, "닫은 도구 빈 인자 검사", labelAction(0, "업무", { grantAllowed: false }));

  const card = page.getByTestId("approval-card");
  await expect(card.getByTestId("approval-args").locator("dt")).toHaveText([
    "name",
    "label_list_visibility",
    "message_list_visibility",
  ]);
  await expect(card.getByTestId("approval-args-more")).toHaveCount(0);
  await expect(card.getByTestId("approval-args-empty")).toBeVisible();
  await expect(card.getByTestId("approval-args-empty")).toHaveText("비어 있는 항목: background_color, text_color");
});

test("모두 승인 중 요청이 실패하면 멈추고 오류를 보이며 목록을 다시 읽는다", async ({ page }) => {
  const { state, reads } = await openWithMany(
    page,
    "모두 승인 실패 검사",
    LABELS.map((name, index) => labelAction(index, name)),
  );
  const ids = state.actions.map((item) => item.actionId);
  const calls: string[] = [];
  await page.route("**/api/connector-actions/*/approve", (route: Route) => {
    const actionId = new URL(route.request().url()).pathname.split("/").at(-2)!;
    calls.push(actionId);
    if (actionId === ids[1]) return route.fulfill({ status: 500, json: { code: "" } });
    state.actions = state.actions.map((item) => (item.actionId === actionId ? { ...item, status: "SUCCEEDED" } : item));
    return route.fulfill({ json: state.actions.find((item) => item.actionId === actionId) });
  });
  const group = page.getByTestId("approval-group");
  await group.getByTestId("approval-group-toggle").click();
  const before = reads.count;

  await group.getByTestId("approval-approve-all").click();

  await expect(page.getByTestId("approval-bulk-error")).toHaveText(
    "요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  );
  expect(calls).toEqual([ids[0], ids[1]]);
  await expect.poll(() => reads.count).toBeGreaterThan(before);
  await expect(group).toHaveAttribute("data-count", "3");
});

test("모두 승인 중 실행했다고 볼 수 없는 결과가 오면 나머지를 보내지 않는다", async ({ page }) => {
  const { state } = await openWithMany(
    page,
    "모두 승인 결과 모름 검사",
    LABELS.map((name, index) => labelAction(index, name)),
  );
  const calls: string[] = [];
  await page.route("**/api/connector-actions/*/approve", (route: Route) => {
    const actionId = new URL(route.request().url()).pathname.split("/").at(-2)!;
    calls.push(actionId);
    state.actions = state.actions.map((item) => (item.actionId === actionId ? { ...item, status: "UNKNOWN" } : item));
    return route.fulfill({ json: state.actions.find((item) => item.actionId === actionId) });
  });
  const group = page.getByTestId("approval-group");
  await group.getByTestId("approval-group-toggle").click();

  await group.getByTestId("approval-approve-all").click();

  await expect(page.getByTestId("approval-bulk-error")).toHaveText(
    "한 건이 끝나지 않아 나머지는 승인하지 않았어요. 남은 건을 확인해 주세요.",
  );
  expect(calls).toHaveLength(1);
  await expect(page.getByTestId("approval-card").filter({ hasText: "실행했는지 알 수 없어요" })).toHaveCount(1);
  await expect(group).toHaveAttribute("data-count", "3");
});

test("모두 승인 중 묶음이 한 장이 되어도 남은 줄의 단추는 막혀 있다", async ({ page }) => {
  const { state } = await openWithMany(page, "모두 승인 한 장 검사", [labelAction(0, "업무"), labelAction(1, "가족")]);
  const ids = state.actions.map((item) => item.actionId);
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/connector-actions/*/approve", async (route: Route) => {
    const actionId = new URL(route.request().url()).pathname.split("/").at(-2)!;
    if (actionId === ids[1]) await held;
    state.actions = state.actions.map((item) => (item.actionId === actionId ? { ...item, status: "SUCCEEDED" } : item));
    return route.fulfill({ json: state.actions.find((item) => item.actionId === actionId) });
  });
  await page.getByTestId("approval-group-toggle").click();

  await page.getByTestId("approval-approve-all").click();

  await expect(page.getByTestId("approval-group")).toHaveCount(0);
  const card = page.getByTestId("approval-card");
  await expect(card).toHaveCount(1);
  await expect(card.getByTestId("approval-approve")).toBeDisabled();
  await expect(card.getByTestId("approval-reject")).toBeDisabled();
  await expect(card.getByTestId("approval-grant")).toBeDisabled();
  release();
  await expect(page.getByTestId("approval-list")).toHaveCount(0);
});

test("묶음의 한 건을 보내는 동안에는 모두 승인을 누를 수 없다", async ({ page }) => {
  const { state } = await openWithMany(page, "한 건 보내는 중 검사", LABELS.map((name, index) => labelAction(index, name)));
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/connector-actions/*/approve", async (route: Route) => {
    const actionId = new URL(route.request().url()).pathname.split("/").at(-2)!;
    await held;
    state.actions = state.actions.map((item) => (item.actionId === actionId ? { ...item, status: "SUCCEEDED" } : item));
    return route.fulfill({ json: state.actions.find((item) => item.actionId === actionId) });
  });
  const group = page.getByTestId("approval-group");
  await group.getByTestId("approval-group-toggle").click();

  await group.getByTestId("approval-card").first().getByTestId("approval-approve").click();
  await expect(group.getByTestId("approval-approve-all")).toBeDisabled();
  release();
  await expect(group).toHaveAttribute("data-count", "3");
  await expect(group.getByTestId("approval-approve-all")).toBeEnabled();
});

test("두 건 묶음에서 한 건을 처리하면 남은 건은 한 장의 카드가 된다", async ({ page }) => {
  const { state } = await openWithMany(page, "두 건 묶음 검사", [labelAction(0, "업무"), labelAction(1, "가족")]);
  await routeDecisions(page, state);
  await page.getByTestId("approval-group-toggle").click();

  await page.getByTestId("approval-card").first().getByTestId("approval-reject").click();

  await expect(page.getByTestId("approval-group")).toHaveCount(0);
  await expect(page.getByTestId("approval-card")).toContainText("라벨 만들기");
  await expect(page.getByTestId("approval-card").getByTestId("approval-args").locator("dd").first()).toHaveText("가족");
});

test("같은 도구라도 실행 중인 줄과 도구 이름이 없는 줄은 묶지 않는다", async ({ page }) => {
  await openWithMany(page, "묶지 않는 줄 검사", [
    labelAction(0, "업무"),
    labelAction(1, "가족"),
    labelAction(2, "영수증", { status: "EXECUTING" }),
    labelAction(3, "뉴스레터", { toolName: null }),
    labelAction(4, "학교", { toolName: null }),
  ]);

  await expect(page.getByTestId("approval-group")).toHaveCount(1);
  await expect(page.getByTestId("approval-group")).toHaveAttribute("data-count", "2");
  // 실행 중인 줄 하나와 이름 없는 줄 둘이 한 장씩이다.
  await expect(page.getByTestId("approval-list").locator(":scope > [data-testid=approval-card]")).toHaveCount(3);
});
