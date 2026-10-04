import { expect, test } from "./fixtures.ts";
import type { Page, Route } from "../../web/node_modules/@playwright/test/index.js";

/** 시험이 채워 넣는 메시지 번호다. 실제로 저장된 메시지 번호와 겹치지 않게 크게 둔다. */
const EARLIER_NOTICE_ID = 91_000_001;
const LAST_NOTICE_ID = 91_000_002;
const RETRY_NOTICE_ID = 91_000_003;
const RETRY_ANSWER_ID = 91_000_004;
const RETRY_EXECUTION_ID = 91_000_005;
const DELIVERY_ID = 7;

const EARLIER_NOTICE = "첫 번째 도우미의 결과가 도착했어요.";
const LAST_NOTICE = "두 번째 도우미의 결과가 도착했어요.";
const RETRY_NOTICE = "두 번째 도우미의 결과를 다시 전달했어요.";
const RETRY_ANSWER = "도우미 두 곳의 결과를 하나로 정리했어요.";

type Delivery = { id: number; status: string } | null;
type Row = Record<string, unknown>;

const SSE_HEADERS = { "Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache" };

function eventStream(events: Row[]): string {
  return events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join("");
}

/** 새 대화를 만들어 질문 하나와 답 하나를 저장하고 그 공개 식별자를 돌려준다. */
async function createConversation(page: Page, label: string): Promise<string> {
  const created = await page.request.post("/api/chat", {
    data: { text: `${label} ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  return ((await created.json()) as { conversationId: string }).conversationId;
}

function notice(id: number, content: string, delivery: Delivery): Row {
  return {
    id, role: "SYSTEM", content, senderName: null, executionId: null, hasChildren: false, switchedTo: null,
    replacesMessageId: null, createdAt: new Date().toISOString(), attachments: [], artifacts: [], activity: null,
    status: null, delivery,
  };
}

function answer(id: number, content: string, executionId: number): Row {
  return {
    id, role: "ASSISTANT", content, senderName: null, executionId, hasChildren: false, switchedTo: null,
    replacesMessageId: null, createdAt: new Date().toISOString(), attachments: [], artifacts: [], activity: null,
    status: "SUCCEEDED", delivery: null,
  };
}

/** 저장된 메시지 끝에 `extra()` 가 돌려주는 줄을 덧붙여 이력으로 준다. */
async function routeMessages(page: Page, conversationId: string, extra: () => Row[], onServed?: () => void) {
  await page.route(`**/api/chat/conversations/${conversationId}/messages`, async (route: Route) => {
    const response = await route.fetch();
    const turns = (await response.json()) as Row[];
    await route.fulfill({ response, json: [...turns, ...extra()] });
    onServed?.();
  });
}

/** 대화 단위 SSE 는 사건 없이 붙잡아 둔다. 시험이 끝나며 경로를 풀 때 함께 버려진다. */
async function holdEvents(page: Page, conversationId: string) {
  await page.route(`**/api/chat/conversations/${conversationId}/events`, async () => {
    await new Promise(() => {});
  });
}

/** 마지막 알림 줄에 `delivery` 가 붙은 이력을 준다. 앞의 알림 줄에는 아무것도 붙지 않는다. */
async function openWithDelivery(page: Page, label: string, delivery: Delivery): Promise<string> {
  const conversationId = await createConversation(page, label);
  await routeMessages(page, conversationId, () => [
    notice(EARLIER_NOTICE_ID, EARLIER_NOTICE, null),
    notice(LAST_NOTICE_ID, LAST_NOTICE, delivery),
  ]);
  await holdEvents(page, conversationId);
  await page.goto(`/chat/${conversationId}`);
  await expect(page.getByTestId("system-message")).toHaveCount(2);
  return conversationId;
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("실패한 전달은 마지막 알림 줄 아래에만 상태와 결과 다시 전달 단추를 보인다", async ({ page }) => {
  await openWithDelivery(page, "전달 실패 표시 검사", { id: DELIVERY_ID, status: "FAILED" });

  const notices = page.getByTestId("system-message");
  await expect(notices.nth(1)).toContainText(LAST_NOTICE);
  await expect(notices.nth(1).getByTestId("delivery-status")).toHaveText("결과를 정리하지 못했어요");
  await expect(notices.nth(1).getByRole("button", { name: "결과 다시 전달" })).toBeEnabled();
  await expect(notices.first()).toContainText(EARLIER_NOTICE);
  await expect(notices.first().getByTestId("delivery-status")).toHaveCount(0);
  await expect(notices.first().getByRole("button")).toHaveCount(0);
  await expect(page.getByTestId("delivery-retry")).toHaveCount(1);
});

test("중지한 전달은 같은 이름의 단추를 보인다", async ({ page }) => {
  await openWithDelivery(page, "전달 중지 표시 검사", { id: DELIVERY_ID, status: "STOPPED" });

  const last = page.getByTestId("system-message").nth(1);
  await expect(last.getByTestId("delivery-status")).toHaveText("결과 정리를 중지했어요");
  await expect(last.getByRole("button", { name: "결과 다시 전달" })).toBeEnabled();
});

for (const [name, delivery] of [
  ["전달 상태가 없는", null],
  ["전달이 끝난", { id: DELIVERY_ID, status: "DELIVERED" }],
  ["전달이 도는 중인", { id: DELIVERY_ID, status: "DELIVERING" }],
] as [string, Delivery][]) {
  test(`${name} 알림 줄에는 상태 글과 단추가 없다`, async ({ page }) => {
    await openWithDelivery(page, "전달 상태 숨김 검사", delivery);

    await expect(page.getByTestId("system-message").nth(1)).toContainText(LAST_NOTICE);
    await expect(page.getByTestId("delivery-status")).toHaveCount(0);
    await expect(page.getByTestId("delivery-retry")).toHaveCount(0);
    await expect(page.getByTestId("system-message").getByRole("button")).toHaveCount(0);
  });
}

test("누르면 단추를 막고 한 번만 보내며 다시 전달한 답을 그린 뒤 단추가 사라진다", async ({ page }) => {
  const conversationId = await createConversation(page, "전달 다시 전달 검사");
  let retried = false;
  await routeMessages(page, conversationId, () => retried
    ? [
      notice(EARLIER_NOTICE_ID, EARLIER_NOTICE, null),
      notice(LAST_NOTICE_ID, LAST_NOTICE, null),
      notice(RETRY_NOTICE_ID, RETRY_NOTICE, { id: DELIVERY_ID, status: "DELIVERED" }),
      answer(RETRY_ANSWER_ID, RETRY_ANSWER, RETRY_EXECUTION_ID),
    ]
    : [
      notice(EARLIER_NOTICE_ID, EARLIER_NOTICE, null),
      notice(LAST_NOTICE_ID, LAST_NOTICE, { id: DELIVERY_ID, status: "FAILED" }),
    ]);
  await holdEvents(page, conversationId);
  const retryRequests: string[] = [];
  let releaseRetry!: () => void;
  const retryReleased = new Promise<void>((resolve) => {
    releaseRetry = resolve;
  });
  await page.route(`**/api/chat/conversations/${conversationId}/deliveries/*/retry`, async (route: Route) => {
    retryRequests.push(`${route.request().method()} ${new URL(route.request().url()).pathname}`);
    // 시험이 막힌 단추를 본 다음에 응답한다.
    await retryReleased;
    retried = true;
    await route.fulfill({ status: 200, headers: SSE_HEADERS, body: eventStream([
      { type: "system", conversationId, messageId: RETRY_NOTICE_ID, text: RETRY_NOTICE },
      { type: "started", conversationId, executionId: RETRY_EXECUTION_ID },
      { type: "delta", text: RETRY_ANSWER },
      { type: "done", conversationId, messageId: RETRY_ANSWER_ID, executionId: RETRY_EXECUTION_ID },
    ]) });
  });
  await page.goto(`/chat/${conversationId}`);

  const button = page.getByRole("button", { name: "결과 다시 전달" });
  await button.click();
  await expect(button).toBeDisabled();
  // 막힌 단추를 다시 눌러도 요청이 늘지 않는다.
  await button.click({ force: true });
  releaseRetry();

  const answers = page.getByTestId("assistant-message");
  await expect(answers.last()).toContainText(RETRY_ANSWER);
  await expect(answers.last().locator("time")).toHaveCount(1);
  await expect(answers).toHaveCount(2);
  await expect(page.getByTestId("system-message")).toHaveCount(3);
  await expect(page.getByTestId("delivery-retry")).toHaveCount(0);
  await expect(page.getByTestId("delivery-status")).toHaveCount(0);
  expect(retryRequests).toEqual([`POST /api/chat/conversations/${conversationId}/deliveries/${DELIVERY_ID}/retry`]);
});

test("한도에 닿으면 안내를 보이고 단추를 다시 누를 수 있다", async ({ page }) => {
  const conversationId = await createConversation(page, "전달 한도 검사");
  await routeMessages(page, conversationId, () => [
    notice(EARLIER_NOTICE_ID, EARLIER_NOTICE, null),
    notice(LAST_NOTICE_ID, LAST_NOTICE, { id: DELIVERY_ID, status: "FAILED" }),
  ]);
  await holdEvents(page, conversationId);
  await page.route(`**/api/chat/conversations/${conversationId}/deliveries/*/retry`, (route: Route) => route.fulfill({
    status: 409,
    json: { code: "USER_BUSY", message: "too many running" },
  }));
  await page.goto(`/chat/${conversationId}`);

  const button = page.getByRole("button", { name: "결과 다시 전달" });
  await button.click();

  await expect(page.getByText("진행 중인 작업이 많아요. 진행 중인 작업이 끝난 뒤 다시 보내 주세요.")).toBeVisible();
  await expect(button).toBeEnabled();
  await expect(page.getByTestId("composer-shell").getByRole("button", { name: "중지" })).toHaveCount(0);
});

test("대화를 연 채 자동 turn 이 시작 전에 실패하면 새로 고치지 않아도 단추를 보인다", async ({ page }) => {
  const conversationId = await createConversation(page, "시작 전 실패 검사");
  // 처음 읽는 이력에는 알림 줄이 없다. 그 이력을 준 뒤 사건을 보내고, 실패한 뒤 읽는 이력에는 FAILED 가 붙는다.
  let failed = false;
  let firstMessagesServed!: () => void;
  const messagesServed = new Promise<void>((resolve) => {
    firstMessagesServed = resolve;
  });
  await routeMessages(page, conversationId, () => failed
    ? [notice(LAST_NOTICE_ID, LAST_NOTICE, { id: DELIVERY_ID, status: "FAILED" })]
    : [], () => firstMessagesServed());
  let eventRequests = 0;
  await page.route(`**/api/chat/conversations/${conversationId}/events`, async (route: Route) => {
    eventRequests += 1;
    if (eventRequests > 1) {
      await new Promise(() => {});
      return;
    }
    await messagesServed;
    failed = true;
    await route.fulfill({ status: 200, headers: SSE_HEADERS, body: eventStream([
      { type: "system", conversationId, messageId: LAST_NOTICE_ID, text: LAST_NOTICE },
      { type: "error", conversationId, code: "HERMES_UNAVAILABLE", message: "connection refused" },
    ]) });
  });

  await page.goto(`/chat/${conversationId}`);

  await expect(page.getByTestId("system-message")).toContainText(LAST_NOTICE);
  await expect(page.getByTestId("delivery-status")).toHaveText("결과를 정리하지 못했어요");
  await expect(page.getByRole("button", { name: "결과 다시 전달" })).toBeEnabled();
  await expect(page.getByTestId("system-message")).toHaveCount(1);
});

test("다른 turn 이 도는 동안 단추가 막히고 그 turn 이 끝나면 다시 눌린다", async ({ page }) => {
  const conversationId = await createConversation(page, "다른 turn 진행 중 검사");
  await routeMessages(page, conversationId, () => [
    notice(EARLIER_NOTICE_ID, EARLIER_NOTICE, null),
    notice(LAST_NOTICE_ID, LAST_NOTICE, { id: DELIVERY_ID, status: "FAILED" }),
  ]);
  // 자동 turn 의 앞부분을 먼저 보내고, 화면이 다시 연결하면 시험이 막힌 단추를 본 다음에 끝 사건을 보낸다.
  let releaseDone!: () => void;
  const doneReleased = new Promise<void>((resolve) => {
    releaseDone = resolve;
  });
  let eventRequests = 0;
  await page.route(`**/api/chat/conversations/${conversationId}/events`, async (route: Route) => {
    eventRequests += 1;
    if (eventRequests === 1) {
      await route.fulfill({ status: 200, headers: SSE_HEADERS, body: eventStream([
        { type: "started", conversationId, executionId: RETRY_EXECUTION_ID },
        { type: "delta", text: RETRY_ANSWER },
      ]) });
      return;
    }
    if (eventRequests === 2) {
      await doneReleased;
      await route.fulfill({ status: 200, headers: SSE_HEADERS, body: eventStream([
        { type: "done", conversationId, messageId: RETRY_ANSWER_ID, executionId: RETRY_EXECUTION_ID },
      ]) });
      return;
    }
    await new Promise(() => {});
  });
  const retryRequests: string[] = [];
  await page.route(`**/api/chat/conversations/${conversationId}/deliveries/*/retry`, async (route: Route) => {
    retryRequests.push(route.request().url());
    await route.fulfill({ status: 409, json: { code: "CONVERSATION_BUSY", message: "busy" } });
  });

  await page.goto(`/chat/${conversationId}`);

  const button = page.getByRole("button", { name: "결과 다시 전달" });
  const composer = page.getByTestId("composer-shell");
  await expect(composer.getByRole("button", { name: "중지" })).toBeVisible();
  await expect(button).toBeDisabled();
  // 막힌 단추를 눌러도 요청이 나가지 않는다.
  await button.click({ force: true });
  releaseDone();

  await expect(composer.getByRole("button", { name: "중지" })).toHaveCount(0, { timeout: 15_000 });
  await expect(button).toBeEnabled();
  expect(retryRequests).toEqual([]);
});
