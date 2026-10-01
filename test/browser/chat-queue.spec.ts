import { CONVERSATION_URL, conversationIdOf, expect, test, type FakeHermesControl } from "./fixtures.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

const PENDING_ROUTE = "**/api/chat/conversations/*/pending";

function composer(page: Page) {
  return page.getByTestId("composer-shell");
}

/** 입력창의 보내기다. 대기 줄의 「보내기」 와 이름이 겹쳐 입력창 안으로 한정해 고른다. */
function sendButton(page: Page) {
  return composer(page).getByRole("button", { name: "보내기" });
}

function stopButton(page: Page) {
  return composer(page).getByRole("button", { name: "중지" });
}

function messageBox(page: Page) {
  return page.getByRole("textbox", { name: "메시지" });
}

/**
 * 답을 붙잡아 둔 채 첫 글을 보낸다. 붙잡힌 turn 의 대화 식별자를 돌려준다.
 *
 * <p>화면이 `started` 를 받아 대화 식별자와 실행 번호를 안 뒤에 돌려준다. 그전에는 대기 메시지를 받을 대화가 없다.
 */
async function beginHeldTurn(page: Page, hermes: FakeHermesControl, text: string): Promise<string> {
  await hermes.holdNextRun();
  await page.goto("/");
  await messageBox(page).fill(text);
  await sendButton(page).click();
  await hermes.waitForHeldRun();
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(stopButton(page)).toBeEnabled();
  return conversationIdOf(page.url());
}

/** 입력창에 글을 쓰고 보내기를 눌러 대기시킨다. 대기 줄이 그 수가 될 때까지 기다린다. */
async function queueByButton(page: Page, text: string, expectedCount: number) {
  await messageBox(page).fill(text);
  await sendButton(page).click();
  await expect(page.getByTestId("pending-item")).toHaveCount(expectedCount);
}

/** 붙잡은 run 을 풀고 답이 그 수만큼 쌓여 도는 turn 이 없을 때까지 기다린다. 다음 검사가 붙잡힌 run 에 걸리지 않게 한다. */
async function releaseAndSettle(page: Page, hermes: FakeHermesControl, expectedAnswers: number) {
  await hermes.releaseHeldRun();
  await expect(page.getByTestId("assistant-message")).toHaveCount(expectedAnswers, { timeout: 30_000 });
  await expect(stopButton(page)).toHaveCount(0, { timeout: 30_000 });
}

/** 대기 메시지를 더하는 요청만 그 오류로 채운다. 대기 줄 읽기는 그대로 둔다. */
async function rejectEnqueue(page: Page, code: string) {
  await page.route(PENDING_ROUTE, (route) => {
    if (route.request().method() !== "POST") return route.continue();
    return route.fulfill({
      status: 409,
      contentType: "application/json",
      body: JSON.stringify({ code, message: "대기 메시지를 받지 못한 응답" }),
    });
  });
}

test("답이 오는 동안 보내면 대기 줄에 쌓이고 답이 끝나면 합쳐 간다", async ({ page, hermes }) => {
  await beginHeldTurn(page, hermes, "대기 줄 첫 질문");
  await expect(messageBox(page)).toBeEnabled();
  await expect(sendButton(page)).toBeVisible();
  await expect(stopButton(page)).toBeVisible();

  await queueByButton(page, "대기 줄 둘째 글", 1);
  await expect(messageBox(page)).toHaveValue("");
  await queueByButton(page, "대기 줄 셋째 글", 2);
  await expect(messageBox(page)).toHaveValue("");
  await expect(page.getByTestId("pending-queue")).toContainText("답이 끝나면 보내요.");
  await expect(page.getByTestId("user-message")).toHaveCount(1);

  await releaseAndSettle(page, hermes, 2);
  await expect(page.getByTestId("pending-queue")).toHaveCount(0);
  const expectMergedMessage = async () => {
    const questions = page.getByTestId("user-message");
    await expect(questions).toHaveCount(2);
    await expect(questions.nth(1)).toContainText("대기 줄 둘째 글");
    await expect(questions.nth(1)).toContainText("대기 줄 셋째 글");
    await expect(page.getByTestId("assistant-message")).toHaveCount(2);
  };
  await expectMergedMessage();
  await page.reload();
  await expectMergedMessage();
  await expect(page.getByTestId("pending-queue")).toHaveCount(0);
});

test("대기 메시지를 취소하면 글이 입력창으로 돌아온다", async ({ page, hermes }) => {
  await beginHeldTurn(page, hermes, "대기 되돌리기 첫 질문");
  await queueByButton(page, "취소할 대기 글", 1);
  await page.getByRole("button", { name: "대기 메시지 취소" }).click();
  await expect(page.getByTestId("pending-item")).toHaveCount(0);
  await expect(messageBox(page)).toHaveValue("취소할 대기 글");

  await releaseAndSettle(page, hermes, 1);
  await expect(page.getByTestId("user-message")).toHaveCount(1);
  await expect(page.getByTestId("user-message")).toContainText("대기 되돌리기 첫 질문");
});

test("중지하면 대기 줄이 멈추고 보내기로 이어 보낸다", async ({ page, hermes }) => {
  await beginHeldTurn(page, hermes, "대기 멈춤 첫 질문");
  await queueByButton(page, "중지 뒤에 보낼 글", 1);
  await stopButton(page).click();
  await expect(page.getByTestId("stopped-mark")).toBeVisible({ timeout: 30_000 });

  const release = page.getByTestId("pending-release");
  await expect(release).toBeVisible();
  await expect(release).toHaveAccessibleName("대기 메시지 보내기");
  await expect(page.getByTestId("pending-queue")).toContainText("중지해서 보내지 않았어요.");
  // 멈춰 둔 글은 사용자가 풀 때까지 보내지 않는다. 사용자 메시지도 도는 turn 도 새로 생기지 않는다.
  await expect(page.getByTestId("pending-item")).toHaveCount(1);
  await expect(page.getByTestId("user-message")).toHaveCount(1);
  await expect(stopButton(page)).toHaveCount(0);

  await release.click();
  const questions = page.getByTestId("user-message");
  await expect(questions).toHaveCount(2, { timeout: 30_000 });
  await expect(questions.nth(1)).toContainText("중지 뒤에 보낼 글");
  await expect(page.getByTestId("assistant-message")).toHaveCount(2, { timeout: 30_000 });
  await expect(stopButton(page)).toHaveCount(0, { timeout: 30_000 });
  await expect(page.getByTestId("pending-queue")).toHaveCount(0);
});

test("Enter 로도 대기시킨다", async ({ page, hermes }) => {
  await beginHeldTurn(page, hermes, "대기 Enter 첫 질문");
  await messageBox(page).fill("Enter 로 보낸 대기 글");
  await messageBox(page).press("Enter");
  await expect(page.getByTestId("pending-item")).toHaveCount(1);
  await expect(page.getByTestId("pending-item")).toContainText("Enter 로 보낸 대기 글");
  await expect(messageBox(page)).toHaveValue("");

  await releaseAndSettle(page, hermes, 2);
});

test("대기 줄이 가득 차면 글을 되돌린다", async ({ page, hermes }) => {
  await beginHeldTurn(page, hermes, "대기 상한 첫 질문");
  await rejectEnqueue(page, "PENDING_QUEUE_FULL");
  await messageBox(page).fill("상한을 넘은 대기 글");
  await sendButton(page).click();
  await expect(page.getByText("대기 중인 메시지가 가득 찼어요")).toBeVisible();
  await expect(messageBox(page)).toHaveValue("상한을 넘은 대기 글");
  await expect(page.getByTestId("pending-item")).toHaveCount(0);

  await releaseAndSettle(page, hermes, 1);
});

test("흐름이 붙은 에이전트라 받지 않으면 까닭을 알린다", async ({ page, hermes }) => {
  await beginHeldTurn(page, hermes, "대기 미수신 첫 질문");
  await rejectEnqueue(page, "CONVERSATION_BUSY");
  await messageBox(page).fill("받지 않는 대기 글");
  await sendButton(page).click();
  await expect(page.getByText("이 대화는 답이 끝난 뒤 보낼 수 있어요")).toBeVisible();
  await expect(messageBox(page)).toHaveValue("받지 않는 대기 글");
  await expect(page.getByTestId("pending-item")).toHaveCount(0);

  await releaseAndSettle(page, hermes, 1);
});

test("다른 창에서 쌓은 대기 메시지가 보인다", async ({ page, hermes }) => {
  const conversationId = await beginHeldTurn(page, hermes, "대기 다른 창 첫 질문");
  const added = await page.request.post(`/api/chat/conversations/${conversationId}/pending`, {
    data: { text: "다른 창에서 쌓은 글" },
  });
  expect(added.status(), "대기 메시지를 더한 응답의 상태").toBe(201);
  // 이 창이 보낸 turn 이 아직 돈다. 그동안에도 대기 줄 사건은 보류하지 않고 곧바로 반영한다.
  await expect(page.getByTestId("pending-item")).toHaveCount(1);
  await expect(page.getByTestId("pending-item")).toContainText("다른 창에서 쌓은 글");
  await expect(stopButton(page)).toBeVisible();

  await releaseAndSettle(page, hermes, 2);
});

test("대기 메시지를 보내지 못하면 입력창 위에 까닭을 알린다", async ({ page }) => {
  const created = await page.request.post("/api/chat", {
    data: { text: `대기 실패 알림 검사 ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok(), "대화를 만든 응답").toBeTruthy();
  const { conversationId } = (await created.json()) as { conversationId: string };

  // 쌓인 글이 하나 있는 대기 줄을 채운다. 실패 사건 뒤에 다시 읽으면 멈춘 줄로 돌려준다.
  let failed = false;
  await page.route(`**/api/chat/conversations/${conversationId}/pending`, (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({
      held: failed,
      items: [{ id: 90_000_101, text: "보내지 못한 대기 글", createdAt: new Date().toISOString() }],
    }),
  }));
  // turn 을 열지 못하면 `started` 없이 `error` 와 `pending` 이 온다. 대기 줄이 화면에 보인 뒤에 보낸다.
  let eventRequests = 0;
  await page.route(`**/api/chat/conversations/${conversationId}/events`, async (route) => {
    eventRequests += 1;
    if (eventRequests > 1) {
      // 두 번째 연결부터는 붙잡아 둔다. 같은 사건을 두 번 받지 않게 한다.
      await new Promise(() => {});
      return;
    }
    await expect(page.getByTestId("pending-item")).toHaveCount(1);
    failed = true;
    await route.fulfill({
      status: 200,
      headers: { "Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache" },
      body: [
        { type: "error", conversationId, code: "AGENT_DISABLED", message: "꺼진 에이전트라는 응답" },
        { type: "pending", conversationId },
      ].map((event) => `data: ${JSON.stringify(event)}\n\n`).join(""),
    });
  });

  await page.goto(`/chat/${conversationId}`);

  await expect(page.getByText("이 에이전트는 지금 사용할 수 없어요.")).toBeVisible();
  await expect(page.getByTestId("pending-release")).toBeVisible();
  await expect(page.getByTestId("pending-item")).toContainText("보내지 못한 대기 글");
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await page.unrouteAll({ behavior: "ignoreErrors" });
});
