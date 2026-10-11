import { conversationIdOf, expect, hermesBaseUrl, test } from "./fixtures.ts";
import { interimWireEvents } from "../e2e/scenarios/interim.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

async function script(input: string, output: string, events: Record<string, unknown>[]) {
  const response = await fetch((await hermesBaseUrl()) + "/__test/script", {
    method: "POST", body: JSON.stringify({ input, output, events, pause: true }),
  });
  expect(response.ok).toBeTruthy();
}

async function send(page: Page, text: string) {
  await page.goto("/");
  await page.getByRole("radio", { name: "브라우저 비서", exact: true }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기", exact: true }).click();
}

test("pinned Hermes wire의 안내는 순서대로 한 번 보이고 최종 답과 새로고침 이력에는 남지 않는다", async ({ page, hermes }) => {
  const input = "중간 안내 브라우저 wire 검사";
  await script(input, "확인한 최종 답이에요.", await interimWireEvents());
  await send(page, input);
  const live = page.locator('[data-testid="activity-block"][data-mode="live"]');
  await expect(live.getByTestId("activity-toggle")).toContainText("일부만 흘린 안내도 전부 보여요.");
  await expect(page.getByRole("button", { name: "중지", exact: true })).toBeEnabled();
  await live.getByTestId("activity-toggle").click();
  const notices = live.locator('[data-kind="interim"]');
  await expect(notices).toHaveCount(3);
  await expect(notices.nth(0)).toContainText("자료를 먼저 확인할게요.");
  await expect(notices.nth(1)).toContainText("두 번째 안내");
  await expect(notices.nth(2)).toContainText("일부만 흘린 안내도 전부 보여요.");
  for (const notice of await notices.all()) {
    await expect(notice).toContainText("비서 안내");
    await expect(notice).not.toContainText("끝남");
    await expect(notice.locator(".text-success")).toHaveCount(0);
  }
  const assistant = page.getByTestId("assistant-body").last();
  await expect(assistant).toContainText("확인한 최종 답이에요.");
  await expect(assistant).not.toContainText("두 번째  안내");
  await expect(assistant).not.toContainText("SYNTHETIC_HIDDEN_REASONING");
  await hermes.releaseLongActivity();
  await expect(live).toHaveCount(0);
  await expect(page.locator('[data-kind="interim"]')).toHaveCount(0);
  const id = conversationIdOf(page.url());
  const history = await page.request.get(`/api/chat/conversations/${id}/messages`);
  const messages = await history.json() as { role: string; content: string }[];
  expect(messages.filter((message) => message.role === "ASSISTANT").map((message) => message.content))
    .toEqual(["확인한 최종 답이에요."]);
  await page.reload();
  await expect(page.getByTestId("assistant-message").last()).toContainText("확인한 최종 답이에요.");
  await expect(page.locator('[data-kind="interim"]')).toHaveCount(0);
});

test("중지 결과가 비어도 안내로 재분류한 prefix는 저장하지 않고 실제 답 조각만 남긴다", async ({ page, hermes }) => {
  const input = "중간 안내 중지 wire 검사";
  await script(input, "", await interimWireEvents());
  await hermes.holdNextRun();
  await send(page, input);
  await expect(page.getByTestId("activity-toggle").last()).toContainText("일부만 흘린 안내도 전부 보여요.");
  const stopped = page.waitForResponse((response) => response.url().endsWith("/stop") && response.request().method() === "POST");
  await page.getByRole("button", { name: "중지", exact: true }).click();
  expect((await stopped).ok()).toBeTruthy();
  await hermes.releaseLongActivity();
  await expect(page.locator('[data-testid="activity-block"][data-mode="live"]')).toHaveCount(0);
  await expect(page.locator('[data-kind="interim"]')).toHaveCount(0);
  await expect(page.getByTestId("assistant-message").last()).toContainText("확인한 최종 답이에요.");
  const history = await page.request.get(`/api/chat/conversations/${conversationIdOf(page.url())}/messages`);
  const messages = await history.json() as { role: string; content: string }[];
  expect(messages.filter((message) => message.role === "ASSISTANT").map((message) => message.content))
    .toEqual(["확인한 최종 답이에요."]);
});

test("안내의 HTML은 텍스트로 보이고 새로고침한 실행에는 임시 안내를 복원하지 않는다", async ({ page, hermes }) => {
  const input = "중간 안내 렌더링과 새로고침 검사";
  const text = '<img src=x onerror="window.interimExecuted=true"> token=small-secret';
  await script(input, "", [
    { event: "message.interim", run_id: "fixture-run", timestamp: 1.1, text, already_streamed: false },
    { event: "message.interim", run_id: "fixture-run", timestamp: 1.1, text: "token=secret-one", already_streamed: false },
    { event: "message.interim", run_id: "fixture-run", timestamp: 1.1, text: "token=secret-two", already_streamed: false },
    { event: "message.interim", run_id: "fixture-run", timestamp: 1.1, text: "token=secret-one", already_streamed: false },
  ]);
  await hermes.holdNextRun();
  await send(page, input);
  const toggle = page.getByTestId("activity-toggle").last();
  await expect(toggle).toContainText("[가림]");
  await toggle.click();
  const notes = page.locator('[data-kind="interim"]');
  await expect(notes).toHaveCount(3);
  const note = notes.nth(0);
  await expect(note).toContainText("<img");
  await expect(note.locator("img")).toHaveCount(0);
  await expect(note).not.toContainText("small-secret");
  await expect(notes.nth(1)).toContainText("token=[가림]");
  await expect(notes.nth(2)).toContainText("token=[가림]");
  expect(await page.evaluate(() => "interimExecuted" in window)).toBe(false);
  await page.reload();
  await expect(page.getByRole("button", { name: "중지", exact: true })).toBeEnabled();
  await expect(page.locator('[data-kind="interim"]')).toHaveCount(0);
  const stopped = page.waitForResponse((response) => response.url().endsWith("/stop") && response.request().method() === "POST");
  await page.getByRole("button", { name: "중지", exact: true }).click();
  expect((await stopped).ok()).toBeTruthy();
  await hermes.releaseLongActivity();
  await expect(page.getByRole("button", { name: "중지", exact: true })).toHaveCount(0);
  await expect(page.getByTestId("assistant-message")).toHaveCount(0);
});
