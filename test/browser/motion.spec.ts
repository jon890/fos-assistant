import { conversationIdOf, expect, test } from "./fixtures.ts";
import type { Locator, Page, TestInfo } from "../../web/node_modules/@playwright/test/index.js";

/** 그 요소에 지금 걸린 animation 의 이름과 길이를 읽는다. */
function animationOf(element: Locator) {
  return element.evaluate((node) => {
    const style = getComputedStyle(node);
    return { name: style.animationName, duration: style.animationDuration };
  });
}

/** tw-animate-css 가 들어오는 움직임에 쓰는 변수의 계산값을 읽는다. */
function enterVariable(element: Locator, name: string) {
  return element.evaluate((node, variable) => getComputedStyle(node).getPropertyValue(variable).trim(), name);
}

async function send(page: Page, text: string) {
  await page.getByRole("radio", { name: "브라우저 비서" }).click();
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
}

async function createConversation(page: Page, title: string): Promise<string> {
  const response = await page.request.post("/api/chat", { data: { text: title, agentCode: "browser" } });
  expect(response.ok()).toBeTruthy();
  return ((await response.json()) as { conversationId: string }).conversationId;
}

async function openSidebar(page: Page, testInfo: TestInfo) {
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
}

function conversationNav(page: Page) {
  return page.getByRole("navigation", { name: "대화 목록" });
}

/** 대화 목록에서 그 대화로 가는 링크다. 대화상자가 열린 동안에는 목록이 접근성 나무에서 빠지므로 역할로 찾지 않는다. */
function conversationLink(page: Page, id: string) {
  return page.locator(`nav[aria-label="대화 목록"] a[href="/chat/${id}"]`);
}

/**
 * 답을 받아 오는 요청을 붙잡아 둔다. 붙잡힌 동안 화면은 보낸 말풍선과 기다림 줄을 그대로 둔다.
 * 돌려준 함수를 부르면 요청이 나간다.
 */
async function holdChatStream(page: Page): Promise<() => void> {
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/api/chat/stream", async (route) => {
    await held;
    await route.continue();
  });
  return release;
}

async function openDeleteDialog(page: Page, testInfo: TestInfo, title: string) {
  await openSidebar(page, testInfo);
  await conversationNav(page).getByRole("button", { name: `${title} 메뉴` }).click();
  await page.getByRole("menuitem", { name: "지우기" }).click();
  const dialog = page.getByRole("alertdialog", { name: "대화 지우기" });
  await expect(dialog).toBeVisible();
  return dialog;
}

/** `data-leaving` 이 붙은 대화 줄의 주소를 `window.leavingSeen` 에 적어 둔다. 줄은 곧 사라지므로 붙는 순간에 적는다. */
async function recordLeavingRows(page: Page) {
  await page.addInitScript(() => {
    const seen: string[] = [];
    (window as unknown as { leavingSeen: string[] }).leavingSeen = seen;
    new MutationObserver((records) => {
      for (const record of records) {
        const row = record.target as Element;
        if (row.getAttribute("data-leaving") !== "true") continue;
        seen.push(row.querySelector("a")?.getAttribute("href") ?? row.textContent ?? "");
      }
    }).observe(document, { attributes: true, subtree: true, attributeFilter: ["data-leaving"] });
  });
}

function leavingSeen(page: Page) {
  return page.evaluate(() => (window as unknown as { leavingSeen: string[] }).leavingSeen);
}

/**
 * 대화 목록 줄에서 animation 이 시작되는 순간 그 줄의 주소와 animation 이름과 길이를 `window.rowAnimationsStarted` 에 적어 둔다.
 * 등장 움직임은 0.2초면 끝나고 끝나면 클래스가 떨어지므로, 나중에 계산값을 읽으면 느린 실행에서 이미 지나가 있다.
 */
async function recordRowAnimationStarts(page: Page) {
  await page.addInitScript(() => {
    const started: Record<string, { name: string; duration: string }> = {};
    (window as unknown as { rowAnimationsStarted: typeof started }).rowAnimationsStarted = started;
    document.addEventListener("animationstart", (event) => {
      const row = event.target as Element;
      if (row.tagName !== "LI" || !row.closest('nav[aria-label="대화 목록"]')) return;
      const href = row.querySelector("a")?.getAttribute("href");
      if (href) started[href] = { name: event.animationName, duration: getComputedStyle(row).animationDuration };
    }, true);
  });
}

function rowAnimationStarted(page: Page, id: string) {
  return page.evaluate(
    (href) => (window as unknown as { rowAnimationsStarted: Record<string, { name: string; duration: string }> }).rowAnimationsStarted[href],
    `/chat/${id}`,
  );
}

test("새로 보낸 줄만 등장 움직임을 갖고 답이 저장된 뒤와 대화를 다시 연 뒤에는 움직이지 않는다", async ({ page }, testInfo) => {
  const existingTitle = `움직임 앞선 대화 ${testInfo.project.name} ${Date.now()}`;
  const existingId = await createConversation(page, existingTitle);
  const release = await holdChatStream(page);
  await recordRowAnimationStarts(page);
  try {
    await page.goto("/");
    await send(page, "등장 움직임 검사");

    const bubble = page.getByTestId("user-message");
    await expect(bubble).toBeVisible();
    expect(await animationOf(bubble), "보낸 직후의 내 말풍선").toEqual({ name: "message-user", duration: "0.2s" });
    // transform-origin 의 계산값은 px 이다. 오른쪽 아래 모서리면 말풍선의 폭과 높이와 같다.
    const origin = await bubble.evaluate((node) => {
      const [x, y] = getComputedStyle(node).transformOrigin.split(" ").map(parseFloat);
      return { x, y, width: (node as HTMLElement).offsetWidth, height: (node as HTMLElement).offsetHeight };
    });
    expect(Math.abs(origin.x - origin.width), "내 말풍선이 커지는 기준점의 가로").toBeLessThanOrEqual(1);
    expect(Math.abs(origin.y - origin.height), "내 말풍선이 커지는 기준점의 세로").toBeLessThanOrEqual(1);
    const pending = page.getByTestId("pending-assistant");
    await expect(pending).toBeVisible();
    expect(await animationOf(pending), "답을 기다리는 비서 줄").toEqual({ name: "message-assistant", duration: "0.2s" });

    release();
    await expect(page.getByRole("button", { name: "답 복사" })).toBeVisible({ timeout: 30_000 });
    // 답이 저장되면 줄이 숫자 id 로 바뀐다. 그 줄에는 걸린 animation 이 없어야 한다.
    await expect.poll(() => bubble.evaluate((node) => node.getAnimations().length), { message: "답이 저장된 뒤 내 말풍선에 걸린 animation 수" })
      .toBe(0);
    expect((await animationOf(page.getByTestId("assistant-message"))).name, "저장된 비서 줄").toBe("none");
    expect((await animationOf(page.getByTestId("assistant-body"))).name, "저장된 답 본문").toBe("none");

    if (testInfo.project.name === "desktop") {
      // 목록을 처음 읽은 뒤에 생긴 대화 줄만 등장 움직임을 갖는다.
      const id = conversationIdOf(page.url());
      const row = (conversationId: string) => conversationNav(page).locator("li").filter({ has: page.locator(`a[href="/chat/${conversationId}"]`) });
      await expect(row(id)).toBeVisible();
      // 읽는 시점에 이미 끝났을 수 있어 움직임이 시작된 순간의 기록을 본다.
      await expect.poll(() => rowAnimationStarted(page, id), { message: "새로 생긴 대화 줄의 움직임" })
        .toEqual({ name: "message-assistant", duration: "0.2s" });
      expect(await rowAnimationStarted(page, existingId), "처음부터 있던 대화 줄의 움직임").toBeUndefined();
      // 한 번 움직인 줄은 검색으로 걸러졌다 다시 나타나도 다시 움직이지 않는다.
      await expect.poll(async () => (await animationOf(row(id))).name, { message: "움직임이 끝난 대화 줄" }).toBe("none");
      const search = page.getByRole("searchbox", { name: "대화 검색" });
      await search.fill("없는 제목 123456");
      await expect(row(id)).toHaveCount(0);
      await search.fill("");
      await expect(row(id)).toBeVisible();
      expect((await animationOf(row(id))).name, "걸러졌다 다시 나타난 대화 줄").toBe("none");
    }

    await page.reload();
    await expect(bubble).toBeVisible();
    await expect(page.getByTestId("assistant-message")).toBeVisible();
    expect((await animationOf(bubble)).name, "다시 연 대화의 내 말풍선").toBe("none");
    expect((await animationOf(page.getByTestId("assistant-message"))).name, "다시 연 대화의 비서 줄").toBe("none");
  } finally {
    release();
  }
});

test("작업 과정 블록은 높이가 transition 으로 바뀌고 접힌 내용은 보이지 않는다", async ({ page }) => {
  await page.goto("/");
  await send(page, "작업 과정 펼침 검사");
  const block = page.locator('[data-testid="activity-block"][data-mode="saved"]').last();
  await expect(block).toBeVisible({ timeout: 30_000 });

  const collapsible = block.locator(".collapsible");
  const transition = await collapsible.evaluate((node) => {
    const style = getComputedStyle(node);
    return { property: style.transitionProperty, duration: style.transitionDuration };
  });
  expect(transition.property).toContain("grid-template-rows");
  expect(transition.duration).toBe("0.26s");

  const toggle = block.getByTestId("activity-toggle");
  const openPanel = block.getByTestId("activity-open-panel");
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await expect(openPanel).toBeHidden();
  expect(await collapsible.evaluate((node) => node.getBoundingClientRect().height), "접힌 내용의 높이").toBe(0);

  await toggle.click();
  await expect(openPanel).toBeVisible();
  await expect.poll(() => collapsible.evaluate((node) => node.getBoundingClientRect().height), { message: "펼친 내용의 높이" })
    .toBeGreaterThan(40);

  // 접으면 높이가 다 줄어든 뒤에 숨는다. 접힌 단추에는 초점이 가지 않는다.
  await toggle.click();
  await expect(openPanel).toBeHidden();
  await expect.poll(() => collapsible.evaluate((node) => node.getBoundingClientRect().height), { message: "다시 접은 내용의 높이" })
    .toBe(0);
  expect(await openPanel.evaluate((node) => node.closest("[inert]") !== null), "접힌 내용은 inert 다").toBe(true);
});

test("줄인 움직임에서 말풍선은 이동하지 않고 흐려짐만 한다", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  const release = await holdChatStream(page);
  try {
    await page.goto("/");
    await send(page, "줄인 움직임 말풍선 검사");
    const bubble = page.getByTestId("user-message");
    await expect(bubble).toBeVisible();
    expect(await animationOf(bubble)).toEqual({ name: "fade-in", duration: "0.1s" });
    expect(await animationOf(page.getByTestId("pending-assistant"))).toEqual({ name: "fade-in", duration: "0.1s" });
  } finally {
    release();
  }
  await expect(page.getByRole("button", { name: "답 복사" })).toBeVisible({ timeout: 30_000 });
});

test("줄인 움직임에서 대화상자는 크기 변화를 하지 않는다", async ({ page }, testInfo) => {
  const title = `움직임 대화상자 ${testInfo.project.name} ${Date.now()}`;
  await createConversation(page, title);
  await page.goto("/");
  await openDeleteDialog(page, testInfo, title);
  const content = page.locator('[data-slot="alert-dialog-content"]');
  expect(Number(await enterVariable(content, "--tw-enter-scale")), "줄이지 않은 대화상자의 시작 크기").toBe(0.96);
  expect(await content.evaluate((node) => getComputedStyle(node).animationDuration)).toBe("0.2s");

  await page.emulateMedia({ reducedMotion: "reduce" });
  expect(Number(await enterVariable(content, "--tw-enter-scale")), "줄인 움직임에서 대화상자의 시작 크기").toBe(1);
});

test("줄인 움직임에서 서랍은 밀려 들어오지 않는다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile");
  await page.goto("/");
  await openSidebar(page, testInfo);
  const drawer = page.locator('[data-slot="sheet-content"][data-side="left"]');
  await expect(drawer).toBeVisible();
  // 줄이지 않았을 때는 서랍 폭만큼 밖에서 밀려 들어온다.
  expect(await enterVariable(drawer, "--tw-enter-translate-x")).toBe("-100%");
  expect(await drawer.evaluate((node) => getComputedStyle(node).animationDuration)).toBe("0.26s");

  await page.emulateMedia({ reducedMotion: "reduce" });
  expect(["0", "0px"]).toContain(await enterVariable(drawer, "--tw-enter-translate-x"));
});

test("대화를 지우면 줄이 나가는 움직임을 거쳐 사라진다", async ({ page }, testInfo) => {
  const title = `움직임 삭제 ${testInfo.project.name} ${Date.now()}`;
  const id = await createConversation(page, title);
  await recordLeavingRows(page);
  await page.goto("/");
  const dialog = await openDeleteDialog(page, testInfo, title);
  expect(await leavingSeen(page), "지우기 전에는 나가는 줄이 없다").toEqual([]);
  await dialog.getByRole("button", { name: "지우기" }).click();
  await expect(dialog).toBeHidden();
  await expect(conversationLink(page, id)).toHaveCount(0);
  expect(await leavingSeen(page), "사라지기 전에 data-leaving 이 붙은 줄").toEqual([`/chat/${id}`]);
});

test("줄인 움직임에서는 지운 대화 줄이 기다리지 않고 사라진다", async ({ page }, testInfo) => {
  const title = `움직임 바로 삭제 ${testInfo.project.name} ${Date.now()}`;
  const id = await createConversation(page, title);
  await recordLeavingRows(page);
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  const dialog = await openDeleteDialog(page, testInfo, title);
  await dialog.getByRole("button", { name: "지우기" }).click();
  await expect(dialog).toBeHidden();
  await expect(conversationLink(page, id)).toHaveCount(0);
  expect(await leavingSeen(page), "줄인 움직임에서는 나가는 움직임을 기다리지 않는다").toEqual([]);
});

test("지우기가 실패하면 줄이 나가는 움직임 없이 남는다", async ({ page }, testInfo) => {
  const title = `움직임 삭제 실패 ${testInfo.project.name} ${Date.now()}`;
  const id = await createConversation(page, title);
  await recordLeavingRows(page);
  await page.route("**/api/chat/conversations/*", (route) => route.request().method() === "DELETE"
    ? route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ code: "INTERNAL_ERROR", message: "지우기 실패" }) })
    : route.continue());
  await page.goto("/");
  const dialog = await openDeleteDialog(page, testInfo, title);
  await dialog.getByRole("button", { name: "지우기" }).click();
  await expect(dialog).toBeHidden();
  await expect(conversationNav(page).getByRole("alert")).toContainText("지우기 실패");
  const link = conversationLink(page, id);
  await expect(link).toBeVisible();
  expect(await link.evaluate((node) => node.closest("li")?.hasAttribute("data-leaving")), "남은 줄의 data-leaving 속성").toBe(false);
  expect(await leavingSeen(page), "실패한 지우기는 나가는 움직임을 시작하지 않는다").toEqual([]);
});

test("새 기억은 등장 움직임을 갖고 지운 기억은 나가는 움직임을 거쳐 사라진다", async ({ page }, testInfo) => {
  const title = `움직임 기억 ${testInfo.project.name} ${Date.now()}`;
  await recordLeavingRows(page);
  await page.goto("/memory");
  await page.getByLabel("범위").selectOption("USER");
  await page.getByLabel("제목", { exact: true }).fill(title);
  await page.getByLabel("내용").fill("움직임을 검사하는 기억");
  await page.getByRole("button", { name: "저장" }).click();
  const item = page.getByRole("heading", { name: title }).locator("xpath=ancestor::article");
  await expect(item).toBeVisible();
  expect(await animationOf(item), "화면을 연 뒤에 생긴 기억").toEqual({ name: "message-assistant", duration: "0.2s" });

  await page.reload();
  await expect(item).toBeVisible();
  expect((await animationOf(item)).name, "처음부터 있던 기억").toBe("none");

  await item.getByRole("button", { name: "지우기" }).click();
  await expect(item).toHaveCount(0);
  expect((await leavingSeen(page)).length, "사라지기 전에 data-leaving 이 붙은 기억 수").toBe(1);
});

test("기억을 지운 뒤 목록을 다시 읽지 못해도 남은 줄이 투명한 채로 있지 않다", async ({ page }, testInfo) => {
  const title = `움직임 다시 읽기 실패 ${testInfo.project.name} ${Date.now()}`;
  await recordLeavingRows(page);
  await page.goto("/memory");
  await page.getByLabel("범위").selectOption("USER");
  await page.getByLabel("제목", { exact: true }).fill(title);
  await page.getByLabel("내용").fill("다시 읽기가 실패하는 기억");
  await page.getByRole("button", { name: "저장" }).click();
  const item = page.getByRole("heading", { name: title }).locator("xpath=ancestor::article");
  await expect(item).toBeVisible();

  // 지우기 요청은 그대로 보내 성공시키고, 뒤이어 목록을 다시 읽는 요청만 실패시킨다.
  await page.route("**/api/memories", (route) => route.request().method() === "GET"
    ? route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ code: "INTERNAL_ERROR", message: "다시 읽기 실패" }) })
    : route.continue());
  const removed = page.waitForResponse((response) => response.request().method() === "DELETE" && /\/api\/memories\/\d+$/.test(new URL(response.url()).pathname));
  const reloaded = page.waitForResponse((response) => response.request().method() === "GET" && new URL(response.url()).pathname === "/api/memories");
  await item.getByRole("button", { name: "지우기" }).click();
  expect((await removed).ok(), "기억 지우기 요청의 성공 여부").toBe(true);
  expect((await reloaded).status(), "목록 다시 읽기의 상태 코드").toBe(500);

  await expect(item, "다시 읽지 못해 남은 줄").toBeVisible();
  await expect(item, "남은 줄의 data-leaving 속성").not.toHaveAttribute("data-leaving");
  expect(await item.evaluate((node) => getComputedStyle(node).opacity), "남은 줄의 불투명도").toBe("1");
  expect((await leavingSeen(page)).length, "다시 읽기 전에 data-leaving 이 붙은 기억 수").toBe(1);
});

test("줄인 움직임에서 「새 메시지」 단추는 바로 내려간다", async ({ page }) => {
  await page.addInitScript(() => {
    const behaviors: unknown[] = [];
    (window as unknown as { scrollBehaviors: unknown[] }).scrollBehaviors = behaviors;
    const original = Element.prototype.scrollTo;
    Element.prototype.scrollTo = function (this: Element, ...args: unknown[]) {
      const options = args[0];
      if (typeof options === "object" && options !== null) behaviors.push((options as ScrollToOptions).behavior);
      return (original as (...rest: unknown[]) => void).apply(this, args);
    } as typeof Element.prototype.scrollTo;
  });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  const composer = page.getByRole("textbox", { name: "메시지" });
  await composer.fill("긴 답 스트림 검사");
  await page.getByRole("button", { name: "보내기" }).click();

  const scroll = page.getByTestId("message-scroll");
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await composer.fill("새 메시지 단추 검사");
  await expect(page.getByRole("button", { name: "보내기" })).toBeEnabled();
  await expect.poll(async () => scroll.evaluate((element) => element.scrollHeight - element.clientHeight))
    .toBeGreaterThan(100);
  await scroll.evaluate((element) => {
    element.scrollTop = 0;
    element.dispatchEvent(new Event("scroll"));
  });
  await page.getByRole("button", { name: "보내기" }).click();
  const jump = page.getByRole("button", { name: "새 메시지" });
  await expect(jump).toBeVisible();
  await jump.click();
  await expect(jump).toHaveCount(0);
  const behaviors = await page.evaluate(() => (window as unknown as { scrollBehaviors: unknown[] }).scrollBehaviors);
  expect(behaviors, "단추가 부른 scrollTo 의 behavior").toContain("auto");
  expect(behaviors).not.toContain("smooth");
  await expect.poll(() => scroll.evaluate((element) => element.scrollHeight - element.scrollTop - element.clientHeight))
    .toBeLessThanOrEqual(1);
});

/** 사이드바의 「사용량」 을 눌러 화면을 옮기고 그 화면의 제목이 보일 때까지 기다린다. */
async function goToUsage(page: Page, testInfo: TestInfo) {
  await openSidebar(page, testInfo);
  await page.getByRole("link", { name: "사용량", exact: true }).click();
  await expect(page).toHaveURL(/\/usage$/);
  await expect(page.getByRole("heading", { name: "사용량", exact: true, level: 1 })).toBeVisible();
}

test("화면을 옮기면 본문만 바뀌고 화면 틀은 같은 DOM 노드로 남는다", async ({ page }, testInfo) => {
  await page.goto("/");
  // 좁은 폭의 서랍은 옮기면 닫히므로, 그 폭에서는 늘 남는 머리의 단추를 화면 틀로 본다.
  const shell = testInfo.project.name === "mobile"
    ? page.getByRole("button", { name: "사이드바 열기" })
    : page.getByRole("complementary", { name: "사이드바" });
  await expect(shell).toBeVisible();
  await shell.evaluate((node) => node.setAttribute("data-probe", "shell"));

  await goToUsage(page, testInfo);
  await expect(shell, "옮긴 뒤에도 화면 틀이 다시 만들어지지 않는다").toHaveAttribute("data-probe", "shell");
  expect(await page.locator('[data-probe="shell"]').count(), "표시를 붙인 화면 틀의 수").toBe(1);
});

test("첫 메시지를 보내 주소가 바뀌어도 대화가 다시 만들어지지 않는다", async ({ page }) => {
  await page.goto("/");
  const composer = page.getByRole("textbox", { name: "메시지" });
  await expect(composer).toBeVisible();
  await composer.evaluate((node) => node.setAttribute("data-probe", "composer"));

  await send(page, "주소가 바뀌는 첫 메시지 검사");
  await expect(page).toHaveURL(/\/chat\/[^/]+$/);
  await expect(page.getByTestId("assistant-message")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "답 복사" })).toBeVisible({ timeout: 30_000 });
  await expect(composer, "주소가 바뀐 뒤에도 입력창이 같은 DOM 노드다").toHaveAttribute("data-probe", "composer");
});

test("줄인 움직임에서 화면을 옮겨도 본문 제목이 보인다", async ({ page }, testInfo) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  await goToUsage(page, testInfo);
  // 본문 제목이 보이는 것은 goToUsage 가 단언한다.
  const wrapper = page.locator(".animate-screen-in");
  if (await wrapper.count() > 0) {
    // 화면 전환을 끈 빌드에서만 들어오는 화면을 감싼 요소가 있다. 오르며 나타나지 않고 짧게 흐려지기만 한다.
    expect(await animationOf(wrapper), "줄인 움직임에서 들어오는 화면").toEqual({ name: "fade-in", duration: "0.1s" });
  }
  // 화면 전환을 켠 빌드에는 감싼 요소가 없다. 줄인 움직임 규칙이 `::view-transition-new` 가상 요소에 걸리는데,
  // 그 요소는 전환이 도는 짧은 동안에만 있어 계산값을 읽을 방법이 없다. 그래서 이 분기에서는 단언하지 않는다.
});
