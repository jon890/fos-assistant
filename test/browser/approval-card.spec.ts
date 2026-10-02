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

test("비밀처럼 보이는 키의 값은 가려서 보인다", async ({ page }) => {
  await openWith(
    page,
    "승인 카드 가림 검사",
    action({
      argsJson: JSON.stringify({ api_key: "raw-secret-value", name: "x" }),
    }),
  );

  const args = page.getByTestId("approval-args");
  await expect(args.locator("dd")).toHaveText(["가려진 값", "x"]);
  await expect(args).not.toContainText("raw-secret-value");
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
  // 길면 카드 안에서 세로로 스크롤한다.
  expect(
    await args.evaluate(
      (element) => element.scrollHeight > element.clientHeight,
    ),
  ).toBe(true);
});
