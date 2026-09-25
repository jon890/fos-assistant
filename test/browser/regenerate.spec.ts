import { expect, test } from "./fixtures.ts";

async function ask(page: import("../../web/node_modules/@playwright/test/index.js").Page, text: string) {
  await page.getByPlaceholder("무엇을 도와줄까요").fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
}

test("마지막 답을 다시 생성하고 이전 판을 볼 수 있다", async ({ page }) => {
  await page.goto("/");
  await ask(page, "다시 생성 화면 검사");
  const answer = page.getByTestId("assistant-message").last().locator("div.leading-7");
  await expect(page.getByTestId("assistant-message").last().getByRole("button", { name: "다시 생성", exact: true })).toBeVisible();
  const firstAnswer = await answer.innerText();
  await page.getByTestId("assistant-message").last().getByRole("button", { name: "다시 생성", exact: true }).click();
  await expect(page.getByTestId("version-label")).toHaveText("2/2", { timeout: 30_000 });
  await expect(answer).not.toHaveText(firstAnswer);
  await page.getByRole("button", { name: "이전 판" }).last().click();
  await expect(page.getByTestId("version-label")).toHaveText("1/2");
  await expect(answer).toHaveText(firstAnswer);
  await expect(page.getByTestId("assistant-message").getByRole("button", { name: "다시 생성", exact: true })).toHaveCount(0);
  await page.reload();
  await expect(page.getByTestId("version-label")).toHaveText("2/2");
});

test("다시 생성 중 오류가 나면 임시 답을 버리고 이전 답을 보인다", async ({ page }) => {
  await page.goto("/");
  await ask(page, "다시 생성 실패 검사");
  await page.route("**/api/chat/conversations/*/regenerate", (route) => route.fulfill({
    status: 200,
    contentType: "text/event-stream",
    body: 'data: {"type":"delta","text":"버릴 임시 답"}\n\ndata: {"type":"error","code":"HERMES_UNAVAILABLE","message":"다시 만들지 못했다"}\n\n',
  }));
  await page.getByTestId("assistant-message").last().getByRole("button", { name: "다시 생성", exact: true }).click();
  await expect(page.getByTestId("turn-error")).toBeVisible();
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect(page.getByTestId("assistant-message").last()).toContainText("다시 생성 실패 검사");
  await expect(page.getByText("버릴 임시 답")).toHaveCount(0);
  await expect(page.getByTestId("version-label")).toHaveCount(0);
});

test("마지막 질문을 고치면 사용자 판을 넘긴다", async ({ page }) => {
  await page.goto("/");
  await ask(page, "수정 전 질문");
  await page.getByTestId("user-message").last().getByRole("button", { name: "수정", exact: true }).click();
  const editor = page.getByRole("textbox", { name: "질문 수정" });
  await editor.fill("수정 후 질문");
  await page.getByTestId("user-message").last().getByRole("button", { name: "보내기", exact: true }).click();
  await expect(page.getByTestId("version-label")).toHaveText("2/2", { timeout: 30_000 });
  await page.reload();
  await expect(page.getByTestId("user-message").getByText("수정 후 질문", { exact: true })).toBeVisible();
  await expect(page.getByTestId("version-label")).toHaveText("2/2");
});

test("다시 생성과 수정도 실행 중지로 끝낼 수 있다", async ({ page, hermes }) => {
  await page.goto("/");
  await ask(page, "재생성 중지 검사");
  const answer = page.getByTestId("assistant-message").last();
  await expect(answer.getByRole("button", { name: "다시 생성", exact: true })).toBeVisible();
  await hermes.holdNextRun();
  await answer.getByRole("button", { name: "다시 생성", exact: true }).click();
  await hermes.waitForHeldRun();
  await page.getByTestId("composer-shell").getByRole("button", { name: "중지" }).click();
  await expect(page.getByTestId("stopped-mark").last()).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("version-label")).toHaveText("2/2");

  await hermes.holdNextRun();
  await page.getByTestId("user-message").last().getByRole("button", { name: "수정", exact: true }).click();
  await page.getByRole("textbox", { name: "질문 수정" }).fill("수정 중지 검사");
  await page.getByTestId("user-message").last().getByRole("button", { name: "보내기", exact: true }).click();
  await hermes.waitForHeldRun();
  await page.getByTestId("composer-shell").getByRole("button", { name: "중지" }).click();
  await expect(page.getByTestId("user-message").last()).toContainText("수정 중지 검사", { timeout: 30_000 });
  await expect(page.getByTestId("stopped-mark").last()).toBeVisible();
});

test("오래된 질문 수정 오류를 보이고 편집 글을 남긴다", async ({ page }) => {
  await page.goto("/");
  await ask(page, "오래된 질문 검사");
  let reads = 0;
  await page.route("**/api/chat/conversations/*/messages", async (route) => {
    reads += 1;
    await route.continue();
  });
  await page.route("**/api/chat/stream", (route) => route.fulfill({
    status: 200,
    contentType: "text/event-stream",
    body: 'data: {"type":"error","code":"MESSAGE_NOT_LATEST","message":"질문이 최신이 아니다"}\n\n',
  }));
  await page.getByTestId("user-message").last().getByRole("button", { name: "수정", exact: true }).click();
  const editor = page.getByRole("textbox", { name: "질문 수정" });
  await editor.fill("고치던 글은 그대로 남아야 한다");
  await page.getByTestId("user-message").last().getByRole("button", { name: "보내기", exact: true }).click();
  await expect(page.getByText("그 사이 대화가 바뀌었다. 최신 대화를 다시 불러왔다.")).toBeVisible();
  await expect(editor).toHaveValue("고치던 글은 그대로 남아야 한다");
  await expect.poll(() => reads).toBeGreaterThan(0);
  await expect(page.getByPlaceholder("무엇을 도와줄까요")).toHaveValue("");
});

test("수정 실행이 시작된 뒤 실패하면 편집칸을 닫고 다시 시도만 보인다", async ({ page }) => {
  await page.goto("/");
  await ask(page, "수정 실행 실패 검사");
  const conversationId = Number(page.url().match(/\/c\/(\d+)$/)?.[1]);
  const history = await (await page.request.get(`/api/chat/conversations/${conversationId}/messages`)).json() as
    { id: number; role: string; content: string; replacesMessageId: number | null }[];
  const question = history.find((message) => message.role === "USER")!;
  let reads = 0;
  await page.route("**/api/chat/conversations/*/messages", (route) => {
    reads += 1;
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([...history, {
        ...question,
        id: Math.max(...history.map((message) => message.id)) + 1,
        content: "실패한 수정 글",
        replacesMessageId: question.id,
      }]),
    });
  });
  await page.route("**/api/chat/stream", (route) => route.fulfill({
    status: 200,
    contentType: "text/event-stream",
    body: `data: ${JSON.stringify({ type: "started", conversationId, executionId: 999 })}\n\n`
      + 'data: {"type":"error","code":"HERMES_RUN_FAILED","message":"실행 실패"}\n\n',
  }));
  await page.getByTestId("user-message").last().getByRole("button", { name: "수정", exact: true }).click();
  await page.getByRole("textbox", { name: "질문 수정" }).fill("실패한 수정 글");
  await page.getByTestId("user-message").last().getByRole("button", { name: "보내기", exact: true }).click();
  await expect(page.getByTestId("turn-error")).toBeVisible();
  await expect(page.getByRole("textbox", { name: "질문 수정" })).toHaveCount(0);
  await expect(page.getByTestId("no-answer")).toBeVisible();
  await expect(page.getByRole("button", { name: "다시 시도" })).toBeVisible();
  await expect.poll(() => reads).toBeGreaterThan(0);
});

test("수정 실행 뒤 이력을 다시 읽지 못해도 편집칸을 닫고 오류를 보인다", async ({ page }) => {
  await page.goto("/");
  await ask(page, "수정 이력 실패 검사");
  const conversationId = Number(page.url().match(/\/c\/(\d+)$/)?.[1]);
  await page.route("**/api/chat/conversations/*/messages", (route) => route.fulfill({
    status: 503,
    contentType: "application/json",
    body: JSON.stringify({ code: "INTERNAL_ERROR", message: "이력을 읽지 못했다" }),
  }));
  await page.route("**/api/chat/stream", (route) => route.fulfill({
    status: 200,
    contentType: "text/event-stream",
    body: `data: ${JSON.stringify({ type: "started", conversationId, executionId: 999 })}\n\n`
      + 'data: {"type":"error","code":"HERMES_RUN_FAILED","message":"실행 실패"}\n\n',
  }));
  await page.getByTestId("user-message").last().getByRole("button", { name: "수정", exact: true }).click();
  await page.getByRole("textbox", { name: "질문 수정" }).fill("이력 실패 수정 글");
  await page.getByTestId("user-message").last().getByRole("button", { name: "보내기", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "질문 수정" })).toHaveCount(0);
  await expect(page.getByTestId("turn-error")).toBeVisible();
  await expect(page.getByTestId("turn-error")).toContainText("대화 이력을 다시 읽지 못했다");
  await expect(page.getByTestId("no-answer")).toHaveCount(0);
  const retry = page.getByTestId("turn-error-retry");
  await expect(retry).toBeVisible();
  const retried = page.waitForRequest((request) => request.method() === "POST"
    && /\/api\/chat\/conversations\/\d+\/regenerate$/.test(request.url()));
  await retry.click();
  await retried;
});

test("앞선 사용자 메시지는 수정할 수 없다", async ({ page }) => {
  await page.goto("/");
  await ask(page, "첫 질문");
  await ask(page, "둘째 질문");
  await expect(page.getByTestId("user-message").first().getByRole("button", { name: "수정" })).toHaveCount(0);
});

test("새 질문을 보내는 동안 이전 turn 을 계속 보인다", async ({ page, hermes }) => {
  await page.goto("/");
  await ask(page, "이전 turn 유지 검사");
  await hermes.holdNextRun();
  await page.getByPlaceholder("무엇을 도와줄까요").fill("새 질문 대기 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  await hermes.waitForHeldRun();
  await expect(page.getByTestId("user-message")).toHaveCount(2);
  await expect(page.getByTestId("assistant-message").first()).toContainText("이전 turn 유지 검사");
  await page.getByTestId("composer-shell").getByRole("button", { name: "중지" }).click();
});

test("같은 글은 수정 전송할 수 없다", async ({ page }) => {
  await page.goto("/");
  await ask(page, "같은 글 검사");
  await page.getByTestId("user-message").last().getByRole("button", { name: "수정", exact: true }).click();
  await expect(page.getByTestId("user-message").last().getByRole("button", { name: "보내기", exact: true })).toBeDisabled();
});

test("답 없는 질문을 다시 시도해 같은 질문에 답을 붙인다", async ({ page, hermes }) => {
  await hermes.holdNextRun();
  await page.goto("/");
  await page.getByPlaceholder("무엇을 도와줄까요").fill("중지 조각 전 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  await hermes.waitForHeldRun();
  await page.getByTestId("composer-shell").getByRole("button", { name: "중지" }).click();
  await expect(page.getByTestId("no-answer")).toBeVisible({ timeout: 30_000 });
  await page.getByRole("button", { name: "다시 시도" }).click();
  await expect(page.getByTestId("assistant-message")).toHaveCount(1, { timeout: 30_000 });
  await expect(page.getByTestId("no-answer")).toHaveCount(0);
  await expect(page.getByTestId("user-message")).toHaveCount(1);
  await page.reload();
  await expect(page.getByTestId("user-message")).toHaveCount(1);
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
});
