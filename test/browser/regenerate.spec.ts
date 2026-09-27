import { expect, test } from "./fixtures.ts";

async function ask(page: import("../../web/node_modules/@playwright/test/index.js").Page, text: string) {
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
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

test("다시 생성도 실행 중지로 끝낼 수 있다", async ({ page, hermes }) => {
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

});

test("사용자 메시지에는 수정 단추가 없다", async ({ page }) => {
  await page.goto("/");
  await ask(page, "첫 질문");
  await ask(page, "둘째 질문");
  await expect(page.getByTestId("user-message").getByRole("button", { name: "수정" })).toHaveCount(0);
});

test("새 질문을 보내는 동안 이전 turn 을 계속 보인다", async ({ page, hermes }) => {
  await page.goto("/");
  await ask(page, "이전 turn 유지 검사");
  await hermes.holdNextRun();
  await page.getByRole("textbox", { name: "메시지" }).fill("새 질문 대기 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  await hermes.waitForHeldRun();
  await expect(page.getByTestId("user-message")).toHaveCount(2);
  await expect(page.getByTestId("assistant-message").first()).toContainText("이전 turn 유지 검사");
  await page.getByTestId("composer-shell").getByRole("button", { name: "중지" }).click();
});

test("답 없는 질문을 다시 시도해 같은 질문에 답을 붙인다", async ({ page, hermes }) => {
  await hermes.holdNextRun();
  await page.goto("/");
  await page.getByRole("textbox", { name: "메시지" }).fill("중지 조각 전 검사");
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
