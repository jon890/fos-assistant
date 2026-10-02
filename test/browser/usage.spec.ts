import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { encode } from "../../web/node_modules/next-auth/jwt.js";
import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import { expect, setSession, test, SWITCH_AGENT_CODE } from "./fixtures.ts";
import { AUTH_SECRET, CONTROL_PLANE_BASE_URL, JWT_SECRET, TEST_EMAIL, WEB_BASE_URL } from "./settings.ts";

const SESSION_COOKIE = "authjs.session-token";

async function controlPlaneToken(): Promise<string> {
  return new SignJWT({ name: "브라우저 테스트" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(TEST_EMAIL)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
}

test("화면 폭에 맞춰 실행 기록을 카드나 표로 보인다", async ({ page }, testInfo) => {
  const response = await page.request.post("/api/chat", {
    data: { text: "사용량 화면 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  await page.goto("/usage?tab=executions");

  const cards = page.getByTestId("execution-cards");
  const table = page.getByTestId("execution-table");
  if (testInfo.project.name === "mobile") {
    await expect(cards).toBeVisible();
    await expect(table).toBeHidden();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  } else {
    await expect(table).toBeVisible();
    await expect(cards).toBeHidden();
  }
  // 관리자는 provider id 원문 대신 표시 이름을 본다.
  const list = testInfo.project.name === "mobile" ? cards : table;
  await expect(list).toContainText("ChatGPT 구독");
  await expect(list).not.toContainText("openai-codex");
});

test("이번 달 합계와 가격을 찾지 못한 실행을 구분한다", async ({ page }, testInfo) => {
  const response = await page.request.post("/api/chat", {
    data: { text: "가격 없음 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  const freeResponse = await page.request.post("/api/chat", {
    data: { text: "무료 모델 검사", agentCode: "browser" },
  });
  expect(freeResponse.ok()).toBeTruthy();
  await page.goto("/usage");

  await expect(page.getByText("API 가격으로 계산한 금액", { exact: true })).toBeVisible();
  await expect(page.getByText("가격을 찾지 못한 실행", { exact: true })).toBeVisible();
  await page.goto("/usage?tab=executions");
  const records = page.getByTestId(testInfo.project.name === "mobile" ? "execution-cards" : "execution-table");
  await expect(records.getByText("가격 없음").first()).toBeVisible();
  await expect(records.getByText("0.0000 USD").first()).toBeVisible();
});

test("금액을 확인하지 못한 도우미 수를 요약에 보인다", async ({ page }) => {
  test.setTimeout(60_000);
  const response = await page.request.post("/api/chat/stream", {
    data: { text: "자식 완료 사건 없음 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  // 부모가 끝나면 자식은 금액을 확인하기 전까지 세어진다. 재조회가 끝나기 전에도 건수에는 들어 있다.
  await expect(async () => {
    await page.goto("/usage");
    await expect(page.getByText("금액을 확인하지 못한 도우미", { exact: true })).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
});

test("돌고 있는 실행은 시간과 금액 없이 보이고 완료 뒤에 끝난다", async ({ page, hermes }, testInfo) => {
  await hermes.holdNextRun();
  const chat = page.request.post("/api/chat", {
    data: { text: "진행 중 실행 검사", agentCode: "browser" },
  });
  try {
    await hermes.waitForHeldRun();

    await page.goto("/usage?tab=executions");
    const records = page.getByTestId(testInfo.project.name === "mobile" ? "execution-cards" : "execution-table");
    const running = records.getByText("실행 중", { exact: true });
    await expect(running).toBeVisible();
    const container = testInfo.project.name === "mobile"
      ? running.locator("xpath=ancestor::article")
      : running.locator("xpath=ancestor::tr");
    await expect(container.getByTestId("execution-duration")).toHaveText("");
    await expect(container.getByTestId("execution-cost")).toHaveText("");
  } finally {
    await hermes.releaseHeldRun();
  }
  expect((await chat).ok()).toBeTruthy();
});

test("고아 실행은 중간에 끊겼다고 보인다", async ({ page }, testInfo) => {
  const response = await page.request.post("/api/chat", {
    data: { text: "고아 실행 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  const orphan = await page.request.post(
    `${CONTROL_PLANE_BASE_URL}/api/v1/test-support/usage/last-execution/orphaned`,
    { headers: { Authorization: `Bearer ${await controlPlaneToken()}` } },
  );
  expect(orphan.ok()).toBeTruthy();
  await page.goto("/usage?tab=executions");

  const records = page.getByTestId(testInfo.project.name === "mobile" ? "execution-cards" : "execution-table");
  await expect(records.getByText("중간에 중단됨", { exact: true }).first()).toBeVisible();
});

test("실행 기록이 없으면 빈 상태를 보인다", async ({ context, page }) => {
  const token = await encode({
    salt: SESSION_COOKIE,
    secret: AUTH_SECRET,
    token: { sub: "empty@example.com", email: "empty@example.com", name: "빈 사용자" },
  });
  await context.addCookies([{
    name: SESSION_COOKIE,
    value: token,
    url: WEB_BASE_URL,
    httpOnly: true,
    sameSite: "Lax",
  }]);

  const provision = await page.request.get("/api/agents");
  expect(provision.ok()).toBeTruthy();

  await page.goto("/usage?tab=executions");
  await expect(page.getByText("아직 실행 기록이 없어요", { exact: true })).toBeVisible();
});

test("막힌 모델로 실패한 실행은 모델을 쓸 수 없다고 보인다", async ({ page, hermes }, testInfo) => {
  const blockedProvider = `usage-blocked-${testInfo.project.name}`;
  const created = await page.request.post("/api/chat/conversations", {
    data: { agentCode: SWITCH_AGENT_CODE },
  });
  expect(created.ok(), `빈 대화를 만들지 못했다: ${created.status()}`).toBeTruthy();
  const { conversationId } = (await created.json()) as { conversationId: string };
  const chosen = await page.request.put(`/api/chat/conversations/${conversationId}/model`, {
    data: { provider: blockedProvider, model: "blocked-model", reasoningEffort: null },
  });
  expect(chosen.ok(), `모델을 고르지 못했다: ${chosen.status()}`).toBeTruthy();

  await hermes.blockProvider(blockedProvider);
  try {
    const response = await page.request.post("/api/chat", {
      data: { conversationId, text: "사용량 막힘 검사" },
    });
    expect(response.ok()).toBeFalsy();
    expect(((await response.json()) as { code: string }).code).toBe("PROVIDER_BLOCKED");
  } finally {
    await hermes.clearBlockedProviders();
  }

  await page.goto("/usage?tab=executions");
  const records = page.getByTestId(
    testInfo.project.name === "mobile" ? "execution-cards" : "execution-table",
  );
  await expect(records.getByText("모델을 쓸 수 없음").first()).toBeVisible();
});

/**
 * 공유 gateway 의 동시 실행 한도에 닿아 거절당한 실행을 보통 실패와 다르게 보이는지 본다.
 *
 * <p>같은 문구로 보이면 한도를 올려야 하는지 이 화면으로 판단할 수 없다.
 */
test("붐벼서 거절된 실행은 다른 실패와 다르게 보인다", async ({ page, hermes }, testInfo) => {
  await hermes.busy();
  try {
    const response = await page.request.post("/api/chat", {
      data: { text: "붐빔 화면 검사", agentCode: "browser" },
    });
    expect(response.status()).toBe(429);
  } finally {
    await hermes.clearBusy();
  }

  await page.goto("/usage?tab=executions");
  const records = page.getByTestId(
    testInfo.project.name === "mobile" ? "execution-cards" : "execution-table",
  );
  await expect(records.getByText("요청이 많아 거절됨", { exact: true }).first()).toBeVisible();
});

/**
 * 요청한 effort 가 실행 한 줄에 보이고, 고르지 않은 실행은 「기본」 으로 보이는지 본다.
 *
 * <p>다른 검사가 남긴 실행이 목록에 섞여 있으므로 응답의 실행 번호로 이 검사의 줄을 특정한다.
 */
test("실행 기록에 요청한 effort 가 보이고 고르지 않으면 기본으로 보인다", async ({ page }, testInfo) => {
  const created = await page.request.post("/api/chat/conversations", {
    data: { agentCode: SWITCH_AGENT_CODE },
  });
  expect(created.ok(), `빈 대화를 만들지 못했다: ${created.status()}`).toBeTruthy();
  const { conversationId } = (await created.json()) as { conversationId: string };
  const chosen = await page.request.put(`/api/chat/conversations/${conversationId}/model`, {
    data: { provider: null, model: null, reasoningEffort: "high" },
  });
  expect(chosen.ok(), `effort 를 고르지 못했다: ${chosen.status()}`).toBeTruthy();

  const withEffort = await page.request.post("/api/chat", {
    data: { conversationId, text: `effort 표시 검사 ${testInfo.project.name}` },
  });
  expect(withEffort.ok(), `effort 를 고른 대화의 보내기가 실패했다: ${withEffort.status()}`).toBeTruthy();
  const withEffortId = ((await withEffort.json()) as { executionId: number }).executionId;

  const byDefault = await page.request.post("/api/chat", {
    data: { text: `기본 effort 표시 검사 ${testInfo.project.name}`, agentCode: "browser" },
  });
  expect(byDefault.ok(), `기본값 보내기가 실패했다: ${byDefault.status()}`).toBeTruthy();
  const byDefaultId = ((await byDefault.json()) as { executionId: number }).executionId;

  await page.goto("/usage?tab=executions");
  const rowOf = (executionId: number) => {
    const link = page.locator(`a[href="/executions/${executionId}"]`);
    return testInfo.project.name === "mobile"
      ? link.locator("xpath=ancestor::article")
      : link.locator("xpath=ancestor::tr");
  };
  await expect(rowOf(withEffortId).getByTestId("execution-effort")).toHaveText("high");
  await expect(rowOf(byDefaultId).getByTestId("execution-effort")).toHaveText("기본");
});

const TAB_LABELS = ["요약", "실행 기록", "스킬", "설정별 사용량"];

function usageTabs(page: Page) {
  return page.getByRole("navigation", { name: "사용량 탭" });
}

test("탭 넷이 보이고 주소의 tab 값이 고른 탭이 된다", async ({ page }) => {
  await page.goto("/usage");
  for (const label of TAB_LABELS) {
    await expect(usageTabs(page).getByRole("link", { name: label, exact: true })).toBeVisible();
  }
  await expect(usageTabs(page).getByRole("link", { name: "요약", exact: true })).toHaveAttribute("aria-current", "page");

  await page.goto("/usage?tab=skills");
  await expect(usageTabs(page).getByRole("link", { name: "스킬", exact: true })).toHaveAttribute("aria-current", "page");
  await expect(usageTabs(page).getByRole("link", { name: "요약", exact: true })).not.toHaveAttribute("aria-current", "page");

  // 모르는 값은 요약으로 본다.
  await page.goto("/usage?tab=nothing");
  await expect(usageTabs(page).getByRole("link", { name: "요약", exact: true })).toHaveAttribute("aria-current", "page");

  await usageTabs(page).getByRole("link", { name: "실행 기록", exact: true }).click();
  // 탭은 서버 컴포넌트의 Link 라 다음 화면을 서버에서 받은 뒤에 주소가 바뀐다.
  // 다른 검사와 함께 돌아 서버가 바쁘면 기본 5초를 넘겨, 이 단언만 기다리는 시간을 늘린다.
  await expect(page).toHaveURL(/\/usage\?tab=executions$/, { timeout: 15_000 });
  await expect(usageTabs(page).getByRole("link", { name: "실행 기록", exact: true })).toHaveAttribute("aria-current", "page");
});

test("모델이 스킬을 읽은 대화는 스킬 탭에 보이고 누르면 그 대화로 가며 실행 기록 줄에 스킬 이름이 붙는다", async ({ page }, testInfo) => {
  const skillRow = () => page.getByTestId("skill-usage-list").locator("li").filter({ hasText: "브라우저 비서" });
  const countBefore = async (): Promise<number> => {
    const response = await page.request.get(`${CONTROL_PLANE_BASE_URL}/api/v1/usage/skills`, {
      headers: { Authorization: `Bearer ${await controlPlaneToken()}` },
    });
    expect(response.ok()).toBeTruthy();
    const rows = (await response.json()) as { agentCode: string; skillName: string; count: number }[];
    return rows.find((row) => row.agentCode === "browser" && row.skillName === "shopping")?.count ?? 0;
  };

  // 두 폭의 검사가 같은 에이전트를 쓰므로 앞 검사가 남긴 횟수를 기준으로 한 번 늘었는지 본다.
  const before = await countBefore();
  // 스킬 읽기 사건은 스트리밍 경로로만 흘러 들어온다. 응답을 다 받으면 실행이 끝난 것이다.
  const sent = await page.request.post("/api/chat/stream", {
    data: { text: "스킬 읽기 검사", agentCode: "browser" },
  });
  expect(sent.ok(), `스킬을 읽는 대화가 실패했다: ${sent.status()}`).toBeTruthy();
  await sent.text();
  const latest = await page.request.get(`${CONTROL_PLANE_BASE_URL}/api/v1/usage/executions?limit=1`, {
    headers: { Authorization: `Bearer ${await controlPlaneToken()}` },
  });
  expect(latest.ok()).toBeTruthy();
  const [{ id: executionId, conversationId }] = (await latest.json()) as { id: number; conversationId: string }[];

  await page.goto("/usage?tab=skills");
  const row = skillRow().filter({ hasText: "shopping" });
  await expect(row.getByText(`${before + 1}회`, { exact: true })).toBeVisible();
  await row.getByRole("link").click();
  await expect(page).toHaveURL(new RegExp(`/chat/${conversationId}$`));

  await page.goto("/usage?tab=executions");
  const link = page.locator(`a[href="/executions/${executionId}"]`);
  const executionRow = testInfo.project.name === "mobile"
    ? link.locator("xpath=ancestor::article")
    : link.locator("xpath=ancestor::tr");
  await expect(executionRow.getByTestId("execution-skills")).toHaveText("스킬 shopping");
});

test("스킬을 부른 적이 없으면 스킬 탭이 빈 상태를 보인다", async ({ context, page }) => {
  const token = await encode({
    salt: SESSION_COOKIE,
    secret: AUTH_SECRET,
    token: { sub: "empty@example.com", email: "empty@example.com", name: "빈 사용자" },
  });
  await context.addCookies([{
    name: SESSION_COOKIE,
    value: token,
    url: WEB_BASE_URL,
    httpOnly: true,
    sameSite: "Lax",
  }]);
  expect((await page.request.get("/api/agents")).ok()).toBeTruthy();

  await page.goto("/usage?tab=skills");
  await expect(page.getByText("아직 부른 스킬이 없어요.", { exact: true })).toBeVisible();
});

const RAW_COMMAND = "ls-원본-명령";
const RAW_RESULT = '{"output":"terminal-raw-result"}';

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

/**
 * `MEMBER` 역할 사용자의 사용량 화면에는 금액, 모델, effort, 문맥 글자 수 같은 내부 값이 없어야 한다.
 *
 * <p>씨 뿌린 에이전트는 관리자 소유의 비공개라, 전용 사용자로 로그인해 자기 에이전트를 만들고 그것으로 실행을 만든다.
 * mobile 과 desktop 이 같은 Control Plane 을 쓰므로 project 마다 다른 사용자다. 만든 에이전트는 검사가 끝나면 지운다.
 */
test.describe("MEMBER 역할 사용자의 사용량 화면", () => {
  const AGENT_NAME = "숙제 비서";

  function memberOf(projectName: string) {
    return { email: `usage-member-${projectName}@example.com`, name: "사용량 보는 사용자" };
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

  test("실행 기록과 요약과 대화의 작업 과정에 내부 값이 없다", async ({ page }, testInfo) => {
    const created = await page.request.post("/api/agents", { data: { name: AGENT_NAME } });
    expect(created.status(), `에이전트를 만들지 못했다: ${created.status()}`).toBe(201);
    const agentCode = ((await created.json()) as { code: string }).code;

    // 자식이 달린 실행을 먼저 만든다. 부모가 끝나면 그 자식은 바로 확인 중으로 세어지므로,
    // 아래에서 문구가 없는 것이 자식이 없어서가 아니라 역할 때문임을 구분한다.
    const withChild = await page.request.post("/api/chat/stream", {
      data: { text: "자식 완료 사건 없음 검사", agentCode: agentCode },
    });
    expect(withChild.ok()).toBeTruthy();

    // 대화에서 보내야 도구 사건이 실린 실행이 생긴다.
    await page.goto("/");
    await page.getByRole("textbox", { name: "메시지" }).fill("사용량 가림 검사");
    await page.getByRole("button", { name: "보내기" }).click();
    const block = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
    await expect(block).toBeVisible({ timeout: 30_000 });
    // 가짜 도구는 줄에 그릴 원본이 없어 관리자에게도 원본 자리가 생기지 않는다.
    // 원본이 있는 `terminal` 도구의 나무로 바꿔야 역할에 따라 가리는지 드러난다.
    await page.route("**/api/usage/executions/*/tree", (route) => route.fulfill({ json: terminalTreeFixture() }));
    await block.getByTestId("activity-toggle").click();
    // 도구 줄은 보이지만 도구 결과의 원본을 펼치는 자리는 없다.
    await expect(block.locator('[data-tool="terminal"]')).toBeVisible();
    await expect(page.getByTestId("activity-raw")).toHaveCount(0);
    await expect(block.getByText(RAW_RESULT)).toHaveCount(0);
    await page.unroute("**/api/usage/executions/*/tree");

    await page.goto("/usage?tab=executions");
    const records = page.getByTestId(testInfo.project.name === "mobile" ? "execution-cards" : "execution-table");
    await expect(records).toBeVisible();
    await expect(records.getByText(AGENT_NAME, { exact: true }).first()).toBeVisible();
    await expect(records.getByText("성공", { exact: true }).first()).toBeVisible();
    for (const hidden of ["USD", "example-model", "effort", "문맥", "가격 없음"]) {
      await expect(records, `실행 기록에 「${hidden}」 이 보인다`).not.toContainText(hidden);
    }
    await expect(records.getByTestId("execution-cost")).toHaveCount(0);
    await expect(records.getByTestId("execution-effort")).toHaveCount(0);
    // 걸린 시간은 밀리초가 아니라 초로 보인다.
    await expect(records.getByTestId("execution-duration").first()).toHaveText(/^(1초 미만|\d+초|\d+분 \d+초)$/);

    await page.goto("/usage");
    await expect(page.getByText("이번 달에 비서와 한 일을 모아 보여 드려요.", { exact: true })).toBeVisible();
    await expect(page.getByText("이번 달 실행", { exact: true })).toBeVisible();
    await expect(page.getByText("금액을 확인하지 못한 도우미")).toHaveCount(0);
    await expect(page.getByText("API 가격")).toHaveCount(0);
    await expect(page.getByRole("main")).not.toContainText("USD");
    await expect(page.getByTestId("breakdown-axis")).toHaveCount(0);
    await expect(usageTabs(page).getByRole("link")).toHaveText(["요약", "실행 기록", "스킬"]);

    // 설정별 사용량 탭의 주소로 와도 요약이 열린다.
    await page.goto("/usage?tab=fingerprints");
    await expect(usageTabs(page).getByRole("link", { name: "요약", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(page.getByText("이번 달 실행", { exact: true })).toBeVisible();
    await expect(page.getByTestId("fingerprint-section")).toHaveCount(0);
  });
});
