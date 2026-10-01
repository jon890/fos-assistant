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
