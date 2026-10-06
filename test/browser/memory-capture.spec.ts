import { expect, test } from "./fixtures.ts";
import type {
  Page,
  Route,
} from "../../web/node_modules/@playwright/test/index.js";

type Capture = Record<string, unknown> & { id: number; status: string };

function capture(
  executionId: number,
  overrides: Record<string, unknown> = {},
): Capture {
  return {
    id: 1,
    memoryId: 101,
    executionId,
    kind: "CREATED",
    status: "ACCEPTED",
    title: "좋아하는 과일",
    content: "사과를 좋아해요.",
    sensitive: false,
    alwaysInject: false,
    createdAt: "2026-10-07T12:00:00Z",
    ...overrides,
  } as Capture;
}

/** 새 대화를 만들어 답 하나를 저장하고 그 공개 식별자와 답의 실행 번호를 돌려준다. */
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

/** 대화 단위 SSE 를 사건 없이 붙잡아 둔다. */
async function holdEvents(page: Page, conversationId: string) {
  await page.route(
    `**/api/chat/conversations/${conversationId}/events`,
    async () => {
      await new Promise(() => {});
    },
  );
}

async function open(page: Page, label: string, first: Capture[]) {
  const { conversationId, executionId } = await createConversation(
    page,
    label,
  );
  const state = { captures: first.map((c) => ({ ...c, executionId })) };
  await holdEvents(page, conversationId);
  await page.route(
    `**/api/chat/conversations/${conversationId}/memory-captures`,
    (route: Route) => route.fulfill({ json: state.captures }),
  );
  await page.goto(`/chat/${conversationId}`);
  return { state, executionId };
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("바로 저장한 기록이 답 아래에 보이고 되돌리면 사라진다", async ({
  page,
}) => {
  const { state } = await open(page, "기억 되돌리기 검사", [capture(0)]);
  const undone: string[] = [];
  await page.route("**/api/memory-captures/*/undo", (route: Route) => {
    undone.push(route.request().url());
    state.captures = [];
    return route.fulfill({ status: 204 });
  });

  const row = page.getByTestId("memory-capture");
  await expect(row).toHaveAttribute("data-kind", "CREATED");
  await expect(row).toHaveAttribute("data-status", "ACCEPTED");
  await expect(row).toContainText("기억했어요: 좋아하는 과일");
  await page.getByTestId("memory-capture-undo").click();

  await expect(page.getByTestId("memory-captures")).toHaveCount(0);
  expect(undone).toHaveLength(1);
  expect(undone[0]).toMatch(/\/api\/memory-captures\/1\/undo$/);
});

test("제안 카드를 받아들이면 기억했어요 줄로 바뀐다", async ({ page }) => {
  const { state } = await open(page, "제안 받아들이기 검사", [
    capture(0, { kind: "PROPOSED", status: "PROPOSED" }),
  ]);
  const accepted: string[] = [];
  await page.route("**/api/memories/101/accept", (route: Route) => {
    accepted.push(route.request().method());
    state.captures = state.captures.map((c) => ({ ...c, status: "ACCEPTED" }));
    return route.fulfill({ json: {} });
  });

  const row = page.getByTestId("memory-capture");
  await expect(row).toHaveAttribute("data-status", "PROPOSED");
  await expect(row).toContainText("받아들이면 기억해요");
  await page.getByTestId("memory-capture-accept").click();

  await expect(row).toHaveAttribute("data-status", "ACCEPTED");
  await expect(row).toContainText("기억했어요: 좋아하는 과일");
  expect(accepted).toEqual(["POST"]);
});

test("제안 카드를 거절하면 카드가 사라진다", async ({ page }) => {
  const { state } = await open(page, "제안 거절 검사", [
    capture(0, { kind: "PROPOSED", status: "PROPOSED" }),
  ]);
  await page.route("**/api/memories/101/reject", (route: Route) => {
    state.captures = [];
    return route.fulfill({ status: 204 });
  });

  await page.getByTestId("memory-capture-reject").click();

  await expect(page.getByTestId("memory-captures")).toHaveCount(0);
});
