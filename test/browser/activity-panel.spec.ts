import { expect, test } from "./fixtures.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

async function send(page: Page, text: string, agent = "브라우저 비서") {
  await page.goto("/");
  await page.getByRole("radio", { name: agent }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
}

async function openSavedPanel(page: Page) {
  const block = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
  await expect(block).toBeVisible({ timeout: 30_000 });
  await block.getByTestId("activity-toggle").click();
  await block.getByTestId("activity-open-panel").click();
  return page.getByTestId("activity-panel");
}

test("끝난 답의 패널에서 실행 나무를 보고 단추와 Esc 로 닫는다", async ({ page }, testInfo) => {
  await send(page, "패널 검사");
  const panel = await openSavedPanel(page);
  await expect(panel.getByTestId("execution-tree")).toBeVisible();
  await expect(page.getByTestId("assistant-message").last().getByTestId("flow-tree-link")).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
    .toBeLessThanOrEqual(0);
  if (testInfo.project.name === "desktop") {
    await expect(page.getByRole("textbox", { name: "메시지" })).toBeVisible();
  } else {
    const bounds = await panel.boundingBox();
    expect(bounds?.width).toBeGreaterThanOrEqual(390);
  }
  await panel.getByRole("button", { name: "작업 과정 닫기" }).click();
  await expect(panel).toHaveCount(0);
  await page.locator('[data-testid="activity-block"][data-mode="saved"]').last()
    .getByTestId("activity-open-panel").click();
  await page.keyboard.press("Escape");
  await expect(panel).toHaveCount(0);
  await expect(page.getByRole("textbox", { name: "메시지" })).toBeVisible();
});

test("도는 중 패널은 사건을 보이고 끝나면 같은 자리에서 나무로 바뀐다", async ({ page, hermes }) => {
  await hermes.holdNextRun();
  let released = false;
  try {
    await send(page, "패널 진행 검사", "흐름 비서");
    await hermes.waitForHeldRun();
    const block = page.locator('[data-testid="activity-block"][data-mode="live"]');
    await expect(block).toBeVisible();
    await block.getByTestId("activity-toggle").click();
    await block.getByTestId("activity-open-panel").click();
    const panel = page.getByTestId("activity-panel");
    await expect(panel.locator('[data-kind="step"]')).toHaveCount(1);
    await expect(panel.getByTestId("execution-tree")).toHaveCount(0);
    await hermes.releaseHeldRun();
    released = true;
    await expect(panel.getByTestId("execution-tree")).toBeVisible({ timeout: 30_000 });
    await expect(panel.getByTestId("flow-tree-link")).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
      .toBeLessThanOrEqual(0);
  } finally {
    if (!released) await hermes.releaseHeldRun();
  }
});

test("패널이 나무를 읽지 못하면 다시 읽을 수 있다", async ({ page }) => {
  await send(page, "패널 재시도 검사");
  await page.route("**/api/usage/executions/*/tree", (route) => route.fulfill({ status: 500, body: "{}" }));
  const panel = await openSavedPanel(page);
  await expect(panel.getByText("실행 나무를 읽지 못했다")).toBeVisible();
  await page.unroute("**/api/usage/executions/*/tree");
  await panel.getByRole("button", { name: "다시 읽기" }).click();
  await expect(panel.getByTestId("execution-tree")).toBeVisible();
});

test("중간 폭에서는 패널이 대화 위 오른쪽에 겹친다", async ({ page }) => {
  await page.setViewportSize({ width: 800, height: 800 });
  await send(page, "중간 폭 검사");
  const panel = await openSavedPanel(page);
  const bounds = await panel.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.width).toBe(384);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(800);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
    .toBeLessThanOrEqual(0);
});

test("다른 답에서 열면 같은 패널이 그 답의 나무로 바뀐다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "패널 옆에서 다른 답을 누를 수 있는 폭에서 검사한다");
  await send(page, "첫 번째 패널 검사");
  await expect(page.locator('[data-testid="activity-block"][data-mode="saved"]')).toHaveCount(1);
  await page.getByRole("textbox", { name: "메시지" }).fill("두 번째 패널 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  const blocks = page.locator('[data-testid="activity-block"][data-mode="saved"]');
  await expect(blocks).toHaveCount(2);
  await blocks.first().getByTestId("activity-toggle").click();
  await blocks.first().getByTestId("activity-open-panel").click();
  const panel = page.getByTestId("activity-panel");
  const firstHref = await panel.getByTestId("flow-tree-link").getAttribute("href");
  await blocks.last().getByTestId("activity-toggle").click();
  await blocks.last().getByTestId("activity-open-panel").click();
  await expect(panel.getByTestId("flow-tree-link")).not.toHaveAttribute("href", firstHref!);
  await expect(panel.getByTestId("execution-tree")).toBeVisible();
});

test("패널을 열고 닫아도 입력창에 올린 사진이 남는다", async ({ page }) => {
  await send(page, "사진 유지 검사");
  await expect(page.locator('[data-testid="activity-block"][data-mode="saved"]')).toHaveCount(1);
  const uploaded = page.waitForResponse((response) => response.request().method() === "POST"
    && /\/api\/chat\/conversations\/\d+\/attachments$/.test(response.url()));
  await page.getByTestId("attachment-input").setInputFiles([
    { name: "panel.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  const attachmentId = ((await (await uploaded).json()) as { id: number }).id;
  const conversationId = Number(page.url().match(/\/c\/(\d+)$/)?.[1]);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  const panel = await openSavedPanel(page);
  await expect(panel).toBeVisible();
  await panel.getByRole("button", { name: "작업 과정 닫기" }).click();
  await expect(page.getByTestId("attachment-previews")).toBeVisible();
  const attachment = await page.request.get(`/api/chat/conversations/${conversationId}/attachments/${attachmentId}`);
  expect(attachment.ok()).toBeTruthy();
});

test("사이드바의 이름 입력칸 Esc 는 열린 패널을 닫지 않는다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "사이드바가 보이는 폭에서 검사한다");
  await send(page, "이름 입력 검사");
  const panel = await openSavedPanel(page);
  await expect(panel).toBeVisible();
  const menu = page.getByRole("button", { name: /이름 입력 검사 메뉴/ });
  await menu.click();
  await page.getByRole("menuitem", { name: "이름 바꾸기" }).click();
  await page.getByRole("textbox", { name: "대화 이름" }).press("Escape");
  await expect(panel).toBeVisible();
});
