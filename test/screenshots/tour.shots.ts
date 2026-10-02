import { mkdir } from "node:fs/promises";
import { join } from "node:path";
import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import type { DemoScript } from "../e2e/fake-hermes.ts";
import {
  expect,
  hermesBaseUrl,
  setSession,
  test,
} from "../browser/fixtures.ts";
import {
  CONTROL_PLANE_BASE_URL,
  JWT_SECRET,
  TEST_EMAIL,
} from "../browser/settings.ts";

/**
 * README 의 「한눈에 보기」 에 싣는 화면을 찍는다.
 *
 * <p>화면에 보이는 값은 모두 이 파일이 넣은 지어낸 데이터다. 사람 이름은 `Alex` 이고 대화는 주간 일정과 장보기
 * 같은 흔한 예시다. 답과 작업 과정은 가짜 Hermes 에 대본으로 넣어 실제 흐름으로 만들고, 실제 흐름으로 만들 수 없는
 * 승인 카드와 기억 제안만 브라우저 검사와 같이 응답을 정해 준다.
 */

const IMAGE_DIR = join(import.meta.dirname, "../../docs/images");
const USER = { email: TEST_EMAIL, name: "Alex" };

/** 브라우저 검사가 심어 둔 에이전트다. 화면에 검사용 이름이 나오지 않게 끈다. */
const SEEDED_AGENT_CODES = [
  "browser",
  "browserflow",
  "browserswitch",
  "browserpersona",
  "browserpersonagroup",
  "browserpersonaempty",
];

const PLAN_QUESTION = "이번 주 일정 정리해 줘";
const PLAN_ANSWER = [
  "이번 주 일정을 정리했어요.",
  "",
  "| 요일 | 일정 | 준비할 것 |",
  "| --- | --- | --- |",
  "| 월 | 저녁 7시 요가 수업 | 매트 |",
  "| 수 | 치과 정기 검진 | 예약 문자 확인 |",
  "| 금 | 장보기 | 장보기 목록 |",
  "| 토 | 근교 나들이 | 도시락, 돗자리 |",
  "",
  "토요일은 낮 기온이 22도 안팎이고 비 소식이 없어요.",
  "나들이 장소는 **호수 공원**과 **수목원** 두 곳을 찾아 두었어요. 어느 쪽이 좋을지 알려 주시면 가는 길도 정리할게요.",
].join("\n");

const PLAN_EVENTS: Record<string, unknown>[] = [
  { event: "tool.started", tool: "memory_read", preview: "주간 일정" },
  { event: "tool.completed", tool: "memory_read", duration: 0.2, error: false },
  { event: "tool.started", tool: "web_search", preview: "이번 주 토요일 날씨" },
  { event: "tool.completed", tool: "web_search", duration: 1.4, error: false },
  {
    event: "subagent.start",
    goal: "토요일 근교 나들이 장소 후보를 찾는다",
    model: "example-fast",
    child_session_id: "child-places",
  },
  {
    event: "subagent.complete",
    goal: "토요일 근교 나들이 장소 후보를 찾는다",
    model: "example-fast",
    child_session_id: "child-places",
    status: "completed",
    duration_seconds: 6.2,
    input_tokens: 12300,
    output_tokens: 410,
  },
  {
    event: "subagent.start",
    goal: "두 장소까지 가는 길과 걸리는 시간을 비교한다",
    model: "example-fast",
    child_session_id: "child-route",
  },
  { event: "tool.started", tool: "web_extract", preview: "수목원 안내" },
];

/** 도는 중인 화면을 찍으려고 멈추는 대본과, 같은 답을 끝까지 흘리는 대본이다. */
const RUNNING_QUESTION = PLAN_QUESTION;
const FINISHED_QUESTION = "이번 주 일정을 표로 정리해 줘";

const SHOPPING_QUESTION = "금요일 장보기 목록을 메모에 저장해 줘";
const SHOPPING_ANSWER =
  "장보기 목록을 메모로 저장하려고 해요. 아래 내용을 확인하고 승인해 주세요.";

async function controlPlaneToken(): Promise<string> {
  return new SignJWT({ name: USER.name })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(TEST_EMAIL)
    .setIssuedAt()
    .setExpirationTime("5m")
    .sign(new TextEncoder().encode(JWT_SECRET));
}

async function script(body: DemoScript): Promise<void> {
  const response = await fetch(`${await hermesBaseUrl()}/__test/script`, {
    method: "POST",
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`대본을 넣지 못했다: ${response.status}`);
}

async function disableSeededAgents(): Promise<void> {
  const token = await controlPlaneToken();
  for (const code of SEEDED_AGENT_CODES) {
    const response = await fetch(
      `${CONTROL_PLANE_BASE_URL}/api/v1/admin/agents/${code}`,
      {
        method: "PATCH",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          enabled: false,
          visibility: "PRIVATE",
          ownerEmail: TEST_EMAIL,
        }),
      },
    );
    if (!response.ok)
      throw new Error(
        `검사용 에이전트를 끄지 못했다: ${code} ${response.status}`,
      );
  }
}

async function createAgent(page: Page, name: string): Promise<string> {
  const response = await page.request.post("/api/agents", { data: { name } });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { code: string }).code;
}

async function shot(page: Page, name: string): Promise<void> {
  await mkdir(IMAGE_DIR, { recursive: true });
  // 깜빡이는 커서와 도는 표시가 찍는 순간마다 달라지지 않게 움직임을 멈춘다.
  await page.screenshot({
    path: join(IMAGE_DIR, `${name}.png`),
    animations: "disabled",
    caret: "hide",
  });
}

test.describe.configure({ mode: "serial" });

let lifeAgent = "";

test.beforeEach(async ({ context }) => {
  await setSession(context, USER);
});

test("데모 데이터를 넣는다", async ({ page }) => {
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  await disableSeededAgents();
  lifeAgent = await createAgent(page, "생활 비서");
  await createAgent(page, "여행 계획");
  await createAgent(page, "자료 조사");

  const delta = {
    event: "message.delta",
    delta: "이번 주 일정을 정리했어요.\n\n",
  };
  await script({
    input: RUNNING_QUESTION,
    output: PLAN_ANSWER,
    events: [delta, ...PLAN_EVENTS],
    pause: true,
  });
  await script({
    input: FINISHED_QUESTION,
    output: PLAN_ANSWER,
    events: [
      ...PLAN_EVENTS,
      {
        event: "tool.completed",
        tool: "web_extract",
        duration: 0.9,
        error: false,
      },
      {
        event: "subagent.complete",
        goal: "두 장소까지 가는 길과 걸리는 시간을 비교한다",
        model: "example-fast",
        child_session_id: "child-route",
        status: "completed",
        duration_seconds: 4.8,
        input_tokens: 8400,
        output_tokens: 350,
      },
      delta,
    ],
  });
  await script({
    input: SHOPPING_QUESTION,
    output: SHOPPING_ANSWER,
    events: [],
  });
});

/** 새 대화 화면에서 에이전트와 단계를 고르고 글을 보낸다. */
async function send(page: Page, text: string): Promise<void> {
  await page.goto("/");
  await page.getByRole("radio", { name: "생활 비서" }).click();
  await page.getByTestId("model-tier-balanced").click();
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
}

test("새 대화", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("radio", { name: "생활 비서" }).click();
  await page.getByTestId("model-tier-balanced").click();
  await expect(
    page.getByRole("button", { name: PLAN_QUESTION, exact: true }),
  ).toBeVisible({ timeout: 20_000 });
  await shot(page, "tour-01-new-conversation");
  // 단계를 고르면 빈 대화가 하나 생긴다. 뒤의 화면 목록에 남지 않게 지운다.
  const listed = (await (
    await page.request.get("/api/chat/conversations")
  ).json()) as { items: { id: string }[] };
  for (const conversation of listed.items) {
    const deleted = await page.request.delete(
      `/api/chat/conversations/${conversation.id}`,
    );
    expect(deleted.ok()).toBeTruthy();
  }
});

test("답을 만드는 중", async ({ page, hermes }) => {
  await send(page, RUNNING_QUESTION);
  const block = page.getByTestId("activity-block").last();
  await expect(block).toBeVisible({ timeout: 30_000 });
  await block.getByTestId("activity-toggle").click();
  await expect(block.getByText("웹 페이지를 읽고 있어요").last()).toBeVisible();
  await shot(page, "tour-02-working");
  await hermes.releaseLongActivity();
  await expect(
    page.locator('[data-testid="activity-block"][data-mode="saved"]').last(),
  ).toBeVisible({ timeout: 30_000 });
});

test("실행 트리 패널", async ({ page }) => {
  await send(page, FINISHED_QUESTION);
  const block = page
    .locator('[data-testid="activity-block"][data-mode="saved"]')
    .last();
  await expect(block).toBeVisible({ timeout: 30_000 });
  await block.getByTestId("activity-toggle").click();
  await block.getByTestId("activity-open-panel").click();
  await expect(page.getByTestId("execution-tree")).toBeVisible();
  await expect(page.getByTestId("execution-tree")).toContainText("도우미");
  await shot(page, "tour-03-run-tree");
});

test("커넥터 쓰기 승인 카드", async ({ page }) => {
  const created = await page.request.post("/api/chat", {
    data: { text: SHOPPING_QUESTION, agentCode: lifeAgent },
  });
  expect(created.ok()).toBeTruthy();
  const { conversationId } = (await created.json()) as {
    conversationId: string;
  };
  // 승인 줄은 실제 커넥터 호출이 만든다. 가짜 Hermes 로는 그 호출을 만들 수 없어 브라우저 검사와 같이 응답을 정해 준다.
  await page.route(
    `**/api/chat/conversations/${conversationId}/events`,
    async () => {
      await new Promise(() => {});
    },
  );
  await page.route(
    `**/api/chat/conversations/${conversationId}/connector-actions`,
    (route) =>
      route.fulfill({
        json: [
          {
            actionId: "0f0e0d0c-0b0a-4908-8706-050403020100",
            connectorId: "demo-notes",
            toolName: "write_note",
            title: "메모 쓰기",
            risk: "WRITE",
            status: "PENDING",
            argsJson: JSON.stringify({
              title: "금요일 장보기",
              body: "우유, 달걀, 사과, 두부, 현미",
            }),
            resultText: null,
            errorCode: null,
            createdAt: new Date().toISOString(),
            expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
            grantAllowed: true,
          },
        ],
      }),
  );
  await page.goto(`/chat/${conversationId}`);
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect(page.getByTestId("approval-card")).toBeVisible();
  await shot(page, "tour-04-approval");
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("Memory", async ({ page }) => {
  for (const memory of [
    {
      scope: "GROUP",
      title: "장보는 날",
      content: "장은 금요일 저녁에 본다.",
      alwaysInject: true,
    },
  ]) {
    const saved = await page.request.post("/api/memories", { data: memory });
    expect(saved.ok()).toBeTruthy();
  }
  // 제안은 에이전트가 MCP 도구로 만든다. 가짜 Hermes 로는 그 호출을 만들 수 없어 목록 응답에 제안 둘을 더한다.
  await page.route("**/api/memories", async (route) => {
    if (route.request().method() !== "GET") return route.continue();
    const response = await route.fetch();
    const saved = (await response.json()) as unknown[];
    await route.fulfill({
      response,
      json: [
        ...saved,
        {
          id: 9001,
          scope: "USER",
          ownerUserId: 1,
          title: "운동 시간",
          content: "월요일 저녁 7시에 요가 수업을 듣는다.",
          alwaysInject: false,
          status: "PROPOSED",
        },
        {
          id: 9002,
          scope: "USER",
          ownerUserId: 1,
          title: "나들이 취향",
          content: "걷기 좋은 공원과 수목원을 좋아한다.",
          alwaysInject: false,
          status: "PROPOSED",
        },
      ],
    });
  });
  await page.setViewportSize({ width: 1280, height: 1000 });
  await page.goto("/memory");
  const form = page
    .locator("form")
    .filter({ has: page.getByRole("heading", { name: "새 기억" }) });
  await form.getByLabel("범위").selectOption("USER");
  await form.getByLabel("제목", { exact: true }).fill("음식 취향");
  await form.getByLabel("내용").fill("매운 음식을 잘 먹지 못한다.");
  await form.getByRole("button", { name: "저장" }).click();
  const review = page.getByRole("heading", { name: "검토할 기억" });
  await expect(review).toBeVisible();
  // 새 기억 폼은 건너뛰고 제안과 받아들인 기억과 문서가 한 화면에 들어오게 한다.
  await review.evaluate((heading) =>
    heading.scrollIntoView({ block: "start" }),
  );
  await page.mouse.move(0, 0);
  await shot(page, "tour-05-memory");
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("문서와 영역", async ({ page }) => {
  const collections = (await (
    await page.request.get("/api/memory-collections")
  ).json()) as { key: string; displayName: string }[];
  const keyOf = (displayName: string) =>
    collections.find((collection) => collection.displayName === displayName)!
      .key;
  const documents = [
    {
      collection: keyOf("집"),
      documentKey: "weekly-routine",
      title: "주간 일과",
      content: "월요일 저녁 요가, 금요일 장보기, 토요일 나들이.",
      sensitive: false,
    },
    {
      collection: keyOf("건강"),
      documentKey: "health-notes",
      title: "건강 메모",
      content: "봄철 꽃가루 알레르기가 있다.",
      sensitive: true,
    },
  ];
  for (const document of documents) {
    const saved = await page.request.post("/api/memory-documents", {
      data: document,
    });
    expect(saved.ok()).toBeTruthy();
  }
  await page.goto("/memory");
  const heading = page.getByRole("heading", { name: "문서", exact: true });
  await expect(page.getByRole("heading", { name: "건강 메모" })).toBeVisible();
  await heading.evaluate((element) =>
    element.scrollIntoView({ block: "start" }),
  );
  await page.mouse.move(0, 0);
  await shot(page, "tour-08-documents");
});

test("에이전트 상세", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 1000 });
  await page.goto(`/agents/${lifeAgent}`);
  await page
    .getByRole("textbox", { name: "생활 비서 성격" })
    .fill(
      [
        "너는 우리 집 생활 비서다.",
        "",
        "- 일정과 장보기, 나들이 계획을 돕는다.",
        "- 답은 짧게 쓰고, 표로 정리할 수 있으면 표로 쓴다.",
        "- 모르는 것은 지어내지 않고 찾아본 뒤 답한다.",
        "- 외부 서비스에 무언가를 쓰기 전에는 내용을 먼저 보여 준다.",
      ].join("\n"),
    );
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await page
    .getByRole("alertdialog", { name: "생활 비서의 성격을 저장할까요?" })
    .getByRole("button", { name: "저장" })
    .click();
  await expect(page.getByRole("alertdialog")).toHaveCount(0);
  await page.mouse.move(0, 0);
  await page.waitForTimeout(500);
  await shot(page, "tour-06-agent");
});

test("사용량과 비용", async ({ page }) => {
  const agents = (await (await page.request.get("/api/agents")).json()) as {
    code: string;
    name: string;
  }[];
  const codeOf = (name: string) =>
    agents.find((agent) => agent.name === name)!.code;
  const now = new Date();
  /** 이번 달 1일의 그 시각이다. 어느 날 찍어도 이번 달 합계에 들어간다. */
  const at = (hour: number) =>
    new Date(now.getFullYear(), now.getMonth(), 1, hour, 0, 0).toISOString();
  const seeds = [
    ...Array.from({ length: 14 }, (_, index) => ({
      agentCode: codeOf("생활 비서"),
      model: "example-balanced",
      startedAt: at(index),
      inputTokens: 8_200 + index * 310,
      outputTokens: 640 + index * 25,
      contextChars: 5_400,
      estimatedCostMicros: 31_000 + index * 1_200,
      actualCostMicros: null,
    })),
    ...Array.from({ length: 6 }, (_, index) => ({
      agentCode: codeOf("여행 계획"),
      model: "example-deep",
      startedAt: at(index + 8),
      inputTokens: 21_500 + index * 900,
      outputTokens: 2_300 + index * 110,
      contextChars: 9_800,
      estimatedCostMicros: 182_000 + index * 9_000,
      actualCostMicros: null,
    })),
    ...Array.from({ length: 9 }, (_, index) => ({
      agentCode: codeOf("자료 조사"),
      model: "example-fast",
      startedAt: at(index + 12),
      inputTokens: 4_100 + index * 150,
      outputTokens: 380 + index * 12,
      contextChars: 3_100,
      estimatedCostMicros: 6_400 + index * 300,
      actualCostMicros: 6_400 + index * 300,
    })),
  ];
  const headers = { Authorization: `Bearer ${await controlPlaneToken()}` };
  const support = `${CONTROL_PLANE_BASE_URL}/api/v1/test-support/usage/executions`;
  expect((await page.request.delete(support, { headers })).ok()).toBeTruthy();
  expect(
    (await page.request.post(support, { headers, data: seeds })).ok(),
  ).toBeTruthy();

  await page.goto("/usage");
  await expect(page.getByTestId("breakdown-table")).toContainText("생활 비서");
  await shot(page, "tour-07-usage");
});
