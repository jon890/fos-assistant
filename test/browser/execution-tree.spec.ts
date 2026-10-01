import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import { expect, setSession, test } from "./fixtures.ts";
import { CONTROL_PLANE_BASE_URL, JWT_SECRET, TEST_EMAIL } from "./settings.ts";

async function controlPlaneToken(email = TEST_EMAIL): Promise<string> {
  return new SignJWT({ name: "브라우저 테스트" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(email)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
}

/** 방금 만든 실행 중 가장 최근 것의 번호를 읽는다. */
async function lastExecutionId(
  page: import("../../web/node_modules/@playwright/test/index.js").Page,
  email = TEST_EMAIL,
): Promise<number> {
  const token = await controlPlaneToken(email);
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
 * <p>가짜 Hermes 로는 깊은 나무를 만들 수 없어 서버 응답을 가로채 만든다. 도구 이름은 화면에서 사람 말로
 * 바뀌므로, 긴 글자는 상세에 넣어 좁은 화면에서 가로로 미는지 본다. 상세를 줄에 그리는 도구는 검색과
 * 사진 보기뿐이라 검색 도구로 만든다.
 */
function deepTreeFixture(depth: number) {
  function node(level: number) {
    return {
      truncated: false,
      executionId: 900 + level,
      agentCode: `깊은-에이전트-${level}`,
      agentName: `아주 길고 긴 하위 에이전트 이름을 넣어 가로 폭을 시험하는 자리 ${level}`,
      status: "SUCCEEDED",
      provider: "openai-codex",
      model: "example-model",
      inputTokens: 10,
      cachedInputTokens: 4,
      outputTokens: 20,
      estimatedCostMicros: 1000,
      latencyMs: 500,
      startedAt: new Date().toISOString(),
      reasoningEffort: "medium",
      reasoningEffortSource: "PROFILE_DEFAULT",
      modelTier: "BALANCED",
      requestReceivedAt: "2026-10-01T00:00:00.000Z",
      submittedAt: "2026-10-01T00:00:00.100Z",
      firstDeltaAt: "2026-10-01T00:00:00.300Z",
      finishedAt: "2026-10-01T00:00:01.000Z",
      events: [
        {
          sequence: 1,
          eventType: "TOOL_STARTED",
          toolName: "web_search",
          subagentName: null,
          durationMs: null,
          detail: `매우-길게-적은-도구-상세-가로로-넘치는지-확인하는-자리-${level}`,
          occurredAt: new Date().toISOString(),
        },
        {
          sequence: 2,
          eventType: "TOOL_COMPLETED",
          toolName: "web_search",
          subagentName: null,
          durationMs: 1234,
          detail: `매우-길게-적은-도구-상세-가로로-넘치는지-확인하는-자리-${level} 아주 길고 긴 상세 설명 문자열을 넣어서 좁은 화면에서도 가로로 넘치지 않는지 시험한다`,
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
function treeWithChildAndSubagentFixture(
  usageStatus: "WAITING" | "RECORDED" | "UNCONFIRMED" | null = null,
  /** 이름 없는 도우미는 Control Plane 이 이름 칸을 Hermes 의 id 로 채운다. */
  subagentName: string | null = null,
) {
  return {
    truncated: false,
    root: {
      truncated: false,
      executionId: 950,
      agentCode: "부모-에이전트",
      agentName: "부모 에이전트",
      status: "SUCCEEDED",
      model: "example-model",
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
          subagentName,
          durationMs: null,
          detail: "하위 에이전트가 찾기 시작했다",
          occurredAt: new Date().toISOString(),
          subagentUsageStatus: usageStatus,
        },
        {
          sequence: 2,
          eventType: "SUBAGENT_COMPLETED",
          toolName: null,
          subagentName,
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
          model: "example-model",
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

test("자식 노드가 있어도 하위 에이전트 사건 줄이 사라지지 않는다", async ({
  page,
}) => {
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({ json: treeWithChildAndSubagentFixture() });
  });
  await page.goto("/executions/950");

  const tree = page.getByTestId("execution-tree");
  await expect(tree).toBeVisible();
  expect(await tree.locator("[data-testid=execution-node]").count()).toBe(2);
  await expect(tree.getByText("도우미: ", { exact: false })).toHaveCount(1);
});

test("하위 에이전트 사용량 확인 상태를 실패와 다르게 보인다", async ({
  page,
}) => {
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({ json: treeWithChildAndSubagentFixture("WAITING") });
  });
  await page.goto("/executions/950");
  const eventRows = page.getByTestId("execution-event-row");
  await expect(eventRows.filter({ hasText: "수치 확인 중" })).toHaveCount(1);
  await expect(page.getByText("실패", { exact: true })).toHaveCount(0);

  await page.unroute("**/api/usage/executions/*/tree");
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({
      json: treeWithChildAndSubagentFixture("UNCONFIRMED"),
    });
  });
  await page.reload();
  await expect(eventRows.filter({ hasText: "사용량 미확인" })).toHaveCount(1);
});

test("실행 상세와 작업 과정에 실제 모델, 단계, 기본 강도와 기록된 시각만 보인다", async ({
  page,
}) => {
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({ json: deepTreeFixture(1) });
  });
  await page.goto("/executions/900");

  await expect(
    page.getByText("openai-codex · example-model", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("균형", { exact: true })).toBeVisible();
  await expect(page.getByText("기본값 medium", { exact: true })).toBeVisible();
  await expect(page.getByTestId("execution-timing")).toContainText("요청 수신");
  await expect(
    page.getByTestId("execution-node-runtime").first(),
  ).toContainText("균형");
});

test("MEMBER는 실행 상세에서 단계와 걸린 시간만 본다", async ({
  context,
  page,
}) => {
  await setSession(context, {
    email: "member@example.com",
    name: "가족 사용자",
  });
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({ json: deepTreeFixture(1) });
  });
  await page.goto("/executions/900");

  await expect(page.getByText("균형", { exact: true })).toBeVisible();
  // 관리자가 아니면 걸린 시간을 초 단위로만 본다. 1초가 안 되면 「1초 미만」 이다.
  await expect(page.getByText("1초 미만", { exact: true })).toBeVisible();
  await expect(page.getByText("500ms", { exact: false })).toHaveCount(0);
  await expect(
    page.getByText("openai-codex · example-model", { exact: true }),
  ).toHaveCount(0);
  await expect(page.getByText("기본값 medium", { exact: true })).toHaveCount(0);
  await expect(page.getByText("입력 토큰", { exact: true })).toHaveCount(0);
  await expect(page.getByText("환산 금액", { exact: true })).toHaveCount(0);
  await expect(page.getByTestId("execution-timing")).toHaveCount(0);
  await expect(page.getByTestId("execution-node-runtime")).toHaveCount(0);
  await expect(page.getByTestId("execution-node-tokens")).toHaveCount(0);
});

test("관리자는 도우미 줄에서 id 로 채워진 이름과 목표를 함께 본다", async ({
  page,
}) => {
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({
      json: treeWithChildAndSubagentFixture(null, "sa-950"),
    });
  });
  await page.goto("/executions/950");
  await expect(page.getByTestId("execution-event-row")).toHaveText(
    "도우미: sa-950 · 하위 에이전트가 찾기 시작했다",
  );
});

test("MEMBER는 도우미 줄에서 목표만 보고 id 는 보지 않는다", async ({
  context,
  page,
}) => {
  await setSession(context, {
    email: "member@example.com",
    name: "가족 사용자",
  });
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({
      json: treeWithChildAndSubagentFixture(null, "sa-950"),
    });
  });
  await page.goto("/executions/950");
  const row = page.getByTestId("execution-event-row");
  await expect(row).toHaveText("도우미: 하위 에이전트가 찾기 시작했다");
  await expect(page.getByRole("main")).not.toContainText("sa-");
});

test("도구 사건 둘과 하위 에이전트 사건이 각각 한 줄로 보인다", async ({
  page,
}) => {
  const response = await page.request.post("/api/chat/stream", {
    data: { text: "실행 나무 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  const id = await lastExecutionId(page);

  await page.goto(`/executions/${id}`);
  const tree = page.getByTestId("execution-tree");
  await expect(tree).toBeVisible();
  await expect(tree.locator('[data-tool="fake-tool"]')).toHaveCount(1);
  await expect(tree.locator('[data-tool="fake-reader"]')).toHaveCount(1);
  await expect(tree.locator('[data-tool="fake-tool"]')).toContainText("도구를 썼어요");
  await expect(tree.locator('[data-tool="fake-reader"]')).toContainText("도구를 썼어요");
  await expect(tree.getByText("도구: ", { exact: false })).toHaveCount(0);
  await expect(tree.getByText("도우미: ", { exact: false })).toHaveCount(1);
  await expect(tree.getByText("끝나지 않음")).toHaveCount(0);
});

test("사건이 없는 실행을 열면 기록된 작업이 없어요고 보이고 요약은 그대로 보인다", async ({
  page,
}) => {
  const response = await page.request.post("/api/chat", {
    data: { text: "실행 나무 빈 사건 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  const id = await lastExecutionId(page);

  await page.goto(`/executions/${id}`);
  await expect(
    page.getByText("기록된 작업이 없어요", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("성공", { exact: true })).toBeVisible();
});

test("깊은 나무를 열어도 좁은 화면과 넓은 화면 모두 가로로 넘치지 않는다", async ({
  page,
}) => {
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({ json: deepTreeFixture(5) });
  });
  await page.goto("/executions/900");

  const tree = page.getByTestId("execution-tree");
  await expect(tree).toBeVisible();
  expect(await tree.locator("[data-testid=execution-node]").count()).toBe(5);

  const viewportWidth = page.viewportSize()?.width;
  expect(viewportWidth).toBeDefined();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(viewportWidth!);
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
      model: "example-model",
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
    await route.fulfill({
      json: singleNodeTreeFixture({
        treeTruncated: true,
        nodeTruncated: false,
      }),
    });
  });
  await page.goto("/executions/970");

  await expect(page.getByTestId("execution-tree-truncated-above")).toHaveText(
    "위쪽 기록이 없어 이곳이 첫 실행이 아닐 수 있어요",
  );
  await expect(page.getByText("이전 실행은 표시되지 않아요")).toHaveCount(0);
});

/**
 * 이 검사는 회귀를 잡는 것이 아니다. 고치기 전 코드에서도 통과한다.
 *
 * <p>위쪽 안내를 「언제나 그린다」 로 잘못 고치는 것을 막는 짝이다.
 * 바로 위의 검사가 이번 결함을 잡고, 이것은 그 고침이 지나치지 않았는지를 본다.
 */
test("노드 아래가 잘린 것이면 위쪽 안내를 따로 그리지 않아 같은 말을 두 번 하지 않는다", async ({
  page,
}) => {
  await page.route("**/api/usage/executions/*/tree", async (route) => {
    await route.fulfill({
      json: singleNodeTreeFixture({ treeTruncated: true, nodeTruncated: true }),
    });
  });
  await page.goto("/executions/970");

  await expect(page.getByTestId("execution-tree-truncated-above")).toHaveCount(
    0,
  );
  await expect(page.getByText("이전 실행은 표시되지 않아요")).toHaveCount(1);
});

const RAW_COMMAND = "ls-원본-명령";
const RAW_RESULT = '{"output":"terminal-raw-result-아주-길게-적어-좁은-화면에서-가로로-넘치는지-확인하는-자리-0123456789-0123456789-0123456789"}';

/** `terminal` 도구를 쓴 실행 하나짜리 나무다. 명령과 결과는 사람 말이 아니라 줄에 그리지 않는 원본이다. */
function terminalTreeFixture() {
  const event = (sequence: number, eventType: string, detail: string) => ({
    sequence, eventType, toolName: "terminal", subagentName: null, hermesSessionId: null, detail, model: null,
    inputTokens: null, outputTokens: null, durationMs: eventType === "TOOL_COMPLETED" ? 2_100 : null,
    failed: eventType === "TOOL_COMPLETED" ? false : null, occurredAt: new Date().toISOString(),
  });
  return {
    truncated: false,
    root: {
      truncated: false, executionId: 980, agentCode: "terminal-agent-code", agentName: "명령 비서", status: "SUCCEEDED",
      model: "example-model", inputTokens: 10, outputTokens: 20, estimatedCostMicros: 1000, latencyMs: 2_500,
      startedAt: new Date().toISOString(), children: [],
      events: [event(1, "TOOL_STARTED", RAW_COMMAND), event(2, "TOOL_COMPLETED", RAW_RESULT)],
    },
  };
}

test("관리자는 붐벼서 실패한 실행의 상세에서 안내 문구와 오류 코드를 함께 본다", async ({ page, hermes }) => {
  await hermes.busy();
  try {
    const response = await page.request.post("/api/chat", {
      data: { text: "실행 나무 붐빔 검사", agentCode: "browser" },
    });
    expect(response.status()).toBe(429);
  } finally {
    await hermes.clearBusy();
  }
  const id = await lastExecutionId(page);

  await page.goto(`/executions/${id}`);
  const row = page.getByTestId("execution-event-row");
  await expect(row).toHaveText("실행 실패: 지금 요청이 많아요. 잠시 뒤 다시 보내 주세요. (HERMES_BUSY)");
  // 머리 요약의 내부 값도 그대로 보인다.
  await expect(page.getByText("입력 토큰", { exact: true })).toBeVisible();
  await expect(page.getByText("환산 금액", { exact: true })).toBeVisible();
});

test("관리자는 도구 결과의 원본을 줄에서 보지 않고 원본 보기를 눌러야 본다", async ({ page }) => {
  await page.route("**/api/usage/executions/*/tree", (route) => route.fulfill({ json: terminalTreeFixture() }));
  await page.goto("/executions/980");

  const terminal = page.getByTestId("execution-tree").locator('[data-tool="terminal"]');
  await expect(terminal).toContainText("작업을 했어요");
  await expect(terminal.getByText(RAW_RESULT)).toBeHidden();
  await expect(terminal.getByText(RAW_COMMAND)).toHaveCount(0);

  const raw = terminal.getByTestId("activity-raw");
  await expect(raw).toHaveCount(1);
  await raw.getByText("원본 보기").click();
  await expect(terminal.getByText(RAW_RESULT)).toBeVisible();
  await expect(raw).toContainText("terminal");
  // 펼친 원본이 좁은 폭에서도 화면을 가로로 밀지 않는다.
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
    .toBeLessThanOrEqual(0);
});

/**
 * `MEMBER` 역할 사용자의 실행 상세에는 오류 코드, 모델, 토큰, 금액, 도구 결과의 원본이 없어야 한다.
 *
 * <p>씨 뿌린 에이전트는 관리자 소유의 비공개라, 전용 사용자로 로그인해 자기 에이전트를 만들고 그것으로 실행을 만든다.
 * mobile 과 desktop 이 같은 Control Plane 을 쓰므로 project 마다 다른 사용자다. 만든 에이전트는 검사가 끝나면 지운다.
 */
test.describe("MEMBER 역할 사용자의 실행 상세", () => {
  const AGENT_NAME = "심부름 비서";

  function memberOf(projectName: string) {
    return { email: `tree-member-${projectName}@example.com`, name: "실행 상세 보는 사용자" };
  }

  async function createAgent(page: import("../../web/node_modules/@playwright/test/index.js").Page): Promise<string> {
    const created = await page.request.post("/api/agents", { data: { name: AGENT_NAME } });
    expect(created.status(), `에이전트를 만들지 못했다: ${created.status()}`).toBe(201);
    return ((await created.json()) as { code: string }).code;
  }

  test.beforeEach(async ({ context, page }, testInfo) => {
    await setSession(context, memberOf(testInfo.project.name));
    expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  });

  test.afterEach(async ({ context, page }, testInfo) => {
    await setSession(context, memberOf(testInfo.project.name));
    const response = await page.request.get("/api/agents");
    const mine = ((await response.json()) as { code: string; ownedByMe: boolean }[]).filter((agent) => agent.ownedByMe);
    for (const agent of mine) {
      const deleted = await page.request.delete(`/api/agents/${agent.code}`);
      expect(deleted.status(), `검사가 만든 에이전트 ${agent.code} 를 지우지 못했다`).toBe(204);
    }
  });

  test("붐벼서 실패한 실행의 상세에 오류 코드 없이 안내 문구만 보인다", async ({ page, hermes }, testInfo) => {
    const agentCode = await createAgent(page);
    await hermes.busy();
    try {
      const response = await page.request.post("/api/chat", { data: { text: "붐빔 상세 검사", agentCode } });
      expect(response.status()).toBe(429);
    } finally {
      await hermes.clearBusy();
    }
    const id = await lastExecutionId(page, memberOf(testInfo.project.name).email);

    await page.goto(`/executions/${id}`);
    await expect(page.getByTestId("execution-event-row")).toHaveText(
      "실행 실패: 지금 요청이 많아요. 잠시 뒤 다시 보내 주세요.",
    );
    const main = page.getByRole("main");
    await expect(main).not.toContainText("HERMES_BUSY");
    // 제목과 머리 요약에는 에이전트 이름, 상태, 고른 단계, 걸린 시간만 남는다.
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(AGENT_NAME);
    await expect(main).not.toContainText(agentCode);
    await expect(main.locator("dt")).toHaveText(["에이전트", "상태", "단계", "걸린 시간"]);
    // 걸린 시간은 밀리초로 보이지 않는다.
    await expect(main.locator("dl")).not.toContainText("ms");
  });

  test("자기 실행의 상세에 도구 결과의 원본을 펼치는 자리가 없다", async ({ page }, testInfo) => {
    const agentCode = await createAgent(page);
    const sent = await page.request.post("/api/chat/stream", { data: { text: "원본 가림 검사", agentCode } });
    expect(sent.ok(), `대화를 보내지 못했다: ${sent.status()}`).toBeTruthy();
    await sent.text();
    const id = await lastExecutionId(page, memberOf(testInfo.project.name).email);

    await page.goto(`/executions/${id}`);
    const tree = page.getByTestId("execution-tree");
    await expect(tree.locator('[data-tool="fake-tool"]')).toContainText("도구를 썼어요");
    await expect(page.getByTestId("activity-raw")).toHaveCount(0);
  });

  test("도구 결과의 원본이 응답에 와도 줄에도 원본 보기에도 그리지 않는다", async ({ page }) => {
    await page.route("**/api/usage/executions/*/tree", (route) => route.fulfill({ json: terminalTreeFixture() }));
    await page.goto("/executions/980");

    const terminal = page.getByTestId("execution-tree").locator('[data-tool="terminal"]');
    await expect(terminal).toContainText("작업을 했어요");
    await expect(page.getByTestId("activity-raw")).toHaveCount(0);
    await expect(page.getByText(RAW_RESULT)).toHaveCount(0);
    // 에이전트 코드와 모델은 응답에 있어도 그리지 않는다.
    await expect(page.getByRole("main")).not.toContainText("terminal-agent-code");
    await expect(page.getByRole("main")).not.toContainText("example-model");
    // 나무가 준 걸린 시간 2.5초는 초 단위로만 보인다.
    await expect(page.getByRole("main").locator("dl")).not.toContainText("ms");
    await expect(page.getByRole("main").locator("dl dd").last()).toHaveText("2초");
  });
});
