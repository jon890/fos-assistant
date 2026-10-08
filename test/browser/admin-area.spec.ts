import {
  bindDemoConnector,
  connectDemoConnector,
  disconnectDemoConnector,
  expect,
  setSession,
  test,
} from "./fixtures.ts";
import { FAKE_USAGE } from "../e2e/fake-hermes.ts";
import type { Page, TestInfo } from "../../web/node_modules/@playwright/test/index.js";

/** 화면 높이(모바일 844px)를 넘길 만큼의 대화 수다. 사이드바는 첫 쪽으로 30개를 읽는다. */
const CONVERSATION_COUNT = 30;

async function openSidebar(page: Page, testInfo: TestInfo): Promise<void> {
  if (testInfo.project.name === "mobile") {
    await page.getByRole("button", { name: "사이드바 열기" }).click();
  }
}

async function createConversation(page: Page): Promise<string> {
  const created = await page.request.post("/api/chat/conversations", { data: { agentCode: "browser" } });
  expect(created.ok(), `대화를 만들지 못했다: ${created.status()}`).toBeTruthy();
  return ((await created.json()) as { conversationId: string }).conversationId;
}

/** 일반 화면의 사이드바에서 관리자 입구를 눌러 관리자 영역으로 들어간다. */
async function enterAdminArea(page: Page, testInfo: TestInfo): Promise<void> {
  await openSidebar(page, testInfo);
  await page.getByTestId("admin-entry").click();
  await expect(page).toHaveURL(/\/admin\/people$/);
}

test("대화가 화면 높이보다 많아도 관리자 입구는 뷰포트 안에 있다", async ({ page }, testInfo) => {
  test.setTimeout(60_000);
  for (let index = 0; index < CONVERSATION_COUNT; index += 1) await createConversation(page);

  await page.goto("/");
  await openSidebar(page, testInfo);
  const list = page.getByRole("navigation", { name: "대화 목록" });
  await expect(list.locator('a[href^="/chat/"]').first()).toBeVisible();
  // 목록이 실제로 넘쳐 그 안에서 스크롤하는 상태여야 이 검사가 뜻을 갖는다.
  const overflow = await list.evaluate((element) => element.scrollHeight - element.clientHeight);
  expect(overflow, "대화 목록이 화면 높이를 넘지 않았다").toBeGreaterThan(0);

  await expect(page.getByTestId("admin-entry")).toBeInViewport({ ratio: 1 });
});

test("화면 높이가 360px 여도 관리자 입구는 뷰포트 안에 있고 메뉴는 그 안에서 스크롤한다", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: page.viewportSize()?.width ?? 390, height: 360 });
  await page.goto("/");
  await openSidebar(page, testInfo);

  await expect(page.getByTestId("admin-entry")).toBeInViewport({ ratio: 1 });

  // 메뉴 구역이 줄어들어도 마지막 링크까지 스크롤해 닿을 수 있고, 그 뒤에도 입구는 그대로 보인다.
  const lastLink = page.getByRole("navigation", { name: "주요 화면" }).getByRole("link", { name: "사용량", exact: true });
  await lastLink.scrollIntoViewIfNeeded();
  await expect(lastLink).toBeInViewport();
  await expect(page.getByTestId("admin-entry")).toBeInViewport({ ratio: 1 });
});

test("MEMBER 역할에는 관리자 입구가 없고 관리자 영역의 주소는 홈으로 넘어간다", async ({ context, page }, testInfo) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });

  await page.goto("/");
  await openSidebar(page, testInfo);
  // 맨 아래 줄이 그려진 뒤에 입구가 없는지 본다.
  await expect(page.getByText("가족 사용자", { exact: true })).toBeVisible();
  await expect(page.getByTestId("admin-entry")).toHaveCount(0);

  await page.goto("/admin/people");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("navigation", { name: "관리자 메뉴" })).toHaveCount(0);
});

test("관리자 입구를 누르면 사이드바 없는 관리자 영역이 열린다", async ({ page }, testInfo) => {
  await page.goto("/");
  await enterAdminArea(page, testInfo);

  await expect(page.getByRole("complementary", { name: "사이드바" })).toHaveCount(0);
  await expect(page.getByRole("dialog", { name: "사이드바" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "사이드바 열기" })).toHaveCount(0);

  const menu = page.getByRole("navigation", { name: "관리자 메뉴" });
  await expect(menu).toBeVisible();
  await expect(menu.getByRole("link")).toHaveText(["사용자", "에이전트", "모델", "도구", "사용량과 비용", "커넥터", "브라우저"]);
  await expect(menu.getByRole("link", { name: "사용자", exact: true })).toHaveAttribute("aria-current", "page");
  await expect(menu.locator('[aria-current="page"]')).toHaveCount(1);
  await expect(page.getByRole("banner").getByText("관리자", { exact: true })).toBeVisible();
  // 좁은 화면의 메뉴는 가로로 밀리는 한 줄이라 본문이 옆으로 넘치지 않는다.
  expect(await page.evaluate(() => document.documentElement.scrollWidth))
    .toBeLessThanOrEqual(page.viewportSize()?.width ?? 0);
});

test("/admin 을 바로 열면 사용자 화면으로 넘어간다", async ({ page }) => {
  await page.goto("/admin");
  await expect(page).toHaveURL(/\/admin\/people$/);
  await expect(page.getByRole("navigation", { name: "관리자 메뉴" })).toBeVisible();
});

test("사용 화면으로 돌아가기는 마지막으로 보던 대화로 간다", async ({ page }, testInfo) => {
  const id = await createConversation(page);
  await page.goto(`/chat/${id}`);
  await enterAdminArea(page, testInfo);

  await page.getByRole("link", { name: "사용 화면으로 돌아가기" }).click();
  await expect(page).toHaveURL(new RegExp(`/chat/${id}$`));
  await expect(page.getByRole("navigation", { name: "관리자 메뉴" })).toHaveCount(0);
});

test("대화를 연 적이 없으면 사용 화면으로 돌아가기는 홈으로 간다", async ({ page }, testInfo) => {
  await page.goto("/");
  await enterAdminArea(page, testInfo);

  await page.getByRole("link", { name: "사용 화면으로 돌아가기" }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("textbox", { name: "메시지" })).toBeVisible();
});

test("일반 화면의 에이전트 목록에는 관리 양식이 없고 ADMIN 도 새 에이전트를 만든다", async ({ page }) => {
  await page.goto("/agents");
  await expect(page.getByRole("heading", { name: "에이전트", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "새 에이전트" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "에이전트 등록" })).toHaveCount(0);
});

test("관리자 영역의 에이전트 목록은 등록 양식을 갖고 상세 보기는 관리자 영역으로 간다", async ({ page }) => {
  await page.goto("/admin/agents");
  await expect(page.getByRole("heading", { name: "에이전트 등록" })).toBeVisible();
  await expect(page.getByRole("button", { name: "새 에이전트" })).toHaveCount(0);

  const card = page
    .getByRole("region", { name: "등록된 에이전트" })
    .locator("article")
    .filter({ hasText: "브라우저 비서" });
  await card.getByRole("link", { name: "상세 보기" }).click();
  await expect(page).toHaveURL(/\/admin\/agents\/browser$/);
  await expect(page.getByRole("region", { name: "관리" })).toBeVisible();
  await expect(page.getByTestId("agent-model-section")).toBeVisible();
});

test("관리 절에서 먼저 살펴보기 쓰기 허용을 켜면 위험 문구가 보이고 새로 고쳐도 남으며 끄면 사라진다", async ({ page }) => {
  const risk = "켜면 먼저 살펴보기가 셸, 파일, 브라우저, 외부 메시지 같은 관리자 도구를 써서, 웹 결과 속 글이 명령 실행이나 외부 연락으로 이어질 수 있어요";
  try {
    await page.goto("/admin/agents/browser");
    const admin = page.getByRole("region", { name: "관리" });
    const toggle = admin.getByRole("switch", { name: "먼저 살펴보기에 쓰기 도구 허용" });
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await expect(admin.getByText(risk)).toHaveCount(0);

    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await expect(admin.getByText(risk)).toBeVisible();

    // 저장됐는지는 서버에서 다시 읽은 화면으로 본다.
    await page.reload();
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await expect(admin.getByText(risk)).toBeVisible();

    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await expect(admin.getByText(risk)).toHaveCount(0);
  } finally {
    // 중간에 실패해도 다른 검사가 쓰는 에이전트를 꺼진 상태로 되돌린다.
    const restored = await page.request.patch("/api/admin/agents/browser", {
      data: { enabled: true, visibility: "PRIVATE", ownerEmail: null, proactiveCheckWritesAllowed: false },
    });
    expect(restored.ok(), `쓰기 허용을 되돌리지 못했다: ${restored.status()}`).toBeTruthy();
  }
});

test("일반 화면의 에이전트 상세에는 관리 절과 모델 절이 없다", async ({ page }) => {
  await page.goto("/agents/browser");
  await expect(page.getByRole("heading", { name: "브라우저 비서" })).toBeVisible();
  await expect(page.getByRole("region", { name: "관리" })).toHaveCount(0);
  await expect(page.getByTestId("agent-model-section")).toHaveCount(0);
});

test("연결 반영 확인은 관리자 영역에 있고 일반 연결 화면에는 없다", async ({ page }) => {
  await page.goto("/admin/connections");
  await expect(page.getByRole("heading", { name: "커넥터", exact: true })).toBeVisible();
  await expect(page.getByTestId("connector-admin-panel")).toBeVisible();

  await page.goto("/connections");
  await expect(page.getByRole("heading", { name: "외부 서비스 연결", exact: true })).toBeVisible();
  await expect(page.getByTestId("connector-admin-panel")).toHaveCount(0);
});

test("관리자 연결 목록은 붙인 에이전트마다 한 줄이고 재시작이 필요 없는 바인딩은 반영 중이고 다시 확인하는 반영 완료 단추가 있다", async ({ context, page }, testInfo) => {
  // mobile 과 desktop 이 같은 Control Plane 을 쓰므로 project 마다 다른 사용자를 둔다.
  const owner = { email: `admin-bindings-${testInfo.project.name}@example.com`, name: `반영 확인 사용자 ${testInfo.project.name}` };
  await setSession(context, owner);
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  await connectDemoConnector(owner.email);
  const codes: string[] = [];
  try {
    for (const name of ["첫째 비서", "둘째 비서"]) {
      const created = await page.request.post("/api/agents", { data: { name } });
      expect(created.status(), `에이전트를 만들지 못했다: ${created.status()}`).toBe(201);
      const { code } = (await created.json()) as { code: string };
      codes.push(code);
      const bound = await bindDemoConnector(owner.email, code);
      // 새 서버만 더한 붙이기는 재시작이 필요 없다. 반영 지연(기본 150초) 안에는 PENDING 이다.
      expect(bound).toMatchObject({ bound: true, status: "PENDING", restartRequired: false });
    }

    await setSession(context, { email: "browser@example.com", name: "브라우저 테스트" });
    await page.goto("/admin/connections");
    const rows = page.getByTestId("connector-admin-panel").getByRole("listitem").filter({ hasText: owner.name });
    await expect(rows).toHaveCount(2);
    const first = rows.filter({ hasText: `에이전트 ${codes[0]}` });
    const second = rows.filter({ hasText: `에이전트 ${codes[1]}` });
    await expect(first).toContainText("반영 중");
    await expect(second).toContainText("반영 중");
    // 예약 확인이 실패해 남은 줄도 관리자가 다시 확인할 수 있도록 단추가 있고, 재시작을 요구하지 않는다.
    for (const row of [first, second]) {
      await expect(row.getByRole("button", { name: "반영 완료" })).toBeVisible();
      await expect(row).toContainText("몇 분이 지나도 남아 있으면 눌러 다시 확인해요.");
      await expect(row).not.toContainText("재시작한 뒤 눌러 주세요");
    }
  } finally {
    await setSession(context, owner);
    for (const code of codes) {
      const deleted = await page.request.delete(`/api/agents/${code}`);
      expect(deleted.status(), `검사가 만든 에이전트 ${code} 를 지우지 못했다`).toBe(204);
    }
    await disconnectDemoConnector(owner.email);
  }
});

test("MEMBER 역할이 관리자 영역의 에이전트 주소를 열면 홈으로 넘어간다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  await page.goto("/admin/agents");
  await expect(page).toHaveURL(/\/$/);
  await page.goto("/admin/agents/browser");
  await expect(page).toHaveURL(/\/$/);
});

test("ADMIN 역할도 일반 사용량 화면에서는 금액과 설정별 사용량 탭과 사용량 내역을 보지 않고 실행 건수를 본다", async ({ page }) => {
  const sent = await page.request.post("/api/chat", { data: { text: "일반 사용량 검사", agentCode: "browser" } });
  expect(sent.ok()).toBeTruthy();

  await page.goto("/usage");
  await expect(page.getByRole("heading", { name: "사용량", exact: true, level: 1 })).toBeVisible();
  await expect(page.getByText("이번 달 실행", { exact: true })).toBeVisible();
  await expect(page.getByText("API 가격으로 계산한 금액", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("main")).not.toContainText("USD");
  await expect(page.getByRole("link", { name: "설정별 사용량", exact: true })).toHaveCount(0);
  await expect(page.getByText("사용량 내역", { exact: true })).toHaveCount(0);
  await expect(page.getByTestId("breakdown-axis")).toHaveCount(0);
});

test("관리자 영역의 사용량에는 금액과 탭 넷이 있고 실행 한 줄은 관리자 영역의 상세로 간다", async ({ page }) => {
  const sent = await page.request.post("/api/chat", { data: { text: "관리자 사용량 검사", agentCode: "browser" } });
  expect(sent.ok()).toBeTruthy();
  const executionId = ((await sent.json()) as { executionId: number }).executionId;

  await page.goto("/admin/usage");
  await expect(page.getByRole("heading", { name: "사용량과 비용", exact: true, level: 1 })).toBeVisible();
  await expect(page.getByText("API 가격으로 계산한 금액", { exact: true })).toBeVisible();
  const tabs = page.getByRole("navigation", { name: "사용량 탭" });
  await expect(tabs.getByRole("link")).toHaveText(["요약", "실행 기록", "스킬", "설정별 사용량"]);

  await tabs.getByRole("link", { name: "설정별 사용량", exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/usage\?tab=fingerprints$/, { timeout: 15_000 });

  await page.goto("/admin/usage?tab=executions");
  const link = page.locator(`a[href="/admin/executions/${executionId}"]:visible`);
  await expect(link).toHaveCount(1);
  await link.click();
  await expect(page).toHaveURL(new RegExp(`/admin/executions/${executionId}$`), { timeout: 15_000 });
  // 라벨만 있고 값이 `-` 로 비면 관리자 영역이 내부 값을 받지 못한 것이다. 가짜 Hermes 가 답한 모델과 토큰 수와 견준다.
  const valueOf = (label: string) =>
    page.locator("dl > div").filter({ has: page.getByText(label, { exact: true }) }).locator("dd");
  await expect(valueOf("모델")).toHaveText("ChatGPT 구독 · example-model");
  await expect(valueOf("입력 토큰")).toHaveText(String(FAKE_USAGE.input_tokens));
  await expect(valueOf("출력 토큰")).toHaveText(String(FAKE_USAGE.output_tokens));
  await expect(page.getByText("환산 금액", { exact: true })).toBeVisible();
});

test("ADMIN 역할도 일반 실행 상세에서는 모델과 토큰과 원본 보기를 보지 않는다", async ({ page }) => {
  await page.route("**/api/usage/executions/*/tree", (route) => route.fulfill({ json: terminalFixture() }));
  await page.goto("/executions/980");

  const terminal = page.getByTestId("execution-tree").locator('[data-tool="terminal"]');
  await expect(terminal).toContainText("작업을 했어요");
  await expect(page.getByTestId("activity-raw")).toHaveCount(0);
  await expect(page.getByText("입력 토큰", { exact: true })).toHaveCount(0);
  await expect(page.getByText("환산 금액", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("main")).not.toContainText("example-model");
  await expect(page.getByTestId("execution-node-tokens")).toHaveCount(0);
});

test("MEMBER 역할이 관리자 영역의 사용량 주소를 열면 홈으로 넘어간다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  await page.goto("/admin/usage");
  await expect(page).toHaveURL(/\/$/);
  await page.goto("/admin/executions/980");
  await expect(page).toHaveURL(/\/$/);
});

test("ADMIN 역할의 대화 화면 설정 창에는 그룹 모델 설정 단추가 없다", async ({ page }) => {
  await page.goto("/");
  await page.getByTestId("model-tier-settings").click();
  await expect(page.getByRole("dialog", { name: "모델 단계 설정" })).toBeVisible();
  await expect(page.getByRole("button", { name: "그룹 모델 설정" })).toHaveCount(0);
});

test("관리자 영역의 모델 화면에서 그룹 기본 단계를 저장하면 새로 고쳐도 남고 모델 숨김 양식이 보인다", async ({ page }) => {
  await page.goto("/admin/models");
  await expect(page.getByRole("heading", { name: "모델", exact: true, level: 1 })).toBeVisible();
  const hidden = page.getByRole("region", { name: "모델 숨김" });
  await expect(hidden.getByRole("heading", { name: "모델 숨김", exact: true })).toBeVisible();
  await expect(hidden.getByRole("button", { name: "저장" })).toBeVisible();

  const section = page.getByRole("region", { name: "그룹 모델 설정" });
  const defaultTier = section.getByLabel("그룹 기본 단계");
  await expect(defaultTier).toHaveValue("");
  try {
    await defaultTier.selectOption("BALANCED");
    await section.getByRole("button", { name: "저장" }).click();
    await expect(section.getByText("저장했어요", { exact: true })).toBeVisible();

    await page.reload();
    await expect(defaultTier).toHaveValue("BALANCED");
  } finally {
    // 다른 검사가 그룹 기본 단계가 없다고 보고 돌므로 원래대로 되돌린다.
    await page.goto("/admin/models");
    await defaultTier.selectOption("");
    await section.getByRole("button", { name: "저장" }).click();
    await expect(section.getByText("저장했어요", { exact: true })).toBeVisible();
  }
});

test("모델 화면에서 숨길 모델의 목록을 읽을 에이전트를 바꿔도 저장하지 않은 그룹 기본 단계가 남는다", async ({ page }) => {
  await page.goto("/admin/models");
  const section = page.getByRole("region", { name: "그룹 모델 설정" });
  const defaultTier = section.getByLabel("그룹 기본 단계");
  await expect(defaultTier).toHaveValue("");
  await defaultTier.selectOption("BALANCED");

  const hidden = page.getByRole("region", { name: "모델 숨김" });
  const agent = hidden.getByLabel("숨길 모델의 목록을 읽을 에이전트");
  const codes = await agent.locator("option").evaluateAll((options) => options.map((option) => (option as HTMLOptionElement).value));
  expect(codes.length, "에이전트가 둘은 있어야 선택을 바꿀 수 있다").toBeGreaterThan(1);
  await agent.selectOption(codes[1]);
  await expect(agent).toHaveValue(codes[1]);
  await expect(hidden.getByRole("button", { name: "저장" })).toBeVisible();

  // 저장하지 않았으므로 다른 검사가 보는 그룹 기본 단계는 그대로다.
  await expect(defaultTier).toHaveValue("BALANCED");
});

test("MEMBER 역할이 관리자 영역의 모델 주소를 열면 홈으로 넘어간다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  await page.goto("/admin/models");
  await expect(page).toHaveURL(/\/$/);
});

/** `terminal` 도구를 쓴 실행 하나짜리 응답이다. 모델과 토큰과 도구 결과의 원본이 실려 있다. */
function terminalFixture() {
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
      events: [event(1, "TOOL_STARTED", "ls-원본-명령"), event(2, "TOOL_COMPLETED", '{"output":"terminal-raw-result"}')],
    },
  };
}

test("에이전트로 읽는 단계 조회가 실패해도 관리자 영역의 모델 화면은 그룹 단계를 읽고 저장한다", async ({ page }) => {
  // 목록의 첫 에이전트가 다른 사용자의 비공개 에이전트면 에이전트로 읽는 조회는 AGENT_NOT_FOUND 로 실패한다.
  // 그룹 단계는 에이전트 없이 읽으므로 그 조회가 통째로 실패해도 화면이 읽고 저장해야 한다.
  let agentScopedReads = 0;
  await page.route(
    (url) => url.pathname === "/api/chat/model-tiers",
    (route) => {
      agentScopedReads += 1;
      return route.fulfill({ status: 404, json: { code: "AGENT_NOT_FOUND", message: "agent not found" } });
    },
  );
  const groupRead = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/admin/model-tiers");

  await page.goto("/admin/models");
  expect((await groupRead).ok()).toBeTruthy();
  const section = page.getByRole("region", { name: "그룹 모델 설정" });
  const defaultTier = section.getByLabel("그룹 기본 단계");
  await expect(defaultTier).toHaveValue("");
  await expect(section.getByRole("alert")).toHaveCount(0);
  try {
    await defaultTier.selectOption("DEEP");
    await section.getByRole("button", { name: "저장" }).click();
    await expect(section.getByText("저장했어요", { exact: true })).toBeVisible();
    await page.reload();
    await expect(defaultTier).toHaveValue("DEEP");
  } finally {
    // 다른 검사가 그룹 기본 단계가 없다고 보고 돌므로 원래대로 되돌린다.
    await page.goto("/admin/models");
    await defaultTier.selectOption("");
    await section.getByRole("button", { name: "저장" }).click();
    await expect(section.getByText("저장했어요", { exact: true })).toBeVisible();
  }
  expect(agentScopedReads, "관리자 영역의 모델 화면은 에이전트로 읽는 단계 조회를 부르지 않는다").toBe(0);
});

test("MEMBER 역할은 그룹 단계의 관리자 조회를 받지 못한다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  const response = await page.request.get("/api/admin/model-tiers");
  expect(response.status()).toBe(403);
});
