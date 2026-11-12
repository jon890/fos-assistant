import { expect, test } from "./fixtures.ts";
import type {
  Page,
  Route,
} from "../../node_modules/@playwright/test/index.js";

const HANDOFF = {
  handoffId: "0190f5b4-0000-7000-8000-0000000000aa",
  connectorId: "naver-shopping",
  connectorTitle: "네이버 쇼핑",
  need: "LOGIN",
  tab: "ABCDEF0123456789",
  createdAt: "2026-11-10T00:00:00Z",
};

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

test("넘기기 카드는 할 일과 플러그인 이름, 그 탭을 고른 내 브라우저 링크를 보이고 「다 했어요」 가 정해 둔 글을 보낸다", async ({
  page,
}) => {
  const conversationId = await createConversation(page);
  let open: unknown[] = [HANDOFF];
  await routeHandoffs(page, conversationId, () => open);
  await page.goto(`/chat/${conversationId}`);

  const card = page.getByTestId("handoff-card");
  await expect(card).toBeVisible();
  await expect(card).toContainText("「내 브라우저」에서 로그인해 주세요");
  await expect(card).toContainText("「네이버 쇼핑」");
  const link = card.getByRole("link", { name: "내 브라우저 열기" });
  await expect(link).toHaveAttribute("href", "/browser?tab=ABCDEF0123456789");
  await expect(link).toHaveAttribute("target", "_blank");

  // 새 turn 이 시작되면 서버가 카드를 닫는다. 그 뒤의 읽기는 빈 목록이다.
  open = [];
  await card.getByRole("button", { name: "다 했어요" }).click();
  await expect(page.getByTestId("user-message").last().locator("p")).toHaveText(
    "「내 브라우저」에서 로그인을 마쳤어요. 멈춘 단계부터 이어서 해 주세요.",
  );
  await expect(card).toHaveCount(0);
});

test("넘기기가 없는 대화에는 카드를 그리지 않는다", async ({ page }) => {
  // 계약 밖의 줄을 버리는 것은 웹 서버 라우트다. 브라우저의 요청 대역은 그 뒤라 web/test/node/handoff-route.test.ts 가 본다.
  const conversationId = await createConversation(page);
  await page.goto(`/chat/${conversationId}`);
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect(page.getByTestId("handoff-card")).toHaveCount(0);
});
