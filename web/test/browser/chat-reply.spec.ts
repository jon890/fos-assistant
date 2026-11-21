import { expect, test } from "./fixtures.ts";
import type { Page } from "../../node_modules/@playwright/test/index.js";

// 1x1 PNG 다.
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
  "base64",
);

/** 답이 하나 있는 실제 대화를 만들어 연다. */
async function openConversation(page: Page): Promise<string> {
  const created = await page.request.post("/api/chat", { data: { text: `답장 검사 ${Date.now()}`, agentCode: "browser" } });
  expect(created.ok(), `대화를 만들지 못했다: ${created.status()}`).toBeTruthy();
  const { conversationId } = (await created.json()) as { conversationId: string };
  return conversationId;
}

/** 마지막 답에 그림 두 장이 묶인 것처럼 메시지와 파일 응답을 바꾼다. 그림 올리기는 Control Plane 테스트가 본다. */
async function withImages(page: Page, conversationId: string) {
  await page.route(`**/api/chat/conversations/${conversationId}/messages`, async (route) => {
    const response = await route.fetch();
    const messages = (await response.json()) as { role: string; artifacts: unknown[] }[];
    const answer = messages.findLast((message) => message.role === "ASSISTANT");
    if (answer)
      answer.artifacts = ["수채화 고양이", "수채화 강아지"].map((title) => ({
        path: `generated/${title}.png`, byteSize: PNG.length, deleted: false, autoVault: null, autoVaultError: null,
        kind: "IMAGE", prompt: "지시문", vaultItemId: null, sourceLinks: [],
      }));
    await route.fulfill({ response, json: messages });
  });
  await page.route(`**/api/chat/conversations/${conversationId}/files/generated/**`, (route) =>
    route.fulfill({ status: 200, contentType: "image/png", body: PNG }));
}

test("답에 마우스를 올려 답장하면 칩이 보이고, 보낸 메시지 위 상자가 다시 열어도 남아 누르면 원래 답으로 간다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "마우스 사례는 넓은 화면에서 본다");
  const conversationId = await openConversation(page);
  await page.goto(`/chat/${conversationId}`);
  const answer = page.getByTestId("assistant-message").first();
  await answer.hover();
  await answer.getByRole("button", { name: "답장", exact: true }).click();
  await expect(page.getByTestId("reply-chip")).toContainText("비서의 답");
  // 「새 대화에서 시작」 은 PR2 가 더한다(설계 chat-reference DECISION21).
  await expect(page.getByRole("button", { name: "새 대화에서 시작" })).toHaveCount(0);

  await page.getByRole("textbox", { name: "메시지" }).fill("이걸 짧게 다시 써 줘");
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByTestId("assistant-message")).toHaveCount(2);
  await expect(page.getByTestId("reply-chip")).toHaveCount(0);

  await page.reload();
  const box = page.getByTestId("reply-box");
  await expect(box).toHaveCount(1);
  await box.click();
  await expect(page.locator("[data-message-id][data-highlight=true]")).toHaveAttribute("data-testid", "assistant-message");
});

test("그림 크게 보기에서 답장하면 그 그림 한 장이 집히고 보내기 요청에 그림 경로가 간다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "넓은 화면에서 본다");
  const conversationId = await openConversation(page);
  await withImages(page, conversationId);
  let sent: { reply?: unknown } | null = null;
  await page.route("**/api/chat/stream", async (route) => {
    sent = route.request().postDataJSON();
    await route.fulfill({ status: 409, json: { code: "REPLY_SOURCE_UNAVAILABLE", message: "the picked image is no longer available" } });
  });
  await page.goto(`/chat/${conversationId}`);

  await page.getByRole("button", { name: "수채화 강아지 크게 보기" }).click();
  await page.getByTestId("generated-image-viewer").getByRole("button", { name: "답장", exact: true }).click();
  await expect(page.getByTestId("reply-chip").getByTestId("reply-image")).toHaveCount(1);

  const box = page.getByRole("textbox", { name: "메시지" });
  await box.fill("이 그림을 수채화로 바꿔 줘");
  await page.getByRole("button", { name: "보내기" }).click();
  await expect.poll(() => sent?.reply).toMatchObject({ imagePath: "generated/수채화 강아지.png", attachmentId: null });
  // 쓸 수 없는 그림이면 안내가 보이고 쓴 글은 입력창에 남는다(스펙 REQ9).
  await expect(page.getByText("원래 메시지를 찾을 수 없어요. 다시 집어 주세요")).toBeVisible();
  await expect(box).toHaveValue("이 그림을 수채화로 바꿔 줘");
});

test("답을 통째로 집으면 칩에 그 답의 그림 두 장이 함께 보인다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "넓은 화면에서 본다");
  const conversationId = await openConversation(page);
  await withImages(page, conversationId);
  await page.goto(`/chat/${conversationId}`);
  const answer = page.getByTestId("assistant-message").first();
  await answer.hover();
  await answer.getByRole("button", { name: "답장", exact: true }).click();
  await expect(page.getByTestId("reply-chip").getByTestId("reply-image")).toHaveCount(2);
  await page.getByRole("button", { name: "답장 취소" }).click();
  await expect(page.getByTestId("reply-chip")).toHaveCount(0);
});

test.describe("휴대폰", () => {
  test.use({ hasTouch: true, isMobile: true });

  test("답을 길게 누르면 「답장」 과 「복사」 메뉴가 열리고 「답장」 이 칩을 만든다", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "mobile", "터치 사례는 휴대폰 폭에서 본다");
    const conversationId = await openConversation(page);
    await page.goto(`/chat/${conversationId}`);
    const body = page.getByTestId("assistant-body").first();
    const rect = (await body.boundingBox())!;
    // 길게 누르기는 터치 포인터로 본다. 손가락을 떼지 않은 채 0.7초 기다린다.
    await body.dispatchEvent("pointerdown", {
      pointerType: "touch",
      bubbles: true,
      clientX: rect.x + 10,
      clientY: rect.y + rect.height / 2,
    });
    await page.waitForTimeout(700);

    const menu = page.getByTestId("reply-menu");
    await expect(menu.getByRole("menuitem")).toHaveText(["답장", "복사"]);
    await menu.getByRole("menuitem", { name: "답장", exact: true }).click();
    await expect(page.getByTestId("reply-chip")).toBeVisible();
  });
});

test("답을 만드는 중에는 집은 것이 있으면 보내기가 꺼지고 안내가 보인다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "넓은 화면에서 본다");
  const conversationId = await openConversation(page);
  await page.goto(`/chat/${conversationId}`);
  const answer = page.getByTestId("assistant-message").first();
  await answer.hover();
  await answer.getByRole("button", { name: "답장", exact: true }).click();
  // 보내는 답이 끝나지 않게 붙잡아 둔다.
  await page.route("**/api/chat/stream", () => new Promise(() => {}));
  await page.getByRole("button", { name: "답장 취소" }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill("먼저 보내는 글");
  await page.getByRole("button", { name: "보내기" }).click();
  await answer.hover();
  await answer.getByRole("button", { name: "답장", exact: true }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill("이걸로 답장");
  await expect(page.getByTestId("reply-wait")).toHaveText("답이 끝나면 보낼 수 있어요");
  await expect(page.getByRole("button", { name: "보내기" })).toBeDisabled();
});
