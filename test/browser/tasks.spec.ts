import { expect, test } from "./fixtures.ts";
import type { Page, Route } from "../../web/node_modules/@playwright/test/index.js";

// 알림 단위 SSE 가 열려 있으면 networkidle 이 오지 않는다. 빈 응답으로 끝낸다.
test.beforeEach(async ({ page }) => {
  await page.route("**/api/notifications/events", (route: Route) =>
    route.fulfill({ status: 200, contentType: "text/event-stream", body: "" }),
  );
  // 검사 사용자의 작업을 모두 지워 목록이 비어 있게 한다.
  const listed = await page.request.get("/api/tasks");
  expect(listed.ok()).toBeTruthy();
  for (const task of (await listed.json()) as { id: string }[]) {
    const removed = await page.request.delete(`/api/tasks/${task.id}`);
    expect(removed.ok()).toBeTruthy();
  }
});

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

async function openSidebarIfNarrow(page: Page, projectName: string) {
  if (projectName === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
}

async function createTaskByApi(page: Page, title: string): Promise<string> {
  const created = await page.request.post("/api/tasks", {
    data: {
      title,
      agentCode: "browser",
      instruction: "오늘 할 일을 정리해 줘",
      schedule: { type: "CRON", cron: "0 9 * * *", timeZone: "Asia/Seoul" },
    },
  });
  expect(created.ok()).toBeTruthy();
  return ((await created.json()) as { id: string }).id;
}

test("메뉴의 예약 작업으로 가면 빈 목록 문구가 보인다", async ({ page }, testInfo) => {
  await page.goto("/");
  await openSidebarIfNarrow(page, testInfo.project.name);
  await page
    .getByRole("navigation", { name: "주요 화면" })
    .getByRole("link", { name: /^예약 작업/ })
    .click();
  await expect(page).toHaveURL(/\/tasks$/);
  await expect(page.getByText("아직 예약 작업이 없어요.")).toBeVisible();
});

test("작업 만들기의 에이전트 목록은 예약 작업을 돌릴 수 없는 에이전트를 뺀다", async ({ page }) => {
  await page.route("**/api/agents", (route: Route) =>
    route.fulfill({
      json: [
        { code: "flowed", name: "흐름 도우미", runsTasks: false },
        { code: "browser", name: "예약 도우미", runsTasks: true },
        { code: "legacy", name: "기존 도우미" },
      ],
    }),
  );
  await page.goto("/tasks/new");
  const agents = page.getByLabel("에이전트", { exact: true });
  await expect(agents.locator("option")).toHaveText(["예약 도우미", "기존 도우미"]);
  await expect(agents).toHaveValue("browser");
});

test("새 작업을 저장하면 상세로 가고 목록에 시각이 사람 말로 보인다", async ({ page }) => {
  await page.goto("/tasks/new");
  await page.getByLabel("이름", { exact: true }).fill("월간 정리");
  await page.getByLabel("에이전트", { exact: true }).selectOption("browser");
  await page.getByLabel("지시", { exact: true }).fill("이번 달 할 일을 정리해 줘");
  await page.getByLabel("시각", { exact: true }).selectOption({ label: "매달" });
  await page.getByLabel("날짜(일)").fill("1");
  await page.getByLabel("시각(시:분)").fill("09:00");
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page).toHaveURL(/\/tasks\/[0-9a-f-]{36}$/);

  await page.goto("/tasks");
  await expect(page.getByTestId("task-item")).toHaveCount(1);
  await expect(page.getByTestId("task-item")).toContainText("월간 정리");
  await expect(page.getByTestId("task-item")).toContainText("매달 1일 09:00");
});

test("멈추기와 다시 켜기가 배지를 바꾼다", async ({ page }) => {
  const id = await createTaskByApi(page, "아침 요약");
  await page.goto(`/tasks/${id}`);
  await expect(page.getByText("켜짐", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "멈추기" }).click();
  await expect(page.getByText("멈춤", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "다시 켜기" }).click();
  await expect(page.getByText("켜짐", { exact: true })).toBeVisible();
});

test("지우기를 확인하면 목록에서 사라진다", async ({ page }) => {
  const id = await createTaskByApi(page, "지울 작업");
  await page.goto(`/tasks/${id}`);
  await expect(page.getByText("켜짐", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "지우기", exact: true }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "지우기", exact: true }).click();
  await expect(page).toHaveURL(/\/tasks$/);
  await expect(page.getByText("아직 예약 작업이 없어요.")).toBeVisible();
  await expect(page.getByTestId("task-item")).toHaveCount(0);
});

test("사이드바는 작업 대화를 작업 이름 아래로 묶고 누르면 펼친다", async ({ page }, testInfo) => {
  const taskId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
  const row = (id: string, title: string, updatedAt: string, withTask: boolean) => ({
    id,
    title,
    agentCode: "browser",
    agentName: "브라우저",
    updatedAt,
    provider: null,
    model: null,
    reasoningEffort: null,
    modelSelectionMode: null,
    modelTier: null,
    taskId: withTask ? taskId : null,
    taskTitle: withTask ? "아침 요약" : null,
  });
  await page.route("**/api/chat/conversations?**", (route: Route) => {
    if (new URL(route.request().url()).pathname !== "/api/chat/conversations") return route.fallback();
    return route.fulfill({
      json: {
        items: [
          row("11111111-1111-4111-8111-111111111111", "보통 대화", "2026-10-04T08:00:00Z", false),
          row("22222222-2222-4222-8222-222222222222", "작업 대화 최근", "2026-10-04T07:00:00Z", true),
          row("33333333-3333-4333-8333-333333333333", "작업 대화 이전", "2026-10-03T07:00:00Z", true),
        ],
        nextCursor: null,
      },
    });
  });
  await page.goto("/");
  await openSidebarIfNarrow(page, testInfo.project.name);
  const nav = page.getByRole("navigation", { name: "대화 목록" });
  await expect(nav.getByRole("heading", { name: "예약 작업" })).toBeVisible();
  const group = nav.getByTestId("task-group");
  await expect(group).toHaveCount(1);
  await expect(group).toContainText("아침 요약");
  await expect(group).toHaveAttribute("aria-expanded", "false");
  await expect(nav.getByRole("link", { name: "보통 대화" })).toBeVisible();
  await expect(nav.getByRole("link", { name: "작업 대화 최근" })).toHaveCount(0);

  await group.click();
  await expect(group).toHaveAttribute("aria-expanded", "true");
  await expect(nav.getByRole("link", { name: "작업 대화 최근" })).toBeVisible();
  await expect(nav.getByRole("link", { name: "작업 대화 이전" })).toBeVisible();
});

test("지금 연 작업 대화의 작업 줄을 눌러 접을 수 있다", async ({ page }, testInfo) => {
  const taskId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
  const row = (id: string, title: string, updatedAt: string, withTask: boolean) => ({
    id,
    title,
    agentCode: "browser",
    agentName: "브라우저",
    updatedAt,
    provider: null,
    model: null,
    reasoningEffort: null,
    modelSelectionMode: null,
    modelTier: null,
    taskId: withTask ? taskId : null,
    taskTitle: withTask ? "아침 요약" : null,
  });
  await page.route("**/api/chat/conversations?**", (route: Route) => {
    if (new URL(route.request().url()).pathname !== "/api/chat/conversations") return route.fallback();
    return route.fulfill({
      json: {
        items: [
          row("11111111-1111-4111-8111-111111111111", "보통 대화", "2026-10-04T08:00:00Z", false),
          row("22222222-2222-4222-8222-222222222222", "작업 대화 최근", "2026-10-04T07:00:00Z", true),
          row("33333333-3333-4333-8333-333333333333", "작업 대화 이전", "2026-10-03T07:00:00Z", true),
        ],
        nextCursor: null,
      },
    });
  });
  await page.goto("/chat/22222222-2222-4222-8222-222222222222");
  await openSidebarIfNarrow(page, testInfo.project.name);
  const nav = page.getByRole("navigation", { name: "대화 목록" });
  const group = nav.getByTestId("task-group");
  await expect(group).toHaveAttribute("aria-expanded", "true");
  await expect(nav.getByRole("link", { name: "작업 대화 이전" })).toBeVisible();
  await group.click();
  await expect(group).toHaveAttribute("aria-expanded", "false");
  await expect(nav.getByRole("link", { name: "작업 대화 이전" })).toHaveCount(0);
  await group.click();
  await expect(group).toHaveAttribute("aria-expanded", "true");
});

test("접어 둔 작업 줄도 검색에 걸리면 다시 펼친다", async ({ page }, testInfo) => {
  const taskId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
  const row = (id: string, title: string, updatedAt: string, withTask: boolean) => ({
    id,
    title,
    agentCode: "browser",
    agentName: "브라우저",
    updatedAt,
    provider: null,
    model: null,
    reasoningEffort: null,
    modelSelectionMode: null,
    modelTier: null,
    taskId: withTask ? taskId : null,
    taskTitle: withTask ? "아침 요약" : null,
  });
  await page.route("**/api/chat/conversations?**", (route: Route) => {
    if (new URL(route.request().url()).pathname !== "/api/chat/conversations") return route.fallback();
    return route.fulfill({
      json: {
        items: [
          row("11111111-1111-4111-8111-111111111111", "보통 대화", "2026-10-04T08:00:00Z", false),
          row("22222222-2222-4222-8222-222222222222", "작업 대화 최근", "2026-10-04T07:00:00Z", true),
          row("33333333-3333-4333-8333-333333333333", "작업 대화 이전", "2026-10-03T07:00:00Z", true),
        ],
        nextCursor: null,
      },
    });
  });
  await page.goto("/chat/22222222-2222-4222-8222-222222222222");
  await openSidebarIfNarrow(page, testInfo.project.name);
  const nav = page.getByRole("navigation", { name: "대화 목록" });
  const group = nav.getByTestId("task-group");
  await expect(group).toHaveAttribute("aria-expanded", "true");
  await expect(nav.getByRole("link", { name: "작업 대화 이전" })).toBeVisible();
  await group.click();
  await expect(group).toHaveAttribute("aria-expanded", "false");

  const search = page.getByRole("searchbox", { name: "대화 검색" });
  await search.fill("작업 대화");
  await expect(group).toHaveAttribute("aria-expanded", "true");
  await expect(nav.getByRole("link", { name: "작업 대화 이전" })).toBeVisible();
  await group.click();
  await expect(group).toHaveAttribute("aria-expanded", "false");
  await expect(nav.getByRole("link", { name: "작업 대화 이전" })).toHaveCount(0);

  await search.fill("작업 대화 이전");
  await expect(group).toHaveAttribute("aria-expanded", "true");
  await expect(nav.getByRole("link", { name: "작업 대화 이전" })).toBeVisible();
  await group.click();
  await expect(group).toHaveAttribute("aria-expanded", "false");

  await search.clear();
  await expect(group).toHaveAttribute("aria-expanded", "true");
  await expect(nav.getByRole("link", { name: "작업 대화 최근" })).toBeVisible();
});
