import { expect, test } from "./fixtures.ts";
import { openAccountMenu } from "./helpers.ts";
import type { Page } from "../../node_modules/@playwright/test/index.js";

// 「설정」 화면과 설정 틀을 본다(docs/features/app-shell/spec.md 의 REQ4, REQ5).

const NARROW = { width: 360, height: 780 };

function settingsMenu(page: Page) {
  return page.getByRole("navigation", { name: "설정 메뉴" });
}

async function createConversation(page: Page, title: string): Promise<string> {
  const response = await page.request.post("/api/chat", { data: { text: title, agentCode: "browser" } });
  expect(response.ok()).toBeTruthy();
  return ((await response.json()) as { conversationId: string }).conversationId;
}

test.describe("넓은 화면", () => {
  test("/settings 는 설정 묶음과 메뉴를 보이고 에이전트가 골라진 채 열린다", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name === "mobile", "넓은 화면 사례다");
    await page.goto("/settings");
    const menu = settingsMenu(page);
    await expect(menu.getByRole("group")).toHaveCount(5);
    for (const [group, links] of [
      ["비서", ["에이전트"]],
      ["알림", ["알림"]],
      ["연결", ["외부 서비스 연결", "내 브라우저"]],
      ["내 데이터", ["파일 공간"]],
      ["도움말", ["개발 문서", "데이터 보관 안내"]],
    ] as const)
      await expect(menu.getByRole("group", { name: group }).getByRole("link")).toHaveText([...links]);
    await expect(menu.getByRole("link", { name: "예약 작업" })).toHaveCount(0);
    await expect(menu.getByRole("link", { name: /^기억/ })).toHaveCount(0);
    await expect(menu.getByRole("link", { name: "에이전트", exact: true })).toHaveAttribute("data-selected", "true");
    await expect(menu.getByRole("link", { name: "에이전트", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("main").getByRole("heading", { name: "에이전트", level: 1 })).toBeVisible();
    // 설정 틀에는 사이드바와 대화 목록이 없다.
    await expect(page.getByRole("complementary", { name: "사이드바" })).toHaveCount(0);
    await expect(page.getByRole("navigation", { name: "대화 목록" })).toHaveCount(0);
  });

  test("저장해 둔 화면 주소를 바로 열어도 설정 틀 안에 그 줄이 골라진 채 보인다", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name === "mobile", "넓은 화면 사례다");
    await page.goto("/connections");
    await expect(settingsMenu(page).getByRole("link", { name: "외부 서비스 연결" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("heading", { name: "외부 서비스 연결", level: 1 })).toBeVisible();


  });

  test("로그인한 사용자의 데이터 보관 안내는 설정 틀 안에 보이고 로그인 안내가 없다", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name === "mobile", "넓은 화면 사례다");
    await page.goto("/privacy");
    await expect(settingsMenu(page).getByRole("link", { name: "데이터 보관 안내" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("heading", { name: "데이터 보관 안내", level: 1 })).toBeVisible();
    await expect(page.getByRole("link", { name: "로그인 화면으로" })).toHaveCount(0);
    await expect(page.getByText(/로그인 전에/)).toHaveCount(0);
  });
});

test("이름 메뉴의 설정에서 「대화로」 를 누르면 보던 대화로 돌아간다", async ({ page }, testInfo) => {
  const id = await createConversation(page, `설정에서 돌아올 대화 ${testInfo.project.name} ${Date.now()}`);
  await page.goto(`/chat/${id}`);
  await openAccountMenu(page, testInfo);
  await page.getByRole("menuitem", { name: "설정" }).click();
  await expect(page).toHaveURL(/\/settings$/);
  await page.getByRole("link", { name: "대화로" }).click();
  await expect(page).toHaveURL(new RegExp(`/chat/${id}$`));
});

test("보던 대화가 없으면 「대화로」 는 홈으로 간다", async ({ page }) => {
  await page.goto("/settings");
  await expect(page.getByRole("link", { name: "대화로" })).toHaveAttribute("href", "/");
});

test("예약 작업, 기억, 지금 볼 것은 사이드바가 있는 틀이다", async ({ page }, testInfo) => {
  for (const pathname of ["/tasks", "/memory", "/now"]) {
    await page.goto(pathname);
    if (testInfo.project.name === "mobile") {
      await expect(page.getByRole("button", { name: "사이드바 열기" })).toBeVisible();
    } else {
      await expect(page.getByRole("complementary", { name: "사이드바" })).toBeVisible();
    }
    await expect(settingsMenu(page)).toHaveCount(0);
  }
});

test.describe("360px 폭", () => {
  test("/settings 는 묶음 목록만 보이고 줄을 누르면 화면이 열리며 뒤로 가기로 목록에 돌아온다", async ({ page }) => {
    await page.setViewportSize(NARROW);
    await page.goto("/settings");
    const menu = settingsMenu(page);
    await expect(menu).toBeVisible();
    await expect(page.getByRole("main")).toBeHidden();
    // 휴대폰의 목록 화면에는 지금 화면이 없다.
    await expect(menu.getByRole("link", { name: "에이전트", exact: true })).not.toHaveAttribute("aria-current", "page");
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(NARROW.width);

    await menu.getByRole("link", { name: "외부 서비스 연결" }).click();
    await expect(page).toHaveURL(/\/connections$/);
    await expect(page.getByRole("heading", { name: "외부 서비스 연결", level: 1 })).toBeVisible();
    await expect(settingsMenu(page)).toBeHidden();
    await page.getByRole("link", { name: "설정 목록으로" }).click();
    await expect(page).toHaveURL(/\/settings$/);
    await expect(settingsMenu(page)).toBeVisible();
  });
});
