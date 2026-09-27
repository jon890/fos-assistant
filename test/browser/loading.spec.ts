import { expect, PERSONA_AGENT_CODE, test } from "./fixtures.ts";
import type { Route } from "../../web/node_modules/@playwright/test/index.js";

/** RSC 로 화면을 옮기는 요청만 참이다. 정적 자원과 API 호출은 걸러진다. */
function isRscNavigationRequest(route: Route): boolean {
  const headers = route.request().headers();
  if (headers["next-router-prefetch"]) return false;
  return headers["rsc"] === "1" || new URL(route.request().url()).searchParams.has("_rsc");
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
  await page.route(isUsagePath, async (route) => {
    if (!isRscNavigationRequest(route)) {
      await route.continue();
      return;
    }
    await heldUntilReleased;
    await route.continue();
  });

  const status = page.locator('[role="status"]');
  try {
    const usageLink = page.getByRole("link", { name: "사용량" });
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
  const { conversationId } = (await response.json()) as { conversationId: number };

  await page.goto(`/c/${conversationId}`);
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();

  let rscRequestSent = false;
  const isConversationPath = (url: URL) => url.pathname === `/c/${conversationId}`;
  await page.route(isConversationPath, async (route) => {
    if (isRscNavigationRequest(route)) rscRequestSent = true;
    await route.continue();
  });

  try {
    const conversationLink = page.getByRole("navigation", { name: "대화 목록" })
      .locator(`a[href="/c/${conversationId}"]`);
    await conversationLink.click();
    await page.waitForTimeout(300);

    await expect(conversationLink.getByTestId("nav-pending")).toHaveCount(0);
    await expect(page).toHaveURL(new RegExp(`/c/${conversationId}$`));
    expect(rscRequestSent).toBe(false);
  } finally {
    await page.unroute(isConversationPath);
  }
});

test("사이드바 링크의 이름이 그대로 맞는다", async ({ page }, testInfo) => {
  await page.goto("/");
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();

  await expect(page.getByRole("link", { name: "사용량" })).toBeVisible();
  await expect(page.getByRole("link", { name: "에이전트", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "에이전트 관리" })).toBeVisible();
});
