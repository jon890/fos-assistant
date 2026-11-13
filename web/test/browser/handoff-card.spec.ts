import { expect, test } from "./fixtures.ts";
import { FRAME, fakeScreen, type Input } from "./fake-screen.ts";
import { openTodo } from "./helpers.ts";
import type {
  Page,
  Route,
} from "../../node_modules/@playwright/test/index.js";

const TAB = "ABCDEF0123456789";
const OTHER_TAB = "FEDCBA9876543210";

const HANDOFF = {
  handoffId: "0190f5b4-0000-7000-8000-0000000000aa",
  connectorId: "naver-shopping",
  connectorTitle: "네이버 쇼핑",
  need: "LOGIN",
  tab: TAB,
  createdAt: "2026-11-10T00:00:00Z",
};

const NEWER = {
  ...HANDOFF,
  handoffId: "0190f5b4-0000-7000-8000-0000000000bb",
  need: "CAPTCHA",
  tab: OTHER_TAB,
  createdAt: "2026-11-10T00:01:00Z",
};

const LOGIN_TITLE = "「내 브라우저」에서 로그인해 주세요";
const CAPTCHA_TITLE = "「내 브라우저」에서 보안 확인을 풀어 주세요";
const LOGIN_DONE =
  "「내 브라우저」에서 로그인을 마쳤어요. 멈춘 단계부터 이어서 해 주세요.";

/** 새 대화를 만들어 질문 하나와 답 하나를 저장하고 그 공개 식별자를 돌려준다. */
async function createConversation(page: Page): Promise<string> {
  const created = await page.request.post("/api/chat", {
    data: { text: `화면 넘기기 ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  return ((await created.json()) as { conversationId: string }).conversationId;
}

/** 대화의 넘기기 읽기를 대역한다. `current` 가 주는 목록을 돌려준다. */
async function routeHandoffs(
  page: Page,
  conversationId: string,
  current: () => unknown[],
) {
  await page.route(
    `**/api/chat/conversations/${conversationId}/handoffs`,
    (route: Route) => route.fulfill({ json: current() }),
  );
}

/**
 * 대화 단위 SSE 를 붙잡아 두고, `bump` 를 부르면 기다리던 연결에 승인 사건 하나를 보내 카드 목록을 다시 읽게 한다.
 * 넘기기를 적거나 닫을 때 Control Plane 이 보내는 사건과 같다. 응답이 끝나면 화면이 몇 초 뒤 다시 잇는다.
 */
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
        body: `data: ${JSON.stringify({ type: "approval", conversationId, detail: HANDOFF.handoffId })}\n\n`,
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

function ofType(inputs: Input[], type: string): Input[] {
  return inputs.filter((input) => input.type === type);
}

function tabs(url: string, tab = TAB) {
  return [
    { id: "OTHER", title: "다른 탭", url: "https://example.com", active: true },
    { id: tab, title: "넘긴 탭", url, active: false },
  ];
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("카드의 「내 브라우저 열기」 는 새 창 없이 대화 위 패널에 그 탭을 띄우고, 패널의 「다 했어요」 가 패널을 닫고 정해 둔 글을 보낸다", async ({
  page,
}, testInfo) => {
  const fake = await fakeScreen(page);
  const conversationId = await createConversation(page);
  let open: unknown[] = [HANDOFF];
  await routeHandoffs(page, conversationId, () => open);
  await page.goto(`/chat/${conversationId}`);

  // 처음 연 대화에 이미 있던 카드는 패널을 저절로 열지 않고 입력창 위 한 줄만 보인다.
  const summary = page.getByTestId("todo-summary");
  await expect(summary).toHaveText("할 일 1건");
  const panel = page.getByTestId("handoff-panel");
  await expect(page.getByTestId("todo-panel")).toHaveCount(0);
  await openTodo(page);
  const card = page.getByTestId("handoff-card");
  await expect(card).toContainText(LOGIN_TITLE);
  await expect(card).toContainText("「네이버 쇼핑」");
  await expect(card.getByRole("link")).toHaveCount(0);

  await card.getByRole("button", { name: "내 브라우저 열기" }).click();
  await expect(panel).toBeVisible();
  await expect(panel).toContainText(LOGIN_TITLE);
  await expect(page.getByTestId("handoff-open-window")).toHaveAttribute(
    "href",
    `/browser?tab=${TAB}`,
  );
  await expect(page.getByTestId("handoff-open-window")).toHaveAttribute(
    "target",
    "_blank",
  );
  await fake.push([
    ["frame", { data: FRAME, width: 400, height: 600 }],
    ["tabs", tabs("https://nid.example.com/login")],
  ]);
  // 첫 탭 목록에서 넘긴 탭으로 한 번 옮긴다.
  await expect
    .poll(() => ofType(fake.inputs, "tab").map((input) => input.id))
    .toEqual([TAB]);

  const composer = page.getByRole("textbox", { name: "메시지" });
  if (testInfo.project.name === "desktop") {
    // 넓은 화면은 대화 옆에 붙어 입력창을 함께 쓴다.
    await expect(composer).toBeVisible();
    await composer.fill("패널을 연 채 쓰는 글");
  } else {
    // 좁은 화면은 전체 폭 시트로 대화를 덮는다.
    const box = await page.getByTestId("todo-panel").boundingBox();
    expect(box?.width).toBe(page.viewportSize()?.width);
    // 화면 글자 칸의 Esc 는 원격 페이지로 가고 시트를 닫지 않는다.
    await page.getByLabel("화면에 글자 넣기").focus();
    await page.keyboard.press("Escape");
    await expect
      .poll(() => ofType(fake.inputs, "key").map((input) => input.key))
      .toContain("Escape");
    await expect(panel).toBeVisible();
  }
  // 다시 그려져도 화면 연결을 다시 열지 않는다.
  expect(await fake.opens()).toHaveLength(1);

  await page.getByRole("button", { name: "옆 패널 닫기" }).click();
  await expect(panel).toHaveCount(0);
  await expect(summary).toBeVisible();

  await openTodo(page);
  await card.getByRole("button", { name: "내 브라우저 열기" }).click();
  await expect(panel).toBeVisible();
  open = [];
  await panel.getByRole("button", { name: "다 했어요", exact: true }).click();
  await expect(panel).toHaveCount(0);
  await expect(page.getByTestId("user-message").last().locator("p")).toHaveText(
    LOGIN_DONE,
  );
  await expect(summary).toHaveCount(0);
});

test("넘긴 탭의 주소가 바뀌면 끝났는지 묻고, 「아직이에요」 뒤에는 다시 바뀔 때만 묻는다", async ({
  page,
}) => {
  const fake = await fakeScreen(page);
  const conversationId = await createConversation(page);
  await routeHandoffs(page, conversationId, () => [HANDOFF]);
  await page.goto(`/chat/${conversationId}`);
  await openTodo(page);
  await page
    .getByTestId("handoff-card")
    .getByRole("button", { name: "내 브라우저 열기" })
    .click();
  const changed = page.getByTestId("handoff-changed");

  // 화면이 처음 열릴 때의 빈 목록으로는 판정을 끄지 않는다. 첫 탭 목록이 기준이다.
  await fake.push([["tabs", tabs("https://nid.example.com/login")]]);
  await fake.push([["tabs", tabs("https://nid.example.com/login#otp")]]);
  await expect.poll(() => ofType(fake.inputs, "tab").length).toBe(1);
  await expect(changed).toHaveCount(0);

  await fake.push([["tabs", tabs("https://nid.example.com/otp")]]);
  await expect(changed).toContainText("화면이 바뀌었어요. 다 마치셨나요?");
  await changed.getByRole("button", { name: "아직이에요" }).click();
  await expect(changed).toHaveCount(0);

  await fake.push([["tabs", tabs("https://nid.example.com/otp")]]);
  await fake.push([["frame", { data: FRAME, width: 400, height: 600 }]]);
  await expect(changed).toHaveCount(0);

  // 넘긴 탭이 닫혀도 묻는다.
  await fake.push([["tabs", tabs("https://example.com", "GONE")]]);
  await expect(changed).toBeVisible();
});

test("보고 있는 대화에 새 넘기기가 생기면 패널이 저절로 열리고, 다른 카드로 바꾸면 확인 줄 없이 그 탭을 보이며, 넘기기가 닫히면 패널도 닫힌다", async ({
  page,
}) => {
  const fake = await fakeScreen(page);
  const conversationId = await createConversation(page);
  let open: unknown[] = [HANDOFF];
  await routeHandoffs(page, conversationId, () => open);
  const events = await routeEvents(page, conversationId);
  await page.goto(`/chat/${conversationId}`);
  await expect(page.getByTestId("todo-summary")).toHaveText("할 일 1건");
  const panel = page.getByTestId("handoff-panel");
  await expect(panel).toHaveCount(0);

  open = [NEWER, HANDOFF];
  await events.bump();
  await expect(panel).toContainText(CAPTCHA_TITLE);
  await fake.push([["tabs", tabs("https://captcha.example.com", OTHER_TAB)]]);
  await fake.push([["tabs", tabs("https://shop.example.com", OTHER_TAB)]]);
  await expect(page.getByTestId("handoff-changed")).toBeVisible();

  // 「할 일」 탭의 다른 카드의 「내 브라우저 열기」 는 「내 브라우저」 탭을 그 카드로 바꾸고, 앞 넘기기의 판정은 남지 않는다.
  await page.getByRole("tab", { name: "할 일" }).click();
  await expect(page.getByTestId("handoff-card")).toHaveCount(2);
  await page
    .getByTestId("handoff-card")
    .filter({ hasText: LOGIN_TITLE })
    .getByRole("button", { name: "내 브라우저 열기" })
    .click();
  await expect(panel).toContainText(LOGIN_TITLE);
  await expect(page.getByTestId("handoff-changed")).toHaveCount(0);

  // 사람이 입력창에 직접 답해 서버가 넘기기를 닫았다.
  open = [];
  await events.bump();
  await expect(panel).toHaveCount(0, { timeout: 15_000 });
  await expect(page.getByTestId("todo-summary")).toHaveCount(0);
});

test("새 넘기기 패널은 작업 과정 패널 자리를 갈아 쓰고, 넘기기가 닫혀도 다른 옆 패널은 닫지 않는다", async ({
  page,
}) => {
  await fakeScreen(page);
  const conversationId = await createConversation(page);
  let open: unknown[] = [];
  await routeHandoffs(page, conversationId, () => open);
  const events = await routeEvents(page, conversationId);
  await page.goto(`/chat/${conversationId}`);
  const block = page
    .locator('[data-testid="activity-block"][data-mode="saved"]')
    .last();
  await block.getByTestId("activity-toggle").click();
  await block.getByTestId("activity-open-panel").click();
  const activity = page.getByTestId("activity-panel");
  await expect(activity).toBeVisible();

  open = [HANDOFF];
  await events.bump();
  const panel = page.getByTestId("handoff-panel");
  await expect(panel).toContainText(LOGIN_TITLE);
  await expect(activity).toHaveCount(0);

  await page.getByRole("button", { name: "옆 패널 닫기" }).click();
  await block.getByTestId("activity-open-panel").click();
  await expect(activity).toBeVisible();
  open = [];
  await events.bump();
  await expect(page.getByTestId("todo-summary")).toHaveCount(0, {
    timeout: 15_000,
  });
  await expect(activity).toBeVisible();
});

test("패널의 「새 창으로 열기」 는 그 탭의 내 브라우저를 새 창으로 열고 패널을 닫는다", async ({
  page,
}) => {
  await fakeScreen(page);
  const conversationId = await createConversation(page);
  await routeHandoffs(page, conversationId, () => [HANDOFF]);
  await page.goto(`/chat/${conversationId}`);
  await openTodo(page);
  const card = page.getByTestId("handoff-card");
  await card.getByRole("button", { name: "내 브라우저 열기" }).click();
  const panel = page.getByTestId("handoff-panel");
  await expect(panel).toBeVisible();

  const [popup] = await Promise.all([
    page.waitForEvent("popup"),
    page.getByTestId("handoff-open-window").click(),
  ]);
  expect(new URL(popup.url()).pathname + new URL(popup.url()).search).toBe(
    `/browser?tab=${TAB}`,
  );
  await popup.close();
  await expect(panel).toHaveCount(0);
  // 닫기로 닫으면 초점이 입력창 위 「할 일」 줄로 돌아온다.
  await openTodo(page);
  await card.getByRole("button", { name: "내 브라우저 열기" }).click();
  await page.getByRole("button", { name: "옆 패널 닫기" }).click();
  await expect(panel).toHaveCount(0);
  await expect(page.getByTestId("todo-summary")).toBeFocused();
});

test("알림에서 들어오면 가장 최근 넘기기의 패널을 열고 주소에서 표시를 지운다", async ({
  page,
}) => {
  await fakeScreen(page);
  const conversationId = await createConversation(page);
  await routeHandoffs(page, conversationId, () => [NEWER, HANDOFF]);
  await page.goto(`/chat/${conversationId}?handoff=open`);

  await expect(page.getByTestId("handoff-panel")).toContainText(CAPTCHA_TITLE);
  await expect(page).toHaveURL(new RegExp(`/chat/${conversationId}$`));
});

test("열린 넘기기가 없는 대화에 알림으로 들어오면 대화만 연다", async ({
  page,
}) => {
  const conversationId = await createConversation(page);
  await routeHandoffs(page, conversationId, () => []);
  await page.goto(`/chat/${conversationId}?handoff=open`);

  await expect(page).toHaveURL(new RegExp(`/chat/${conversationId}$`));
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect(page.getByTestId("handoff-panel")).toHaveCount(0);
});

test("넘기기가 없는 대화에는 카드를 그리지 않는다", async ({ page }) => {
  // 계약 밖의 줄을 버리는 것은 웹 서버 라우트다. 브라우저의 요청 대역은 그 뒤라 web/test/node/handoff-route.test.ts 가 본다.
  const conversationId = await createConversation(page);
  await page.goto(`/chat/${conversationId}`);
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect(page.getByTestId("handoff-card")).toHaveCount(0);
});
