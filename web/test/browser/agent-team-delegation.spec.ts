import { expect, test } from "./fixtures.ts";
import type {
  Page,
  Route,
} from "../../node_modules/@playwright/test/index.js";

/**
 * 대화 안에 맡긴 줄, 결과 도착 줄, 승인 카드의 요청 에이전트가 보이는지 본다(docs/features/agent-team/spec.md 의 REQ3, REQ4).
 *
 * <p>위임은 가짜 Hermes 가 만들지 않으므로 이력과 대화 단위 SSE 를 대역한다. 칸의 모양은 `ChatDtos.MessageView` 와 같다.
 */

const SHOPPER = {
  code: "shopper",
  name: "쇼핑 담당",
  icon: "🛍️",
  color: "ROSE",
};
const CHILD_EXECUTION_ID = 91_000_001;
const SYSTEM_MESSAGE_ID = 91_000_002;
const AUTO_ANSWER_ID = 91_000_003;
const AUTO_EXECUTION_ID = 91_000_004;
const ACTION_ID = "0f0e0d0c-0b0a-4908-8706-0504030201aa";

type Turn = Record<string, unknown>;

async function createConversation(page: Page, label: string): Promise<string> {
  const created = await page.request.post("/api/chat", {
    data: { text: `${label} ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  return ((await created.json()) as { conversationId: string }).conversationId;
}

/** 저장된 이력을 받아 `change` 로 고쳐 돌려준다. */
async function routeMessages(
  page: Page,
  conversationId: string,
  change: (turns: Turn[]) => Turn[],
) {
  await page.route(
    `**/api/chat/conversations/${conversationId}/messages`,
    async (route: Route) => {
      const response = await route.fetch();
      const turns = (await response.json()) as Turn[];
      await route.fulfill({ response, json: change(turns) });
    },
  );
}

/** 대화 단위 SSE 의 첫 요청에 `first` 를 보내고, `ready` 가 풀릴 때까지 붙잡는다. 다음 요청부터는 사건 없이 붙잡는다. */
async function routeEvents(
  page: Page,
  conversationId: string,
  first: Record<string, unknown>[],
  ready: Promise<void> = Promise.resolve(),
) {
  let count = 0;
  await page.route(
    `**/api/chat/conversations/${conversationId}/events`,
    async (route: Route) => {
      count += 1;
      if (count === 1 && first.length > 0) {
        await ready;
        await route.fulfill({
          status: 200,
          headers: {
            "Content-Type": "text/event-stream; charset=utf-8",
            "Cache-Control": "no-cache",
          },
          body: first
            .map((event) => `data: ${JSON.stringify(event)}\n\n`)
            .join(""),
        });
        return;
      }
      await new Promise(() => {});
    },
  );
}

function delegation(parentExecutionId: number, status: string) {
  return {
    executionId: CHILD_EXECUTION_ID,
    parentExecutionId,
    status,
    agent: SHOPPER,
  };
}

/** 마지막 답의 실행 번호다. 맡긴 줄의 부모로 쓴다. */
function lastAnswerExecution(turns: Turn[]): number {
  const answer = turns.findLast((turn) => turn.role === "ASSISTANT");
  return answer!.executionId as number;
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("답 아래 맡긴 줄이 진행 중으로 보이고 자식이 끝나면 새로 고치지 않아도 끝남으로 바뀌며 새로 고쳐도 같은 줄이다", async ({
  page,
}) => {
  const conversationId = await createConversation(page, "맡긴 줄 검사");
  let parent = 0;
  let finished = false;
  let served!: () => void;
  const firstServed = new Promise<void>((resolve) => {
    served = resolve;
  });
  await routeMessages(page, conversationId, (turns) => {
    parent = lastAnswerExecution(turns);
    const result = turns.map((turn) =>
      turn.role === "ASSISTANT" && turn.executionId === parent
        ? {
            ...turn,
            delegations: [
              delegation(parent, finished ? "SUCCEEDED" : "RUNNING"),
            ],
          }
        : turn,
    );
    served();
    return result;
  });
  // 처음 읽은 이력을 그린 뒤에 끝난 사건을 보낸다. 사건을 받기 전에 진행 중이 먼저 보여야 한다.
  let release!: () => void;
  const released = new Promise<void>((resolve) => {
    release = resolve;
  });
  await routeEvents(
    page,
    conversationId,
    [
      {
        type: "delegation",
        conversationId,
        executionId: CHILD_EXECUTION_ID,
        get delegation() {
          return delegation(parent, "SUCCEEDED");
        },
      },
    ],
    firstServed.then(() => released),
  );

  await page.goto(`/chat/${conversationId}`);

  const line = page
    .getByTestId("assistant-message")
    .last()
    .getByTestId("delegation-line");
  await expect(line).toHaveCount(1);
  await expect(line).toContainText("→ 쇼핑 담당에게 맡김");
  await expect(line.getByTestId("delegation-status")).toHaveText("진행 중");
  await expect(line.getByTestId("agent-badge")).toHaveText("🛍️");

  release();
  await expect(line.getByTestId("delegation-status")).toHaveText("끝남");

  finished = true;
  await page.reload();
  const reloaded = page
    .getByTestId("assistant-message")
    .last()
    .getByTestId("delegation-line");
  await expect(reloaded).toHaveCount(1);
  await expect(reloaded.getByTestId("delegation-status")).toHaveText("끝남");
});

test("자동 turn 이 도는 동안에도 결과 도착 줄은 이름표로 보이고 그 turn 이 맡기면 기다림 줄 아래에 진행 중 줄이 생긴다", async ({
  page,
}) => {
  const conversationId = await createConversation(
    page,
    "실시간 결과 도착 줄 검사",
  );
  await routeEvents(page, conversationId, [
    {
      type: "system",
      conversationId,
      messageId: SYSTEM_MESSAGE_ID,
      text: "쇼핑 담당 에이전트의 결과가 도착했어요",
      deliveredResults: [delegation(1, "SUCCEEDED")],
    },
    { type: "started", conversationId, executionId: AUTO_EXECUTION_ID },
    {
      type: "delegation",
      conversationId,
      executionId: CHILD_EXECUTION_ID + 10,
      delegation: {
        ...delegation(AUTO_EXECUTION_ID, "RUNNING"),
        executionId: CHILD_EXECUTION_ID + 10,
      },
    },
  ]);

  await page.goto(`/chat/${conversationId}`);

  await expect(
    page.getByTestId("system-message").getByTestId("result-arrival"),
  ).toHaveText("🛍️쇼핑 담당이 끝냈어요");
  const waiting = page.getByTestId("pending-assistant");
  await expect(waiting.getByTestId("delegation-line")).toContainText(
    "→ 쇼핑 담당에게 맡김",
  );
  await expect(
    waiting.getByTestId("delegation-line").getByTestId("delegation-status"),
  ).toHaveText("진행 중");
});

test("결과 도착 줄은 맡은 에이전트의 이름표와 글로 보이고 그 아래 답은 대화 에이전트의 이름표다", async ({
  page,
}) => {
  const conversationId = await createConversation(page, "결과 도착 줄 검사");
  await routeMessages(page, conversationId, (turns) => {
    const createdAt = new Date().toISOString();
    const parent = lastAnswerExecution(turns);
    return [
      ...turns,
      {
        id: SYSTEM_MESSAGE_ID,
        role: "SYSTEM",
        content: "쇼핑 담당 에이전트의 결과가 도착했어요",
        senderName: null,
        executionId: null,
        createdAt,
        attachments: [],
        artifacts: [],
        activity: null,
        status: null,
        delegations: [],
        deliveredResults: [delegation(parent, "SUCCEEDED")],
      },
      {
        id: AUTO_ANSWER_ID,
        role: "ASSISTANT",
        content: "운동화를 찾았어요.",
        senderName: null,
        executionId: AUTO_EXECUTION_ID,
        createdAt,
        attachments: [],
        artifacts: [],
        activity: null,
        status: "SUCCEEDED",
        delegations: [],
        deliveredResults: [],
      },
    ];
  });
  await routeEvents(page, conversationId, []);

  await page.goto(`/chat/${conversationId}`);

  const arrival = page
    .getByTestId("system-message")
    .getByTestId("result-arrival");
  await expect(arrival).toHaveText("🛍️쇼핑 담당이 끝냈어요");
  const answers = page.getByTestId("assistant-message");
  await expect(answers.last()).toContainText("운동화를 찾았어요.");
  const chiefName = await answers
    .first()
    .getByTestId("answer-agent-name")
    .textContent();
  await expect(answers.last().getByTestId("answer-agent-name")).toHaveText(
    chiefName!,
  );
  expect(chiefName).not.toBe("쇼핑 담당");
});

test("위임 자식이 만든 승인 카드에 「쇼핑 담당이 요청」 이 보이고 승인 응답으로 카드를 바꿔도 남는다", async ({
  page,
}) => {
  const conversationId = await createConversation(
    page,
    "승인 카드 요청 에이전트 검사",
  );
  const card = (overrides: Record<string, unknown>) => ({
    actionId: ACTION_ID,
    connectorId: "demo-notes",
    toolName: "write_note",
    title: "메모 쓰기",
    risk: "WRITE",
    status: "PENDING",
    argsJson: JSON.stringify({ title: "장보기" }),
    resultText: null,
    errorCode: null,
    createdAt: "2026-09-30T12:00:00Z",
    expiresAt: "2026-10-01T12:00:00Z",
    grantAllowed: false,
    hiddenArgs: false,
    ...overrides,
  });
  await routeEvents(page, conversationId, []);
  await page.route(
    `**/api/chat/conversations/${conversationId}/connector-actions`,
    (route: Route) => route.fulfill({ json: [card({ requestedBy: SHOPPER })] }),
  );
  await page.route(`**/api/connector-actions/${ACTION_ID}/approve`, (route) =>
    route.fulfill({ json: card({ status: "EXECUTING" }) }),
  );

  await page.goto(`/chat/${conversationId}`);

  const requested = page
    .getByTestId("approval-card")
    .getByTestId("approval-requested-by");
  await expect(requested).toHaveText("🛍️쇼핑 담당이 요청");
  await page.getByTestId("approval-approve").click();
  await expect(page.getByTestId("approval-card")).toHaveAttribute(
    "data-status",
    "EXECUTING",
  );
  await expect(requested).toHaveText("🛍️쇼핑 담당이 요청");
});

test("대화 에이전트가 만든 승인 카드에는 요청 에이전트를 붙이지 않는다", async ({
  page,
}) => {
  const conversationId = await createConversation(
    page,
    "승인 카드 요청 없음 검사",
  );
  await routeEvents(page, conversationId, []);
  await page.route(
    `**/api/chat/conversations/${conversationId}/connector-actions`,
    (route: Route) =>
      route.fulfill({
        json: [
          {
            actionId: ACTION_ID,
            connectorId: "demo-notes",
            toolName: "write_note",
            title: "메모 쓰기",
            risk: "WRITE",
            status: "PENDING",
            argsJson: "{}",
            resultText: null,
            errorCode: null,
            createdAt: "2026-09-30T12:00:00Z",
            expiresAt: "2026-10-01T12:00:00Z",
            grantAllowed: false,
            hiddenArgs: false,
            requestedBy: null,
          },
        ],
      }),
  );

  await page.goto(`/chat/${conversationId}`);

  await expect(page.getByTestId("approval-card")).toHaveCount(1);
  await expect(page.getByTestId("approval-requested-by")).toHaveCount(0);
});
