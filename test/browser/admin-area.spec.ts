import { expect, setSession, test } from "./fixtures.ts";
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
  await expect(menu.getByRole("link")).toHaveText(["사용자", "에이전트", "모델", "사용량과 비용", "커넥터"]);
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
  await expect(page.getByRole("heading", { name: "연결", exact: true })).toBeVisible();
  await expect(page.getByTestId("connector-admin-panel")).toHaveCount(0);
});

test("MEMBER 역할이 관리자 영역의 에이전트 주소를 열면 홈으로 넘어간다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  await page.goto("/admin/agents");
  await expect(page).toHaveURL(/\/$/);
  await page.goto("/admin/agents/browser");
  await expect(page).toHaveURL(/\/$/);
});
