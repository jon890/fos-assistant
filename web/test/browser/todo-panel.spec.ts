import { expect, test } from "./fixtures.ts";
import { fakeScreen } from "./fake-screen.ts";
import { openTodo } from "./helpers.ts";
import type {
  Page,
  Route,
} from "../../node_modules/@playwright/test/index.js";

const HANDOFF = {
  handoffId: "0190f5b4-0000-7000-8000-0000000000cc",
  connectorId: "naver-shopping",
  connectorTitle: "네이버 쇼핑",
  need: "LOGIN",
  tab: "ABCDEF0123456789",
  createdAt: "2026-11-10T00:00:00Z",
};

function action(index: number, title = "메모 쓰기") {
  return {
    actionId: `2f0e0d0c-0b0a-4908-8706-0504030201${String(index).padStart(2, "0")}`,
    connectorId: "demo-notes",
    toolName: `write_note_${index}`,
    title,
    risk: "WRITE",
    status: "PENDING",
    argsJson: JSON.stringify({ title: "장보기" }),
    resultText: null,
    errorCode: null,
    createdAt: "2026-11-10T00:00:00Z",
    expiresAt: "2026-11-11T00:00:00Z",
    grantAllowed: true,
    hiddenArgs: false,
  };
}

async function createConversation(page: Page): Promise<string> {
  const created = await page.request.post("/api/chat", {
    data: { text: `할 일 탭 ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  return ((await created.json()) as { conversationId: string }).conversationId;
}

/** 넘기기와 승인 줄 읽기를 대역한다. `state` 가 바뀌면 다음 읽기가 그 목록을 준다. */
async function routeTodo(
  page: Page,
  conversationId: string,
  state: { handoffs: unknown[]; actions: unknown[] },
) {
  const base = `**/api/chat/conversations/${conversationId}`;
  await page.route(`${base}/handoffs`, (route: Route) =>
    route.fulfill({ json: state.handoffs }),
  );
  await page.route(`${base}/connector-actions`, (route: Route) =>
    route.fulfill({ json: state.actions }),
  );
}

/** 대화 단위 SSE 를 붙잡아 두고, `bump` 를 부르면 승인 사건 하나를 보내 목록을 다시 읽게 한다. */
async function routeEvents(page: Page, conversationId: string) {
  const waiting: (() => void)[] = [];
  await page.route(
    `**/api/chat/conversations/${conversationId}/events`,
    async (route: Route) => {
      await new Promise<void>((resolve) => waiting.push(resolve));
      await route.fulfill({
        status: 200,
        headers: {
          "Content-Type": "text/event-stream; charset=utf-8",
          "Cache-Control": "no-cache",
        },
        body: `data: ${JSON.stringify({ type: "approval", conversationId, detail: "x" })}\n\n`,
      });
    },
  );
  return {
    bump: async () => {
      await expect
        .poll(() => waiting.length, { timeout: 15_000 })
        .toBeGreaterThan(0);
      waiting.shift()!();
    },
  };
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("승인과 넘기기가 「할 일」 탭에 함께 보이고, 「내 브라우저」 를 보는 중에 새 승인이 오면 「할 일」 탭으로 바꾼다", async ({
  page,
}) => {
  await fakeScreen(page);
  const conversationId = await createConversation(page);
  const state = { handoffs: [HANDOFF] as unknown[], actions: [action(0)] };
  await routeTodo(page, conversationId, state);
  const events = await routeEvents(page, conversationId);
  await page.goto(`/chat/${conversationId}`);

  // 처음 열 때 이미 있던 승인과 넘기기는 한 줄 요약만 보인다.
  const summary = page.getByTestId("todo-summary");
  await expect(summary).toHaveText("할 일 2건");
  await expect(page.getByTestId("todo-panel")).toHaveCount(0);
  await expect(page.getByTestId("approval-list")).toHaveCount(0);

  await openTodo(page);
  const todo = page.getByTestId("todo-tab-todo");
  await expect(todo.getByTestId("handoff-card")).toHaveCount(1);
  await expect(todo.getByTestId("approval-card")).toHaveCount(1);
  // 넘기기가 승인보다 위다.
  const order = await todo.evaluate((element) =>
    [...element.querySelectorAll("[data-testid]")]
      .map((node) => node.getAttribute("data-testid"))
      .filter((id) => id === "handoff-card" || id === "approval-card"),
  );
  expect(order).toEqual(["handoff-card", "approval-card"]);
  // 넘기기를 고르기 전에는 「내 브라우저」 탭을 누를 수 없다.
  await expect(page.getByRole("tab", { name: "내 브라우저" })).toBeDisabled();

  await todo.getByRole("button", { name: "내 브라우저 열기" }).click();
  await expect(page.getByTestId("handoff-panel")).toBeVisible();
  await expect(page.getByRole("tab", { name: "내 브라우저" })).toHaveAttribute(
    "aria-selected",
    "true",
  );

  state.actions = [action(1, "일정 넣기"), action(0)];
  await events.bump();
  await expect(todo).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId("handoff-panel")).toHaveCount(0);
  await expect(todo.getByTestId("approval-card")).toHaveCount(2);
  await expect(summary).toHaveText("할 일 3건");

  // 보던 넘기기는 기억해 「내 브라우저」 탭으로 돌아간다.
  await page.getByRole("tab", { name: "내 브라우저" }).click();
  await expect(page.getByTestId("handoff-panel")).toBeVisible();
});

test("승인 알림으로 들어오면 「할 일」 탭을 열고 주소에서 표시를 지운다", async ({
  page,
}) => {
  const conversationId = await createConversation(page);
  await routeTodo(page, conversationId, { handoffs: [], actions: [action(0)] });
  await page.goto(`/chat/${conversationId}?todo=open`);

  await expect(page.getByTestId("todo-tab-todo")).toBeVisible();
  await expect(page.getByTestId("approval-card")).toContainText("메모 쓰기");
  await expect(page).toHaveURL(new RegExp(`/chat/${conversationId}$`));
});

test("기다리는 일이 없는 대화에 승인 알림으로 들어오면 대화만 연다", async ({
  page,
}) => {
  const conversationId = await createConversation(page);
  await routeTodo(page, conversationId, { handoffs: [], actions: [] });
  await page.goto(`/chat/${conversationId}?todo=open`);

  await expect(page).toHaveURL(new RegExp(`/chat/${conversationId}$`));
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect(page.getByTestId("todo-panel")).toHaveCount(0);
  await expect(page.getByTestId("todo-summary")).toHaveCount(0);
});
