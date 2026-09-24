import { expect, test } from "./fixtures.ts";
import type { Page, TestInfo } from "../../web/node_modules/@playwright/test/index.js";

const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

async function openSidebar(page: Page, testInfo: TestInfo): Promise<void> {
  if (testInfo.project.name === "mobile") {
    await page.getByRole("button", { name: "사이드바 열기" }).click();
  }
}

async function newConversation(page: Page, testInfo: TestInfo): Promise<void> {
  await openSidebar(page, testInfo);
  await page.getByTestId("new-conversation-link").click();
  await expect(page).toHaveURL(/\/$/);
}

async function send(page: Page, text: string): Promise<void> {
  await page.getByPlaceholder("무엇을 도와줄까요").fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
}

async function createConversation(page: Page, title: string): Promise<number> {
  const response = await page.request.post("/api/chat", { data: { text: title, agentCode: "browser" } });
  expect(response.ok()).toBeTruthy();
  return ((await response.json()) as { conversationId: number }).conversationId;
}

function conversationNav(page: Page) {
  return page.getByRole("navigation", { name: "대화 목록" });
}

test("새 대화에서 보내면 주소와 목록이 바뀌고 다시 열 수 있다", async ({ page }, testInfo) => {
  await page.goto("/");
  const title = `주소 검사 ${testInfo.project.name} ${Date.now()}`;
  await send(page, title);
  await expect(page).toHaveURL(/\/c\/\d+$/);
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
  const url = page.url();
  await openSidebar(page, testInfo);
  await expect(page.getByRole("navigation", { name: "대화 목록" })
    .getByRole("heading", { name: "오늘" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "대화 목록" }).getByRole("link", { name: title })).toBeVisible();
  await page.reload();
  await expect(page).toHaveURL(url);
  await expect(page.getByTestId("user-message").last()).toContainText(title);
});

test("시작 사건 뒤 새 대화를 누르면 기존 메시지와 입력이 비고 다음 대화가 생긴다", async ({ page }, testInfo) => {
  await page.goto("/");
  await send(page, `첫 대화 ${testInfo.project.name} ${Date.now()}`);
  await expect(page).toHaveURL(/\/c\/\d+$/);
  const firstUrl = page.url();
  await newConversation(page, testInfo);
  await expect(page.getByTestId("user-message")).toHaveCount(0);
  await expect(page.getByPlaceholder("무엇을 도와줄까요")).toHaveValue("");
  await send(page, `다음 대화 ${testInfo.project.name} ${Date.now()}`);
  await expect(page).toHaveURL(/\/c\/\d+$/);
  expect(page.url()).not.toBe(firstUrl);
});

test("존재하지 않는 주소는 새 대화 링크를 보인다", async ({ page }) => {
  await page.goto("/c/999999");
  await expect(page.getByTestId("conversation-not-found")).toBeVisible();
  await expect(page.getByTestId("conversation-not-found").getByRole("link", { name: "새 대화" }))
    .toHaveAttribute("href", "/");
});

test("사이드바는 일반 화면에 있고 로그인 화면에는 없다", async ({ page, context }, testInfo) => {
  await page.goto("/usage");
  await openSidebar(page, testInfo);
  await expect(page.getByRole("complementary", { name: "사이드바" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth))
    .toBeLessThanOrEqual(page.viewportSize()?.width ?? 0);
  await context.clearCookies();
  await page.goto("/signin");
  await expect(page.getByRole("complementary", { name: "사이드바" })).toHaveCount(0);
});

test("사진을 먼저 올려 번호가 생겨도 미리보기가 남고 다른 대화를 고르면 서버에서 지운다", async ({ page }, testInfo) => {
  await page.goto("/");
  const first = await page.request.post("/api/chat", {
    data: { text: `돌아갈 대화 ${testInfo.project.name} ${Date.now()}`, agentCode: "browser" },
  });
  expect(first.ok()).toBeTruthy();
  const firstId = ((await first.json()) as { conversationId: number }).conversationId;
  await page.reload();
  await page.getByTestId("attachment-input").setInputFiles([
    { name: "shell.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  await expect(page).toHaveURL(/\/c\/\d+$/);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews")).toBeVisible();
  const uploadedId = Number(page.url().match(/\/c\/(\d+)$/)?.[1]);
  const listed = await page.request.get("/api/chat/conversations");
  expect(listed.ok()).toBeTruthy();
  expect((await listed.json() as { id: number; title: string }[])
    .some((item) => item.id === uploadedId && item.title === "")).toBeTruthy();

  const deleted = page.waitForResponse((response) =>
    response.request().method() === "DELETE"
    && new RegExp(`/api/chat/conversations/${uploadedId}/attachments/\\d+$`).test(response.url()));
  await openSidebar(page, testInfo);
  await page.getByRole("navigation", { name: "대화 목록" })
    .locator(`a[href="/c/${firstId}"]`).click();
  await deleted;
  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);
});

test("서랍은 Esc 한 번에 닫힌다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile");
  await page.goto("/");
  await openSidebar(page, testInfo);
  const sidebar = page.getByRole("complementary", { name: "사이드바" });
  await expect.poll(async () => (await sidebar.boundingBox())?.x ?? -1).toBeGreaterThanOrEqual(0);
  await page.keyboard.press("Escape");
  await expect.poll(async () => (await sidebar.boundingBox())?.x ?? 0).toBeLessThan(0);
});

test("started 뒤 실패하면 입력은 비고 저장된 질문은 하나다", async ({ page }) => {
  await page.goto("/");
  await page.route("**/api/chat/stream", async (route) => {
    const response = await route.fetch();
    const raw = await response.text();
    const started = raw.split("\n").find((line) => line.startsWith("data:")
      && JSON.parse(line.slice(5).trim()).type === "started");
    if (!started) throw new Error("started 사건을 찾지 못했다");
    await route.fulfill({ status: 200, contentType: "text/event-stream", body:
      `${started}\n\ndata: {"type":"error","code":"HERMES_RUN_FAILED","message":"failed"}\n\n` });
  });
  const text = `시작 뒤 실패 ${Date.now()}`;
  await send(page, text);
  await expect(page).toHaveURL(/\/c\/\d+$/);
  await expect(page.getByTestId("turn-error")).toBeVisible();
  await expect(page.getByPlaceholder("무엇을 도와줄까요")).toHaveValue("");
  await page.reload();
  await expect(page.getByTestId("user-message")).toHaveCount(1);
  await expect(page.getByTestId("user-message").first()).toContainText(text);
});

test("대화 이름을 바꾸고 새로 고쳐도 유지한다", async ({ page }, testInfo) => {
  await page.goto("/");
  const original = `이름 변경 전 ${testInfo.project.name} ${Date.now()}`;
  const changed = `이름 변경 후 ${testInfo.project.name} ${Date.now()}`;
  const id = await createConversation(page, original);
  await page.reload();
  await openSidebar(page, testInfo);
  await conversationNav(page).getByRole("button", { name: `${original} 메뉴` }).click();
  await conversationNav(page).getByRole("menuitem", { name: "이름 바꾸기" }).click();
  const input = conversationNav(page).getByRole("textbox", { name: "대화 이름" });
  await input.fill(changed);
  await input.press("Enter");
  await expect(conversationNav(page).locator(`a[href="/c/${id}"]`)).toHaveText(changed);
  await page.reload();
  await openSidebar(page, testInfo);
  await expect(conversationNav(page).locator(`a[href="/c/${id}"]`)).toHaveText(changed);
});

test("대화 이름의 Esc 와 빈 이름은 원래 이름을 유지한다", async ({ page }, testInfo) => {
  await page.goto("/");
  const title = `이름 취소 ${testInfo.project.name} ${Date.now()}`;
  const id = await createConversation(page, title);
  await page.reload();
  await openSidebar(page, testInfo);
  const nav = conversationNav(page);
  await nav.getByRole("button", { name: `${title} 메뉴` }).click();
  await nav.getByRole("menuitem", { name: "이름 바꾸기" }).click();
  await nav.getByRole("textbox", { name: "대화 이름" }).fill("바뀌지 않을 이름");
  await nav.getByRole("textbox", { name: "대화 이름" }).press("Escape");
  await expect(nav.locator(`a[href="/c/${id}"]`)).toHaveText(title);
  await nav.getByRole("button", { name: `${title} 메뉴` }).click();
  await nav.getByRole("menuitem", { name: "이름 바꾸기" }).click();
  await nav.getByRole("textbox", { name: "대화 이름" }).fill("   ");
  await nav.getByRole("textbox", { name: "대화 이름" }).press("Enter");
  await expect(nav.locator(`a[href="/c/${id}"]`)).toHaveText(title);
});

test("열어 둔 대화를 지우면 홈으로 가고 다시 열 수 없다", async ({ page }, testInfo) => {
  await page.goto("/");
  const title = `삭제 성공 ${testInfo.project.name} ${Date.now()}`;
  const id = await createConversation(page, title);
  await page.goto(`/c/${id}`);
  await openSidebar(page, testInfo);
  await conversationNav(page).getByRole("button", { name: `${title} 메뉴` }).click();
  await conversationNav(page).getByRole("menuitem", { name: "지우기" }).click();
  const dialog = page.getByRole("dialog", { name: "대화 지우기" });
  await expect(dialog).toContainText("사용량 기록은 남는다");
  await dialog.getByRole("button", { name: "지우기" }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(conversationNav(page).locator(`a[href="/c/${id}"]`)).toHaveCount(0);
  await page.goto(`/c/${id}`);
  await expect(page.getByTestId("conversation-not-found")).toBeVisible();
});

test("지우기가 실패하면 대화가 남고 오류가 보인다", async ({ page }, testInfo) => {
  await page.goto("/");
  const title = `삭제 실패 ${testInfo.project.name} ${Date.now()}`;
  const id = await createConversation(page, title);
  await page.reload();
  await page.route(`**/api/chat/conversations/${id}`, (route) => route.request().method() === "DELETE"
    ? route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ code: "INTERNAL_ERROR", message: "지우기 실패" }) })
    : route.continue());
  await openSidebar(page, testInfo);
  await conversationNav(page).getByRole("button", { name: `${title} 메뉴` }).click();
  await conversationNav(page).getByRole("menuitem", { name: "지우기" }).click();
  await page.getByRole("dialog", { name: "대화 지우기" }).getByRole("button", { name: "지우기" }).click();
  await expect(conversationNav(page).locator(`a[href="/c/${id}"]`)).toBeVisible();
  await expect(conversationNav(page).getByRole("alert")).toContainText("지우기 실패");
});

test("제목 검색은 맞는 대화만 남기고 날짜 묶음을 유지한다", async ({ page }, testInfo) => {
  await page.goto("/");
  const unique = Date.now();
  const first = await createConversation(page, `찾을 대화 ${testInfo.project.name} ${unique}`);
  const second = await createConversation(page, `다른 제목 ${testInfo.project.name} ${unique}`);
  await page.reload();
  await openSidebar(page, testInfo);
  await page.getByRole("searchbox", { name: "대화 검색" }).fill("찾을 대화");
  await expect(conversationNav(page).locator(`a[href="/c/${first}"]`)).toBeVisible();
  await expect(conversationNav(page).locator(`a[href="/c/${second}"]`)).toHaveCount(0);
  await expect(conversationNav(page).getByRole("heading", { name: "오늘" })).toBeVisible();
  await page.getByRole("searchbox", { name: "대화 검색" }).fill("없는 제목 123456");
  await expect(conversationNav(page)).toContainText("맞는 대화가 없다");
});

test("넓은 화면에서 사이드바를 접고 새로 고쳐도 유지한다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile");
  await page.goto("/");
  await page.getByRole("button", { name: "사이드바 접기" }).click();
  await expect(page.getByRole("button", { name: "사이드바 펴기" })).toBeVisible();
  await expect(page.getByRole("complementary", { name: "사이드바" })).toBeHidden();
  await page.reload();
  await expect(page.getByRole("button", { name: "사이드바 펴기" })).toBeVisible();
  await page.getByRole("button", { name: "사이드바 펴기" }).click();
  await expect(page.getByRole("complementary", { name: "사이드바" })).toBeVisible();
  await page.keyboard.press("Control+Shift+S");
  await expect(page.getByRole("button", { name: "사이드바 펴기" })).toBeVisible();
  await page.keyboard.press("Control+Shift+S");
  await expect(page.getByRole("complementary", { name: "사이드바" })).toBeVisible();
});

test("단축키로 홈으로 가고 검색에 초점을 준다", async ({ page }, testInfo) => {
  await page.goto("/");
  await send(page, `단축키 ${testInfo.project.name} ${Date.now()}`);
  await expect(page).toHaveURL(/\/c\/\d+$/);
  await page.keyboard.press("Control+Shift+O");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId("user-message")).toHaveCount(0);
  await expect(page.getByPlaceholder("무엇을 도와줄까요")).toHaveValue("");
  await page.keyboard.press("Control+K");
  await expect(page.getByRole("searchbox", { name: "대화 검색" })).toBeFocused();
});

test("모바일 이름 입력과 지우기 창의 Esc 는 서랍을 닫지 않는다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile");
  await page.goto("/");
  const title = `Esc 격리 ${Date.now()}`;
  const id = await createConversation(page, title);
  await page.goto(`/c/${id}`);
  await openSidebar(page, testInfo);
  const nav = conversationNav(page);
  await nav.getByRole("button", { name: `${title} 메뉴` }).click();
  await nav.getByRole("menuitem", { name: "이름 바꾸기" }).click();
  await nav.getByRole("textbox", { name: "대화 이름" }).fill("바꿀 이름");
  await nav.getByRole("textbox", { name: "대화 이름" }).press("Escape");
  await expect(nav.locator(`a[href="/c/${id}"]`)).toHaveText(title);
  await expect(page.getByRole("complementary", { name: "사이드바" })).toBeInViewport();
  await nav.getByRole("button", { name: `${title} 메뉴` }).click();
  await nav.getByRole("menuitem", { name: "지우기" }).click();
  await page.getByRole("dialog", { name: "대화 지우기" }).press("Escape");
  await expect(page.getByRole("dialog", { name: "대화 지우기" })).toBeHidden();
  await expect(page.getByRole("complementary", { name: "사이드바" })).toBeInViewport();
  await expect(page).toHaveURL(new RegExp(`/c/${id}$`));
  await expect(nav.getByRole("button", { name: `${title} 메뉴` })).toBeFocused();
});
