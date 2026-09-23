import { expect, test } from "./fixtures.ts";
import type { Page, TestInfo } from "../../web/node_modules/@playwright/test/index.js";

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
  if (testInfo.project.name === "mobile") {
    await page.getByRole("button", { name: "대화 목록 열기" }).click();
  }
  await page.getByRole("button", { name: "새 대화", exact: true }).click();
}

async function openConversationListDrawer(page: Page): Promise<void> {
  const opener = page.getByRole("button", { name: "대화 목록 열기" });
  if (await opener.isVisible()) await opener.click();
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
    await page.getByPlaceholder("무엇을 도와줄까요").fill("사진 미리보기 검사");
    await expect(send).toBeEnabled();

    await page.getByTestId("attachment-input").setInputFiles([pngFile("photo.png")]);

    await expect(page.getByTestId("attachment-previews")).toBeVisible();
    await expect(page.getByTestId("attachment-uploading")).toBeVisible();
    await expect(send).toBeDisabled();

    releaseUpload?.();
    await expect(page.getByTestId("attachment-uploading")).toBeHidden();
    await expect(send).toBeEnabled();
  } finally {
    await page.unroute("**/api/chat/conversations/*/attachments");
  }
});

test("미리보기의 지우는 단추를 누르면 그 사진만 빠진다", async ({ page }, testInfo) => {
  await openNewConversation(page, testInfo);

  await page.getByTestId("attachment-input").setInputFiles([pngFile("a.png"), pngFile("b.png")]);

  const items = page.getByTestId("attachment-previews").locator("> div");
  await expect(items).toHaveCount(2);

  await items.first().getByRole("button", { name: "사진 지우기" }).click();
  await expect(items).toHaveCount(1);
});

test("새 대화에서 사진을 고르고 글과 함께 보내면 사진이 보이고 대화 제목이 그 글이다", async ({
  page,
}, testInfo) => {
  await openNewConversation(page, testInfo);

  await page.getByTestId("attachment-input").setInputFiles([pngFile("send.png")]);
  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(1);
  await expect(page.getByTestId("attachment-uploading")).toBeHidden();

  const text = `사진 전송 검사 ${testInfo.project.name}`;
  await page.getByPlaceholder("무엇을 도와줄까요").fill(text);
  await page.getByRole("button", { name: "보내기" }).click();

  const userMessage = page.getByTestId("user-message").last();
  await expect(userMessage.getByTestId("message-attachment")).toBeVisible();

  await openConversationListDrawer(page);
  await expect(
    page
      .getByRole("complementary", { name: "대화 목록" })
      .getByRole("button", { name: new RegExp(text) })
      .first(),
  ).toBeVisible();
});

test("11장을 고르면 10장만 올라가고 넘은 것을 알린다", async ({ page }, testInfo) => {
  await openNewConversation(page, testInfo);

  const files = Array.from({ length: 11 }, (_, index) => pngFile(`over-${index}.png`));
  await page.getByTestId("attachment-input").setInputFiles(files);

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

test("흐름이 붙은 에이전트의 대화에는 사진 단추가 없다", async ({ page }) => {
  await page.goto("/");
  await openConversationListDrawer(page);
  await page.getByRole("button", { name: "새 대화", exact: true }).click();
  await page.getByRole("combobox").selectOption({ label: "흐름 비서" });

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

  await expect(page.getByTestId("attachment-uploading")).toBeHidden();

  const text = `만료 검사 ${testInfo.project.name}`;
  await page.getByPlaceholder("무엇을 도와줄까요").fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByTestId("message-attachment").last()).toBeVisible();

  const deleteResponse = await page.request.delete(
    `/api/chat/conversations/${conversationId}/attachments/${attachment.id}`,
  );
  expect(deleteResponse.ok()).toBeTruthy();

  await page.reload();
  await openConversationListDrawer(page);
  await page
    .getByRole("complementary", { name: "대화 목록" })
    .getByRole("button", { name: new RegExp(text) })
    .first()
    .click();

  await expect(page.getByTestId("message-attachment-gone").last()).toBeVisible();
  await expect(page.getByText("보관 기간이 지나 볼 수 없습니다.").last()).toBeVisible();
});
