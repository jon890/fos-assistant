import { expect, test, FLOW_AGENT_CODE } from "./fixtures.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

/** 흐름 에이전트로 새 대화를 열고 한 마디를 보낸다. */
async function sendWithTheFlowAgent(page: Page, text: string): Promise<void> {
  await page.goto("/");
  await page.getByRole("combobox").selectOption({ label: "흐름 비서" });
  await page.getByPlaceholder("무엇을 도와줄까요").fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
}

test("흐름 중에는 도착한 단계만 보이고 끝난 답에 작업 과정이 남는다", async ({ page, hermes }) => {
  await hermes.holdNextRun();
  let released = false;
  try {
    await sendWithTheFlowAgent(page, "전기차를 사는 게 나을까?");
    await hermes.waitForHeldRun();

    const block = page.locator('[data-testid="activity-block"][data-mode="live"]');
    await expect(block).toBeVisible();
    await block.getByTestId("activity-toggle").click();
    await expect(block.locator('[data-step="chief"]')).toHaveAttribute("data-state", "running");
    await expect(block.locator('[data-step="synthesizer"]')).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
      .toBeLessThanOrEqual(0);

    await hermes.releaseHeldRun();
    released = true;
    await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
    await expect(page.locator('[data-testid="activity-block"][data-mode="saved"]').last()).toBeVisible();
  } finally {
    if (!released) await hermes.releaseHeldRun();
  }
});

test("실패한 단계만 실패로 보이고 오지 않은 단계는 그리지 않는다", async ({ page }) => {
  await sendWithTheFlowAgent(page, "흐름 계약 위반 검사");
  const block = page.locator('[data-testid="activity-block"][data-mode="live"]');
  await expect(block).toBeVisible({ timeout: 30_000 });
  await block.getByTestId("activity-toggle").click();
  await expect(block.locator('[data-step="chief"]')).toHaveAttribute("data-state", "failed", { timeout: 30_000 });
  await expect(block.locator('[data-step="researcher"]')).toHaveCount(0);
  await expect(block.locator('[data-step="engineer"]')).toHaveCount(0);
  await expect(block.locator('[data-step="synthesizer"]')).toHaveCount(0);
});

test("일반 대화에는 단계 없이 도구 줄이 남고 읽기 실패를 다시 시도할 수 있다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("combobox").selectOption({ label: "브라우저 비서" });
  await page.getByPlaceholder("무엇을 도와줄까요").fill("그냥 대화");
  await page.getByRole("button", { name: "보내기" }).click();

  const block = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
  await expect(block).toBeVisible({ timeout: 30_000 });
  await page.route("**/api/usage/executions/*/tree", (route) => route.fulfill({ status: 500, body: "{}" }));
  await block.getByTestId("activity-toggle").click();
  await expect(block.getByTestId("activity-load-error")).toBeVisible();
  await page.unroute("**/api/usage/executions/*/tree");
  await block.getByRole("button", { name: "다시 읽기" }).click();
  await expect(block.locator('[data-kind="tool"]')).toHaveCount(2);
  await expect(block.locator('[data-kind="step"]')).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
    .toBeLessThanOrEqual(0);
});

test("흐름 에이전트의 코드가 바뀌면 이 검사가 먼저 알린다", async ({ page }) => {
  const agents = await page.request.get("/api/agents");
  expect(agents.ok()).toBeTruthy();
  const codes = ((await agents.json()) as { code: string }[]).map((agent) => agent.code);
  expect(codes).toContain(FLOW_AGENT_CODE);
});
