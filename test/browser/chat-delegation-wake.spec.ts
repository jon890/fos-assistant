import { expect, test } from "./fixtures.ts";
import type { Page, Route } from "../../web/node_modules/@playwright/test/index.js";

/** 화면이 대화 단위 SSE 를 다시 열기까지 기다리는 시간이다. 화면의 값과 같아야 한다. */
const EVENTS_RECONNECT_MS = 5_000;

/** 시험이 채워 넣는 메시지 번호다. 실제로 저장된 메시지 번호와 겹치지 않게 크게 둔다. */
const SYSTEM_MESSAGE_ID = 90_000_001;
const AUTO_ANSWER_ID = 90_000_002;
const AUTO_EXECUTION_ID = 90_000_003;

const NOTICE = "위임한 작업의 결과가 도착했어요.";
const AUTO_ANSWER = "위임 결과를 정리했어요.";

type ChatEventBody = Record<string, unknown> & { type: string };

/** 새 대화를 만들어 질문 하나와 답 하나를 저장하고 그 공개 식별자를 돌려준다. */
async function createConversation(page: Page, label: string): Promise<string> {
  const created = await page.request.post("/api/chat", {
    data: { text: `${label} ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  return ((await created.json()) as { conversationId: string }).conversationId;
}

function eventStream(events: ChatEventBody[]): string {
  return events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join("");
}

/**
 * 대화 단위 SSE 의 첫 요청에 `first` 사건을 한 번에 채워 주고, 두 번째 요청부터는 사건 없이 붙잡아 둔다.
 *
 * <p>한 번에 채운 응답은 곧바로 닫히므로 화면이 다시 연결한다. 붙잡아 두어야 같은 사건을 두 번 받지 않는다.
 * 요청이 온 시각을 차례로 돌려준다.
 */
async function routeEvents(
  page: Page,
  conversationId: string,
  first: ChatEventBody[],
  beforeFirst?: () => Promise<void>,
) {
  const requestedAt: number[] = [];
  await page.route(`**/api/chat/conversations/${conversationId}/events`, async (route: Route) => {
    requestedAt.push(Date.now());
    if (requestedAt.length === 1) {
      await beforeFirst?.();
      await route.fulfill({
        status: 200,
        headers: { "Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache" },
        body: eventStream(first),
      });
      return;
    }
    // 연결을 붙잡아 둔다. 시험이 끝나며 경로를 풀 때 함께 버려진다.
    await new Promise(() => {});
  });
  return requestedAt;
}

/** 저장된 메시지 끝에 알림 줄과 그 뒤의 답을 덧붙인다. `ready` 가 참일 때만 덧붙인다. */
async function routeMessagesWithWake(
  page: Page,
  conversationId: string,
  notice: string,
  ready: () => boolean,
  onServed?: () => void,
) {
  await page.route(`**/api/chat/conversations/${conversationId}/messages`, async (route: Route) => {
    const response = await route.fetch();
    const turns = (await response.json()) as Record<string, unknown>[];
    if (ready()) {
      const createdAt = new Date().toISOString();
      turns.push(
        { id: SYSTEM_MESSAGE_ID, role: "SYSTEM", content: notice, senderName: null, executionId: null,
          hasChildren: false, switchedTo: null, replacesMessageId: null, createdAt, attachments: [], artifacts: [],
          activity: null, status: null },
        { id: AUTO_ANSWER_ID, role: "ASSISTANT", content: AUTO_ANSWER, senderName: null,
          executionId: AUTO_EXECUTION_ID, hasChildren: false, switchedTo: null, replacesMessageId: null, createdAt,
          attachments: [], artifacts: [], activity: null, status: "SUCCEEDED" },
      );
    }
    await route.fulfill({ response, json: turns });
    onServed?.();
  });
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("대화를 연 채로 자동 turn 이 오면 알림 줄과 답이 보인다", async ({ page }) => {
  const conversationId = await createConversation(page, "위임 깨우기 실시간 검사");
  // 처음 읽는 이력에는 알림 줄과 답이 없다. 그 이력을 준 뒤에 사건을 보내야 화면이 사건으로 그린 것을 본다.
  let delivered = false;
  let firstMessagesServed!: () => void;
  const messagesServed = new Promise<void>((resolve) => {
    firstMessagesServed = resolve;
  });
  await routeMessagesWithWake(page, conversationId, NOTICE, () => delivered, () => firstMessagesServed());
  await routeEvents(page, conversationId, [
    { type: "system", conversationId, messageId: SYSTEM_MESSAGE_ID, text: NOTICE },
    { type: "started", conversationId, executionId: AUTO_EXECUTION_ID },
    { type: "delta", text: AUTO_ANSWER },
    { type: "done", conversationId, messageId: AUTO_ANSWER_ID, executionId: AUTO_EXECUTION_ID },
  ], async () => {
    await messagesServed;
    delivered = true;
  });

  await page.goto(`/chat/${conversationId}`);

  await expect(page.getByTestId("system-message")).toHaveText(NOTICE);
  const answer = page.getByTestId("assistant-message").last();
  await expect(answer).toContainText(AUTO_ANSWER);
  // 끝난 뒤 저장된 이력으로 맞춰져 알림 줄과 답이 한 번씩만 보인다.
  await expect(answer.locator("time")).toHaveCount(1);
  await expect(page.getByTestId("system-message")).toHaveCount(1);
  await expect(page.getByTestId("assistant-message")).toHaveCount(2);
  await expect(page.getByTestId("composer-shell").getByRole("button", { name: "보내기" })).toBeVisible();
});

test("저장된 알림 줄은 버튼 없는 한 줄로 보이고 그 뒤 답은 다시 만들 수 없으며 가로로 넘치지 않는다", async ({ page }) => {
  const conversationId = await createConversation(page, "알림 줄 표시 검사");
  // 띄어 쓸 자리가 없는 긴 글도 줄바꿈해야 한다.
  const longNotice = `${NOTICE} ${"결과".repeat(20)}${"a".repeat(240)}`;
  await routeMessagesWithWake(page, conversationId, longNotice, () => true);
  await routeEvents(page, conversationId, []);

  await page.goto(`/chat/${conversationId}`);

  const notice = page.getByTestId("system-message");
  await expect(notice).toHaveText(longNotice);
  await expect(notice.getByRole("button")).toHaveCount(0);
  const answers = page.getByTestId("assistant-message");
  await expect(answers).toHaveCount(2);
  await expect(answers.last()).toContainText(AUTO_ANSWER);
  // 저장된 이력을 그린 뒤라 시각이 붙는다. 그때까지 기다려야 단추가 그려진 뒤를 본다.
  await expect(answers.last().locator("time")).toHaveCount(1);
  await expect(answers.last().getByRole("button", { name: "답 다시 만들기" })).toHaveCount(0);
  // 동작 줄은 그려졌고 다시 생성만 빠졌는지 보려고 복사 단추가 있는지 함께 본다.
  await expect(answers.last().getByRole("button", { name: /복사/ })).toHaveCount(1);

  const width = page.viewportSize()!.width;
  const box = await notice.boundingBox();
  expect(box, "알림 줄이 그려지지 않았다").not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width, `알림 줄이 화면 폭 ${width} 을 넘었다`).toBeLessThanOrEqual(width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
    .toBeLessThanOrEqual(0);
  expect(await page.getByTestId("message-scroll").evaluate((element) => element.scrollWidth - element.clientWidth))
    .toBeLessThanOrEqual(0);
});

test("대화 단위 SSE 가 닫히면 5초 뒤 다시 연결하고 같은 사건을 두 번 그리지 않는다", async ({ page }) => {
  const conversationId = await createConversation(page, "대화 SSE 다시 연결 검사");
  const requestedAt = await routeEvents(page, conversationId, [
    { type: "system", conversationId, messageId: SYSTEM_MESSAGE_ID, text: NOTICE },
  ]);

  await page.goto(`/chat/${conversationId}`);

  await expect(page.getByTestId("system-message")).toHaveText(NOTICE);
  await expect.poll(() => requestedAt.length, { timeout: EVENTS_RECONNECT_MS + 10_000 }).toBeGreaterThanOrEqual(2);
  const waited = requestedAt[1] - requestedAt[0];
  expect(waited, `다시 연결하기까지 ${waited}ms 걸렸다`).toBeGreaterThanOrEqual(EVENTS_RECONNECT_MS - 500);
  await expect(page.getByTestId("system-message")).toHaveCount(1);
});

test("보낸 turn 이 도는 중에 자동 turn 사건이 와도 보낸 답과 알림 줄과 자동 답이 모두 남고 자동 turn 이 끝날 때까지 보내기를 막는다", async ({ page }) => {
  const conversationId = await createConversation(page, "보낸 turn 과 자동 turn 겹침 검사");
  const question = `겹침 검사 질문 ${Date.now()}`;
  let wakeSaved = false;
  await routeMessagesWithWake(page, conversationId, NOTICE, () => wakeSaved);

  let markUserSent!: () => void;
  const userSent = new Promise<void>((resolve) => {
    markUserSent = resolve;
  });
  let markEventsDelivered!: () => void;
  const eventsDelivered = new Promise<void>((resolve) => {
    markEventsDelivered = resolve;
  });
  let releaseAutoDone!: () => void;
  const autoDoneReleased = new Promise<void>((resolve) => {
    releaseAutoDone = resolve;
  });
  let eventRequests = 0;
  await page.route(`**/api/chat/conversations/${conversationId}/events`, async (route: Route) => {
    eventRequests += 1;
    const headers = { "Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache" };
    if (eventRequests === 1) {
      // 보낸 turn 의 스트림이 끝나기 전에 자동 turn 의 앞부분을 보낸다. 응답이 닫혀 화면이 다시 연결한다.
      await userSent;
      await route.fulfill({ status: 200, headers, body: eventStream([
        { type: "system", conversationId, messageId: SYSTEM_MESSAGE_ID, text: NOTICE },
        { type: "started", conversationId, executionId: AUTO_EXECUTION_ID },
        { type: "delta", text: AUTO_ANSWER },
      ]) });
      markEventsDelivered();
      return;
    }
    if (eventRequests === 2) {
      // 다시 연결한 뒤 시험이 막힌 입력창을 본 다음에 자동 turn 을 끝낸다.
      await autoDoneReleased;
      wakeSaved = true;
      await route.fulfill({ status: 200, headers, body: eventStream([
        { type: "done", conversationId, messageId: AUTO_ANSWER_ID, executionId: AUTO_EXECUTION_ID },
      ]) });
      return;
    }
    await new Promise(() => {});
  });
  await page.route("**/api/chat/stream", async (route: Route) => {
    markUserSent();
    await eventsDelivered;
    // 화면이 앞의 사건을 읽을 틈을 둔 뒤에 보낸 turn 의 스트림을 돌려준다.
    await new Promise((resolve) => setTimeout(resolve, 500));
    const response = await route.fetch();
    await route.fulfill({ response });
  });

  await page.goto(`/chat/${conversationId}`);
  const answers = page.getByTestId("assistant-message");
  await expect(answers).toHaveCount(1);
  await page.getByRole("textbox", { name: "메시지" }).fill(question);
  await page.getByTestId("composer-shell").getByRole("button", { name: "보내기" }).click();

  // 보낸 turn 의 답이 저장된 이력으로 바뀐 뒤에도 알림 줄과 흘러온 자동 답이 남아 있다.
  await expect(page.getByTestId("user-message").last()).toContainText(question);
  await expect(answers.nth(1).locator("time")).toHaveCount(1);
  await expect(page.getByTestId("system-message")).toHaveText(NOTICE);
  await expect(answers).toHaveCount(3);
  await expect(answers.last()).toContainText(AUTO_ANSWER);
  const composer = page.getByTestId("composer-shell");
  await expect(composer.getByRole("button", { name: "중지" })).toBeVisible();
  await expect(composer.getByRole("button", { name: "보내기" })).toHaveCount(0);

  // 다시 연결해 자동 turn 의 끝을 받을 때까지 기다린다.
  await expect.poll(() => eventRequests, { timeout: EVENTS_RECONNECT_MS + 10_000 }).toBeGreaterThanOrEqual(2);
  await expect(composer.getByRole("button", { name: "보내기" })).toHaveCount(0);
  releaseAutoDone();

  await expect(composer.getByRole("button", { name: "보내기" })).toBeVisible();
  await expect(answers.last().locator("time")).toHaveCount(1);
  await expect(answers).toHaveCount(3);
  await expect(answers.last()).toContainText(AUTO_ANSWER);
  await expect(page.getByTestId("system-message")).toHaveCount(1);
});

test("대화 단위 SSE 가 다시 연결되면 끊긴 사이 끝난 자동 turn 을 이력에서 읽어 보인다", async ({ page }) => {
  const conversationId = await createConversation(page, "다시 연결 이력 검사");
  // 처음 이력을 준 뒤 연결이 끊긴 사이에 자동 turn 이 열리고 끝난 것으로 둔다.
  let wakeSaved = false;
  let firstMessagesServed!: () => void;
  const messagesServed = new Promise<void>((resolve) => {
    firstMessagesServed = resolve;
  });
  await routeMessagesWithWake(page, conversationId, NOTICE, () => wakeSaved, () => firstMessagesServed());
  // 화면은 다시 연 연결의 응답을 받은 뒤 이력을 읽는다. 그래서 두 번째 요청까지는 사건 없이 응답하고 그 뒤로는 붙잡아 둔다.
  let eventRequests = 0;
  await page.route(`**/api/chat/conversations/${conversationId}/events`, async (route: Route) => {
    eventRequests += 1;
    if (eventRequests > 2) {
      await new Promise(() => {});
      return;
    }
    if (eventRequests === 1) {
      await messagesServed;
      wakeSaved = true;
    }
    await route.fulfill({
      status: 200,
      headers: { "Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache" },
      body: "",
    });
  });

  await page.goto(`/chat/${conversationId}`);

  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect(page.getByTestId("system-message")).toHaveCount(0);
  await expect(page.getByTestId("system-message")).toHaveText(NOTICE, { timeout: EVENTS_RECONNECT_MS + 10_000 });
  expect(eventRequests, "다시 연결하기 전에 이력을 다시 읽었다").toBeGreaterThanOrEqual(2);
  const answers = page.getByTestId("assistant-message");
  await expect(answers).toHaveCount(2);
  await expect(answers.last()).toContainText(AUTO_ANSWER);
});

test("자동 turn 을 받던 중 대화 단위 SSE 가 끊겨 끝 사건을 놓쳐도 그 turn 이 돌지 않으면 저장된 답을 보이고 입력창을 푼다", async ({ page }) => {
  const conversationId = await createConversation(page, "자동 turn 끝 놓침 검사");
  let wakeSaved = false;
  await routeMessagesWithWake(page, conversationId, NOTICE, () => wakeSaved);
  // 처음 읽은 이력이 그려진 뒤에 사건을 보낸다. 이력을 합칠 때 번호 없는 임시 답 줄은 버려지기 때문이다.
  let releaseEvents!: () => void;
  const eventsReleased = new Promise<void>((resolve) => {
    releaseEvents = resolve;
  });
  // 끊긴 사이 자동 turn 이 끝났다. 다시 연결해도 끝 사건은 오지 않고, 도는 turn 도 없다.
  await page.route(`**/api/chat/conversations/${conversationId}/running`, (route: Route) =>
    route.fulfill({ json: { running: false, executionId: null, startedAt: null } }));
  let releaseReconnect!: () => void;
  const reconnectReleased = new Promise<void>((resolve) => {
    releaseReconnect = resolve;
  });
  let eventRequests = 0;
  await page.route(`**/api/chat/conversations/${conversationId}/events`, async (route: Route) => {
    eventRequests += 1;
    const headers = { "Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache" };
    if (eventRequests === 1) {
      await eventsReleased;
      await route.fulfill({ status: 200, headers, body: eventStream([
        { type: "system", conversationId, messageId: SYSTEM_MESSAGE_ID, text: NOTICE },
        { type: "started", conversationId, executionId: AUTO_EXECUTION_ID },
        { type: "delta", text: AUTO_ANSWER },
      ]) });
      return;
    }
    if (eventRequests === 2) {
      // 시험이 막힌 입력창을 본 다음에 다시 연결을 받는다.
      await reconnectReleased;
      wakeSaved = true;
      await route.fulfill({ status: 200, headers, body: "" });
      return;
    }
    await new Promise(() => {});
  });

  await page.goto(`/chat/${conversationId}`);

  const answers = page.getByTestId("assistant-message");
  const composer = page.getByTestId("composer-shell");
  await expect(answers.first().locator("time")).toHaveCount(1);
  releaseEvents();
  await expect(page.getByTestId("system-message")).toHaveText(NOTICE);
  await expect(answers.last()).toContainText(AUTO_ANSWER);
  await expect(composer.getByRole("button", { name: "중지" })).toBeVisible();
  await expect(composer.getByRole("button", { name: "보내기" })).toHaveCount(0);
  releaseReconnect();

  await expect(composer.getByRole("button", { name: "보내기" })).toBeVisible({ timeout: EVENTS_RECONNECT_MS + 10_000 });
  expect(eventRequests, "다시 연결하기 전에 입력창이 풀렸다").toBeGreaterThanOrEqual(2);
  await expect(answers).toHaveCount(2);
  await expect(answers.last().locator("time")).toHaveCount(1);
  await expect(answers.last()).toContainText(AUTO_ANSWER);
  await expect(page.getByTestId("system-message")).toHaveCount(1);
});
