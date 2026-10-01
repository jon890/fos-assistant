import { conversationIdOf, expect, test, FLOW_AGENT_CODE } from "./fixtures.ts";
import { LONG_ACTIVITY_PROBE } from "../e2e/fake-hermes.ts";
import type { Locator, Page } from "../../web/node_modules/@playwright/test/index.js";

/** 흐름 에이전트로 새 대화를 열고 한 마디를 보낸다. */
async function sendWithTheFlowAgent(page: Page, text: string): Promise<void> {
  await page.goto("/");
  await page.getByRole("radio", { name: "흐름 비서" }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
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

test("연결이 끊기면 실패 줄은 남고 자식의 결과 누락을 보인다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("radio", { name: "브라우저 비서" }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill("첫 답");
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.locator('[data-testid="activity-block"][data-mode="saved"]').last()).toBeVisible();
  const conversationId = conversationIdOf(page.url());
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
  await page.getByRole("textbox", { name: "메시지" }).fill("연결 중단 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  const block = page.locator('[data-testid="activity-block"][data-mode="live"]');
  await expect(page.getByTestId("turn-error")).toBeVisible();
  await expect(block.getByTestId("activity-toggle")).toHaveAttribute("aria-expanded", "false");
  const stoppedTitle = await block.getByTestId("activity-toggle").textContent();
  await page.waitForTimeout(1_200);
  expect(await block.getByTestId("activity-toggle").textContent()).toBe(stoppedTitle);
  await block.getByTestId("activity-toggle").click();
  await expect(block.locator('[data-kind="tool"][data-state="failed"]')).toHaveCount(1);
  const child = block.locator('[data-kind="subagent"][data-state="result-missing"]');
  await expect(child).toContainText("결과를 받지 못함");
  await expect(child).not.toContainText("입력 ");
  await expect(child).not.toContainText("출력 ");
  await expect(block.getByTestId("activity-toggle")).toContainText("끝까지 하지 못했어요");
  await expect(block.locator('[data-kind="tool"][data-state="failed"]')).toContainText("실패");
  await page.reload();
  await expect(page.locator('[data-testid="activity-block"][data-mode="live"]')).toHaveCount(0);
});

test("사건 없는 자식은 저장된 줄에서 빼고 머리는 수 없이 한 줄로 보인다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("radio", { name: "브라우저 비서" }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill("사건 없는 자식 검사");
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
  await expect(block.locator('[data-kind="subagent"]')).toHaveCount(1);
  // 머리의 한 줄은 수를 그리지 않고, 도구와 도우미를 모두 썼다는 문장만 보인다.
  await expect(block.getByTestId("activity-toggle")).toContainText("찾아보고 도우미와 함께 정리했어요");
  await expect(block.getByTestId("activity-toggle")).not.toContainText(/[0-9]/);
});

test("병렬 도우미가 거꾸로 끝나도 저장된 줄의 시간이 맞고 모델과 토큰은 보이지 않는다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("radio", { name: "브라우저 비서" }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill("병렬 하위 에이전트 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  const block = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
  await expect(block).toBeVisible({ timeout: 30_000 });
  await expect(block.getByTestId("activity-toggle")).toContainText("찾아보고 도우미와 함께 정리했어요");
  await block.getByTestId("activity-toggle").click();
  const agents = block.locator('[data-kind="subagent"]');
  await expect(agents).toHaveCount(2);
  await expect(agents.nth(0)).toContainText("첫째 조사");
  await expect(agents.nth(0)).not.toContainText("model-first");
  await expect(agents.nth(0)).not.toContainText("입력 ");
  await expect(agents.nth(0)).not.toContainText("100");
  await expect(agents.nth(0)).toContainText("1초");
  await expect(agents.nth(1)).toContainText("둘째 조사");
  await expect(agents.nth(1)).not.toContainText("model-second");
  await expect(agents.nth(1)).not.toContainText("입력 ");
  await expect(agents.nth(1)).not.toContainText("200");
  await expect(agents.nth(1)).toContainText("2초");
  await expect(block.locator('[data-kind="tool"][data-state="failed"]')).toHaveCount(1);
});

test("일반 대화에는 단계 없이 도구 줄이 남고 읽기 실패를 다시 시도할 수 있다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("radio", { name: "브라우저 비서" }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill("그냥 대화");
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

/**
 * 접힌 줄과 펼친 목록 어디에도 수와 모델과 토큰이 글자로 나오지 않는다.
 *
 * <p>`toContainText` 는 찾는 글의 끝 공백을 지우므로 「도구 」 같은 글은 정규식으로 찾는다.
 */
async function expectNoInternalValues(block: Locator): Promise<void> {
  for (const text of [/도구 [0-9]/, /입력 /, /출력 /, "하위 에이전트", "example-model", "z-ai/glm-5.2", "12,300"]) {
    await expect(block).not.toContainText(text);
  }
}

for (const { durationMs, duration } of [
  { durationMs: 72_000, duration: "걸린 시간 1분 12초" },
  { durationMs: 999, duration: null },
]) {
  test(`끝난 답의 작업 과정은 수와 모델과 토큰 없이 한 줄로 접히고 펼치면 걸린 시간 ${durationMs}ms 를 ${duration ?? "그리지 않는다"}`, async ({ page }) => {
  // 걸린 시간은 실행마다 달라, 저장된 답의 요약에서 그 값만 고정한다.
  await page.route("**/api/chat/conversations/*/messages", async (route) => {
    const response = await route.fetch();
    const turns = (await response.json()) as { activity: { durationMs: number | null } | null }[];
    for (const turn of turns) if (turn.activity) turn.activity.durationMs = durationMs;
    await route.fulfill({ response, json: turns });
  });
  await page.goto("/");
  await page.getByRole("radio", { name: "브라우저 비서" }).click();
  // 이 입력의 도우미는 모델 이름과 토큰 수를 싣고 끝난다. 그 값이 대화에 나오지 않는지 본다.
  await page.getByRole("textbox", { name: "메시지" }).fill("하위 에이전트 칸 검사");
  await page.getByRole("button", { name: "보내기" }).click();

  const block = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
  await expect(block).toBeVisible({ timeout: 30_000 });
  const toggle = block.getByTestId("activity-toggle");
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await expect(toggle).toHaveText("작업 과정: 찾아보고 도우미와 함께 정리했어요");
  await expect(block.getByTestId("activity-duration")).toHaveCount(0);
  await expectNoInternalValues(block);
  expect((await toggle.boundingBox())?.height).toBeGreaterThanOrEqual(44);

  await toggle.click();
  await expect(block.locator('[data-kind="tool"]')).toHaveCount(2);
  await expect(block.locator('[data-kind="subagent"]')).toHaveText(/^끝남도우미숙소 후보를 조사한다1초$/);
  if (duration === null) await expect(block.getByTestId("activity-duration")).toHaveCount(0);
  else await expect(block.getByTestId("activity-duration")).toHaveText(duration);
  await expect(block.getByTestId("activity-open-panel")).toHaveText("자세히 보기");
  await expectNoInternalValues(block);
});
}

/** 블록의 위쪽이 같은 줄 「비서」 이름의 아래쪽보다 위에 있지 않다. */
async function expectBlockBelowName(row: Locator): Promise<void> {
  const name = await row.getByText("비서", { exact: true }).boundingBox();
  const block = await row.locator('[data-testid="activity-block"][data-mode="live"]').boundingBox();
  expect(name, "비서 이름의 자리").not.toBeNull();
  expect(block, "진행 중 블록의 자리").not.toBeNull();
  expect(block!.y, "진행 중 블록의 top 과 이름의 bottom").toBeGreaterThanOrEqual(name!.y + name!.height);
}

test("진행 중 블록은 답이 없을 때도 답이 흘러나올 때도 비서 이름 아래에 있다", async ({ page, hermes }) => {
  // 답이 아직 없을 때다. 흐름은 run 이 붙잡힌 동안에도 단계 사건을 먼저 보낸다.
  await hermes.holdNextRun();
  let released = false;
  try {
    await sendWithTheFlowAgent(page, "자리 고정 검사");
    await hermes.waitForHeldRun();
    const pending = page.getByTestId("pending-assistant");
    await expect(pending.locator('[data-testid="activity-block"][data-mode="live"]')).toBeVisible();
    await expect(pending.getByTestId("activity-signal")).toBeVisible();
    await expect(page.getByTestId("assistant-message")).toHaveCount(0);
    await expectBlockBelowName(pending);
    await hermes.releaseHeldRun();
    released = true;
    await expect(page.locator('[data-testid="activity-block"][data-mode="saved"]').last())
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId("pending-assistant")).toHaveCount(0);
  } finally {
    if (!released) await hermes.releaseHeldRun();
  }

  // 답이 흘러나오는 중이다. 이 입력은 첫 조각을 보낸 뒤 도구 줄을 남기고 기다린다.
  try {
    await page.goto("/");
    await page.getByRole("radio", { name: "브라우저 비서" }).click();
    await page.getByRole("textbox", { name: "메시지" }).fill(LONG_ACTIVITY_PROBE);
    await page.getByRole("button", { name: "보내기" }).click();
    const answer = page.getByTestId("assistant-message").last();
    await expect(answer.getByTestId("assistant-body")).toContainText("긴 작업 과정");
    await expect(answer.locator('[data-testid="activity-block"][data-mode="live"]')).toBeVisible();
    await expect(page.getByTestId("pending-assistant")).toHaveCount(0);
    await expectBlockBelowName(answer);
    const block = await answer.locator('[data-testid="activity-block"]').boundingBox();
    const body = await answer.getByTestId("assistant-body").boundingBox();
    expect(body!.y, "답 본문의 top 과 블록의 bottom").toBeGreaterThanOrEqual(block!.y + block!.height);
  } finally {
    await hermes.releaseLongActivity();
    await hermes.releaseLongActivity();
  }
});

test("기다림 점이 있던 자리에 답이 들어와 얼굴과 첫 줄이 움직이지 않는다", async ({ page, hermes }) => {
  // 도구를 쓰지 않은 답으로 만든다. 스트림에서 도구와 도우미 사건을 빼고, 저장된 답의 요약도 비운다.
  await page.route("**/api/chat/stream", async (route) => {
    const response = await route.fetch();
    const body = (await response.text()).split("\n\n").filter((chunk) => {
      const line = chunk.split("\n").find((candidate) => candidate.startsWith("data:"));
      if (line === undefined) return true;
      const type = (JSON.parse(line.slice("data:".length)) as { type?: string }).type;
      return type !== "tool" && type !== "subagent";
    }).join("\n\n");
    await route.fulfill({ response, body });
  });
  await page.route("**/api/chat/conversations/*/messages", async (route) => {
    const response = await route.fetch();
    const turns = (await response.json()) as { role: string; activity: unknown }[];
    for (const turn of turns) if (turn.role === "ASSISTANT") turn.activity = null;
    await route.fulfill({ response, json: turns });
  });
  await hermes.holdNextRun();
  let released = false;
  try {
    await page.goto("/");
    await page.getByRole("radio", { name: "브라우저 비서" }).click();
    await page.getByRole("textbox", { name: "메시지" }).fill("짧은 답");
    await page.getByRole("button", { name: "보내기" }).click();
    await hermes.waitForHeldRun();

    const pending = page.getByTestId("pending-assistant");
    const dots = pending.getByRole("status", { name: "비서의 답을 기다리는 중" });
    await expect(dots).toBeVisible();
    // 좁은 폭에서는 답이 오면 대화 위의 에이전트 줄이 숨어 대화 영역이 통째로 올라간다. 그래서 자리는
    // 대화 영역의 위쪽에서부터 측정한다. 그 안에서 얼굴이 움직이면 이 값이 달라진다.
    const scrollTop = () => page.getByTestId("message-scroll")
      .evaluate((element) => element.getBoundingClientRect().top);
    const avatarTop = (locator: Locator) => locator.locator("> span").first()
      .evaluate((element) => element.getBoundingClientRect().top);
    const pendingBase = await scrollTop();
    const pendingAvatarOffset = await avatarTop(pending) - pendingBase;
    const dotsOffset = await dots.evaluate((element) => element.getBoundingClientRect().top) - pendingBase;

    await hermes.releaseHeldRun();
    released = true;
    const answer = page.getByTestId("assistant-message");
    await expect(answer).toHaveCount(1, { timeout: 30_000 });
    await expect(page.getByTestId("pending-assistant")).toHaveCount(0);
    await expect(answer.getByTestId("activity-block")).toHaveCount(0);
    // 한 화면에 들어오는 짧은 대화라 스크롤이 움직이지 않는다.
    expect(await page.getByTestId("message-scroll")
      .evaluate((element) => element.scrollHeight <= element.clientHeight), "대화가 한 화면에 들어온다").toBe(true);
    const answerBase = await scrollTop();
    expect(await avatarTop(answer) - answerBase, "대화 영역 위에서 답 줄 얼굴까지").toBe(pendingAvatarOffset);
    const bodyOffset = await answer.getByTestId("assistant-body")
      .evaluate((element) => element.getBoundingClientRect().top) - answerBase;
    expect(Math.abs(bodyOffset - dotsOffset), `기다림 점 ${dotsOffset} 과 답 첫 줄 ${bodyOffset} 의 차이`)
      .toBeLessThanOrEqual(4);
  } finally {
    if (!released) await hermes.releaseHeldRun();
  }
});

test("흐름 에이전트의 코드가 바뀌면 이 검사가 먼저 알린다", async ({ page }) => {
  const agents = await page.request.get("/api/agents");
  expect(agents.ok()).toBeTruthy();
  const codes = ((await agents.json()) as { code: string }[]).map((agent) => agent.code);
  expect(codes).toContain(FLOW_AGENT_CODE);
});
