import { expect, test, FLOW_AGENT_CODE } from "./fixtures.ts";
import type { Page, TestInfo } from "../../web/node_modules/@playwright/test/index.js";

/** 흐름 에이전트로 새 대화를 열고 한 마디를 보낸다. */
async function sendWithTheFlowAgent(
  page: Page,
  testInfo: TestInfo,
  text: string,
): Promise<void> {
  await page.goto("/");
  if (testInfo.project.name === "mobile") {
    await page.getByRole("button", { name: "대화 목록 열기" }).click();
  }
  await page.getByRole("button", { name: "새 대화" }).click();
  await page.getByRole("combobox").selectOption({ label: "흐름 비서" });
  await page.getByPlaceholder("무엇을 도와줄까요").fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
}

async function stateOf(page: Page, step: string): Promise<string | null> {
  return page.getByTestId(`flow-step-${step}`).getAttribute("data-state");
}

test("흐름이 도는 동안 네 단계가 보이고 끝나면 답과 실행 나무로 가는 길이 남는다", async ({
  page,
  hermes,
}, testInfo) => {
  await hermes.holdNextRun();
  let released = false;
  try {
    await sendWithTheFlowAgent(page, testInfo, "전기차를 사는 게 나을까?");
    await hermes.waitForHeldRun();

    const progress = page.getByTestId("flow-progress");
    await expect(progress).toBeVisible();
    for (const label of ["정리", "조사", "구현", "합치기"]) {
      await expect(progress.getByText(label, { exact: true })).toBeVisible();
    }
    await expect.poll(() => stateOf(page, "chief")).toBe("started");
    expect(await stateOf(page, "researcher")).toBe("pending");
    expect(await stateOf(page, "engineer")).toBe("pending");
    expect(await stateOf(page, "synthesizer")).toBe("pending");

    // 단계 목록이 두 폭 모두에서 가로로 넘치지 않는다.
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);

    await hermes.releaseHeldRun();
    released = true;

    // 답이 흘러나오면 단계 목록을 접는다. 흐름은 답을 마지막에 한 번에 보내므로,
    // 답이 보이는 시점이 곧 접히는 시점이다.
    await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
    await expect(progress).toBeHidden();
    await expect(page.getByTestId("flow-tree-link").last()).toBeVisible();
  } finally {
    if (!released) await hermes.releaseHeldRun();
  }
});

test("한 단계가 실패하면 그 단계에 오류 표시가 남고 나머지는 흐린 상태로 멈춘다", async ({
  page,
}, testInfo) => {
  await sendWithTheFlowAgent(page, testInfo, "흐름 계약 위반 검사");

  await expect.poll(() => stateOf(page, "chief"), { timeout: 30_000 }).toBe("failed");
  expect(await stateOf(page, "researcher")).toBe("pending");
  expect(await stateOf(page, "engineer")).toBe("pending");
  expect(await stateOf(page, "synthesizer")).toBe("pending");
});

test("흐름이 아닌 대화에는 단계 목록이 보이지 않는다", async ({ page }, testInfo) => {
  await page.goto("/");
  if (testInfo.project.name === "mobile") {
    await page.getByRole("button", { name: "대화 목록 열기" }).click();
  }
  await page.getByRole("button", { name: "새 대화" }).click();
  await page.getByRole("combobox").selectOption({ label: "브라우저 비서" });
  await page.getByPlaceholder("무엇을 도와줄까요").fill("그냥 대화");
  await page.getByRole("button", { name: "보내기" }).click();

  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("flow-progress")).toBeHidden();
  await expect(page.getByTestId("flow-tree-link")).toHaveCount(0);
});

test("흐름 에이전트의 코드가 바뀌면 이 검사가 먼저 알린다", async ({ page }) => {
  const agents = await page.request.get("/api/agents");
  expect(agents.ok()).toBeTruthy();
  const codes = ((await agents.json()) as { code: string }[]).map((agent) => agent.code);
  expect(codes).toContain(FLOW_AGENT_CODE);
});
