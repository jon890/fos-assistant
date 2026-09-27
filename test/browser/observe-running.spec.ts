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
    await expect(other.getByText("Hermes 런타임에 연결하지 못했다.")).toHaveCount(0);
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
