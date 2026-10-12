import { expect, test } from "./fixtures.ts";
import type {
  Page,
  Route,
} from "../../web/node_modules/@playwright/test/index.js";

const ACTION_ID = "1f0e0d0c-0b0a-4908-8706-050403020100";

type Action = Record<string, unknown> & { status: string };
type Grant = {
  grantId: number;
  title: string;
  expiresAt: string;
  agentName: string | null;
};

function action(overrides: Record<string, unknown> = {}): Action {
  return {
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
    grantAllowed: true,
    hiddenArgs: false,
    conversationGrantAllowed: true,
    ...overrides,
  };
}

/** 승인 줄과 대화 허락 읽기를 대역한 대화를 연다. 사건 연결은 붙잡아 둬 다시 읽기를 테스트가 정한다. */
async function openWith(
  page: Page,
  label: string,
  first: Action[],
  grants: Grant[] = [],
) {
  const created = await page.request.post("/api/chat", {
    data: { text: `${label} ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  const { conversationId } = (await created.json()) as {
    conversationId: string;
  };
  const state = { actions: first, grants };
  await page.route(
    `**/api/chat/conversations/${conversationId}/events`,
    async () => {
      await new Promise(() => {});
    },
  );
  await page.route(
    `**/api/chat/conversations/${conversationId}/connector-actions`,
    (route: Route) => route.fulfill({ json: state.actions }),
  );
  await page.route(
    `**/api/chat/conversations/${conversationId}/connector-conversation-grants`,
    (route: Route) => route.fulfill({ json: state.grants }),
  );
  await page.goto(`/chat/${conversationId}`);
  return { conversationId, state };
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("이 대화에서 계속 허용을 누르면 대화 허락과 함께 승인하고 기간은 싣지 않는다", async ({
  page,
}) => {
  await openWith(page, "대화 허락 승인", [action()]);
  let body: unknown;
  await page.route(
    `**/api/connector-actions/${ACTION_ID}/approve`,
    (route: Route) => {
      body = route.request().postDataJSON();
      return route.fulfill({ json: action({ status: "SUCCEEDED" }) });
    },
  );

  await page.getByTestId("approval-conversation-grant").click();

  await expect(page.getByTestId("approval-list")).toHaveCount(0);
  expect(body).toEqual({ grant: null, conversationGrant: true });
});

test("밖으로 나가는 동작과 가려진 내용이 있는 줄에는 이 대화에서 계속 허용 단추가 없다", async ({
  page,
}) => {
  const { state } = await openWith(page, "대화 허락 불가", [
    action({ conversationGrantAllowed: false, grantAllowed: false }),
  ]);
  await expect(page.getByTestId("approval-approve")).toBeVisible();
  await expect(page.getByTestId("approval-conversation-grant")).toHaveCount(0);

  state.actions = [action({ hiddenArgs: true, grantAllowed: false })];
  await page.reload();
  await expect(page.getByTestId("approval-hidden-args")).toBeVisible();
  await expect(page.getByTestId("approval-conversation-grant")).toHaveCount(0);
});

test("이 대화에서 계속 허용 중인 동작을 보이고 다시 묻기를 누르면 거두고 줄이 사라진다", async ({
  page,
}) => {
  const { conversationId, state } = await openWith(
    page,
    "대화 허락 거두기",
    [],
    [
      {
        grantId: 7,
        title: "메모 쓰기",
        expiresAt: "2026-10-13T00:00:00Z",
        agentName: null,
      },
      {
        grantId: 8,
        title: "글 불러오기",
        expiresAt: "2026-10-13T05:30:00Z",
        agentName: "글 다듬기",
      },
    ],
  );
  const rows = page.getByTestId("conversation-grant");
  // 끝나는 시각은 가족이 사는 곳의 시각으로 보인다.
  await expect(rows.nth(0)).toHaveText(
    /2026\. 10\. 13\. 오전 9:00까지 이 대화에서 묻지 않고 실행해요:\s*「메모 쓰기」/,
  );
  await expect(rows.nth(0)).not.toContainText("에이전트만");
  await expect(rows.nth(1)).toHaveText(
    /오후 2:30까지 이 대화에서 묻지 않고 실행해요:\s*「글 불러오기」 · 글 다듬기 에이전트만/,
  );
  let method: string | null = null;
  await page.route(
    `**/api/chat/conversations/${conversationId}/connector-conversation-grants/7`,
    (route: Route) => {
      method = route.request().method();
      state.grants = [];
      return route.fulfill({ status: 204, body: "" });
    },
  );

  await page.getByTestId("conversation-grant-revoke").first().click();

  await expect(page.getByTestId("conversation-grant-list")).toHaveCount(0);
  expect(method).toBe("DELETE");
});
