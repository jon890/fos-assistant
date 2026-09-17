import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import { expect, test } from "./fixtures.ts";
import { CONTROL_PLANE_BASE_URL, JWT_SECRET, TEST_EMAIL } from "./settings.ts";

async function controlPlaneToken(): Promise<string> {
  return new SignJWT({ name: "브라우저 테스트" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(TEST_EMAIL)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
}

/** 방금 만든 실행 중 가장 최근 것의 번호를 읽는다. */
async function lastExecutionId(page: import("../../web/node_modules/@playwright/test/index.js").Page): Promise<number> {
  const token = await controlPlaneToken();
  const response = await page.request.get(`${CONTROL_PLANE_BASE_URL}/api/v1/usage/executions?limit=1`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok()).toBeTruthy();
  const [execution] = (await response.json()) as { id: number }[];
  return execution.id;
}

/**
 * 깊이 `depth` 짜리 가짜 나무를 만든다.
 *
 * <p>자식 실행을 만드는 경로가 아직 없어, 들여쓰기가 화면을 미는지는 이렇게 서버 응답을 가로채
 * 확인한다.
 */
function deepTreeFixture(depth: number) {
  function node(level: number) {
    return {
      truncated: false,
      executionId: 900 + level,
      agentCode: `깊은-에이전트-${level}`,
      agentName: `아주 길고 긴 하위 에이전트 이름을 넣어 가로 폭을 시험하는 자리 ${level}`,
      status: "SUCCEEDED",
      model: "gpt-5.5",
      inputTokens: 10,
      outputTokens: 20,
      estimatedCostMicros: 1000,
      latencyMs: 500,
      startedAt: new Date().toISOString(),
      events: [
        {
          sequence: 1,
          eventType: "TOOL_STARTED",
          toolName: `매우-길게-적은-도구-이름-가로로-넘치는지-확인하는-자리-${level}`,
          subagentName: null,
          durationMs: null,
          detail: "시작",
          occurredAt: new Date().toISOString(),
        },
        {
          sequence: 2,
          eventType: "TOOL_COMPLETED",
          toolName: `매우-길게-적은-도구-이름-가로로-넘치는지-확인하는-자리-${level}`,
          subagentName: null,
          durationMs: 1234,
          detail: "아주 길고 긴 상세 설명 문자열을 넣어서 좁은 화면에서도 가로로 넘치지 않는지 시험한다",
          occurredAt: new Date().toISOString(),
        },
      ],
      children: level + 1 < depth ? [node(level + 1)] : [],
    };
  }
  return { root: node(0), truncated: false };
}

test("도구 사건 둘과 하위 에이전트 사건이 각각 한 줄로 보인다", async ({ page }) => {
  const response = await page.request.post("/api/chat/stream", {
    data: { text: "실행 나무 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  const id = await lastExecutionId(page);

  await page.goto(`/executions/${id}`);
  const tree = page.getByTestId("execution-tree");
  await expect(tree).toBeVisible();
  await expect(tree.getByText("fake-tool", { exact: false })).toHaveCount(1);
  await expect(tree.getByText("fake-reader", { exact: false })).toHaveCount(1);
  await expect(tree.getByText("하위 에이전트", { exact: false })).toHaveCount(1);
  await expect(tree.getByText("끝나지 않음")).toHaveCount(0);
});

test("사건이 없는 실행을 열면 기록된 사건이 없다고 보이고 요약은 그대로 보인다", async ({ page }) => {
  const response = await page.request.post("/api/chat", {
    data: { text: "실행 나무 빈 사건 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  const id = await lastExecutionId(page);

  await page.goto(`/executions/${id}`);
  await expect(page.getByText("기록된 사건이 없다", { exact: true })).toBeVisible();
  await expect(page.getByText("성공", { exact: true })).toBeVisible();
});

test("깊은 나무를 열어도 좁은 화면과 넓은 화면 모두 가로로 넘치지 않는다", async ({ page }) => {
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({ json: deepTreeFixture(5) });
  });
  await page.goto("/executions/900");

  const tree = page.getByTestId("execution-tree");
  await expect(tree).toBeVisible();
  expect(await tree.locator("[data-testid=execution-node]").count()).toBe(5);

  const viewportWidth = page.viewportSize()?.width;
  expect(viewportWidth).toBeDefined();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewportWidth!);
});
