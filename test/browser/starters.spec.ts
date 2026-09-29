import { FAKE_STARTER_PROMPTS } from "../e2e/fake-hermes.ts";
import { CONVERSATION_URL, expect, FLOW_AGENT_CODE, PERSONA_AGENT_CODE, test } from "./fixtures.ts";

const STARTERS_PATTERN = "**/api/agents/*/starters";

/** 추천 질문 응답 한 건의 모양이다 */
function startersBody(status: "READY" | "GENERATING" | "NONE", prompts: string[]) {
  return { prompts, status };
}

test("가로채지 않고 열면 가짜 Hermes 가 만든 네 추천이 칩으로 보이고 누르면 그 글로 보낸다", async ({ page }) => {
  await page.goto("/");

  // 추천을 처음 만드는 경우라도 다시 읽기가 끝나면 채워진다.
  for (const prompt of FAKE_STARTER_PROMPTS) {
    await expect(page.getByRole("button", { name: prompt, exact: true })).toBeVisible({ timeout: 15_000 });
  }

  await page.getByRole("button", { name: FAKE_STARTER_PROMPTS[0], exact: true }).click();

  await expect(page.getByTestId("user-message").last()).toContainText(FAKE_STARTER_PROMPTS[0]);
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
});

test("만드는 중이면 자리를 비워 두었다가 다시 읽어 칩을 채운다", async ({ page }) => {
  let requests = 0;
  await page.route(STARTERS_PATTERN, async (route) => {
    requests += 1;
    await route.fulfill({
      json: requests === 1 ? startersBody("GENERATING", []) : startersBody("READY", ["채워진 추천이다"]),
    });
  });
  await page.goto("/");

  await expect.poll(() => requests).toBeGreaterThanOrEqual(1);
  await expect(page.getByRole("list", { name: "추천 질문" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "채워진 추천이다", exact: true })).toBeVisible({ timeout: 10_000 });
  expect(requests, "채워질 때까지 나간 읽기").toBe(2);
});

test("세 번 다시 읽어도 만드는 중이면 자리를 비워 두고 더 읽지 않는다", async ({ page }) => {
  let requests = 0;
  await page.route(STARTERS_PATTERN, async (route) => {
    requests += 1;
    await route.fulfill({ json: startersBody("GENERATING", []) });
  });
  await page.goto("/");

  // 첫 읽기 하나와 2초 간격의 다시 읽기 셋이다.
  await expect.poll(() => requests, { timeout: 15_000 }).toBe(4);
  // 다음 다시 읽기가 있었다면 2초 안에 나갔을 것이다.
  await page.waitForTimeout(3_000);
  expect(requests, "다시 읽기를 마친 뒤 나간 읽기").toBe(4);
  await expect(page.getByRole("list", { name: "추천 질문" })).toHaveCount(0);
});

test("에이전트를 바꾸면 늦게 온 이전 에이전트의 추천은 그리지 않는다", async ({ page }) => {
  let releaseFirst!: () => void;
  const firstReleased = new Promise<void>((resolve) => {
    releaseFirst = resolve;
  });
  let firstFulfilled!: () => void;
  const firstDone = new Promise<void>((resolve) => {
    firstFulfilled = resolve;
  });
  await page.route(STARTERS_PATTERN, async (route) => {
    if (route.request().url().includes(`/agents/${FLOW_AGENT_CODE}/`)) {
      await route.fulfill({ json: startersBody("READY", ["흐름 추천이다"]) });
      return;
    }
    await firstReleased;
    await route.fulfill({ json: startersBody("READY", ["늦게 온 추천이다"]) });
    firstFulfilled();
  });
  await page.goto("/");
  await expect(page.getByRole("radio", { name: "브라우저 비서" })).toHaveAttribute("aria-checked", "true");

  await page.getByRole("radio", { name: "흐름 비서" }).click();
  await expect(page.getByRole("button", { name: "흐름 추천이다", exact: true })).toBeVisible();

  releaseFirst();
  await firstDone;
  // 응답이 화면에 닿을 시간을 준 뒤에도 이전 에이전트의 추천이 없어야 한다.
  await page.waitForTimeout(500);
  await expect(page.getByRole("button", { name: "늦게 온 추천이다", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "흐름 추천이다", exact: true })).toBeVisible();
});

test("에이전트 상세에는 추천 질문 편집 절이 없다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);

  await expect(page.getByRole("textbox", { name: "성격 비서 성격" })).toBeVisible();
  await expect(page.getByText("추천 질문")).toHaveCount(0);
  await expect(page.getByRole("textbox", { name: "성격 비서 소개" })).toHaveCount(0);
});
