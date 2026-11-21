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

/** 첫 내 메시지에 사진 한 장을, 둘째 내 메시지에 그 답을 집은 상자를 붙인 것처럼 이력 응답을 바꾼다. */
async function withPhotoAndReply(page: Page, conversationId: string, imageGone = false) {
  await page.route(`**/api/chat/conversations/${conversationId}/messages`, async (route) => {
    const response = await route.fetch();
    const messages = (await response.json()) as { id: number; role: string; attachments: unknown[]; reply?: unknown }[];
    const users = messages.filter((message) => message.role === "USER");
    const answer = messages.find((message) => message.role === "ASSISTANT");
    users[0].attachments = [
      { id: 990001, originalName: "신발.png", byteSize: PNG.length, visible: true, expiresAt: "2999-01-01T00:00:00Z" },
    ];
    if (users[1] && answer)
      users[1].reply = {
        messageId: answer.id, role: "ASSISTANT", excerpt: "앞 줄", available: true,
        images: [{ kind: "GENERATED", path: "generated/지운 그림.png", attachmentId: null, deleted: imageGone }],
      };
    await route.fulfill({ response, json: messages });
  });
  await page.route(`**/api/chat/conversations/${conversationId}/attachments/**`, (route) =>
    route.fulfill({ status: 200, contentType: "image/png", body: PNG }));
}

async function longPress(page: Page, target: ReturnType<Page["getByTestId"]>) {
  const rect = (await target.boundingBox())!;
  await target.dispatchEvent("pointerdown", {
    pointerType: "touch", bubbles: true, clientX: rect.x + rect.width / 2, clientY: rect.y + rect.height / 2,
  });
  await page.waitForTimeout(700);
}

test.describe("휴대폰 사진과 메뉴", () => {
  test.use({ hasTouch: true, isMobile: true });

  test("내 메시지의 사진을 길게 누르면 메뉴가 하나만 열리고 그 사진 한 장이 집히며, 메뉴 밖을 누르면 닫힌다", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "mobile", "터치 사례는 휴대폰 폭에서 본다");
    const conversationId = await openConversation(page);
    await withPhotoAndReply(page, conversationId);
    await page.goto(`/chat/${conversationId}`);

    await longPress(page, page.getByTestId("message-attachment").first());
    await expect(page.getByTestId("reply-menu")).toHaveCount(1);
    // 손가락을 뗄 때의 클릭은 열린 직후 0.4초 동안 무시한다. 그 뒤의 바깥 누르기만 닫는다.
    await page.waitForTimeout(500);
    await page.getByTestId("reply-menu-backdrop").click({ position: { x: 5, y: 5 } });
    await expect(page.getByTestId("reply-menu")).toHaveCount(0);

    await longPress(page, page.getByTestId("message-attachment").first());
    await page.getByTestId("reply-menu").getByRole("menuitem", { name: "답장", exact: true }).click();
    await expect(page.getByTestId("reply-chip").getByTestId("reply-image")).toHaveCount(1);
    await expect(page.getByTestId("reply-chip")).toContainText("내 메시지");
  });
});

test("보낸 답장 상자의 그림 파일이 지워졌으면 보관 기간이 지났다고 보인다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "넓은 화면에서 본다");
  const conversationId = await openConversation(page);
  const second = await page.request.post("/api/chat", { data: { conversationId, text: "둘째 질문", agentCode: "browser" } });
  expect(second.ok()).toBeTruthy();
  await withPhotoAndReply(page, conversationId, true);
  await page.goto(`/chat/${conversationId}`);
  await expect(page.getByTestId("reply-box").getByTestId("reply-image-gone")).toHaveText("보관 기간이 지나 볼 수 없어요.");
});

test("내 메시지에 키보드로 초점을 옮기면 「답장」 이 보이고, 새로 집으면 칩이 바뀐다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "키보드 사례는 넓은 화면에서 본다");
  const conversationId = await openConversation(page);
  await withImages(page, conversationId);
  await page.goto(`/chat/${conversationId}`);
  const mine = page.locator("li[data-message-id]").filter({ has: page.getByTestId("user-message") }).first();
  await mine.focus();
  const reply = mine.getByRole("button", { name: "답장", exact: true });
  await expect.poll(() => reply.evaluate((element) => getComputedStyle(element.parentElement!).opacity)).toBe("1");
  await reply.click();
  await expect(page.getByTestId("reply-chip")).toContainText("내 메시지");

  await page.getByRole("button", { name: "수채화 고양이 크게 보기" }).click();
  await page.getByTestId("generated-image-viewer").getByRole("button", { name: "답장", exact: true }).click();
  await expect(page.getByTestId("reply-chip")).toContainText("비서의 답");
  await expect(page.getByTestId("reply-chip").getByTestId("reply-image")).toHaveCount(1);
});

test("답이 끝나면 집은 것과 함께 보내기가 켜지고, 글이 없으면 꺼져 있다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "넓은 화면에서 본다");
  const conversationId = await openConversation(page);
  await page.goto(`/chat/${conversationId}`);
  let release = () => {};
  await page.route("**/api/chat/stream", (route) => new Promise<void>((resolve) => {
    release = () => {
      void route.continue();
      resolve();
    };
  }), { times: 1 });
  await page.getByRole("textbox", { name: "메시지" }).fill("먼저 보내는 글");
  await page.getByRole("button", { name: "보내기" }).click();
  const answer = page.getByTestId("assistant-message").first();
  await answer.hover();
  await answer.getByRole("button", { name: "답장", exact: true }).click();
  const send = page.getByRole("button", { name: "보내기" });
  await page.getByRole("textbox", { name: "메시지" }).fill("이걸로 답장");
  await expect(send).toBeDisabled();

  release();
  await expect(page.getByTestId("assistant-message")).toHaveCount(2);
  await expect(page.getByTestId("reply-wait")).toHaveCount(0);
  await expect(send).toBeEnabled();
  await page.getByRole("textbox", { name: "메시지" }).fill("");
  await expect(send).toBeDisabled();
});

test("보내기를 누른 순간 집은 것이 그 메시지에 묶이고 칩이 비어, 사진이 올라가는 동안 새로 집은 것은 다음 메시지로 남는다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "넓은 화면에서 본다");
  const conversationId = await openConversation(page);
  await withImages(page, conversationId);
  let releaseUpload: (() => void) | null = null;
  await page.route("**/api/chat/conversations/*/attachments", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    await new Promise<void>((resolve) => {
      releaseUpload = resolve;
    });
    await route.continue();
  });
  const sent: { reply?: { imagePath?: string | null } }[] = [];
  await page.route("**/api/chat/stream", async (route) => {
    sent.push(route.request().postDataJSON());
    // 답을 붙잡아 둔다. 집은 것이 없는 대기 메시지는 그동안에도 보낼 수 있어야 한다.
    await new Promise(() => {});
  });
  await page.goto(`/chat/${conversationId}`);

  await page.getByRole("button", { name: "수채화 고양이 크게 보기" }).click();
  await page.getByTestId("generated-image-viewer").getByRole("button", { name: "답장", exact: true }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill("고양이로 해 줘");
  await page.getByTestId("attachment-input").setInputFiles([
    { name: "photo.png", mimeType: "image/png", buffer: PNG },
  ]);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByTestId("reply-chip")).toHaveCount(0);

  await page.getByRole("button", { name: "수채화 강아지 크게 보기" }).click();
  await page.getByTestId("generated-image-viewer").getByRole("button", { name: "답장", exact: true }).click();
  await expect.poll(() => releaseUpload !== null).toBe(true);
  releaseUpload!();
  await expect.poll(() => sent[0]?.reply?.imagePath).toBe("generated/수채화 고양이.png");
  await expect(page.getByTestId("reply-chip")).toContainText("비서의 답");

  // 집은 것을 닫으면 답을 만드는 중에도 글을 대기 메시지로 보낼 수 있다.
  await page.getByRole("button", { name: "답장 취소" }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill("대기 글");
  await expect(page.getByRole("button", { name: "보내기" })).toBeEnabled();
});
