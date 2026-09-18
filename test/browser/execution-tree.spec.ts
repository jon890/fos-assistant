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
 * <p>가짜 Hermes 로는 깊은 나무를 만들 수 없어 서버 응답을 가로채 만든다. 도구 이름과 상세를
 * 길게 넣어 좁은 화면에서 가로로 미는지 본다.
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

/**
 * 자식 노드가 있는 실행에 `SUBAGENT_STARTED` 사건도 함께 담은 나무다.
 *
 * <p>자식은 Memory 제안 실행처럼 하위 에이전트와 무관하게 달릴 수 있다. 그런 자식이 있어도
 * `SUBAGENT_STARTED` 줄이 사라지면 안 된다는 것을 이 나무로 확인한다.
 */
function treeWithChildAndSubagentFixture() {
  return {
    truncated: false,
    root: {
      truncated: false,
      executionId: 950,
      agentCode: "부모-에이전트",
      agentName: "부모 에이전트",
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
          eventType: "SUBAGENT_STARTED",
          toolName: null,
          subagentName: null,
          durationMs: null,
          detail: "하위 에이전트가 찾기 시작했다",
          occurredAt: new Date().toISOString(),
        },
        {
          sequence: 2,
          eventType: "SUBAGENT_COMPLETED",
          toolName: null,
          subagentName: null,
          durationMs: null,
          detail: "하위 에이전트가 찾기를 마쳤다",
          occurredAt: new Date().toISOString(),
        },
      ],
      children: [
        {
          truncated: false,
          executionId: 951,
          agentCode: "자식-에이전트",
          agentName: "Memory 제안 실행",
          status: "SUCCEEDED",
          model: "gpt-5.5",
          inputTokens: 5,
          outputTokens: 5,
          estimatedCostMicros: 500,
          latencyMs: 200,
          startedAt: new Date().toISOString(),
          events: [],
          children: [],
        },
      ],
    },
  };
}

test("자식 노드가 있어도 하위 에이전트 사건 줄이 사라지지 않는다", async ({ page }) => {
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({ json: treeWithChildAndSubagentFixture() });
  });
  await page.goto("/executions/950");

  const tree = page.getByTestId("execution-tree");
  await expect(tree).toBeVisible();
  expect(await tree.locator("[data-testid=execution-node]").count()).toBe(2);
  await expect(tree.getByText("하위 에이전트", { exact: false })).toHaveCount(1);
});

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

/**
 * 노드 하나짜리 나무다. `treeTruncated` 와 `nodeTruncated` 를 따로 받아
 * 나무 전체가 잘린 것과 그 노드 아래가 잘린 것을 가르는 화면 판정을 확인하는 데 쓴다.
 */
function singleNodeTreeFixture({
  treeTruncated,
  nodeTruncated,
}: {
  treeTruncated: boolean;
  nodeTruncated: boolean;
}) {
  return {
    truncated: treeTruncated,
    root: {
      truncated: nodeTruncated,
      executionId: 970,
      agentCode: "단일-에이전트",
      agentName: "단일 에이전트",
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
          toolName: "fake-tool",
          subagentName: null,
          durationMs: null,
          detail: "시작",
          occurredAt: new Date().toISOString(),
        },
        {
          sequence: 2,
          eventType: "TOOL_COMPLETED",
          toolName: "fake-tool",
          subagentName: null,
          durationMs: 100,
          detail: null,
          occurredAt: new Date().toISOString(),
        },
      ],
      children: [],
    },
  };
}

test("나무가 위쪽에서 잘렸으면 뿌리 위에 안내가 보인다", async ({ page }) => {
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({ json: singleNodeTreeFixture({ treeTruncated: true, nodeTruncated: false }) });
  });
  await page.goto("/executions/970");

  await expect(page.getByTestId("execution-tree-truncated-above")).toHaveText("위쪽이 잘려 여기가 뿌리가 아닐 수 있다");
  await expect(page.getByText("여기부터 보이지 않는다")).toHaveCount(0);
});

test("노드 아래가 잘린 것이면 위쪽 안내를 따로 그리지 않아 같은 말을 두 번 하지 않는다", async ({ page }) => {
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({ json: singleNodeTreeFixture({ treeTruncated: true, nodeTruncated: true }) });
  });
  await page.goto("/executions/970");

  await expect(page.getByTestId("execution-tree-truncated-above")).toHaveCount(0);
  await expect(page.getByText("여기부터 보이지 않는다")).toHaveCount(1);
});
