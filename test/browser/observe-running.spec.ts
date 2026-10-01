import {
  CONVERSATION_URL,
  conversationIdOf,
  expect,
  test,
  waitForStreamedContentAfterLoad,
  type FakeHermesControl,
} from "./fixtures.ts";
import type { BrowserContext, Page } from "../../web/node_modules/@playwright/test/index.js";

/** 보는 창이 도는 turn 을 다시 묻는 주기다. 화면의 값과 같아야 한다. */
const OBSERVE_INTERVAL_MS = 3_000;

/** 실패시킨 조회가 돌려주는 문구다. 화면 어디에도 나오지 않아야 한다. */
const FAILED_RUNNING_MESSAGE = "도는 turn 을 묻지 못한 응답";

/** 보낸 창에서 답을 붙잡아 두고 보낸다. 붙잡힌 turn 의 대화 식별자를 돌려준다. */
async function startHeldTurn(page: Page, hermes: FakeHermesControl, text: string): Promise<string> {
  await hermes.holdNextRun();
  await page.goto("/");
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
  await hermes.waitForHeldRun();
  await expect(page).toHaveURL(CONVERSATION_URL);
  return conversationIdOf(page.url());
}

/** 같은 사용자로 둘째 창을 열어 그 대화를 연다. */
async function openOtherWindow(context: BrowserContext, conversationId: string): Promise<Page> {
  const other = await context.newPage();
  waitForStreamedContentAfterLoad(other);
  await other.goto(`/chat/${conversationId}`);
  return other;
}

/** 그 창이 그 대화에 보낸 도는 turn 조회를 센다. */
function countRunningRequests(page: Page, conversationId: string): () => number {
  let count = 0;
  const path = `/api/chat/conversations/${conversationId}/running`;
  page.on("request", (request) => {
    if (new URL(request.url()).pathname === path) count += 1;
  });
  return () => count;
}

/** 붙잡은 run 을 풀고 보낸 창의 turn 이 끝날 때까지 기다린다. 다음 검사가 붙잡힌 run 에 걸리지 않게 한다. */
async function releaseAndSettle(page: Page, hermes: FakeHermesControl) {
  await hermes.releaseHeldRun();
  await expect(page.getByTestId("composer-shell").getByRole("button", { name: "보내기" }))
    .toBeVisible({ timeout: 30_000 });
}

function composer(page: Page) {
  return page.getByTestId("composer-shell");
}

/** 끊김 문구의 앞부분이다. 넘어간 창에는 나오지 않아야 한다. */
const INTERRUPTED_MESSAGE = "응답 연결이 끊겼어요";

/**
 * 그 창의 `/api/chat/stream` 응답을 시험이 끊을 수 있게 감싼다. `cutChatStream()` 을 부르면 화면은 읽던 스트림이
 * 깨지고, 받아 오던 연결은 취소된다.
 *
 * <p>`route.fetch()` 는 붙잡은 실행이 끝날 때까지 응답 전체를 기다리므로 흘러오는 중에 끊을 수 없다. 그래서
 * 화면의 fetch 를 감싸 흘러오는 본문을 그대로 넘기다가 그 자리에서 끊는다. 시험만 이 감싸기를 넣고 운영
 * 코드에는 아무 문도 두지 않는다.
 */
async function makeChatStreamCuttable(page: Page) {
  await page.addInitScript(() => {
    const original = window.fetch.bind(window);
    const cuts: Array<() => void> = [];
    (window as unknown as { cutChatStream(): void }).cutChatStream = () => {
      for (const cut of cuts.splice(0)) cut();
    };
    window.fetch = async (input, init) => {
      const response = await original(input, init);
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (new URL(url, location.href).pathname !== "/api/chat/stream" || !response.body) return response;
      const reader = response.body.getReader();
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          cuts.push(() => {
            controller.error(new TypeError("시험이 끊은 연결"));
            void reader.cancel().catch(() => {});
          });
        },
        async pull(controller) {
          try {
            const { value, done } = await reader.read();
            if (done) controller.close();
            else controller.enqueue(value);
          } catch (reason) {
            // 끊은 뒤 끝난 읽기다. 이미 깨진 스트림이면 error() 는 아무 일도 하지 않는다.
            controller.error(reason);
          }
        },
      });
      return new Response(body, { status: response.status, statusText: response.statusText, headers: response.headers });
    };
  });
}

async function cutChatStream(page: Page) {
  await page.evaluate(() => (window as unknown as { cutChatStream(): void }).cutChatStream());
}

/** 도는 turn 이 없다는 응답이다. */
const NOT_RUNNING = { running: false, executionId: null, startedAt: null };

/** 푼 run 이 끝나기를 기다리는 한계다. 붙잡힌 turn 을 기다리는 다른 검사와 같다. */
const FINISH_TIMEOUT_MS = 30_000;

/** 푼 run 이 끝났는지 되묻는 사이의 간격이다. */
const FINISH_CHECK_INTERVAL_MS = 200;

/** 그 대화에 도는 turn 이 있는지 Control Plane 에 실제로 묻는다. 화면에 건 route 를 거치지 않는다. */
async function isRunning(page: Page, conversationId: string): Promise<boolean> {
  const response = await page.request.get(`/api/chat/conversations/${conversationId}/running`);
  expect(response.ok(), `도는 turn 조회가 실패했다: ${response.status()}`).toBeTruthy();
  return ((await response.json()) as { running: boolean }).running;
}

test("다른 창에서 답하는 중이면 기다리는 표시와 중지를 보이고 끝나면 답을 읽는다", async ({ context, page, hermes }) => {
  const conversationId = await startHeldTurn(page, hermes, "다른 창 보기 검사");
  try {
    const other = await openOtherWindow(context, conversationId);
    await expect(other.getByTestId("observing-notice")).toBeVisible();
    // 붙잡힌 run 이 이미 사건을 남겼으면 작업 과정이, 아니면 기다리는 점이 보인다. 어느 쪽이든 기다리는 표시다.
    await expect(other.getByLabel("비서의 답을 기다리는 중")
      .or(other.locator('[data-testid="activity-block"][data-mode="live"]')).first()).toBeVisible();
    await expect(composer(other).getByRole("button", { name: "중지" })).toBeEnabled({ timeout: 10_000 });
    await expect(composer(other).getByRole("button", { name: "보내기" })).toHaveCount(0);

    await hermes.releaseHeldRun();
    await expect(other.getByTestId("assistant-message")).toHaveCount(1, { timeout: 30_000 });
    await expect(other.getByTestId("observing-notice")).toHaveCount(0);
    await expect(composer(other).getByRole("button", { name: "보내기" })).toBeVisible();
  } finally {
    await releaseAndSettle(page, hermes);
  }
});

test("다른 창에서 중지하면 두 창 모두 중지된 답을 본다", async ({ context, page, hermes }) => {
  const conversationId = await startHeldTurn(page, hermes, "다른 창 중지 검사");
  try {
    const other = await openOtherWindow(context, conversationId);
    await expect(other.getByTestId("observing-notice")).toBeVisible();
    const stop = composer(other).getByRole("button", { name: "중지" });
    await expect(stop).toBeEnabled({ timeout: 10_000 });
    await stop.click();
    await expect(other.getByTestId("stopped-mark")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId("stopped-mark")).toBeVisible({ timeout: 30_000 });
    await expect(other.getByTestId("observing-notice")).toHaveCount(0);
  } finally {
    await releaseAndSettle(page, hermes);
  }
});

test("도는 turn 조회가 이어 실패하면 오류 없이 기다리는 표시를 거둔다", async ({ context, page, hermes }) => {
  const conversationId = await startHeldTurn(page, hermes, "다른 창 이어 실패 검사");
  try {
    const other = await openOtherWindow(context, conversationId);
    await expect(other.getByTestId("observing-notice")).toBeVisible();
    await other.route(`**/api/chat/conversations/${conversationId}/running`, (route) => route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ code: "HERMES_UNAVAILABLE", message: FAILED_RUNNING_MESSAGE }),
    }));
    // 세 번 이어 실패해야 거두므로 주기 세 번보다 넉넉히 기다린다.
    await expect(other.getByTestId("observing-notice")).toHaveCount(0, { timeout: OBSERVE_INTERVAL_MS * 3 + 10_000 });
    await expect(composer(other).getByRole("button", { name: "보내기" })).toBeVisible();
    await expect(other.getByText("연결할 수 없어요. 잠시 뒤 다시 시도해 주세요.")).toHaveCount(0);
    await expect(other.getByText(FAILED_RUNNING_MESSAGE)).toHaveCount(0);
    await expect(other.getByTestId("turn-error")).toHaveCount(0);
  } finally {
    await releaseAndSettle(page, hermes);
  }
});

test("보는 동안 대화가 지워지면 대화를 찾을 수 없다는 화면으로 간다", async ({ context, page, hermes }) => {
  const conversationId = await startHeldTurn(page, hermes, "다른 창 대화 지움 검사");
  try {
    const other = await openOtherWindow(context, conversationId);
    await expect(other.getByTestId("observing-notice")).toBeVisible();
    await other.route(`**/api/chat/conversations/${conversationId}/running`, (route) => route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify({ code: "CONVERSATION_NOT_FOUND", message: "대화가 없다" }),
    }));
    // route 를 걸기 직전에 떠난 조회는 정상 응답을 받으므로 주기 두 번에 여유를 더해 기다린다.
    // 404 를 실패로만 세었다면 이력을 다시 읽어 이 화면이 아니라 대화가 보이므로, 화면으로 둘을 구분한다.
    await expect(other.getByTestId("conversation-not-found")).toBeVisible({ timeout: OBSERVE_INTERVAL_MS * 2 + 3_000 });
    await expect(other.getByTestId("observing-notice")).toHaveCount(0);
  } finally {
    await releaseAndSettle(page, hermes);
  }
});

test("도는 turn 이 없는 대화는 조회를 되풀이하지 않는다", async ({ page }, testInfo) => {
  const created = await page.request.post("/api/chat", {
    data: { text: `도는 turn 없는 대화 검사 ${testInfo.project.name} ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  const { conversationId } = (await created.json()) as { conversationId: string };
  const runningRequests = countRunningRequests(page, conversationId);

  await page.goto(`/chat/${conversationId}`);
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect.poll(runningRequests).toBeGreaterThan(0);
  await expect(page.getByTestId("observing-notice")).toHaveCount(0);
  const afterOpen = runningRequests();
  // 요청이 더 가지 않는 것을 보려면 기다릴 조건이 없다. 주기 한 번을 넘길 만큼 고정으로 기다린다.
  await page.waitForTimeout(OBSERVE_INTERVAL_MS + 1_500);
  expect(runningRequests(), "도는 turn 이 없는데 조회를 되풀이했다").toBe(afterOpen);
});

test("보는 중에 다른 대화로 옮기면 입력창이 풀리고 옮기기 전 대화를 더 묻지 않는다", async ({ context, page, hermes }, testInfo) => {
  const created = await page.request.post("/api/chat", {
    data: { text: `옮겨 갈 대화 검사 ${testInfo.project.name} ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  const { conversationId: target } = (await created.json()) as { conversationId: string };
  const conversationId = await startHeldTurn(page, hermes, "다른 창 옮기기 검사");
  try {
    const other = await openOtherWindow(context, conversationId);
    const runningRequests = countRunningRequests(other, conversationId);
    await expect(other.getByTestId("observing-notice")).toBeVisible();

    if (testInfo.project.name === "mobile") await other.getByRole("button", { name: "사이드바 열기" }).click();
    await other.getByRole("navigation", { name: "대화 목록" }).locator(`a[href="/chat/${target}"]`).click();
    await expect(other).toHaveURL(new RegExp(`/chat/${target}$`));
    await expect(other.getByTestId("assistant-message")).toHaveCount(1);
    await expect(other.getByTestId("observing-notice")).toHaveCount(0);
    await expect(composer(other).getByRole("button", { name: "보내기" })).toBeVisible();

    const afterMove = runningRequests();
    // 요청이 더 가지 않는 것을 보려면 기다릴 조건이 없다. 주기 한 번을 넘길 만큼 고정으로 기다린다.
    await other.waitForTimeout(OBSERVE_INTERVAL_MS + 1_500);
    expect(runningRequests(), "옮긴 뒤에도 옮기기 전 대화를 물었다").toBe(afterMove);
  } finally {
    await releaseAndSettle(page, hermes);
  }
});

test("보낸 창을 새로 고치면 기다리는 표시를 보이고 끝나면 답을 읽는다", async ({ page, hermes }) => {
  await startHeldTurn(page, hermes, "새로 고친 창 보기 검사");
  try {
    await page.reload();
    await expect(page.getByTestId("observing-notice")).toBeVisible();
    await expect(page.getByLabel("비서의 답을 기다리는 중")
      .or(page.locator('[data-testid="activity-block"][data-mode="live"]')).first()).toBeVisible();
    await expect(page.getByTestId("no-answer")).toHaveCount(0);
    await expect(page.getByTestId("turn-error")).toHaveCount(0);

    await hermes.releaseHeldRun();
    await expect(page.getByTestId("assistant-message")).toHaveCount(1, { timeout: 30_000 });
    await expect(page.getByTestId("observing-notice")).toHaveCount(0);
    await expect(page.getByTestId("no-answer")).toHaveCount(0);
  } finally {
    await releaseAndSettle(page, hermes);
  }
});

test("보낸 창의 스트림이 끊겨도 turn 이 돌면 기다리는 표시로 바뀌고 끝나면 저장된 것만 남는다", async ({ page, hermes }) => {
  await makeChatStreamCuttable(page);
  const text = "스트림 끊김 넘어가기 검사";
  await startHeldTurn(page, hermes, text);
  try {
    await cutChatStream(page);
    const notice = page.getByTestId("observing-notice");
    await expect(notice).toBeVisible();
    await expect(notice).toContainText("답을 기다리고 있어요");
    await expect(notice).not.toContainText("다른 창");
    await expect(page.getByLabel("비서의 답을 기다리는 중")
      .or(page.locator('[data-testid="activity-block"][data-mode="live"]')).first()).toBeVisible();
    await expect(page.getByText(INTERRUPTED_MESSAGE)).toHaveCount(0);
    await expect(page.getByTestId("turn-error")).toHaveCount(0);
    await expect(page.getByTestId("user-message")).toHaveCount(1);

    await hermes.releaseHeldRun();
    await expect(page.getByTestId("assistant-message")).toHaveCount(1, { timeout: 30_000 });
    await expect(notice).toHaveCount(0);
    await expect(page.getByTestId("user-message")).toHaveCount(1);
    await expect(page.getByTestId("user-message").first()).toContainText(text);
    await expect(page.getByText(INTERRUPTED_MESSAGE)).toHaveCount(0);
    await expect(composer(page).getByRole("button", { name: "보내기" })).toBeVisible();
  } finally {
    await releaseAndSettle(page, hermes);
  }
});

test("스트림이 끊겼을 때 turn 이 이미 끝나 답이 저장됐으면 오류 없이 답을 보인다", async ({ page, hermes }) => {
  await makeChatStreamCuttable(page);
  const conversationId = await startHeldTurn(page, hermes, "스트림 끊김 뒤 답 있음 검사");
  try {
    // 끊긴 창이 묻는 순간에는 turn 이 끝나 있게 한다. 붙잡은 run 을 풀고 실제로 끝난 뒤의 응답을 넘긴다.
    await page.route(`**/api/chat/conversations/${conversationId}/running`, async (route) => {
      await hermes.releaseHeldRun();
      const deadline = Date.now() + FINISH_TIMEOUT_MS;
      while (Date.now() < deadline) {
        const response = await route.fetch();
        if (!((await response.json()) as { running: boolean }).running) {
          await route.fulfill({ response });
          return;
        }
        // route handler 안이라 expect.poll 로 기다릴 수 없다. 되묻는 사이만 짧게 쉰다.
        await new Promise((done) => setTimeout(done, FINISH_CHECK_INTERVAL_MS));
      }
      // 끝내 끝나지 않았다. 조회를 실패시켜 아래 단언이 답이 없다는 것으로 분명히 실패하게 한다.
      await route.abort();
    });
    await cutChatStream(page);
    await expect(page.getByTestId("assistant-message")).toHaveCount(1, { timeout: 30_000 });
    await expect(page.getByTestId("user-message")).toHaveCount(1);
    await expect(page.getByTestId("observing-notice")).toHaveCount(0);
    await expect(page.getByText(INTERRUPTED_MESSAGE)).toHaveCount(0);
    await expect(page.getByTestId("turn-error")).toHaveCount(0);
    await expect(composer(page).getByRole("button", { name: "보내기" })).toBeVisible();
  } finally {
    await releaseAndSettle(page, hermes);
  }
});

test("스트림이 끊기고 turn 이 돌지 않는데 답도 없으면 끊김 문구를 보인다", async ({ page, hermes }) => {
  await makeChatStreamCuttable(page);
  const conversationId = await startHeldTurn(page, hermes, "스트림 끊김 답 없음 검사");
  try {
    await page.route(`**/api/chat/conversations/${conversationId}/running`, (route) => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(NOT_RUNNING),
    }));
    await cutChatStream(page);
    await expect(page.getByTestId("turn-error")).toContainText(INTERRUPTED_MESSAGE);
    await expect(page.getByTestId("observing-notice")).toHaveCount(0);
    await expect(page.getByTestId("assistant-message")).toHaveCount(0);
    await expect(page.getByTestId("user-message")).toHaveCount(1);
    await expect(composer(page).getByRole("button", { name: "보내기" })).toBeVisible();
  } finally {
    await releaseAndSettle(page, hermes);
  }
});

test("스트림이 끊긴 뒤 대화가 지워졌으면 대화를 찾을 수 없다는 화면으로 간다", async ({ page, hermes }) => {
  await makeChatStreamCuttable(page);
  const conversationId = await startHeldTurn(page, hermes, "스트림 끊김 대화 지움 검사");
  const runningPath = `**/api/chat/conversations/${conversationId}/running`;
  try {
    await page.route(runningPath, (route) => route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify({ code: "CONVERSATION_NOT_FOUND", message: "대화가 없다" }),
    }));
    await cutChatStream(page);
    await expect(page.getByTestId("conversation-not-found")).toBeVisible();
    await expect(page.getByText(INTERRUPTED_MESSAGE)).toHaveCount(0);
  } finally {
    // 대화를 찾을 수 없다는 화면에는 보내기 단추가 없어 화면으로 끝을 볼 수 없다. 실제 조회로 turn 이 끝난 것을 본다.
    await hermes.releaseHeldRun();
    await page.unroute(runningPath);
    await expect.poll(() => isRunning(page, conversationId), { timeout: FINISH_TIMEOUT_MS }).toBe(false);
  }
});
