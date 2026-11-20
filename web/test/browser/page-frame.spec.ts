import { expect, PERSONA_AGENT_CODE, setSession, test } from "./fixtures.ts";
import type { Page, Route } from "../../node_modules/@playwright/test/index.js";

// 사이드바와 설정에서 여는 화면이 같은 머리 틀과 같은 안내를 쓰는지 본다(docs/features/app-shell/spec.md 의 REQ19, REQ20).

/** 대상 화면 여덟이다. 앞의 다섯은 설정 틀, 뒤의 셋은 사이드바 틀에서 보인다. */
const SCREENS = [
  { url: "/agents", title: "에이전트", action: true },
  { url: "/connections", title: "외부 서비스 연결", action: true },
  { url: "/browser", title: "내 브라우저", action: false },
  { url: "/files", title: "파일 공간", action: true },
  { url: "/dev-docs", title: "개발 문서", action: false },
  { url: "/tasks", title: "예약 작업", action: true },
  { url: "/memory", title: "기억", action: false },
  { url: "/now", title: "지금 볼 것", action: false },
] as const;

type Box = { x: number; y: number; width: number; height: number };

function frame(page: Page) {
  return page.locator('main [data-slot="page-frame"]');
}

async function boxOf(page: Page, selector: string): Promise<Box> {
  const box = await frame(page).locator(selector).first().boundingBox();
  if (!box) throw new Error(`${selector} 의 자리를 읽지 못했다`);
  return box;
}

function overlaps(a: Box, b: Box): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
}

/** 화면을 열어 제목, 본문, 주 단추의 자리를 잰다. 페이지가 가로로 넘치지 않는지도 함께 본다. */
async function measure(page: Page, screen: (typeof SCREENS)[number]) {
  await page.goto(screen.url);
  await expect(frame(page).getByRole("heading", { level: 1, name: screen.title, exact: true })).toBeVisible();
  await expect(frame(page).locator('[data-slot="page-description"]'), `${screen.title} 에 한 줄 설명이 없다`).toBeVisible();
  const title = await boxOf(page, "h1");
  const body = await boxOf(page, '[data-slot="page-body"]');
  const actionSlot = frame(page).locator('[data-slot="page-action"]');
  await expect(actionSlot, `${screen.title} 의 주 단추 자리`).toHaveCount(screen.action ? 1 : 0);
  const action = screen.action ? await boxOf(page, '[data-slot="page-action"]') : null;
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, `${screen.title} 화면이 가로로 넘친다`).toBeLessThanOrEqual(0);
  return { title, body, action };
}

test("대상 화면을 차례로 열면 제목의 왼쪽 끝과 본문의 폭이 모두 같고 주 단추가 같은 자리에 있다", async ({ page }) => {
  test.slow();
  const first = await measure(page, SCREENS[0]);
  for (const screen of SCREENS) {
    const measured = await measure(page, screen);
    expect(Math.abs(measured.title.x - first.title.x), `${screen.title} 의 제목 왼쪽 끝`).toBeLessThanOrEqual(1);
    expect(Math.abs(measured.body.width - first.body.width), `${screen.title} 의 본문 폭`).toBeLessThanOrEqual(1);
    expect(Math.abs(measured.body.x - first.body.x), `${screen.title} 의 본문 왼쪽 끝`).toBeLessThanOrEqual(1);
    if (measured.action) {
      const right = measured.action.x + measured.action.width;
      expect(Math.abs(right - (measured.body.x + measured.body.width)), `${screen.title} 의 주 단추 오른쪽 끝`).toBeLessThanOrEqual(1);
      expect(overlaps(measured.title, measured.action), `${screen.title} 의 제목과 주 단추가 겹친다`).toBe(false);
    }
  }
});

test("360px 에서 제목과 주 단추가 겹치지 않고 화면이 가로로 넘치지 않는다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile", "좁은 폭만 본다");
  test.slow();
  await page.setViewportSize({ width: 360, height: 740 });
  for (const screen of SCREENS) {
    const measured = await measure(page, screen);
    expect(measured.body.width, `${screen.title} 의 본문이 화면보다 넓다`).toBeLessThanOrEqual(360);
    if (measured.action) {
      expect(overlaps(measured.title, measured.action), `${screen.title} 의 제목과 주 단추가 겹친다`).toBe(false);
    }
  }
});

test("상세 화면은 목록 화면과 같은 폭에 뒤로 가기와 제목이 같은 자리에 있다", async ({ page }) => {
  const created = await page.request.post("/api/tasks", {
    data: {
      title: `머리 틀 검사 ${Date.now()}`,
      agentCode: "browser",
      instruction: "오늘 할 일을 정리해 줘",
      schedule: { type: "CRON", cron: "0 9 * * *", timeZone: "Asia/Seoul" },
    },
  });
  expect(created.ok()).toBeTruthy();
  const taskId = ((await created.json()) as { id: string }).id;
  try {
    const list = await measure(page, SCREENS[0]);
    const details = [
      { url: `/agents/${PERSONA_AGENT_CODE}`, back: "에이전트", listUrl: /\/agents$/ },
      { url: `/tasks/${taskId}`, back: "예약 작업", listUrl: /\/tasks$/ },
      { url: "/tasks/new", back: "예약 작업", listUrl: /\/tasks$/ },
      // 형식이 틀린 id 는 서버를 부르지 않고 「찾을 수 없는 서비스예요」 를 같은 틀로 그린다.
      { url: "/connections/Bad_Id", back: "외부 서비스 연결", listUrl: /\/connections$/ },
      { url: "/files/workspace", back: "내 파일", listUrl: /\/files$/ },
    ];
    for (const detail of details) {
      await page.goto(detail.url);
      const back = frame(page).locator('[data-slot="page-back"]');
      await expect(back, `${detail.url} 의 뒤로 가기`).toHaveText(detail.back);
      await expect(frame(page).getByRole("heading", { level: 1 })).toBeVisible();
      const backBox = await boxOf(page, '[data-slot="page-back"]');
      const title = await boxOf(page, "h1");
      const body = await boxOf(page, '[data-slot="page-body"]');
      expect(Math.abs(backBox.x - list.title.x), `${detail.url} 의 뒤로 가기 왼쪽 끝`).toBeLessThanOrEqual(1);
      expect(Math.abs(title.x - list.title.x), `${detail.url} 의 제목 왼쪽 끝`).toBeLessThanOrEqual(1);
      expect(backBox.y, `${detail.url} 의 뒤로 가기는 제목 위다`).toBeLessThan(title.y);
      expect(Math.abs(body.width - list.body.width), `${detail.url} 의 본문 폭`).toBeLessThanOrEqual(1);
      await back.click();
      await expect(page).toHaveURL(detail.listUrl);
    }
  } finally {
    await page.request.delete(`/api/tasks/${taskId}`);
  }
});

const GIB = 1024 ** 3;

/** 브라우저가 목록을 읽는 화면이다. 요청을 가로채 읽는 중, 실패, 빈 목록을 차례로 만든다. */
const CLIENT_LISTS = [
  { title: "예약 작업", url: "/tasks", api: "/api/tasks", empty: [], emptyText: "아직 예약 작업이 없어요." },
  { title: "외부 서비스 연결", url: "/connections", api: "/api/connectors", empty: [], emptyText: "연결할 수 있는 서비스가 없어요" },
  {
    title: "파일 공간",
    url: "/files",
    api: "/api/artifact-vault",
    empty: { items: [], nextCursor: null, usedBytes: 0, quotaBytes: 5 * GIB, full: false },
    emptyText: "아직 내 파일이 없어요",
  },
  {
    title: "내 브라우저",
    url: "/browser",
    api: "/api/browser",
    empty: { enabled: true, exists: false, idleTimeoutSeconds: 600 },
    emptyText: "아직 브라우저가 없어요",
  },
  {
    title: "지금 볼 것",
    url: "/now?tab=notifications",
    api: "/api/notifications",
    empty: { items: [], nextCursor: null, unreadCount: 0 },
    emptyText: "아직 알림이 없어요.",
  },
] as const;

for (const list of CLIENT_LISTS) {
  test(`${list.url} 은 읽는 중, 불러오기 실패, 빈 목록에 같은 모양의 안내를 보인다`, async ({ page }) => {
    let mode: "hold" | "fail" | "ok" = "hold";
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => { release = resolve; });
    const matches = (url: URL) => url.pathname === list.api;
    await page.route(matches, async (route: Route) => {
      if (route.request().method() !== "GET") return route.fallback();
      if (mode === "hold") await held;
      if (mode === "fail") return route.fulfill({ status: 500, json: { code: "INTERNAL", message: "x" } });
      return route.fulfill({ json: list.empty });
    });
    try {
      await page.goto(list.url);
      const heading = frame(page).getByRole("heading", { level: 1, name: list.title, exact: true });
      // 읽는 중: 머리는 그대로이고 본문에 뼈대가 보인다.
      await expect(heading).toBeVisible();
      await expect(frame(page).getByRole("status", { name: "불러오는 중" })).toBeVisible();
      const body = await boxOf(page, '[data-slot="page-body"]');

      mode = "fail";
      release();
      const failure = frame(page).locator('[data-slot="load-failure"]');
      await expect(failure).toBeVisible();
      await expect(failure).toHaveAttribute("role", "alert");
      await expect(heading).toBeVisible();
      await expect(frame(page).getByRole("status", { name: "불러오는 중" })).toHaveCount(0);
      const failureBox = await boxOf(page, '[data-slot="load-failure"]');
      expect(Math.abs(failureBox.x - body.x), "실패 안내의 왼쪽 끝").toBeLessThanOrEqual(1);
      expect(Math.abs(failureBox.width - body.width), "실패 안내의 폭").toBeLessThanOrEqual(1);

      mode = "ok";
      await failure.getByRole("button", { name: "다시 읽기" }).click();
      await expect(frame(page).getByText(list.emptyText)).toBeVisible();
      await expect(failure).toHaveCount(0);
      await expect(heading).toBeVisible();
    } finally {
      release();
      await page.unroute(matches);
    }
  });
}

test("서버가 읽는 화면은 읽는 중에도 머리의 제목을 그대로 보인다", async ({ page }) => {
  await page.addInitScript(() => {
    const record = () => {
      const skeleton = document.querySelector<HTMLElement>('main [data-testid="page-skeleton"]');
      if (skeleton) (window as Window & { frameSkeleton?: string }).frameSkeleton = skeleton.outerHTML;
    };
    new MutationObserver(record).observe(document, { childList: true, subtree: true });
  });
  for (const screen of SCREENS.filter((item) => ["/agents", "/memory", "/now"].includes(item.url))) {
    await page.goto(screen.url);
    await expect(frame(page).getByRole("heading", { level: 1, name: screen.title, exact: true })).toBeVisible();
    const skeleton = await page.evaluate(() => (window as Window & { frameSkeleton?: string }).frameSkeleton);
    expect(skeleton, `${screen.title} 의 뼈대를 보지 못했다`).toBeTruthy();
    expect(skeleton).toContain(`>${screen.title}</h1>`);
    expect(skeleton).toContain('data-slot="list-skeleton"');
    expect(skeleton).toContain("max-w-4xl");
  }
});

test("할 것이 없는 사용자의 지금 볼 것은 다른 화면과 같은 빈 목록 안내를 보인다", async ({ page, context }, testInfo) => {
  // 아무것도 만들지 않는 사용자라 반복해도 빈 채로 남는다. 반복마다 새 사용자를 만들지 않는다.
  await setSession(context, { email: `frame-empty-${testInfo.project.name}@example.com`, name: "빈 화면 사용자" });
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  await page.goto("/now");
  await expect(frame(page).getByRole("heading", { level: 1, name: "지금 볼 것", exact: true })).toBeVisible();
  await expect(frame(page).getByText("지금 확인할 것이 없어요")).toBeVisible();
  await expect(frame(page).locator('[data-slot="load-failure"]')).toHaveCount(0);
});
