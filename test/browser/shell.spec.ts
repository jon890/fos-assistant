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
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
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
  await expect(page.getByRole("textbox", { name: "메시지" })).toHaveValue("");
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
  const uploaded = page.waitForResponse((response) => response.request().method() === "POST"
    && /\/api\/chat\/conversations\/\d+\/attachments$/.test(response.url()));
  await page.getByTestId("attachment-input").setInputFiles([
    { name: "shell.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  const uploadedResponse = await uploaded;
  expect(uploadedResponse.ok()).toBeTruthy();
  const attachmentId = ((await uploadedResponse.json()) as { id: number }).id;
  await expect(page).toHaveURL(/\/c\/\d+$/);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews")).toBeVisible();
  const uploadedId = Number(page.url().match(/\/c\/(\d+)$/)?.[1]);
  const listed = await page.request.get("/api/chat/conversations");
  expect(listed.ok()).toBeTruthy();
  expect((await listed.json() as { id: number; title: string }[])
    .some((item) => item.id === uploadedId && item.title === "")).toBeTruthy();
  await openSidebar(page, testInfo);
  await expect(conversationNav(page).locator(`a[href="/c/${uploadedId}"]`)).toHaveText("새 대화");

  const deleted = page.waitForResponse((response) =>
    response.request().method() === "DELETE"
    && new RegExp(`/api/chat/conversations/${uploadedId}/attachments/\\d+$`).test(response.url()));
  await conversationNav(page).locator(`a[href="/c/${firstId}"]`).click();
  expect((await deleted).status()).toBe(204);
  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);
  const afterDelete = await page.request.get(`/api/chat/conversations/${uploadedId}/attachments/${attachmentId}`);
  expect(afterDelete.status()).toBe(410);
});

test("사진을 먼저 올리고 보내면 같은 대화에 첫 메시지와 제목이 남는다", async ({ page }, testInfo) => {
  await page.goto("/");
  const title = `사진 첫 메시지 ${testInfo.project.name} ${Date.now()}`;
  await page.getByTestId("attachment-input").setInputFiles([
    { name: "shell.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  await expect(page).toHaveURL(/\/c\/\d+$/);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  const id = Number(page.url().match(/\/c\/(\d+)$/)?.[1]);
  await openSidebar(page, testInfo);
  await expect(conversationNav(page).locator(`a[href="/c/${id}"]`)).toHaveText("새 대화");
  if (testInfo.project.name === "mobile") {
    await page.keyboard.press("Escape");
  }
  const before = await page.request.get("/api/chat/conversations");
  expect(before.ok()).toBeTruthy();
  const beforeCount = (await before.json() as { id: number }[]).length;
  await send(page, title);
  await expect(page).toHaveURL(new RegExp(`/c/${id}$`));
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
  await openSidebar(page, testInfo);
  await expect(conversationNav(page).locator(`a[href="/c/${id}"]`)).toHaveText(title);
  const after = await page.request.get("/api/chat/conversations");
  expect(after.ok()).toBeTruthy();
  expect((await after.json() as { id: number }[]).length).toBe(beforeCount);
});

test("사진을 올린 뒤 같은 대화 링크를 눌러도 미리보기와 파일이 남는다", async ({ page }, testInfo) => {
  await page.goto("/");
  const uploaded = page.waitForResponse((response) => response.request().method() === "POST"
    && /\/api\/chat\/conversations\/\d+\/attachments$/.test(response.url()));
  await page.getByTestId("attachment-input").setInputFiles([
    { name: "shell.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  const attachmentId = ((await (await uploaded).json()) as { id: number }).id;
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  const id = Number(page.url().match(/\/c\/(\d+)$/)?.[1]);
  await openSidebar(page, testInfo);
  await conversationNav(page).locator(`a[href="/c/${id}"]`).click();
  await expect(page).toHaveURL(new RegExp(`/c/${id}$`));
  await expect(page.getByTestId("attachment-previews")).toBeVisible();
  const file = await page.request.get(`/api/chat/conversations/${id}/attachments/${attachmentId}`);
  expect(file.status()).toBe(200);
});

test("서랍은 Esc 한 번에 닫힌다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile");
  await page.goto("/");
  await openSidebar(page, testInfo);
  const sidebar = page.getByRole("complementary", { name: "사이드바" });
  await expect(sidebar).toBeInViewport();
  await page.keyboard.press("Escape");
  await expect(sidebar).toBeHidden();
});

test("답을 만드는 중에 서랍을 Esc 로 닫으면 서랍만 닫히고 답은 멈추지 않는다", async ({ page, hermes }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile");
  await hermes.holdNextRun();
  await page.goto("/");
  await send(page, `서랍 Esc 격리 ${Date.now()}`);
  await hermes.waitForHeldRun();
  await openSidebar(page, testInfo);
  const sidebar = page.getByRole("complementary", { name: "사이드바" });
  await expect(sidebar).toBeInViewport();
  await page.keyboard.press("Escape");
  await expect(sidebar).toBeHidden();
  await expect(page.getByTestId("composer-shell").getByRole("button", { name: "중지" })).toBeEnabled();
  await hermes.releaseHeldRun();
  await expect(page.getByTestId("assistant-message")).toHaveCount(1, { timeout: 30_000 });
  await expect(page.getByTestId("stopped-mark")).toHaveCount(0);
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
  await expect(page.getByRole("textbox", { name: "메시지" })).toHaveValue("");
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
  await page.getByRole("menuitem", { name: "이름 바꾸기" }).click();
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
  await page.getByRole("menuitem", { name: "이름 바꾸기" }).click();
  // 닫히는 메뉴가 사라진 뒤에 누른다. 사라지기 전의 Esc 는 메뉴가 받고, 곧바로 다시 누른 메뉴 단추는 닫히는 메뉴를 되살린다.
  await expect(page.getByRole("menu")).toHaveCount(0);
  await nav.getByRole("textbox", { name: "대화 이름" }).fill("바뀌지 않을 이름");
  await nav.getByRole("textbox", { name: "대화 이름" }).press("Escape");
  await expect(nav.locator(`a[href="/c/${id}"]`)).toHaveText(title);
  await nav.getByRole("button", { name: `${title} 메뉴` }).click();
  await page.getByRole("menuitem", { name: "이름 바꾸기" }).click();
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
  await page.getByRole("menuitem", { name: "지우기" }).click();
  const dialog = page.getByRole("alertdialog", { name: "대화 지우기" });
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
  await page.getByRole("menuitem", { name: "지우기" }).click();
  await page.getByRole("alertdialog", { name: "대화 지우기" }).getByRole("button", { name: "지우기" }).click();
  await expect(conversationNav(page).locator(`a[href="/c/${id}"]`)).toBeVisible();
  await expect(conversationNav(page).getByRole("alert")).toContainText("지우기 실패");
});

test("지우는 요청이 도는 동안 창을 열어 두고 두 단추를 잠그며 끝나면 창을 닫는다", async ({ page }, testInfo) => {
  await page.goto("/");
  const title = `삭제 대기 ${testInfo.project.name} ${Date.now()}`;
  const id = await createConversation(page, title);
  await page.reload();
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  let deleteSeen = false;
  await page.route(`**/api/chat/conversations/${id}`, async (route) => {
    if (route.request().method() !== "DELETE") return route.continue();
    deleteSeen = true;
    await held;
    await route.continue();
  });
  await openSidebar(page, testInfo);
  await conversationNav(page).getByRole("button", { name: `${title} 메뉴` }).click();
  await page.getByRole("menuitem", { name: "지우기" }).click();
  const dialog = page.getByRole("alertdialog", { name: "대화 지우기" });
  await dialog.getByRole("button", { name: "지우기" }).click();
  await expect.poll(() => deleteSeen).toBe(true);
  // 누른 뒤에는 단추 이름이 「지우는 중」 으로 바뀐다.
  const busy = dialog.getByRole("button", { name: "지우는 중" });
  await expect(busy).toHaveAttribute("aria-busy", "true");
  await expect(busy).toBeDisabled();
  await expect(dialog.getByRole("button", { name: "취소" })).toBeDisabled();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeVisible();
  release();
  await expect(dialog).toBeHidden();
  await expect(conversationNav(page).locator(`a[href="/c/${id}"]`)).toHaveCount(0);
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
  await expect(page.getByRole("textbox", { name: "메시지" })).toHaveValue("");
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
  await page.getByRole("menuitem", { name: "이름 바꾸기" }).click();
  // 닫히는 메뉴가 사라진 뒤에 누른다. 그래야 입력칸의 Esc 를 서랍이 받는 경우를 검사한다.
  await expect(page.getByRole("menu")).toHaveCount(0);
  await nav.getByRole("textbox", { name: "대화 이름" }).fill("바꿀 이름");
  await nav.getByRole("textbox", { name: "대화 이름" }).press("Escape");
  await expect(nav.locator(`a[href="/c/${id}"]`)).toHaveText(title);
  await expect(page.getByRole("complementary", { name: "사이드바" })).toBeInViewport();
  await nav.getByRole("button", { name: `${title} 메뉴` }).click();
  await page.getByRole("menuitem", { name: "지우기" }).click();
  await page.getByRole("alertdialog", { name: "대화 지우기" }).press("Escape");
  await expect(page.getByRole("alertdialog", { name: "대화 지우기" })).toBeHidden();
  await expect(page.getByRole("complementary", { name: "사이드바" })).toBeInViewport();
  await expect(page).toHaveURL(new RegExp(`/c/${id}$`));
  await expect(nav.getByRole("button", { name: `${title} 메뉴` })).toBeFocused();
});

test("대화 메뉴는 Esc 와 바깥 클릭으로 닫히고 서랍은 유지한다", async ({ page }, testInfo) => {
  await page.goto("/");
  const title = `메뉴 닫기 ${testInfo.project.name} ${Date.now()}`;
  const id = await createConversation(page, title);
  await page.goto(`/c/${id}`);
  await openSidebar(page, testInfo);
  const nav = conversationNav(page);
  const menuButton = nav.getByRole("button", { name: `${title} 메뉴` });
  await menuButton.click();
  await expect(page.getByRole("menu", { name: `${title} 메뉴` })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menu", { name: `${title} 메뉴` })).toHaveCount(0);
  await expect(menuButton).toBeFocused();
  if (testInfo.project.name === "mobile") {
    await expect(page.getByRole("complementary", { name: "사이드바" })).toBeInViewport();
  }
  await menuButton.click();
  await page.getByRole("searchbox", { name: "대화 검색" }).click();
  await expect(page.getByRole("menu", { name: `${title} 메뉴` })).toHaveCount(0);
  await expect(page).toHaveURL(new RegExp(`/c/${id}$`));
});

/** 창의 버블 단계에 닿은 Esc 마다 이미 기본 동작이 막혔는지를 적는다. 대화 화면의 처리기가 보는 값과 같다 */
async function recordEscapes(page: Page): Promise<() => Promise<boolean[]>> {
  await page.evaluate(() => {
    const store = window as Window & { escapes?: boolean[] };
    store.escapes = [];
    window.addEventListener("keydown", (event) => {
      if (event.key === "Escape") store.escapes?.push(event.defaultPrevented);
    });
  });
  return () => page.evaluate(() => (window as Window & { escapes?: boolean[] }).escapes ?? []);
}

async function expectRunContinues(page: Page, hermes: { releaseHeldRun(): Promise<void> }, testInfo: TestInfo): Promise<void> {
  if (testInfo.project.name === "mobile") {
    // 서랍이 열려 있는 동안 서랍 밖은 가려진다. 닫고 나서 입력창을 본다.
    await page.getByRole("button", { name: "사이드바 닫기" }).click();
    await expect(page.getByRole("complementary", { name: "사이드바" })).toBeHidden();
  }
  await expect(page.getByTestId("composer-shell").getByRole("button", { name: "중지" })).toBeEnabled();
  await hermes.releaseHeldRun();
  await expect(page.getByTestId("assistant-message")).toHaveCount(1, { timeout: 30_000 });
  await expect(page.getByTestId("stopped-mark")).toHaveCount(0);
}

test("답을 만드는 중에 대화 메뉴를 Esc 로 닫으면 메뉴만 닫히고 답은 계속 흐른다", async ({ page, hermes }, testInfo) => {
  await hermes.holdNextRun();
  await page.goto("/");
  const title = `메뉴 Esc 실행 ${testInfo.project.name} ${Date.now()}`;
  await send(page, title);
  await hermes.waitForHeldRun();
  const escapes = await recordEscapes(page);
  await openSidebar(page, testInfo);
  const menuButton = conversationNav(page).getByRole("button", { name: `${title} 메뉴` });
  await menuButton.click();
  await expect(page.getByRole("menu", { name: `${title} 메뉴` })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menu", { name: `${title} 메뉴` })).toHaveCount(0);
  await expect(menuButton).toBeFocused();
  // Radix 는 Esc 로 닫으며 기본 동작을 막는다. 그래서 대화 화면의 처리기가 중지로 넘기지 않는다.
  expect(await escapes()).toEqual([true]);
  await expectRunContinues(page, hermes, testInfo);
});

test("답을 만드는 중에 지우기 확인 창을 Esc 로 닫으면 창만 닫히고 답은 계속 흐른다", async ({ page, hermes }, testInfo) => {
  await hermes.holdNextRun();
  await page.goto("/");
  const title = `지우기 Esc 실행 ${testInfo.project.name} ${Date.now()}`;
  await send(page, title);
  await hermes.waitForHeldRun();
  const escapes = await recordEscapes(page);
  await openSidebar(page, testInfo);
  await conversationNav(page).getByRole("button", { name: `${title} 메뉴` }).click();
  await page.getByRole("menuitem", { name: "지우기" }).click();
  const dialog = page.getByRole("alertdialog", { name: "대화 지우기" });
  await expect(dialog).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  expect(await escapes()).toEqual([true]);
  if (testInfo.project.name === "mobile") {
    await expect(page.getByRole("complementary", { name: "사이드바" })).toBeInViewport();
  }
  await expectRunContinues(page, hermes, testInfo);
});

test("서랍 안에서 Tab 을 거듭 눌러도 초점이 서랍 밖으로 나가지 않는다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile");
  await page.goto("/");
  await createConversation(page, `초점 가두기 ${Date.now()}`);
  await page.reload();
  await openSidebar(page, testInfo);
  const sidebar = page.getByRole("complementary", { name: "사이드바" });
  await expect(sidebar).toBeInViewport();
  const drawer = page.getByRole("dialog", { name: "사이드바" });
  const focusable = await drawer.locator("a[href], button:not([disabled]), input:not([disabled])").count();
  for (let index = 0; index < focusable + 3; index += 1) {
    await page.keyboard.press("Tab");
    expect(await drawer.evaluate((node) => node.contains(document.activeElement)),
      `Tab ${index + 1} 번째에 초점이 서랍 밖으로 나갔다`).toBe(true);
  }
});

test("사진을 올린 뒤 서랍을 열고 닫아도 미리보기가 남는다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile");
  await page.goto("/");
  await page.getByTestId("attachment-input").setInputFiles([
    { name: "drawer.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews").locator("img")).toHaveCount(1);
  await openSidebar(page, testInfo);
  await expect(page.getByRole("complementary", { name: "사이드바" })).toBeInViewport();
  await page.getByRole("button", { name: "사이드바 닫기" }).click();
  await expect(page.getByRole("complementary", { name: "사이드바" })).toBeHidden();
  await expect(page.getByTestId("attachment-previews").locator("img")).toHaveCount(1);
});

test("아이콘 단추에 마우스를 올리면 접근성 이름과 같은 풀이가 보인다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop");
  await page.goto("/");
  const collapse = page.getByRole("button", { name: "사이드바 접기" });
  await expect(collapse).not.toHaveAttribute("title");
  await collapse.hover();
  await expect(page.getByRole("tooltip")).toHaveText("사이드바 접기");
});

test("서랍은 옮기는 동안 열려 있다가 옮긴 뒤 닫히고, 지금 경로를 누르면 곧바로 닫힌다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile");
  await page.goto("/");
  const sidebar = page.getByRole("complementary", { name: "사이드바" });

  // 지금 경로와 같은 「새 대화」 는 경로가 바뀌지 않으므로 누르자마자 닫힌다.
  await openSidebar(page, testInfo);
  await expect(sidebar).toBeInViewport();
  await page.getByTestId("new-conversation-link").click();
  await expect(sidebar).toBeHidden();
  await expect(page).toHaveURL(/\/$/);

  // 다른 경로로 옮기는 동안에는 서랍이 남고, 옮긴 화면이 보인 뒤 닫힌다.
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  await page.route((url) => url.pathname === "/usage", async (route) => {
    const headers = route.request().headers();
    if (!headers["next-router-prefetch"] && headers["rsc"] === "1") await held;
    await route.continue();
  });
  try {
    await openSidebar(page, testInfo);
    await page.getByRole("navigation", { name: "주요 화면" }).getByRole("link", { name: "사용량", exact: true }).click();
    await expect(sidebar.getByRole("status")).toHaveText("옮기는 중");
    await expect(sidebar).toBeInViewport();
    release();
    await expect(page.getByRole("heading", { name: "사용량" })).toBeVisible();
    await expect(sidebar).toBeHidden();
  } finally {
    release();
  }
});
