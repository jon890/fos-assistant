import { expect, PERSONA_AGENT_CODE, test } from "./fixtures.ts";
import type { Page, Request, Route } from "../../web/node_modules/@playwright/test/index.js";

/** RSC 로 화면을 옮기는 요청만 참이다. 정적 자원과 API 호출, 미리 읽기는 걸러진다. */
function isRscNavigationRequest(request: Request): boolean {
  const headers = request.headers();
  if (headers["next-router-prefetch"]) return false;
  return headers["rsc"] === "1" || new URL(request.url()).searchParams.has("_rsc");
}

/** 사이드바의 주요 화면 링크다. 제목에 같은 낱말이 든 대화 줄과 겹치지 않게 그 목록 안에서 정확한 이름으로 찾는다. */
function mainNavLink(page: Page, name: string) {
  return page.getByRole("navigation", { name: "주요 화면" }).getByRole("link", { name, exact: true });
}

/** 사이드바에 하나뿐인 낭독기 안내 영역이다. 서랍이 닫혀도 DOM 에 남으므로 CSS 로 찾는다. */
function sidebarStatus(page: Page) {
  return page.locator('aside[aria-label="사이드바"] [role="status"]');
}

test("화면을 옮기면 뼈대가 먼저 보이고 이전 화면의 제목은 사라진다", async ({ page, hermes }) => {
  await page.goto("/agents");
  await expect(page.getByRole("heading", { name: "에이전트" })).toBeVisible();

  await hermes.holdNextSoul();
  try {
    await page.locator(`a[href="/agents/${PERSONA_AGENT_CODE}"]`).click();

    await expect(page.getByTestId("page-skeleton")).toBeVisible();
    await expect(page.getByRole("heading", { name: "에이전트" })).toHaveCount(0);

    await hermes.releaseHeldSoul();

    await expect(page.getByTestId("page-skeleton")).toHaveCount(0);
    await expect(page.getByRole("textbox", { name: "성격 비서 성격" })).toBeVisible();
  } finally {
    await hermes.releaseHeldSoul();
  }
});

test("뼈대의 폭이 내용이 온 뒤 바깥 틀의 폭과 같다", async ({ page, hermes }) => {
  await page.goto("/agents");

  await hermes.holdNextSoul();
  try {
    await page.locator(`a[href="/agents/${PERSONA_AGENT_CODE}"]`).click();

    // 같은 자리(main 바로 아래 첫 칸)가 뼈대에서 내용으로 바뀐다. 로딩 중에는 뼈대의 바깥 틀이고,
    // 내용이 오면 PersonaEditor 의 바깥 틀이다.
    const container = page.locator("main > div").first();
    await expect(container).toHaveAttribute("data-testid", "page-skeleton");
    const skeletonBox = await container.boundingBox();

    await hermes.releaseHeldSoul();

    await expect(page.getByRole("textbox", { name: "성격 비서 성격" })).toBeVisible();
    const editorBox = await container.boundingBox();

    expect(skeletonBox).not.toBeNull();
    expect(editorBox).not.toBeNull();
    expect(Math.abs((skeletonBox?.width ?? 0) - (editorBox?.width ?? 0))).toBeLessThanOrEqual(1);
  } finally {
    await hermes.releaseHeldSoul();
  }
});

test("사이드바에서 다른 화면으로 옮기는 동안 누른 줄과 사이드바 안내에 표시가 붙는다", async ({ page }, testInfo) => {
  await page.goto("/agents");
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();

  let releaseRequest: () => void = () => {};
  const heldUntilReleased = new Promise<void>((resolve) => { releaseRequest = resolve; });
  const isUsagePath = (url: URL) => url.pathname === "/usage";
  await page.route(isUsagePath, async (route: Route) => {
    if (!isRscNavigationRequest(route.request())) {
      await route.continue();
      return;
    }
    await heldUntilReleased;
    await route.continue();
  });

  const status = sidebarStatus(page);
  try {
    const usageLink = mainNavLink(page, "사용량");
    await usageLink.click();

    // 좁은 폭에서는 누르자마자 서랍이 닫혀 화면 밖으로 밀려난다. DOM 에는 남지만 Playwright 는 그
    // 자리를 여전히 보이는 것으로 셀 수 있어, 사이드바에 하나뿐인 안내만 본다.
    if (testInfo.project.name !== "mobile") {
      await expect(usageLink.getByTestId("nav-pending")).toBeVisible();
    }
    await expect(status).toHaveText("옮기는 중");

    releaseRequest();
    await expect(page.getByRole("heading", { name: "사용량" })).toBeVisible();

    if (testInfo.project.name !== "mobile") {
      await expect(usageLink.getByTestId("nav-pending")).toHaveCount(0);
    }
    await expect(status).toHaveText("");
  } finally {
    releaseRequest();
    await page.unroute(isUsagePath);
  }
});

test("지금 열린 대화 줄을 다시 누르면 옮기지 않는다", async ({ page }, testInfo) => {
  const response = await page.request.post("/api/chat", {
    data: { text: `같은 대화 재클릭 검사 ${testInfo.project.name} ${Date.now()}`, agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  const { conversationId } = (await response.json()) as { conversationId: string };

  await page.goto(`/chat/${conversationId}`);
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();

  const rscPaths: string[] = [];
  const recordRsc = (request: Request) => {
    if (isRscNavigationRequest(request)) rscPaths.push(new URL(request.url()).pathname);
  };
  page.on("request", recordRsc);

  try {
    const conversationLink = page.getByRole("navigation", { name: "대화 목록" })
      .locator(`a[href="/chat/${conversationId}"]`);
    await conversationLink.click();
    // 이 단언은 첫 조회에서 곧바로 통과하므로 표시가 잠깐 떴다 사라지는 것까지는 잡지 못한다.
    // 이동이 실제로 일어났는지는 아래의 RSC 요청 기록이 판정한다.
    await expect(conversationLink.getByTestId("nav-pending")).toHaveCount(0);
    await expect(page).toHaveURL(new RegExp(`/chat/${conversationId}$`));
    await expect(sidebarStatus(page)).toHaveText("");

    // 기준점: 뒤이어 다른 화면으로 옮기는 요청이 나간 것을 본 뒤에 판정한다.
    // 같은 대화로 가는 요청이 나갔다면 그보다 먼저 나갔을 것이다.
    if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
    const usageRequest = page.waitForRequest(
      (request) => isRscNavigationRequest(request) && new URL(request.url()).pathname === "/usage",
    );
    await mainNavLink(page, "사용량").click();
    await usageRequest;

    expect(rscPaths).not.toContain(`/chat/${conversationId}`);
  } finally {
    page.off("request", recordRsc);
  }
});

test("사이드바 링크의 이름이 그대로 맞는다", async ({ page }, testInfo) => {
  await page.goto("/");
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();

  // 회전 표시는 aria-hidden 이라 링크 이름에 들어가지 않는다. 지금 검사들이 쓰는 이름이 그대로 맞아야 한다.
  await expect(mainNavLink(page, "사용량")).toBeVisible();
  await expect(mainNavLink(page, "에이전트")).toBeVisible();
  await expect(mainNavLink(page, "에이전트 관리")).toBeVisible();
});
