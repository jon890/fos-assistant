import { CONVERSATION_URL, expect, test } from "./fixtures.ts";
import { ARTIFACT_PROBE, ARTIFACT_SAME_NAME_PROBE } from "../e2e/fake-hermes.ts";
import type { Page, TestInfo } from "../../web/node_modules/@playwright/test/index.js";

/** 새 대화에서 결과물을 만드는 글을 보내고, 답 아래 결과물 줄이 뜰 때까지 기다린다. */
async function sendProbe(page: Page, text: string = ARTIFACT_PROBE, count = 1) {
  await page.goto("/");
  await page.getByRole("radio", { name: "브라우저 비서" }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page).toHaveURL(CONVERSATION_URL);
  const rows = page.getByTestId("assistant-message").last().getByTestId("message-artifact");
  await expect(rows).toHaveCount(count, { timeout: 30_000 });
  return rows;
}

/** 대화 목록에서 그 대화를 누른다. 좁은 폭에서는 서랍을 먼저 연다. */
async function openFromSidebar(page: Page, testInfo: TestInfo, conversationId: string) {
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
  await page.getByRole("navigation", { name: "대화 목록" }).locator(`a[href="/chat/${conversationId}"]`).click();
  await expect(page).toHaveURL(new RegExp(`/chat/${conversationId}$`));
}

/** API 로 대화 하나를 만들고 그 답까지 받는다. */
async function createConversation(page: Page, text: string): Promise<string> {
  const created = await page.request.post("/api/chat", { data: { text, agentCode: "browser" } });
  expect(created.ok(), `대화를 만들지 못했다: ${created.status()}`).toBeTruthy();
  return ((await created.json()) as { conversationId: string }).conversationId;
}

test("답 아래 결과물을 누르면 스크립트가 막힌 iframe 에 사진과 함께 뜬다", async ({ page }) => {
  // 평문 HTTP 에서 제공되지 않는 API 없이도 패널을 열 수 있어야 한다.
  await page.addInitScript(() => {
    Object.defineProperty(window.crypto, "randomUUID", { value: undefined });
  });
  const rows = await sendProbe(page);
  await expect(rows.first()).toHaveText("초안");

  await rows.first().getByRole("button").click();
  const panel = page.getByTestId("artifact-panel");
  await expect(panel.getByRole("heading", { name: "초안" })).toBeVisible();
  const frame = page.getByTestId("artifact-frame");
  await expect(frame).toBeVisible();
  const sandbox = await frame.getAttribute("sandbox");
  expect(sandbox, "iframe 이 스크립트를 허용한다").not.toContain("allow-scripts");
  expect(sandbox).toContain("allow-same-origin");

  // 상대 경로 사진이 로그인 쿠키와 함께 읽혔는지 본다. 깨진 이미지면 naturalWidth 가 0 이다.
  const document = frame.contentFrame();
  const image = document.locator("img");
  await expect(image).toBeVisible();
  await expect.poll(() => image.evaluate((img) => (img as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
  // 대역 HTML 의 스크립트는 제목을 「스크립트가 돌았다」 로 바꾸려 한다. 돌지 않았으면 원래 제목이 남는다.
  const inner = await (await frame.elementHandle())?.contentFrame();
  expect(await inner?.title(), "iframe 안에서 스크립트가 돌았다").toBe("초안");

  // 주소를 직접 열어도 스크립트가 돌지 않도록 응답 머리글이 옮겨져 있어야 한다.
  const src = await frame.getAttribute("src");
  expect(src).toBeTruthy();
  const openedUrl = new URL(src!, page.url());
  expect(openedUrl.searchParams.get("open")).toBeTruthy();
  await expect(panel.getByTestId("artifact-open-tab")).toHaveAttribute("href", src!);
  const direct = await page.request.get(src!);
  expect(direct.status()).toBe(200);
  const headers = direct.headers();
  expect(headers["content-type"]).toContain("text/html");
  expect(headers["content-security-policy"]).toContain("sandbox");
  expect(headers["content-security-policy"]).not.toContain("allow-scripts");
  expect(headers["x-content-type-options"]).toBe("nosniff");
  // 옮기는 목록 밖의 머리글이 붙으면 같은 출처 iframe 이 막힌다.
  expect(headers["x-frame-options"]).toBeUndefined();

  // 조건부 요청이 Control Plane 까지 가서 304 로 돌아와야 한다. 본문은 비고 캐시 지시는 그대로 붙는다.
  const etag = headers["etag"];
  const lastModified = headers["last-modified"];
  expect(etag, "ETag 가 옮겨지지 않았다").toBeTruthy();
  expect(lastModified, "Last-Modified 가 옮겨지지 않았다").toBeTruthy();
  const byEtag = await page.request.get(src!, { headers: { "If-None-Match": etag } });
  expect(byEtag.status()).toBe(304);
  expect((await byEtag.body()).length).toBe(0);
  expect(byEtag.headers()["cache-control"]).toBe("private, no-cache");
  const byDate = await page.request.get(src!, { headers: { "If-Modified-Since": lastModified } });
  expect(byDate.status()).toBe(304);

  await panel.getByRole("button", { name: "결과물 닫기" }).click();
  await expect(panel).toHaveCount(0);

  // 같은 파일을 다시 열어도 이전 응답의 차단 머리글이 남은 캐시 주소를 쓰지 않는다.
  await rows.first().getByRole("button").click();
  await expect(frame).toBeVisible();
  const reopenedSrc = await frame.getAttribute("src");
  expect(reopenedSrc).not.toBe(src);
  expect(new URL(reopenedSrc!, page.url()).pathname).toBe(openedUrl.pathname);
  await expect(panel.getByTestId("artifact-open-tab")).toHaveAttribute("href", reopenedSrc!);
  await expect(frame.contentFrame().locator("img")).toBeVisible();
});

test("결과물 패널을 연 채 작업 과정 패널을 열면 결과물 패널이 닫힌다", async ({ page }, testInfo) => {
  const rows = await sendProbe(page);
  const block = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
  await expect(block).toBeVisible();
  await block.getByTestId("activity-toggle").click();

  await rows.first().getByRole("button").click();
  await expect(page.getByTestId("artifact-panel")).toBeVisible();

  const openActivity = block.getByTestId("activity-open-panel");
  if (testInfo.project.name === "desktop") {
    await openActivity.click();
  } else {
    // 좁은 폭의 결과물 패널은 대화를 덮어 사람이 누를 수 없다. 누른 것과 같은 사건을 보내 패널이 하나만 남는지 본다.
    await openActivity.dispatchEvent("click");
  }
  await expect(page.getByTestId("activity-panel")).toBeVisible();
  await expect(page.getByTestId("artifact-panel")).toHaveCount(0);
});

test("결과물 패널을 연 채 다른 대화로 옮기면 패널이 닫힌다", async ({ page }, testInfo) => {
  const other = await createConversation(page, `결과물 옮겨 갈 대화 ${testInfo.project.name} ${Date.now()}`);
  const target = await createConversation(page, ARTIFACT_PROBE);

  await page.goto(`/chat/${other}`);
  await expect(page.getByTestId("assistant-message")).toHaveCount(1, { timeout: 30_000 });
  await openFromSidebar(page, testInfo, target);
  const rows = page.getByTestId("assistant-message").last().getByTestId("message-artifact");
  await expect(rows).toHaveCount(1, { timeout: 30_000 });
  await rows.first().getByRole("button").click();
  await expect(page.getByTestId("artifact-panel")).toBeVisible();

  // 뒤로 가기는 화면을 다시 읽지 않고 앞 대화로 옮긴다. 표시가 남아 있으면 같은 화면에서 대화만 바뀐 것이다.
  await page.evaluate(() => { (window as Window & { __sameDocument?: boolean }).__sameDocument = true; });
  await page.goBack();
  await expect(page).toHaveURL(new RegExp(`/chat/${other}$`));
  await expect(page.getByTestId("artifact-panel")).toHaveCount(0);
  expect(await page.evaluate(() => (window as Window & { __sameDocument?: boolean }).__sameDocument),
    "대화를 옮길 때 화면을 새로 읽었다").toBe(true);
});

test("폴더 이름이 같은 결과물은 답 아래 줄과 패널 머리가 같은 이름을 보인다", async ({ page }) => {
  const rows = await sendProbe(page, ARTIFACT_SAME_NAME_PROBE, 2);
  // 줄 순서는 Control Plane 이 정하므로 이름만 본다.
  await expect(rows.filter({ hasText: "가/초안" })).toHaveCount(1);
  await expect(rows.filter({ hasText: "나/초안" })).toHaveCount(1);

  for (const name of ["가/초안", "나/초안"]) {
    await rows.filter({ hasText: name }).getByRole("button").click();
    const panel = page.getByTestId("artifact-panel");
    await expect(panel.getByRole("heading")).toHaveText(name);
    await panel.getByRole("button", { name: "결과물 닫기" }).click();
    await expect(panel).toHaveCount(0);
  }
});
