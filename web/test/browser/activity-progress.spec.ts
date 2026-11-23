import { expect, hermesBaseUrl, test } from "./fixtures.ts";
import { expectStartAgent } from "./helpers.ts";
import { SCRIPT_PAUSE_EVENT } from "../../../test/e2e/fake-hermes.ts";
import type { Page } from "../../node_modules/@playwright/test/index.js";

// 작업 과정 표시(docs/features/app-shell/spec.md REQ11, REQ13-REQ15)를 가짜 Hermes 의 도구 사건으로 본다.

async function script(input: string, events: Record<string, unknown>[]) {
  const response = await fetch((await hermesBaseUrl()) + "/__test/script", {
    method: "POST",
    body: JSON.stringify({ input, output: `${input} 답`, events }),
  });
  expect(response.ok).toBeTruthy();
}

function tool(name: string, preview: string, failed = false) {
  return [
    { event: "tool.started", tool: name, preview },
    { event: "tool.completed", tool: name, duration: 0.2, error: failed },
  ];
}

async function send(page: Page, text: string) {
  // 휴대폰 폭은 스펙이 정한 360px 로 본다.
  if (test.info().project.name === "mobile")
    await page.setViewportSize({ width: 360, height: 780 });
  await page.goto("/new");
  await expectStartAgent(page, "브라우저 비서");
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기", exact: true }).click();
}

test("답을 만드는 동안 한 줄이 검색과 검색어에서 페이지 읽기로 바뀌고 펼치면 앞의 검색 줄이 있다", async ({
  page,
  hermes,
}) => {
  const input = "작업 과정 한 줄 검사";
  await script(input, [
    { event: "tool.started", tool: "web_search", preview: "서울 날씨" },
    { event: SCRIPT_PAUSE_EVENT },
    { event: "tool.completed", tool: "web_search", duration: 0.3, error: false },
    {
      event: "tool.started",
      tool: "web_extract",
      preview: "https://example.com/weather",
    },
    { event: SCRIPT_PAUSE_EVENT },
    { event: "tool.completed", tool: "web_extract", duration: 0.4, error: false },
  ]);
  let paused = 2;
  try {
    await send(page, input);
    const live = page.locator('[data-testid="activity-block"][data-mode="live"]');
    const toggle = live.getByTestId("activity-toggle");
    await expect(live.getByTestId("activity-current")).toContainText(
      "검색하고 있어요",
    );
    await expect(live.getByTestId("activity-current-target")).toHaveText(
      "서울 날씨",
    );
    await expect(live.getByTestId("activity-announce")).toHaveText(
      "검색하고 있어요 서울 날씨",
    );
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
    // 움직임 줄이기를 켠 기기에서는 글자가 움직이지 않는다.
    await expect(live.locator(".activity-shimmer")).toHaveCSS(
      "animation-name",
      "none",
    );

    await hermes.releaseLongActivity();
    paused--;
    await expect(live.getByTestId("activity-current")).toContainText(
      "웹 페이지를 읽고 있어요",
    );
    // 페이지 읽기의 대상은 사람 말이 아니라서 보이지 않는다.
    await expect(live.getByTestId("activity-current-target")).toHaveCount(0);

    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
    const items = live.getByTestId("activity-item");
    await expect(items).toHaveCount(2);
    await expect(items.nth(0)).toContainText("검색했어요");
    await expect(items.nth(0)).toContainText("서울 날씨");
    await expect(items.nth(0).locator('[data-mark="kind"]')).toHaveCount(1);
    await expect(items.nth(1)).toHaveAttribute("data-state", "running");
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "false");

    await hermes.releaseLongActivity();
    paused--;
    const saved = page
      .locator('[data-testid="activity-block"][data-mode="saved"]')
      .last();
    await expect(saved.getByTestId("activity-toggle")).toContainText(
      "웹 검색 · 페이지 읽기",
      { timeout: 30_000 },
    );
  } finally {
    for (; paused > 0; paused--) await hermes.releaseLongActivity();
  }
});

test("도는 동안 플러그인 도구는 「서비스 이름 · 도구 제목」 으로 보인다", async ({
  page,
  hermes,
}) => {
  const input = "작업 과정 플러그인 도는 줄 검사";
  await script(input, [
    { event: "tool.started", tool: "mcp__demo__list_scopes", preview: "{}" },
    { event: SCRIPT_PAUSE_EVENT },
  ]);
  try {
    await send(page, input);
    const live = page.locator(
      '[data-testid="activity-block"][data-mode="live"]',
    );
    await expect(live.getByTestId("activity-current")).toHaveText(
      "검사용 메모 · 범위 목록 보기",
    );
    await expect(live).not.toContainText("mcp__");
  } finally {
    await hermes.releaseLongActivity();
  }
});

test.describe("움직임을 줄이지 않은 기기", () => {
  test.use({ reducedMotion: "no-preference" });

  test("도는 줄의 글자가 움직인다", async ({ page, hermes }) => {
    const input = "작업 과정 움직임 검사";
    await script(input, [
      { event: "tool.started", tool: "web_search", preview: "움직임" },
      { event: SCRIPT_PAUSE_EVENT },
    ]);
    try {
      await send(page, input);
      const live = page.locator(
        '[data-testid="activity-block"][data-mode="live"]',
      );
      await expect(live.locator(".activity-shimmer")).toHaveCSS(
        "animation-name",
        "activity-shimmer",
      );
    } finally {
      await hermes.releaseLongActivity();
    }
  });
});

test("끝난 답의 접힌 줄은 쓴 종류를 셋까지 잇고 플러그인 도구는 서비스 이름과 도구 제목으로 보인다", async ({
  page,
}) => {
  const input = "작업 과정 종류 검사";
  await script(input, [
    ...tool("web_search", "가습기 추천"),
    ...tool("web_extract", "https://example.com"),
    ...tool("mcp__demo__list_scopes", "{}"),
    ...tool("mcp__demo__hidden_tool", "{}"),
    ...tool("mcp__ghost__read", "{}"),
    ...tool("terminal", "ls", true),
  ]);
  await send(page, input);
  const saved = page
    .locator('[data-testid="activity-block"][data-mode="saved"]')
    .last();
  const summary = "웹 검색 · 페이지 읽기 · 검사용 메모 외 2";
  await expect(saved.getByTestId("activity-toggle")).toContainText(summary, {
    timeout: 30_000,
  });

  // 다시 열어도 펼치지 않은 채 같은 줄이다.
  await page.reload();
  const reopened = page
    .locator('[data-testid="activity-block"][data-mode="saved"]')
    .last();
  await expect(reopened.getByTestId("activity-toggle")).toContainText(summary);

  await reopened.getByTestId("activity-toggle").click();
  const items = reopened.getByTestId("activity-item");
  await expect(items).toHaveCount(6);
  await expect(items.nth(0)).toContainText("가습기 추천");
  await expect(items.nth(2)).toContainText("검사용 메모 · 범위 목록 보기");
  // 선언에 없는 도구도 서버 이름으로 플러그인을 찾으면 서비스 이름만 보인다.
  await expect(items.nth(3).locator("span.break-words")).toHaveText(
    "검사용 메모",
  );
  await expect(items.nth(4)).toContainText("연결된 서비스를 썼어요");
  await expect(items.nth(5)).toHaveAttribute("data-state", "failed");
  await expect(items.nth(5)).toContainText("실패");
  await expect(page.locator("body")).not.toContainText("mcp__");

  // 옆 패널도 같은 이름이다.
  await reopened.getByTestId("activity-open-panel").click();
  const panel = page.getByTestId("activity-panel");
  await expect(panel).toContainText("검사용 메모 · 범위 목록 보기");
  await expect(panel).not.toContainText("mcp__");
});

test("끝까지 하지 못한 답은 다시 열어도 접힌 줄이 「끝까지 하지 못했어요」 로 시작한다", async ({
  page,
}) => {
  const input = "작업 과정 실패 다시 열기 검사";
  await script(input, tool("web_search", "실패 검색어"));
  await send(page, input);
  await expect(
    page.locator('[data-testid="activity-block"][data-mode="saved"]').last(),
  ).toBeVisible({ timeout: 30_000 });

  // 실패한 turn 을 저장된 이력으로 다시 연 경우를 만든다.
  await page.route("**/api/chat/conversations/*/messages", async (route) => {
    const response = await route.fetch();
    const turns = (await response.json()) as { role: string; status?: string }[];
    for (const turn of turns) if (turn.role === "ASSISTANT") turn.status = "FAILED";
    await route.fulfill({ response, json: turns });
  });
  await page.reload();
  await expect(
    page
      .locator('[data-testid="activity-block"][data-mode="saved"]')
      .last()
      .getByTestId("activity-toggle"),
  ).toHaveText("작업 과정: 끝까지 하지 못했어요 · 웹 검색");
});
