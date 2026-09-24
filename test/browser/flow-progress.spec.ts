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
    const saved = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
    await expect(saved).toBeVisible();
    await expect(saved.getByTestId("activity-toggle")).toHaveAttribute("aria-expanded", "true");
    await page.reload();
    await expect(saved).toBeVisible();
    await expect(saved).toContainText("작업 과정");
    await saved.getByTestId("activity-toggle").click();
    await expect(saved.locator('[data-kind="subagent"]')).toHaveCount(3);
    await saved.getByTestId("activity-open-panel").click();
    await expect(page.getByTestId("activity-panel").getByTestId("flow-tree-link")).toBeVisible();
    await page.getByTestId("activity-panel").getByTestId("flow-tree-link").click();
    await expect(page).toHaveURL(/\/executions\/\d+$/);
  } finally {
    if (!released) await hermes.releaseHeldRun();
  }
});

test("실패한 단계만 실패로 보이고 오지 않은 단계는 그리지 않는다", async ({ page }) => {
  await sendWithTheFlowAgent(page, "흐름 계약 위반 검사");
  const block = page.locator('[data-testid="activity-block"][data-mode="live"]');
  await expect(block).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("turn-error")).toBeVisible({ timeout: 30_000 });
  await expect(block.getByTestId("activity-toggle")).toHaveAttribute("aria-expanded", "false");
  const stoppedTitle = await block.getByTestId("activity-toggle").textContent();
  await page.waitForTimeout(1_200);
  expect(await block.getByTestId("activity-toggle").textContent()).toBe(stoppedTitle);
  await block.getByTestId("activity-toggle").click();
  await expect(block.locator('[data-step="chief"]')).toHaveAttribute("data-state", "failed", { timeout: 30_000 });
  await expect(block.locator('[data-step="researcher"]')).toHaveCount(0);
  await expect(block.locator('[data-step="engineer"]')).toHaveCount(0);
  await expect(block.locator('[data-step="synthesizer"]')).toHaveCount(0);
});

test("연결이 끊기면 실패 줄은 남고 끝나지 않은 줄은 멈춘다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("combobox").selectOption({ label: "브라우저 비서" });
  await page.getByPlaceholder("무엇을 도와줄까요").fill("첫 답");
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.locator('[data-testid="activity-block"][data-mode="saved"]').last()).toBeVisible();
  const conversationId = Number(new URL(page.url()).pathname.split("/").at(-1));
  const events = [
    { type: "started", conversationId, executionId: 1 },
    { type: "tool", phase: "started", toolName: "실패 도구" },
    { type: "tool", phase: "completed", toolName: "실패 도구", failed: true },
    { type: "subagent", phase: "started", goal: "끝나지 않은 조사", subagentId: "pending" },
  ];
  await page.route("**/api/chat/stream", (route) => route.fulfill({
    status: 200,
    contentType: "text/event-stream",
    body: events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join(""),
  }));
  await page.getByPlaceholder("무엇을 도와줄까요").fill("연결 중단 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  const block = page.locator('[data-testid="activity-block"][data-mode="live"]');
  await expect(page.getByTestId("turn-error")).toBeVisible();
  await expect(block.getByTestId("activity-toggle")).toHaveAttribute("aria-expanded", "false");
  const stoppedTitle = await block.getByTestId("activity-toggle").textContent();
  await page.waitForTimeout(1_200);
  expect(await block.getByTestId("activity-toggle").textContent()).toBe(stoppedTitle);
  await block.getByTestId("activity-toggle").click();
  await expect(block.locator('[data-kind="tool"][data-state="failed"]')).toHaveCount(1);
  await expect(block.locator('[data-kind="subagent"][data-state="unfinished"]')).toContainText("끝나지 않음");
  await page.reload();
  await expect(page.locator('[data-testid="activity-block"][data-mode="live"]')).toHaveCount(0);
});

test("사건 없는 자식은 저장된 줄에서 빼고 머리의 수와 맞춘다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("combobox").selectOption({ label: "브라우저 비서" });
  await page.getByPlaceholder("무엇을 도와줄까요").fill("사건 없는 자식 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  const block = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
  await expect(block).toBeVisible({ timeout: 30_000 });
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    const response = await route.fetch();
    const tree = await response.json();
    tree.root.children.push({ ...tree.root, executionId: -1, events: [], children: [] });
    await route.fulfill({ response, json: tree });
  });
  await block.getByTestId("activity-toggle").click();
  await expect(block).toContainText("하위 에이전트 1");
  await expect(block.locator('[data-kind="subagent"]')).toHaveCount(1);
});

test("병렬 하위 에이전트가 거꾸로 끝나도 저장된 줄의 토큰과 시간이 맞는다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("combobox").selectOption({ label: "브라우저 비서" });
  await page.getByPlaceholder("무엇을 도와줄까요").fill("병렬 하위 에이전트 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  const block = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
  await expect(block).toBeVisible({ timeout: 30_000 });
  await expect(block).toContainText("하위 에이전트 2");
  await block.getByTestId("activity-toggle").click();
  const agents = block.locator('[data-kind="subagent"]');
  await expect(agents).toHaveCount(2);
  await expect(agents.nth(0)).toContainText("첫째 조사");
  await expect(agents.nth(0)).toContainText("model-first");
  await expect(agents.nth(0)).toContainText("입력 100");
  await expect(agents.nth(0)).toContainText("1.0초");
  await expect(agents.nth(1)).toContainText("둘째 조사");
  await expect(agents.nth(1)).toContainText("model-second");
  await expect(agents.nth(1)).toContainText("입력 200");
  await expect(agents.nth(1)).toContainText("2.0초");
  await expect(block.locator('[data-kind="tool"][data-state="failed"]')).toHaveCount(1);
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
