import { expect, test } from "./fixtures.ts";
import type {
  Page,
  Route,
} from "../../web/node_modules/@playwright/test/index.js";

type Use = {
  executionId: number;
  memoryId: number;
  title: string;
  scope: "USER" | "GROUP";
  via: "ALWAYS" | "FACTS" | "SEARCH" | "READ";
};

/**
 * 새 대화를 만들어 답 하나를 저장하고 그 공개 식별자와 답의 실행 번호를 돌려준다.
 *
 * <p>대화 제목은 사이드바 단추의 이름에 들어간다. 다른 spec 이 이름으로 찾는 낱말을 넣지 않는다.
 */
async function createConversation(page: Page, label: string) {
  const created = await page.request.post("/api/chat", {
    data: { text: `${label} ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  const conversationId = ((await created.json()) as { conversationId: string })
    .conversationId;
  const messages = (await (
    await page.request.get(`/api/chat/conversations/${conversationId}/messages`)
  ).json()) as { role: string; executionId: number | null }[];
  const executionId = messages.find(
    (message) => message.role === "ASSISTANT",
  )?.executionId;
  expect(executionId).toBeTruthy();
  return { conversationId, executionId: executionId as number };
}

/**
 * 저장된 답에 도구 호출 요약을 붙인다. 참고한 기억은 작업 과정 블록 안에서만 보이므로 블록이 있어야 한다.
 */
async function withActivity(page: Page, conversationId: string) {
  await page.route(
    `**/api/chat/conversations/${conversationId}/messages`,
    async (route: Route) => {
      const response = await route.fetch();
      const turns = (await response.json()) as Record<string, unknown>[];
      for (const turn of turns) {
        if (turn.role === "ASSISTANT") {
          turn.activity = { toolCount: 1, subagentCount: 0, durationMs: null };
        }
      }
      await route.fulfill({ response, json: turns });
    },
  );
}

/** 대화 단위 SSE 를 사건 없이 붙잡아 둔다. */
async function holdEvents(page: Page, conversationId: string) {
  await page.route(
    `**/api/chat/conversations/${conversationId}/events`,
    async () => {
      await new Promise(() => {});
    },
  );
}

/** 기억 기록은 비워 둔다. 작업 과정 블록 밖에는 참고한 기억 말고 볼 것이 없다. */
async function emptyCaptures(page: Page, conversationId: string) {
  await page.route(
    `**/api/chat/conversations/${conversationId}/memory-captures`,
    (route: Route) => route.fulfill({ json: [] }),
  );
}

function threeUses(executionId: number): Use[] {
  return [
    {
      executionId,
      memoryId: 12,
      title: "딸 이름",
      scope: "USER",
      via: "FACTS",
    },
    {
      executionId,
      memoryId: 40,
      title: "우리 집 규칙",
      scope: "GROUP",
      via: "ALWAYS",
    },
    {
      executionId,
      memoryId: 77,
      title: "경력 요약",
      scope: "USER",
      via: "READ",
    },
  ];
}

/** 세 줄 뒤에 질문으로 찾은 기억 한 줄을 더한다. */
function fourUses(executionId: number): Use[] {
  return [
    ...threeUses(executionId),
    {
      executionId,
      memoryId: 91,
      title: "자동차 관리",
      scope: "USER",
      via: "SEARCH",
    },
  ];
}

/** 대화가 그려졌는지 본다. 질문과 답이 모두 보여야 한다. */
async function waitForConversation(page: Page, label: string) {
  await expect(page.getByTestId("user-message")).toContainText(label);
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("참고한 기억은 답 아래에 보이지 않고 작업 과정을 펼치면 제목과 출처와 링크가 보인다", async ({
  page,
}) => {
  const { conversationId, executionId } = await createConversation(
    page,
    "참고 기억 검사 하나",
  );
  await holdEvents(page, conversationId);
  await withActivity(page, conversationId);
  await emptyCaptures(page, conversationId);
  await page.route(
    `**/api/chat/conversations/${conversationId}/memory-uses`,
    (route: Route) => route.fulfill({ json: fourUses(executionId) }),
  );
  await page.goto(`/chat/${conversationId}`);

  const block = page.getByTestId("activity-block");
  await expect(block).toBeVisible();
  // 답 아래의 접힌 단추는 없고, 접힌 머리 줄에도 개수가 붙지 않는다
  await expect(page.getByTestId("memory-uses-toggle")).toHaveCount(0);
  await expect(block.getByTestId("activity-toggle")).not.toContainText(
    "참고한 기억",
  );
  await expect(page.getByTestId("activity-memory-uses")).not.toBeVisible();

  await block.getByTestId("activity-toggle").click();

  await expect(page.getByTestId("activity-memory-uses")).toBeVisible();
  const items = page.getByTestId("activity-memory-use");
  await expect(items).toHaveCount(4);
  await expect(items.nth(0)).toContainText("딸 이름");
  await expect(items.nth(0)).toContainText("기억한 사실");
  await expect(items.nth(1)).toContainText("우리 집 규칙");
  await expect(items.nth(1)).toContainText("항상");
  await expect(items.nth(1)).toContainText("그룹");
  await expect(items.nth(2)).toContainText("경력 요약");
  await expect(items.nth(2)).toContainText("찾아 읽음");
  await expect(items.nth(3)).toContainText("자동차 관리");
  await expect(items.nth(3)).toContainText("질문으로 찾은 기억");
  await expect(items.nth(0)).not.toContainText("그룹");
  const link = page
    .getByTestId("activity-memory-uses")
    .getByRole("link", { name: "기억 화면에서 고치기" });
  await expect(link).toHaveAttribute("href", "/memory");
});

test("다른 실행의 줄만 있으면 작업 과정 안에 참고한 기억이 없다", async ({
  page,
}) => {
  const { conversationId, executionId } = await createConversation(
    page,
    "참고 기억 검사 다른 실행",
  );
  await holdEvents(page, conversationId);
  await withActivity(page, conversationId);
  await emptyCaptures(page, conversationId);
  let served = 0;
  await page.route(
    `**/api/chat/conversations/${conversationId}/memory-uses`,
    (route: Route) => {
      served += 1;
      return route.fulfill({ json: threeUses(executionId + 1000) });
    },
  );
  await page.goto(`/chat/${conversationId}`);

  await waitForConversation(page, "참고 기억 검사 다른 실행");
  await expect.poll(() => served).toBeGreaterThan(0);
  await expect(page.getByTestId("activity-memory-uses")).toHaveCount(0);
});

test("참고한 기억을 읽지 못해도 대화가 보이고 참고한 기억 절은 없다", async ({
  page,
}) => {
  const { conversationId } = await createConversation(
    page,
    "참고 기억 검사 실패",
  );
  await holdEvents(page, conversationId);
  await withActivity(page, conversationId);
  await emptyCaptures(page, conversationId);
  let served = 0;
  await page.route(
    `**/api/chat/conversations/${conversationId}/memory-uses`,
    (route: Route) => {
      served += 1;
      return route.fulfill({
        status: 500,
        json: { code: "INTERNAL_ERROR", message: "실패" },
      });
    },
  );
  await page.goto(`/chat/${conversationId}`);

  await waitForConversation(page, "참고 기억 검사 실패");
  await expect.poll(() => served).toBeGreaterThan(0);
  await expect(page.getByTestId("activity-memory-uses")).toHaveCount(0);
});

test("기억했어요 줄에서 되돌리면 참고한 기억을 다시 읽는다", async ({
  page,
}) => {
  const { conversationId, executionId } = await createConversation(
    page,
    "참고 기억 검사 되돌리기",
  );
  await holdEvents(page, conversationId);
  await withActivity(page, conversationId);
  const state = {
    captures: [
      {
        id: 1,
        memoryId: 12,
        executionId,
        kind: "CREATED",
        status: "ACCEPTED",
        title: "딸 이름",
        content: "딸 이름은 하늘이에요.",
        sensitive: false,
        alwaysInject: false,
        createdAt: "2026-10-07T12:00:00Z",
      },
    ],
    uses: threeUses(executionId),
  };
  await page.route(
    `**/api/chat/conversations/${conversationId}/memory-captures`,
    (route: Route) => route.fulfill({ json: state.captures }),
  );
  let usesRequests = 0;
  await page.route(
    `**/api/chat/conversations/${conversationId}/memory-uses`,
    (route: Route) => {
      usesRequests += 1;
      return route.fulfill({ json: state.uses });
    },
  );
  await page.route("**/api/memory-captures/*/undo", (route: Route) => {
    state.captures = [];
    state.uses = state.uses.filter((use) => use.memoryId !== 12);
    return route.fulfill({ status: 204 });
  });
  await page.goto(`/chat/${conversationId}`);

  await page.getByTestId("activity-toggle").click();
  await expect(page.getByTestId("activity-memory-use")).toHaveCount(3);
  const before = usesRequests;
  await page.getByTestId("memory-capture-undo").click();

  await expect(page.getByTestId("memory-captures")).toHaveCount(0);
  await expect.poll(() => usesRequests).toBeGreaterThan(before);
  await expect(page.getByTestId("activity-memory-use")).toHaveCount(2);
});

test("제목은 글자 그대로 보인다", async ({ page }) => {
  const { conversationId, executionId } = await createConversation(
    page,
    "참고 기억 검사 평문",
  );
  await holdEvents(page, conversationId);
  await withActivity(page, conversationId);
  await emptyCaptures(page, conversationId);
  await page.route(
    `**/api/chat/conversations/${conversationId}/memory-uses`,
    (route: Route) =>
      route.fulfill({
        json: [
          {
            executionId,
            memoryId: 5,
            title: "<b>굵게</b>",
            scope: "USER",
            via: "FACTS",
          },
        ],
      }),
  );
  await page.goto(`/chat/${conversationId}`);

  await page.getByTestId("activity-toggle").click();

  const item = page.getByTestId("activity-memory-use");
  await expect(item).toContainText("<b>굵게</b>");
  await expect(item.locator("b")).toHaveCount(0);
});
