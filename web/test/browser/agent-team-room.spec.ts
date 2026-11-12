import { CONVERSATION_URL, expect, setSession, test } from "./fixtures.ts";
import { TEST_EMAIL } from "./settings.ts";
import type {
  Page,
  Route,
  TestInfo,
} from "../../node_modules/@playwright/test/index.js";

/**
 * 기본 비서와 늘 이어지는 대화방을 본다(docs/features/agent-team/spec.md 의 REQ8).
 *
 * <p>기본 비서는 허용 목록의 사람이 처음 들어올 때 생기므로 관리자가 사람을 더한 뒤 그 사람으로 들어온다. 더한 사람은 지워지지 않아
 * 검사와 폭마다 다른 이메일과 profile 을 쓴다. 날짜별 요약(REQ9)의 판정과 저장은 시계를 옮겨야 해 backend 통합 테스트가 보고,
 * 여기서는 요약 알림 줄이 든 이력을 그리는 것만 본다.
 */

const ADMIN = { email: TEST_EMAIL, name: "브라우저 테스트" };

async function signInAsNewPerson(page: Page, testInfo: TestInfo) {
  const profile = `room${Date.now().toString(36)}${testInfo.project.name.replace(/[^a-z0-9]/g, "")}`;
  const person = { email: `${profile}@example.com`, name: "대화방 사람" };
  await setSession(page.context(), ADMIN);
  const added = await page.request.post("/api/admin/people", {
    data: {
      email: person.email,
      displayName: person.name,
      hermesProfile: profile,
    },
  });
  expect(
    added.ok(),
    `사람을 더하지 못했다: ${added.status()} ${await added.text()}`,
  ).toBeTruthy();
  await setSession(page.context(), person);
  // 실제 로그인은 로그인 완료 요청이 사용자와 첫 에이전트를 만든다. 검사는 세션만 심으므로 내 정보 읽기로 사용자를 만든다.
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
}

async function openSidebar(page: Page, testInfo: TestInfo) {
  if (testInfo.project.name === "mobile") {
    await page.getByRole("button", { name: "사이드바 열기" }).click();
  }
}

test("로그인 뒤 `/` 가 기본 비서의 대화방을 열고 사이드바 맨 위에 고정되며 목록과 메뉴에는 없다", async ({
  page,
}, testInfo) => {
  await signInAsNewPerson(page, testInfo);

  await page.goto("/");
  await expect(page).toHaveURL(CONVERSATION_URL);
  const roomUrl = page.url();
  const roomId = new URL(roomUrl).pathname.split("/").at(-1);
  // 배포 뒤 처음 열면 빈 방이다.
  await expect(page.getByTestId("user-message")).toHaveCount(0);

  const composer = page.getByRole("textbox", { name: "메시지" });
  await composer.fill("대화방에 처음 보내는 글");
  await composer.press("Enter");
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect(page).toHaveURL(roomUrl);

  await openSidebar(page, testInfo);
  const room = page.getByTestId("room-link");
  await expect(room).toContainText("비서");
  await expect(room).toHaveAttribute("aria-current", "page");
  const nav = page.getByRole("navigation", { name: "대화 목록" });
  await expect(nav.locator(`a[href="/chat/${roomId}"]`)).toHaveCount(0);

  // 「새 대화」 는 지금처럼 새 대화 화면을 연다.
  await page.getByTestId("new-conversation-link").click();
  await expect(page).toHaveURL(/\/new$/);
  await expect(page.getByTestId("user-message")).toHaveCount(0);

  await openSidebar(page, testInfo);
  await page.getByTestId("room-link").click();
  await expect(page).toHaveURL(roomUrl);
  await expect(page.getByTestId("user-message")).toContainText(
    "대화방에 처음 보내는 글",
  );
});

test("대화방의 요약 알림 줄은 첫 줄과 「요약 보기」 로 보이고 펼치면 요약이 보인다", async ({
  page,
}, testInfo) => {
  await signInAsNewPerson(page, testInfo);
  await page.goto("/");
  await expect(page).toHaveURL(CONVERSATION_URL);
  const roomId = new URL(page.url()).pathname.split("/").at(-1);
  const composer = page.getByRole("textbox", { name: "메시지" });
  await composer.fill("어제 이야기");
  await composer.press("Enter");
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);

  // 날짜 경계는 서버 시계로 정해 화면 검사에서 만들 수 없다. 이력 응답 끝에 서버가 남기는 모양의 요약 줄을 더한다.
  await page.route(
    `**/api/chat/conversations/${roomId}/messages`,
    async (route: Route) => {
      const response = await route.fetch();
      const turns = (await response.json()) as Record<string, unknown>[];
      await route.fulfill({
        response,
        json: [
          ...turns,
          {
            ...turns[0],
            id: 9_000_000_726,
            role: "SYSTEM",
            content: "어제까지 이야기를 요약해 이어요\n\n등산 준비를 이야기했다",
            senderName: null,
            executionId: null,
            status: null,
            delegations: [],
            deliveredResults: [],
          },
        ],
      });
    },
  );
  await page.reload();

  const summary = page.getByTestId("room-summary");
  await expect(summary).toContainText("어제까지 이야기를 요약해 이어요 · 요약 보기");
  await expect(summary.getByText("등산 준비를 이야기했다")).toBeHidden();
  await summary.getByText("요약 보기").click();
  await expect(summary.getByText("등산 준비를 이야기했다")).toBeVisible();
});

test("기본 비서의 상세에는 지우기 단추가 없고 지울 수 없다는 안내가 보인다", async ({
  page,
}, testInfo) => {
  await signInAsNewPerson(page, testInfo);
  const listed = await page.request.get("/api/agents");
  expect(listed.ok()).toBeTruthy();
  const primary = ((await listed.json()) as { code: string; primary?: boolean }[]).filter(
    (agent) => agent.primary === true,
  );
  expect(primary, "기본 비서는 하나다").toHaveLength(1);

  await page.goto(`/agents/${primary[0]!.code}`);

  const section = page.getByRole("region", { name: "공개와 삭제" });
  await expect(section.getByText("기본 비서는 지울 수 없어요.")).toBeVisible();
  await expect(section.getByRole("button", { name: "에이전트 지우기" })).toHaveCount(0);
  // 공개 범위 변경은 그대로 쓸 수 있다.
  await expect(section.getByRole("button", { name: /변경$/ })).toBeVisible();
});
