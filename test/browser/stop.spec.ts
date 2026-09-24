import { expect, FLOW_AGENT_CODE, test } from "./fixtures.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

async function beginHeldTurn(page: Page, text: string) {
  await page.goto("/");
  await page.getByPlaceholder("무엇을 도와줄까요").fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
}

test("답을 만드는 동안 중지 단추를 보이고 중지한 답을 남긴다", async ({ page, hermes }) => {
  await hermes.holdNextRun();
  await beginHeldTurn(page, "중지 화면 검사");
  await hermes.waitForHeldRun();
  const stop = page.getByTestId("composer-shell").getByRole("button", { name: "중지" });
  await expect(stop).toBeVisible();
  await expect(page.getByRole("button", { name: "보내기" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "답 복사" })).toHaveCount(0);
  await stop.click();
  await expect(page.getByTestId("stopped-mark")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "보내기" })).toBeVisible();
  await page.reload();
  await expect(page.getByTestId("stopped-mark")).toBeVisible();
});

test("사진을 실은 실행 중에는 사진을 지우거나 다음 사진을 고를 수 없다", async ({ page, hermes }) => {
  await page.goto("/");
  const uploaded = page.waitForResponse((response) => response.request().method() === "POST"
    && /\/api\/chat\/conversations\/\d+\/attachments$/.test(response.url()));
  await page.getByTestId("attachment-input").setInputFiles([
    { name: "running.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  const attachmentId = ((await (await uploaded).json()) as { id: number }).id;
  const conversationId = Number(page.url().match(/\/c\/(\d+)$/)?.[1]);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await hermes.holdNextRun();
  await page.getByPlaceholder("무엇을 도와줄까요").fill("사진 중지 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  await hermes.waitForHeldRun();
  await expect(page.getByTestId("attachment-input")).toBeDisabled();
  await expect(page.getByRole("button", { name: "사진 첨부" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "사진 지우기" })).toBeDisabled();
  await page.getByTestId("composer-shell").getByRole("button", { name: "중지" }).click();
  await expect(page.getByTestId("stopped-mark")).toBeVisible({ timeout: 30_000 });
  const attachment = await page.request.get(`/api/chat/conversations/${conversationId}/attachments/${attachmentId}`);
  expect(attachment.ok()).toBeTruthy();
});

test("중지 요청 실패 뒤에는 다시 중지할 수 있다", async ({ page, hermes }) => {
  await hermes.holdNextRun();
  await beginHeldTurn(page, "중지 재시도 검사");
  await hermes.waitForHeldRun();
  await page.route("**/api/chat/executions/*/stop", (route) => route.fulfill({
    status: 503,
    contentType: "application/json",
    body: JSON.stringify({ code: "HERMES_UNAVAILABLE", message: "중지하지 못했다" }),
  }));
  const stop = page.getByTestId("composer-shell").getByRole("button", { name: "중지" });
  await stop.click();
  await expect(stop).toBeEnabled();
  await page.unroute("**/api/chat/executions/*/stop");
  await stop.click();
  await expect(page.getByTestId("stopped-mark")).toBeVisible({ timeout: 30_000 });
});

test("남긴 답이 없으면 사용자 메시지 아래에 안내를 보인다", async ({ page, hermes }) => {
  await hermes.holdNextRun();
  await beginHeldTurn(page, "중지 조각 전 검사");
  await hermes.waitForHeldRun();
  await page.getByTestId("composer-shell").getByRole("button", { name: "중지" }).click();
  await expect(page.getByTestId("no-answer")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("assistant-message")).toHaveCount(0);
  await page.reload();
  await expect(page.getByTestId("no-answer")).toBeVisible();
});

test("답과 코드 블록의 원문을 복사한다", async ({ context, page }, testInfo) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto("/");
  await page.getByPlaceholder("무엇을 도와줄까요").fill("코드 블록 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  const answerCopy = page.getByRole("button", { name: "답 복사" }).last();
  await expect(answerCopy).toBeVisible({ timeout: 30_000 });
  if (testInfo.project.name === "mobile") await expect(answerCopy).toBeVisible();
  await answerCopy.click();
  await expect(answerCopy).toHaveText("복사됨");
  const codeCopy = page.getByRole("button", { name: "코드 복사" }).first();
  await codeCopy.click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toContain("public class Greeting");
});

test("작업 과정 패널을 닫는 Esc 가 중지보다 먼저다", async ({ page, hermes }) => {
  await hermes.holdNextRun();
  await page.goto("/");
  await page.getByRole("combobox").selectOption(FLOW_AGENT_CODE);
  await page.getByPlaceholder("무엇을 도와줄까요").fill("중지 패널 Esc 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  await hermes.waitForHeldRun();
  const block = page.locator('[data-testid="activity-block"][data-mode="live"]');
  await expect(block).toBeVisible();
  await block.getByTestId("activity-toggle").click();
  await block.getByTestId("activity-open-panel").click();
  await expect(page.getByTestId("activity-panel")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByTestId("activity-panel")).toHaveCount(0);
  await expect(page.getByTestId("composer-shell").getByRole("button", { name: "중지" })).toBeEnabled();
  await page.keyboard.press("Escape");
  await expect(page.getByTestId("no-answer")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("composer-shell").getByRole("button", { name: "보내기" })).toBeVisible();
});

test("입력칸에 초점이 있어도 Esc 로 답을 중지한다", async ({ page, hermes }) => {
  await hermes.holdNextRun();
  await beginHeldTurn(page, "중지 입력 Esc 검사");
  await hermes.waitForHeldRun();
  await page.getByPlaceholder("무엇을 도와줄까요").focus();
  await page.keyboard.press("Escape");
  await expect(page.getByTestId("stopped-mark")).toBeVisible({ timeout: 30_000 });
});
