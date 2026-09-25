import { expect, test } from "./fixtures.ts";
import type { Locator, Page, TestInfo } from "../../web/node_modules/@playwright/test/index.js";

/** 실제로 디코딩되는 1x1 PNG 다. 저장소에 이미지를 넣지 않으려고 바이트로 만들어 쓴다. */
const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

function pngFile(name: string) {
  return { name, mimeType: "image/png", buffer: PNG_1X1 };
}

/** 새 대화를 열고 입력창을 쓸 수 있는 상태로 만든다. */
async function openNewConversation(page: Page, testInfo: TestInfo): Promise<void> {
  await page.goto("/");
}

async function openConversationListDrawer(page: Page): Promise<void> {
  const opener = page.getByRole("button", { name: "사이드바 열기" });
  if (await opener.isVisible()) await opener.click();
}

/** 사진이 실제로 읽혔는지 본다. `toBeVisible` 만으로는 깨진 이미지도 통과한다. */
async function expectImageLoaded(locator: Locator): Promise<void> {
  await expect(locator).toBeVisible();
  await expect
    .poll(() => locator.evaluate((img) => (img as HTMLImageElement).naturalWidth))
    .toBeGreaterThan(0);
}

test("사진을 고르면 미리보기가 붙고 올리는 동안 보내기가 잠긴다", async ({ page }, testInfo) => {
  await openNewConversation(page, testInfo);

  let releaseUpload: (() => void) | null = null;
  await page.route("**/api/chat/conversations/*/attachments", async (route) => {
    if (route.request().method() !== "POST") {
      await route.continue();
      return;
    }
    await new Promise<void>((resolve) => {
      releaseUpload = resolve;
    });
    await route.continue();
  });

  try {
    const send = page.getByRole("button", { name: "보내기" });
    await page.getByRole("textbox", { name: "메시지" }).fill("사진 미리보기 검사");
    await expect(send).toBeEnabled();

    await page.getByTestId("attachment-input").setInputFiles([pngFile("photo.png")]);

    await expect(page.getByTestId("attachment-previews")).toBeVisible();
    await expect(page.getByTestId("attachment-uploading")).toBeVisible();
    await expect(send).toBeDisabled();

    releaseUpload?.();
    await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
    await expect(send).toBeEnabled();
  } finally {
    await page.unroute("**/api/chat/conversations/*/attachments");
  }
});

test("미리보기의 지우는 단추를 누르면 그 사진만 빠진다", async ({ page }, testInfo) => {
  await openNewConversation(page, testInfo);

  await page.getByTestId("attachment-input").setInputFiles([pngFile("a.png"), pngFile("b.png")]);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);

  const items = page.getByTestId("attachment-previews").locator("> div");
  await expect(items).toHaveCount(2);

  await items.first().getByRole("button", { name: "사진 지우기" }).click();
  await expect(items).toHaveCount(1);
});

test("지운 첨부는 서버 상한을 계속 차지하지 않아 지운 뒤 다시 10장을 올릴 수 있다", async ({
  page,
}, testInfo) => {
  await openNewConversation(page, testInfo);

  // 열 장을 채운 뒤 하나를 지운다. 지우는 단추가 서버 DELETE 를 부르지 않으면 「묶이지 않은 첨부」
  // 상한을 계속 차지해, 그 뒤로 한 장도 더 올릴 수 없다.
  await page.getByTestId("attachment-input").setInputFiles(
    Array.from({ length: 10 }, (_, index) => pngFile(`cap-${index}.png`)),
  );
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  const items = page.getByTestId("attachment-previews").locator("> div");
  await expect(items).toHaveCount(10);

  // DELETE 가 끝나기 전에 다음 사진을 올리면 순서에 따라 상한에 걸린다. 응답을 기다린 뒤 고른다.
  const deleted = page.waitForResponse(
    (response) =>
      /\/api\/chat\/conversations\/\d+\/attachments\/\d+$/.test(response.url())
      && response.request().method() === "DELETE",
  );
  await items.first().getByRole("button", { name: "사진 지우기" }).click();
  await expect(items).toHaveCount(9);
  expect((await deleted).ok()).toBeTruthy();

  await page.getByTestId("attachment-input").setInputFiles([pngFile("cap-extra.png")]);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(items).toHaveCount(10);
  // 서버가 상한 초과로 거절했으면 이 문구가 그 첨부의 자리에 남는다. DELETE 가 불렸다면 나오지 않는다.
  await expect(page.getByText("too many images are waiting to be sent")).toHaveCount(0);
});

test("새 대화에서 사진을 고르고 글과 함께 보내면 사진이 보이고 대화 제목이 그 글이다", async ({
  page,
}, testInfo) => {
  await openNewConversation(page, testInfo);

  await page.getByTestId("attachment-input").setInputFiles([pngFile("send.png")]);
  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(1);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);

  const text = `사진 전송 검사 ${testInfo.project.name}`;
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();

  const userMessage = page.getByTestId("user-message").last();
  await expectImageLoaded(userMessage.getByTestId("message-attachment"));

  await openConversationListDrawer(page);
  await expect(
    page
      .getByRole("complementary", { name: "사이드바" })
      .getByRole("link", { name: new RegExp(text) })
      .first(),
  ).toBeVisible();
});

test("11장을 고르면 10장만 올라가고 넘은 것을 알린다", async ({ page }, testInfo) => {
  await openNewConversation(page, testInfo);

  const files = Array.from({ length: 11 }, (_, index) => pngFile(`over-${index}.png`));
  await page.getByTestId("attachment-input").setInputFiles(files);

  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(10);
  await expect(page.getByTestId("attachment-notice")).toContainText("10장까지");
  // 개수만 세면 통과하지만 실제로는 열 장 모두가 올라가는 중 오류로 빠졌을 수도 있다.
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(10);
  await expect(page.getByText("올리지 못했습니다")).toHaveCount(0);
});

test("이미 여섯 장을 붙인 뒤 여섯 장을 더 고르면 네 장만 올라가고 알린다", async ({ page }, testInfo) => {
  await openNewConversation(page, testInfo);

  await page.getByTestId("attachment-input").setInputFiles(
    Array.from({ length: 6 }, (_, index) => pngFile(`slot-${index}.png`)),
  );
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(6);

  await page.getByTestId("attachment-input").setInputFiles(
    Array.from({ length: 6 }, (_, index) => pngFile(`slot-more-${index}.png`)),
  );

  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(10);
  await expect(page.getByTestId("attachment-notice")).toContainText("10장까지");
});

test("10MB 를 넘는 파일은 올라가지 않고 알린다", async ({ page }, testInfo) => {
  await openNewConversation(page, testInfo);

  const big = { name: "big.png", mimeType: "image/png", buffer: Buffer.alloc(10 * 1024 * 1024 + 1, 1) };
  await page.getByTestId("attachment-input").setInputFiles([big]);

  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);
  await expect(page.getByTestId("attachment-notice")).toContainText("10MB");
});

test("이미지가 아닌 파일은 고르기에서 걸린다", async ({ page }, testInfo) => {
  await openNewConversation(page, testInfo);

  await page.getByTestId("attachment-input").setInputFiles([
    { name: "notes.txt", mimeType: "text/plain", buffer: Buffer.from("hello") },
  ]);

  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);
});

test("흐름이 붙은 에이전트의 대화에는 사진 단추가 없다", async ({ page }, testInfo) => {
  // 방금 만든 대화가 아니라, 흐름 에이전트로 이미 만들어진 기존 대화를 목록에서 열어 확인한다.
  await openNewConversation(page, testInfo);
  await page.getByRole("radio", { name: "흐름 비서" }).click();
  const text = `흐름 대화 사진 단추 검사 ${testInfo.project.name}`;
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });

  await page.reload();
  await openConversationListDrawer(page);
  await page
    .getByRole("complementary", { name: "사이드바" })
    .getByRole("link", { name: new RegExp(text) })
    .first()
    .click();

  await expect(page.getByRole("button", { name: "보내기" })).toBeVisible();
  await expect(page.getByRole("button", { name: "사진 첨부" })).toBeHidden();
});

test("보관 기간이 지난 첨부는 자리를 남기고 알린다", async ({ page }, testInfo) => {
  await openNewConversation(page, testInfo);

  const uploadResponse = page.waitForResponse(
    (response) =>
      /\/api\/chat\/conversations\/\d+\/attachments$/.test(response.url())
      && response.request().method() === "POST",
  );
  await page.getByTestId("attachment-input").setInputFiles([pngFile("expire.png")]);
  const response = await uploadResponse;
  const conversationId = response.url().match(/conversations\/(\d+)\/attachments$/)?.[1];
  expect(conversationId).toBeTruthy();
  const attachment = (await response.json()) as { id: number };

  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);

  const text = `만료 검사 ${testInfo.project.name}`;
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
  await expectImageLoaded(page.getByTestId("message-attachment").last());

  const deleteResponse = await page.request.delete(
    `/api/chat/conversations/${conversationId}/attachments/${attachment.id}`,
  );
  expect(deleteResponse.ok()).toBeTruthy();

  await page.reload();
  await openConversationListDrawer(page);
  await page
    .getByRole("complementary", { name: "사이드바" })
    .getByRole("link", { name: new RegExp(text) })
    .first()
    .click();

  await expect(page.getByTestId("message-attachment-gone").last()).toBeVisible();
  await expect(page.getByText("보관 기간이 지나 볼 수 없습니다.").last()).toBeVisible();
});

test("대화를 바꾸면 미리보기가 비워진다", async ({ page }, testInfo) => {
  // 먼저 대화 하나를 만들어 목록에 남긴다.
  await openNewConversation(page, testInfo);
  const firstText = `전환 검사 원래 대화 ${testInfo.project.name}`;
  await page.getByRole("textbox", { name: "메시지" }).fill(firstText);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });

  // 새 대화를 열고 사진을 고른다. 아직 보내지 않는다.
  await openNewConversation(page, testInfo);
  await page.getByTestId("attachment-input").setInputFiles([pngFile("switch-away.png")]);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews")).toBeVisible();

  // 원래 대화로 돌아가면 미리보기가 비워져야 한다. 다른 대화로 옮겨가면 안 된다.
  await openConversationListDrawer(page);
  await page
    .getByRole("complementary", { name: "사이드바" })
    .getByRole("link", { name: new RegExp(firstText) })
    .first()
    .click();

  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);
});

/**
 * 목록에서 제목이 `text` 인 대화를 열고 그 번호를 돌려준다.
 *
 * <p>번호는 목록 API 에서 제목으로 찾는다. 앞선 전송이 늦게 부른 메시지 읽기가 섞일 수 있어, 아무 메시지
 * 요청이나 기다리지 않고 그 번호의 메시지 응답만 기다린다.
 */
async function selectConversationByText(page: Page, text: string): Promise<number> {
  const listed = (await (await page.request.get("/api/chat/conversations")).json()) as Array<{
    id: number;
    title: string;
  }>;
  const conversation = listed.find((candidate) => candidate.title.includes(text));
  if (!conversation) throw new Error(`목록에 「${text}」 대화가 없다`);
  const id = conversation.id;

  await openConversationListDrawer(page);
  const loaded = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/chat/conversations/${id}/messages`)
      && response.request().method() === "GET",
  );
  await page
    .getByRole("complementary", { name: "사이드바" })
    .getByRole("link", { name: new RegExp(text) })
    .first()
    .click();
  await loaded;
  return id;
}

/** 글만 보내 대화 하나를 만든다. */
async function createConversationWithText(page: Page, testInfo: TestInfo, text: string): Promise<void> {
  await openNewConversation(page, testInfo);
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
}

/** 글을 보내고, 그 요청이 어느 대화로 갔는지와 사진을 실었는지를 돌려준다. */
async function sendTextAndReadRequest(
  page: Page,
  text: string,
): Promise<{ conversationId: number | null; attachmentIds: number[] }> {
  const sent = page.waitForRequest(
    (request) => /\/api\/chat(\/stream)?$/.test(request.url()) && request.method() === "POST",
  );
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
  const body = (await sent).postDataJSON() as { conversationId: number | null; attachmentIds: number[] };
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
  return body;
}

test("올리는 중에 대화를 바꾸면 그 사진은 새 대화에 붙지 않고 원래 대화의 상한도 차지하지 않는다", async ({
  page,
}, testInfo) => {
  const suffix = `${testInfo.project.name} ${Date.now()}`;
  const originText = `올리는 중 전환 원래 ${suffix}`;
  const targetText = `올리는 중 전환 대상 ${suffix}`;
  await createConversationWithText(page, testInfo, originText);
  await createConversationWithText(page, testInfo, targetText);

  const originId = await selectConversationByText(page, originText);

  let targetId = 0;
  const heldUploads: Array<() => void> = [];
  await page.route("**/api/chat/conversations/*/attachments", async (route) => {
    if (route.request().method() !== "POST") {
      await route.continue();
      return;
    }
    await new Promise<void>((resolve) => heldUploads.push(resolve));
    await route.continue();
  });

  let deletedCount = 0;
  page.on("response", (response) => {
    if (
      response.request().method() === "DELETE"
      && new RegExp(`/api/chat/conversations/${originId}/attachments/\\d+$`).test(response.url())
      && response.ok()
    ) {
      deletedCount += 1;
    }
  });

  try {
    await page.getByTestId("attachment-input").setInputFiles(
      Array.from({ length: 10 }, (_, index) => pngFile(`held-${index}.png`)),
    );
    await expect.poll(() => heldUploads.length).toBe(10);

    targetId = await selectConversationByText(page, targetText);
    expect(targetId).not.toBe(originId);

    for (const release of heldUploads) release();
    // 사라진 입력창이 응답을 받은 뒤 열 장을 모두 서버에서 지워야 원래 대화의 상한이 빈다.
    await expect.poll(() => deletedCount, { timeout: 15_000 }).toBe(10);
  } finally {
    await page.unroute("**/api/chat/conversations/*/attachments");
  }

  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);

  // 다시 고르지 않고 보낸다. 사라진 입력창이 선택을 바꿨다면 이 글이 다른 대화로 간다.
  const sent = await sendTextAndReadRequest(page, `전환 뒤 보낸 글 ${suffix}`);
  expect(sent.conversationId).toBe(targetId);
  expect(sent.attachmentIds).toEqual([]);
  await expect(page.getByTestId("user-message").last().getByTestId("message-attachment")).toHaveCount(0);

  await selectConversationByText(page, originText);
  await page.getByTestId("attachment-input").setInputFiles(
    Array.from({ length: 10 }, (_, index) => pngFile(`again-${index}.png`)),
  );
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(10);
  await expect(page.getByText("too many images are waiting to be sent")).toHaveCount(0);
  await expect(page.getByText("올리지 못했습니다")).toHaveCount(0);
});

test("빈 대화를 만드는 중에 대화를 바꾸면 고른 대화가 그대로 남는다", async ({ page }, testInfo) => {
  const originText = `대화 생성 중 전환 ${testInfo.project.name} ${Date.now()}`;
  await createConversationWithText(page, testInfo, originText);
  await openNewConversation(page, testInfo);

  const heldCreates: Array<() => void> = [];
  await page.route("**/api/chat/conversations", async (route) => {
    if (route.request().method() !== "POST") {
      await route.continue();
      return;
    }
    await new Promise<void>((resolve) => heldCreates.push(resolve));
    await route.continue();
  });

  let originId = 0;
  try {
    const created = page.waitForResponse(
      (response) => /\/api\/chat\/conversations$/.test(response.url()) && response.request().method() === "POST",
    );
    await page.getByTestId("attachment-input").setInputFiles([pngFile("creating.png")]);
    await expect.poll(() => heldCreates.length).toBe(1);

    originId = await selectConversationByText(page, originText);
    for (const release of heldCreates) release();
    await created;
  } finally {
    await page.unroute("**/api/chat/conversations");
  }

  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);
  const sent = await sendTextAndReadRequest(page, `생성 중 전환 뒤 보낸 글 ${testInfo.project.name}`);
  expect(sent.conversationId).toBe(originId);
});
