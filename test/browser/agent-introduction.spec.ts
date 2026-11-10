import { CONVERSATION_URL, expect, setSession, test } from "./fixtures.ts";
import type { BrowserContext, Page } from "../../web/node_modules/@playwright/test/index.js";

/**
 * 에이전트의 소개와 이름표를 본다(docs/features/agent-team/spec.md 의 REQ1, REQ2).
 *
 * <p>mobile 과 desktop 이 같은 Control Plane 을 함께 쓰므로 project 마다 다른 사용자로 만든다. 이 검사가 만든 에이전트는
 * 끝날 때마다 지운다.
 */

const NAME = "장보기 도우미";

function ownerOf(projectName: string) {
  return { email: `introduction-${projectName}@example.com`, name: "소개 쓰는 사용자" };
}

function otherOf(projectName: string) {
  return { email: `introduction-other-${projectName}@example.com`, name: "다른 사용자" };
}

async function loginAs(context: BrowserContext, page: Page, user: { email: string; name: string }) {
  await setSession(context, user);
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
}

async function createAgent(page: Page, visibility?: "GROUP"): Promise<string> {
  const response = await page.request.post("/api/agents", { data: { name: NAME, visibility } });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { code: string }).code;
}

function introductionSection(page: Page) {
  return page.getByRole("region", { name: "소개" });
}

test.beforeEach(async ({ context, page }, testInfo) => {
  await loginAs(context, page, ownerOf(testInfo.project.name));
});

test.afterEach(async ({ context, page }, testInfo) => {
  await setSession(context, ownerOf(testInfo.project.name));
  const response = await page.request.get("/api/agents");
  const mine = ((await response.json()) as { code: string; ownedByMe: boolean }[]).filter((agent) => agent.ownedByMe);
  for (const agent of mine) {
    const deleted = await page.request.delete(`/api/agents/${agent.code}`);
    expect(deleted.status(), `검사가 만든 에이전트 ${agent.code} 를 지우지 못했다`).toBe(204);
  }
});

test("주인이 소개, 아이콘, 색을 저장하면 목록과 대화의 이름표에 보인다", async ({ page }) => {
  const code = await createAgent(page);
  await page.goto(`/agents/${code}`);
  const section = introductionSection(page);
  await section.getByLabel("이름", { exact: true }).fill("쇼핑 담당");
  await section.getByLabel("한 줄 소개").fill("쇼핑 검색과 장바구니를 맡아요");
  await section.getByRole("group", { name: "아이콘" }).getByRole("button", { name: "🛍️" }).click();
  await section.getByRole("group", { name: "색" }).getByRole("button", { name: "분홍" }).click();
  await section.getByRole("button", { name: "소개 저장" }).click();
  await expect(section.getByText("저장했어요.")).toBeVisible();
  await expect(page.getByRole("heading", { level: 1, name: "쇼핑 담당" })).toBeVisible();

  // 다시 열어도 같다.
  await page.reload();
  await expect(introductionSection(page).getByLabel("한 줄 소개")).toHaveValue("쇼핑 검색과 장바구니를 맡아요");
  await expect(introductionSection(page).getByRole("button", { name: "🛍️" })).toHaveAttribute("aria-pressed", "true");

  await page.goto("/agents");
  const card = page.getByRole("link").filter({ hasText: "쇼핑 담당" });
  await expect(card).toContainText("쇼핑 검색과 장바구니를 맡아요");
  await expect(card.getByTestId("agent-badge")).toHaveText("🛍️");

  // 그 에이전트와 대화하면 답 줄에 그 이름표가 보인다. 좁은 화면에서도 보인다.
  await page.goto(`/?agent=${code}`);
  await page.getByRole("textbox", { name: "메시지" }).fill("운동화 찾아줘");
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page).toHaveURL(CONVERSATION_URL);
  const answer = page.getByTestId("assistant-message").last();
  await expect(answer).toContainText("운동화 찾아줘", { timeout: 30_000 });
  await expect(answer.getByTestId("answer-agent-name")).toHaveText("쇼핑 담당");
  await expect(answer.getByTestId("agent-badge")).toHaveText("🛍️");
  await expect(answer.getByTestId("agent-badge")).toBeVisible();
});

test("아이콘을 고르지 않으면 이름 첫 글자로 그리고, 목록 밖 값과 긴 소개는 저장되지 않는다", async ({ page }) => {
  const code = await createAgent(page);
  await page.goto("/agents");
  await expect(page.getByRole("link").filter({ hasText: NAME }).first().getByTestId("agent-badge")).toHaveText("장");

  for (const data of [
    { name: NAME, description: "가".repeat(61), icon: null, color: null },
    { name: NAME, description: null, icon: "A", color: null },
    { name: NAME, description: null, icon: null, color: "PINK" },
  ]) {
    const response = await page.request.patch(`/api/agents/${code}/introduction`, { data });
    expect(response.status(), JSON.stringify(data)).toBe(400);
    expect(((await response.json()) as { code: string }).code).toBe("VALIDATION_FAILED");
  }
});

test("주인이 아닌 사용자는 그룹 공개 에이전트의 소개를 읽기만 한다", async ({ context, page }, testInfo) => {
  const code = await createAgent(page, "GROUP");
  const saved = await page.request.patch(`/api/agents/${code}/introduction`, {
    data: { name: NAME, description: "함께 쓰는 장보기", icon: "🍳", color: "GREEN" },
  });
  expect(saved.ok()).toBeTruthy();

  await loginAs(context, page, otherOf(testInfo.project.name));
  await page.goto(`/agents/${code}`);
  const section = introductionSection(page);
  await expect(section).toContainText("함께 쓰는 장보기");
  await expect(section.getByRole("button", { name: "소개 저장" })).toHaveCount(0);
  const denied = await page.request.patch(`/api/agents/${code}/introduction`, {
    data: { name: "바꾼 이름", description: null, icon: null, color: null },
  });
  expect(denied.status()).toBe(403);
});
