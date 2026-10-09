import { expect, test } from "./fixtures.ts";
import type {
  Page,
  Route,
} from "../../web/node_modules/@playwright/test/index.js";

const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);
const photo = (name: string) => ({ name, mimeType: "image/png", buffer: PNG });

const UPLOAD_ROUTE = "**/api/chat/conversations/*/attachments";
const send = (page: Page) =>
  page.getByTestId("composer-shell").getByRole("button", { name: "보내기" });
const input = (page: Page) => page.getByRole("textbox", { name: "메시지" });

test("사진 네 장을 천천히 올려도 보내자마자 입력창이 비고 내 메시지에 붙는다", async ({
  page,
  hermes,
}) => {
  await page.goto("/");
  const uploads: Route[] = [];
  await page.route(UPLOAD_ROUTE, async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    uploads.push(route);
  });
  let sends = 0;
  page.on("request", (request) => {
    if (request.url().endsWith("/api/chat/stream")) sends++;
  });
  await page.getByTestId("attachment-input").setInputFiles(
    [2, 2.5, 3, 3.5].map((mb, index) => ({
      ...photo(`slow-${index}.png`),
      buffer: Buffer.concat([PNG, Buffer.alloc(mb * 1024 * 1024 - PNG.length)]),
    })),
  );
  await expect.poll(() => uploads.length).toBe(4);
  await input(page).fill("사진 네 장을 봐 주세요");
  await expect(send(page)).toBeEnabled();
  // 같은 브라우저 사건에서 연달아 보내도 사진 메시지가 한 번만 예약돼야 한다.
  await page.getByTestId("composer-shell").evaluate((shell) => {
    const form = shell.closest("form")!;
    form.requestSubmit();
    form.requestSubmit();
  });
  await expect(input(page)).toHaveValue("");
  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);
  const message = page.getByTestId("user-message");
  await expect(message).toContainText("사진 네 장을 봐 주세요");
  await expect(message.getByTestId("outgoing-uploading")).toHaveCount(4);
  await expect
    .poll(() =>
      message
        .getByTestId("outgoing-thumbnail")
        .evaluateAll(
          (images) =>
            images.filter(
              (image) => (image as HTMLImageElement).naturalWidth > 0,
            ).length,
        ),
    )
    .toBe(4);
  expect(sends).toBe(0);
  await input(page).fill("다음에 쓸 글");
  for (const route of uploads.slice(0, 3)) await route.continue();
  await expect(message.getByTestId("outgoing-uploading")).toHaveCount(1);
  expect(sends).toBe(0);
  await hermes.holdNextRun();
  await uploads[3]!.continue();
  await hermes.waitForHeldRun();
  await expect(message.getByTestId("message-attachment")).toHaveCount(4);
  await expect(page.getByTestId("outgoing-attachment")).toHaveCount(0);
  await expect(input(page)).toHaveValue("다음에 쓸 글");
  expect(sends).toBe(1);
  // 답을 만드는 중에는 사진을 새로 붙이지 못하지만 글은 기존 대기 경로로 보낸다.
  await expect(page.getByTestId("attachment-input")).toBeDisabled();
  await send(page).click();
  await expect(page.getByTestId("pending-item")).toContainText("다음에 쓸 글");
  await expect(input(page)).toHaveValue("");
  await hermes.releaseHeldRun();
  await expect(page.getByTestId("assistant-message")).toHaveCount(2, {
    timeout: 30_000,
  });
  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);
  await expect(page.getByTestId("message-attachment")).toHaveCount(4);
  await page.reload();
  await expect(page.getByTestId("message-attachment")).toHaveCount(4);
});

test("업로드 실패는 내 메시지에서 그 사진만 다시 시도한다", async ({
  page,
}) => {
  await page.goto("/");
  let attempts = 0;
  await page.route(UPLOAD_ROUTE, (route) => {
    if (route.request().method() !== "POST") return route.continue();
    attempts++;
    if (attempts === 1)
      return route.fulfill({
        status: 500,
        contentType: "application/json",
        body: JSON.stringify({
          code: "INTERNAL_ERROR",
          message: "사진을 올리지 못했어요.",
        }),
      });
    return route.continue();
  });
  await page
    .getByTestId("attachment-input")
    .setInputFiles([photo("retry.png")]);
  await expect(page.getByTestId("attachment-previews")).toContainText(
    "사진을 올리지 못했어요",
  );
  await input(page).fill("실패한 사진을 다시 보내요");
  await send(page).click();
  await expect(input(page)).toHaveValue("");
  const message = page.getByTestId("user-message");
  await expect(
    message.getByRole("button", { name: "다시 시도", exact: true }),
  ).toBeVisible();
  await expect(page.getByTestId("assistant-message")).toHaveCount(0);
  await message.getByRole("button", { name: "다시 시도", exact: true }).click();
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect(message.getByTestId("message-attachment")).toHaveCount(1);
  expect(attempts).toBe(2);
  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);
});

test("실패한 사진을 빼고 보내면 성공한 사진만 전송한다", async ({ page }) => {
  await page.goto("/");
  await page.route(UPLOAD_ROUTE, (route) => {
    if (route.request().method() !== "POST") return route.continue();
    if (
      route
        .request()
        .postDataBuffer()
        ?.includes(Buffer.from('filename="bad.png"'))
    )
      return route.fulfill({
        status: 500,
        contentType: "application/json",
        body: JSON.stringify({ code: "INTERNAL_ERROR" }),
      });
    return route.continue();
  });
  await page
    .getByTestId("attachment-input")
    .setInputFiles([photo("good.png"), photo("bad.png")]);
  await expect(
    page.getByTestId("attachment-previews").locator("> div"),
  ).toHaveCount(2);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await input(page).fill("성공한 사진만 보내요");
  await send(page).click();
  const sent = page.waitForRequest("**/api/chat/stream");
  await page
    .getByTestId("user-message")
    .getByRole("button", { name: "빼고 보내기" })
    .click();
  expect((await sent).postDataJSON().attachmentIds).toHaveLength(1);
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect(page.getByTestId("message-attachment")).toHaveAttribute(
    "alt",
    "good.png",
  );
});

test("전송을 거절하면 말풍선에서 다시 보내고 새로 쓴 글을 보존한다", async ({
  page,
}) => {
  await page.goto("/");
  let attempts = 0;
  await page.route("**/api/chat/stream", (route) => {
    attempts++;
    if (attempts === 1)
      return route.fulfill({
        status: 400,
        contentType: "application/json",
        body: JSON.stringify({
          code: "INTERNAL_ERROR",
          message: "메시지를 보내지 못했어요.",
        }),
      });
    return route.continue();
  });
  await page
    .getByTestId("attachment-input")
    .setInputFiles([photo("send-retry.png")]);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await input(page).fill("다시 보낼 사진 메시지");
  await send(page).click();
  await expect(
    page
      .getByTestId("user-message")
      .getByRole("button", { name: "다시 보내기" }),
  ).toBeVisible();
  await input(page).fill("새로 쓴 글");
  await page
    .getByTestId("user-message")
    .getByRole("button", { name: "다시 보내기" })
    .click();
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect(page.getByTestId("user-message")).toHaveCount(1);
  await expect(input(page)).toHaveValue("새로 쓴 글");
  expect(attempts).toBe(2);
});

test("올리는 중 페이지를 떠나려 하면 경고하고 취소하면 사진을 보존한다", async ({
  page,
}) => {
  await page.goto("/");
  let upload: Route | undefined;
  await page.route(UPLOAD_ROUTE, (route) => {
    if (route.request().method() !== "POST") return route.continue();
    upload = route;
  });
  await page
    .getByTestId("attachment-input")
    .setInputFiles([photo("leave.png")]);
  await expect.poll(() => upload !== undefined).toBe(true);
  await input(page).fill("떠나기 경고 검사");
  await send(page).click();
  await expect(page.getByTestId("outgoing-uploading")).toHaveCount(1);
  const currentUrl = page.url();
  const leave = page
    .getByRole("link", { name: "새 대화", exact: true })
    .first();
  if (!(await leave.isVisible()))
    await page.getByRole("button", { name: "사이드바 열기" }).click();
  const dialog = page.waitForEvent("dialog");
  const click = leave.click();
  const warning = await dialog;
  expect(warning.message()).toContain("아직 보내지 못한 사진");
  await warning.dismiss();
  await click;
  await expect(page).toHaveURL(currentUrl);
  await expect(page.getByTestId("outgoing-uploading")).toHaveCount(1);
  expect(
    await page.evaluate(() =>
      window.dispatchEvent(new Event("beforeunload", { cancelable: true })),
    ),
  ).toBe(false);
  await upload!.continue();
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
});

test("올리는 중 뒤로 가기를 취소하면 주소와 메시지가 그대로 남는다", async ({
  page,
}) => {
  await page.goto("/agents");
  await page.goto("/");
  let upload: Route | undefined;
  await page.route(UPLOAD_ROUTE, (route) => {
    if (route.request().method() !== "POST") return route.continue();
    upload = route;
  });
  await page.getByTestId("attachment-input").setInputFiles([photo("back.png")]);
  await expect.poll(() => upload !== undefined).toBe(true);
  await input(page).fill("뒤로 가기 경고 검사");
  await send(page).click();
  await expect(page.getByTestId("outgoing-uploading")).toHaveCount(1);
  const currentUrl = page.url();
  const dialog = page.waitForEvent("dialog");
  const back = page.evaluate(() => window.history.back());
  await (await dialog).dismiss();
  await back;
  await expect(page).toHaveURL(currentUrl);
  await expect(page.getByTestId("outgoing-uploading")).toHaveCount(1);
  await upload!.continue();
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
});

test("떠나기를 확인하면 아직 묶이지 않은 사진과 늦게 끝난 업로드를 정리한다", async ({
  page,
}) => {
  await page.goto("/");
  let upload: Route | undefined;
  let deleted = 0;
  page.on("response", (response) => {
    if (
      response.request().method() === "DELETE" &&
      /\/attachments\/\d+$/.test(response.url()) &&
      response.ok()
    )
      deleted++;
  });
  await page.route(UPLOAD_ROUTE, (route) => {
    if (
      route
        .request()
        .postDataBuffer()
        ?.includes(Buffer.from('filename="late.png"'))
    ) {
      upload = route;
      return;
    }
    return route.continue();
  });
  await page
    .getByTestId("attachment-input")
    .setInputFiles([photo("done.png"), photo("late.png")]);
  await expect.poll(() => upload !== undefined).toBe(true);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(1);
  await input(page).fill("떠나기 전에 보낸 사진");
  await send(page).click();
  await expect(page.getByTestId("outgoing-uploading")).toHaveCount(1);
  const leave = page
    .getByRole("link", { name: "새 대화", exact: true })
    .first();
  const dialog = page.waitForEvent("dialog");
  const click = leave.click();
  await (await dialog).accept();
  await click;
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId("outgoing-attachment")).toHaveCount(0);
  await expect.poll(() => deleted).toBe(1);
  await upload!.continue();
  await expect.poll(() => deleted).toBe(2);
  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);
});
