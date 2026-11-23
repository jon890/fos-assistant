import { FAKE_STARTER_PROMPTS } from "../../../test/e2e/fake-hermes.ts";
import { CONVERSATION_URL, expect, PERSONA_AGENT_CODE, test } from "./fixtures.ts";
import { expectStartAgent, seedConversation, startAgentTag } from "./helpers.ts";
import type { Locator, Page } from "../../node_modules/@playwright/test/index.js";

/** 실제로 디코딩되는 1x1 PNG 다. 저장소에 이미지를 넣지 않으려고 바이트로 만들어 쓴다. */
const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

/** 가짜 Hermes 는 모든 profile 에 같은 네 추천을 답한다. 그 가운데 첫 번째다. */
const PROMPT = FAKE_STARTER_PROMPTS[0];
const STARTERS_PATTERN = "**/api/agents/*/starters";

/** `/api/agents` 한 줄의 모양이다. 화면이 읽는 칸을 모두 갖춘다. 새 대화는 `primary` 인 줄의 에이전트로 연다. */
function agentRow(code: string, name: string, primary = true) {
  return {
    code,
    name,
    visibility: "PRIVATE",
    acceptsAttachments: true,
    primary,
  };
}

function composer(page: Page): Locator {
  return page.getByRole("textbox", { name: "메시지" });
}

async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow, "페이지가 가로로 밀린 폭(px)").toBeLessThanOrEqual(0);
}

/** 사진이 실제로 읽혔는지 본다. `toBeVisible` 만으로는 깨진 이미지도 통과한다. */
async function expectImageLoaded(locator: Locator): Promise<void> {
  await expect(locator).toBeVisible();
  await expect
    .poll(() => locator.evaluate((img) => (img as HTMLImageElement).naturalWidth))
    .toBeGreaterThan(0);
}

test("새 대화 화면에 인사와 기본 비서의 이름표와 추천 질문이 보이고 카드는 없다", async ({ page }) => {
  await page.goto("/new");

  await expect(page.getByRole("heading", { level: 1, name: /님, 무엇을 도와드릴까요\?$/ })).toBeVisible();
  await expectStartAgent(page, "브라우저 비서");
  // 에이전트 카드(radio 묶음)가 없다. 새 대화는 늘 기본 비서다(스펙 assistant-hub REQ4).
  await expect(page.getByRole("radio")).toHaveCount(0);
  await expect(page.getByRole("button", { name: PROMPT, exact: true })).toBeVisible({ timeout: 15_000 });
  await expect(composer(page)).toHaveAttribute("placeholder", "무엇이든 물어보세요");
  await expectNoHorizontalScroll(page);
});

test("추천 질문을 누르면 그 글로 바로 보내고 입력창의 글은 그대로 둔다", async ({ page }) => {
  await page.goto("/new");
  await composer(page).fill("쓰던 글");

  await page.getByRole("button", { name: PROMPT, exact: true }).click({ timeout: 15_000 });

  await expect(page.getByTestId("user-message").last()).toContainText(PROMPT);
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
  await expect(composer(page)).toHaveValue("쓰던 글");
  // 첫 메시지 뒤에는 새 대화 화면이 사라지고 입력창이 아래로 간다.
  await expect(page.getByTestId("start-agent-tag")).toHaveCount(0);
  await expect(page.getByRole("button", { name: PROMPT, exact: true })).toHaveCount(0);
});

test("주소에 다른 에이전트를 붙여 열어도 기본 비서의 새 대화다", async ({ page }) => {
  await page.goto(`/new?agent=${PERSONA_AGENT_CODE}`);
  await expectStartAgent(page, "브라우저 비서");

  await composer(page).fill("주소의 에이전트 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
  const conversationId = new URL(page.url()).pathname.split("/").at(-1);
  const conversations = await (await page.request.get("/api/chat/conversations")).json();
  expect(conversations.items.find((item: { id: string }) => item.id === conversationId).agentCode).toBe("browser");
});

test("새 대화 화면에서 @ 를 쳐도 에이전트를 고르는 목록이 뜨지 않는다", async ({ page }) => {
  await page.goto("/new");

  await composer(page).fill("@빈");

  await expect(composer(page)).toHaveValue("@빈");
  await expect(page.getByRole("listbox", { name: "에이전트 고르기" })).toHaveCount(0);
  await expectStartAgent(page, "브라우저 비서");
});

for (const listed of ["빈 목록", "기본 비서가 없는 목록"] as const) {
  test(`${listed}이면 입력창을 잠그고 비서를 쓸 수 없다고 알린다`, async ({ page }) => {
    // 목록은 켜진 에이전트만 담아 기본 비서가 꺼졌으면 목록에 없다(스펙 assistant-hub DECISION15).
    await page.route("**/api/agents", async (route) => {
      await route.fulfill({ json: listed === "빈 목록" ? [] : [agentRow(PERSONA_AGENT_CODE, "성격 비서", false)] });
    });
    await page.goto("/new");

    await expect(page.getByTestId("primary-unavailable")).toHaveText("비서를 쓸 수 없어요. 관리자에게 알려 주세요");
    await expect(composer(page)).toBeDisabled();
    await expect(page.getByRole("button", { name: "다시 읽기" })).toHaveCount(0);
    await expect(page.getByTestId("start-agent-tag")).toHaveCount(0);
  });
}

for (const failure of ["HTTP", "fetch", "JSON", "객체 응답", "null 응답"] as const) {
  test(`목록 ${failure} 실패에는 다시 읽기만 보이고 재시도 성공은 입력창을 유지한다`, async ({ page }) => {
    await page.route("**/api/agents", async (route) => {
      if (failure === "fetch") await route.abort("failed");
      else if (failure === "JSON") await route.fulfill({ contentType: "application/json", body: "{" });
      else if (failure === "객체 응답") await route.fulfill({ status: 200, json: {} });
      else if (failure === "null 응답") await route.fulfill({ status: 200, contentType: "application/json", body: "null" });
      else await route.fulfill({ status: 500, json: {} });
    });
    await page.goto("/new");
    await expect(page.getByRole("main").getByRole("alert")).toContainText("에이전트 목록을 읽지 못했어요");
    await expect(composer(page)).toBeDisabled();
    await expect(page.getByRole("link", { name: "에이전트 만들기" })).toHaveCount(0);
    await expectNoHorizontalScroll(page);
    // DOM에 표시를 남겨 재시도가 입력창을 새로 만들지 않는지 함께 본다.
    await composer(page).evaluate((node) => node.setAttribute("data-kept", "true"));
    await page.unroute("**/api/agents");
    const retry = page.getByRole("button", { name: "다시 읽기" });
    await retry.focus();
    await retry.press("Enter");
    await expect(composer(page)).toBeEnabled();
    await expect(composer(page)).toHaveAttribute("data-kept", "true");
    await expect(page.getByRole("main").getByRole("alert")).toHaveCount(0);
    await expect(page.getByRole("button", { name: PROMPT, exact: true })).toBeVisible({ timeout: 15_000 });
  });
}

test("재시도 연속 누름은 한 요청이며 실패하면 다시 읽을 수 있다", async ({ page }) => {
  let retryCalls = 0;
  let release!: () => void;
  const released = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/api/agents", (route) => route.fulfill({ status: 500, json: {} }));
  await page.goto("/new");
  await expect(page.getByRole("button", { name: "다시 읽기" })).toBeVisible();
  await page.unroute("**/api/agents");
  await page.route("**/api/agents", async (route) => {
    ++retryCalls;
    await released;
    await route.fulfill({ status: 500, json: {} });
  });
  // 같은 이벤트 턴의 두 호출은 다시 그리기 전에 진행 중 요청 가드를 거친다.
  await page.getByRole("button", { name: "다시 읽기" }).evaluate((button) => {
    (button as HTMLButtonElement).click();
    (button as HTMLButtonElement).click();
  });
  await expect(page.getByRole("status").filter({ hasText: "에이전트를 읽는 중" })).toBeVisible();
  await expect.poll(() => retryCalls).toBe(1);
  release();
  await expect(page.getByRole("button", { name: "다시 읽기" })).toBeEnabled();
  await expect(composer(page)).toBeDisabled();
  await page.unroute("**/api/agents");
  await page.getByRole("button", { name: "다시 읽기" }).click();
  await expect(composer(page)).toBeEnabled();
});

for (const result of ["실패", "빈 목록", "다른 에이전트", "객체 응답", "null 응답"] as const) {
  test(`기존 빈 대화는 목록 ${result}에도 에이전트와 글을 유지한다`, async ({ page }) => {
    // 새 대화는 기본 비서로만 열리므로 다른 에이전트와의 지난 빈 대화를 만든다(스펙 assistant-hub REQ4).
    const conversationId = await seedConversation(PERSONA_AGENT_CODE);
    await page.route("**/api/agents", (route) => route.fulfill(
      result === "실패" ? { status: 500, json: {} }
        : result === "객체 응답" ? { status: 200, json: {} }
          : result === "null 응답" ? { status: 200, contentType: "application/json", body: "null" }
            : { json: result === "빈 목록" ? [] : [agentRow("browser", "다른 비서")] },
    ));
    try {
      await page.goto(`/chat/${conversationId}`);
      await expect(composer(page)).toBeEnabled();
      await composer(page).fill("남겨 둔 글");
      await composer(page).evaluate((node) => node.setAttribute("data-kept", "true"));
      // 이미 연 대화에는 첫 화면의 재시도 UI가 없다. 목록 결과가 전송할 에이전트를 바꾸지 않는지 본다.
      await expect(page.getByRole("button", { name: "다시 읽기" })).toHaveCount(0);
      await expect(composer(page)).toHaveValue("남겨 둔 글");
      await expect(composer(page)).toHaveAttribute("data-kept", "true");
      await expect(page).toHaveURL(new RegExp(`/chat/${conversationId}$`));
      await page.getByRole("button", { name: "보내기" }).click();
      await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
      const conversations = await (await page.request.get("/api/chat/conversations")).json();
      expect(conversations.items.find((item: { id: string }) => item.id === conversationId).agentCode).toBe(PERSONA_AGENT_CODE);
    } finally {
      await page.request.delete(`/api/chat/conversations/${conversationId}`);
    }
  });
}

test("unmount 뒤 도착한 목록 응답은 새 화면의 선택을 덮어쓰지 않는다", async ({ page }, testInfo) => {
  // 이전 ChatPanel의 unmount 격리를 본다. 요청 번호 가드는 아래 Strict Mode 검사로 검증한다.
  // 요청을 제거하면 Playwright가 이미 처리된 route를 해제하므로 fetch의 응답만 보류한다.
  await page.addInitScript(() => {
    const original = window.fetch.bind(window);
    let hold = true;
    const queued: Array<(response: Response) => void> = [];
    Object.assign(window, {
      agentReads: queued,
      resumeAgentReads: () => { hold = false; },
      releaseAgentReads: () => queued.forEach((resolve) => resolve(Response.json([{ code: "browser", name: "늦은 비서" }]))),
    });
    window.fetch = (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (hold && new URL(url, location.href).pathname === "/api/agents") {
        return new Promise<Response>((resolve) => queued.push(resolve));
      }
      return original(input, init);
    };
  });
  await page.goto("/new");
  await expect.poll(() => page.evaluate(() => (window as unknown as { agentReads: unknown[] }).agentReads.length)).toBeGreaterThan(0);
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
  // 사이드바가 남는 화면으로 떠나 ChatPanel 을 내린다.
  await page.locator('a[href="/tasks"]').first().click();
  await expect(page).toHaveURL(/\/tasks$/);
  await page.evaluate(() => (window as unknown as { resumeAgentReads(): void }).resumeAgentReads());
  // 기본 비서가 있는 사용자의 홈은 대화방이라 사이드바의 「새 대화」 로 돌아온다.
  if (testInfo.project.name === "mobile") await page.getByRole("button", { name: "사이드바 열기" }).click();
  await page.getByRole("link", { name: "새 대화", exact: true }).first().click();
  await expectStartAgent(page, "브라우저 비서");
  await page.evaluate(async () => {
    (window as unknown as { releaseAgentReads(): void }).releaseAgentReads();
    await new Promise(requestAnimationFrame);
  });
  await expectStartAgent(page, "브라우저 비서");
  await expect(page.getByText("늦은 비서", { exact: true })).toHaveCount(0);
});

test("Strict Mode의 이전 요청과 finally는 다음 요청의 읽는 중 상태를 풀지 않는다", async ({ page }) => {
  test.skip(process.env.BROWSER_WEB_SERVER !== "dev", "Strict Mode의 effect 재실행은 개발 서버에서 확인한다");
  await page.addInitScript(() => {
    const original = window.fetch.bind(window);
    const queued: Array<(response: Response) => void> = [];
    Object.assign(window, { agentReads: queued });
    window.fetch = (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (new URL(url, location.href).pathname === "/api/agents") {
        return new Promise<Response>((resolve) => queued.push(resolve));
      }
      return original(input, init);
    };
  });
  await page.goto("/new");
  const reads = () => page.evaluate(() => (window as unknown as { agentReads: unknown[] }).agentReads.length);
  await expect.poll(reads).toBe(2);
  // 이전 응답은 성공이어도 선택과 loading을 바꾸지 못한다.
  await page.evaluate(() => {
    (window as unknown as { agentReads: Array<(response: Response) => void> }).agentReads[0](Response.json([{ code: "old", name: "이전 비서" }]));
  });
  await expect(page.getByRole("status").filter({ hasText: "에이전트를 읽는 중" })).toBeVisible();
  await expect(page.getByText("이전 비서")).toHaveCount(0);
  await page.evaluate(() => {
    (window as unknown as { agentReads: Array<(response: Response) => void> }).agentReads[1](new Response("{}", { status: 500 }));
  });
  await expect(page.getByRole("button", { name: "다시 읽기" })).toBeVisible();
  await page.getByRole("button", { name: "다시 읽기" }).click();
  await expect.poll(reads).toBe(3);
  await page.evaluate(() => {
    (window as unknown as { agentReads: Array<(response: Response) => void> }).agentReads[2](Response.json([{ code: "browser", name: "최신 비서", acceptsAttachments: true, primary: true }]));
  });
  await expectStartAgent(page, "최신 비서");
  await expect(composer(page)).toBeEnabled();
});

test("새 대화에서 사진을 먼저 올려 빈 대화가 생겨도 기본 비서의 이름표가 그대로다", async ({ page }) => {
  await page.goto("/new");
  await expectStartAgent(page, "브라우저 비서");

  await page.getByTestId("attachment-input").setInputFiles([
    { name: "lock.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(1);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);

  // 빈 대화가 생겨도 메시지가 없는 동안은 새 대화 화면 모양 그대로다.
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(page.getByRole("heading", { level: 1, name: /무엇을 도와드릴까요\?$/ })).toBeVisible();
  await expectStartAgent(page, "브라우저 비서");
  await startAgentTag(page).click();
  await expect(page.getByRole("menuitemradio")).toHaveCount(0);
});

test("새 대화 화면에서 사진과 글을 보내면 입력창이 아래로 가도 사진이 메시지에 남는다", async ({ page }, testInfo) => {
  await page.goto("/new");
  await expect(startAgentTag(page)).toBeVisible();

  await page.getByTestId("attachment-input").setInputFiles([
    { name: "start.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(1);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);

  await composer(page).fill(`새 대화 화면 사진 검사 ${testInfo.project.name}`);
  await page.getByRole("button", { name: "보내기" }).click();

  const userMessage = page.getByTestId("user-message").last();
  await expectImageLoaded(userMessage.getByTestId("message-attachment"));
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("start-agent-tag")).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);

  // 새로 읽어도 사진이 남아 있어야 한다. 입력창이 새로 만들어졌다면 정리가 그 사진을 서버에서 지운다.
  await page.reload();
  await expectImageLoaded(page.getByTestId("user-message").last().getByTestId("message-attachment"));
});

test("사진을 올려 두고 추천 질문으로 새 대화 화면을 벗어나도 사진이 입력창에 남는다", async ({ page }, testInfo) => {
  await page.goto("/new");
  await expect(page.getByRole("button", { name: PROMPT, exact: true })).toBeVisible({ timeout: 15_000 });

  await page.getByTestId("attachment-input").setInputFiles([
    { name: "kept.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  const previews = page.getByTestId("attachment-previews").locator("> div");
  await expect(previews).toHaveCount(1);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);

  // 추천 질문은 첨부를 싣지 않는다. 새 대화 화면이 사라질 때 입력창이 새로 만들어지면 정리가 사진을 지운다.
  await page.getByRole("button", { name: PROMPT, exact: true }).click();
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("start-agent-tag")).toHaveCount(0);
  await expect(page.getByTestId("user-message").first().getByTestId("message-attachment")).toHaveCount(0);
  await expect(previews).toHaveCount(1);

  await composer(page).fill(`남은 사진 검사 ${testInfo.project.name}`);
  await page.getByRole("button", { name: "보내기" }).click();
  await expectImageLoaded(page.getByTestId("user-message").last().getByTestId("message-attachment"));
  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);

  await page.reload();
  await expect(page.getByTestId("user-message")).toHaveCount(2);
  await expectImageLoaded(page.getByTestId("user-message").last().getByTestId("message-attachment"));
});

test("사진 때문에 빈 대화를 만드는 동안에는 추천 질문을 누를 수 없어 대화가 하나만 생긴다", async ({ page }) => {
  let release!: () => void;
  const released = new Promise<void>((resolve) => {
    release = resolve;
  });
  const createdIds: string[] = [];
  // route 는 goto 보다 먼저 건다. 대화 목록을 읽는 GET 은 그대로 보낸다.
  await page.route((url) => url.pathname === "/api/chat/conversations", async (route) => {
    if (route.request().method() !== "POST") {
      await route.fallback();
      return;
    }
    await released;
    const response = await route.fetch();
    const body = (await response.json()) as { conversationId: string };
    createdIds.push(body.conversationId);
    await route.fulfill({ response, json: body });
  });
  await page.goto("/new");
  const promptButton = page.getByRole("button", { name: PROMPT, exact: true });
  await expect(promptButton).toBeEnabled({ timeout: 15_000 });

  await page.getByTestId("attachment-input").setInputFiles([
    { name: "slow.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  await expect(promptButton).toBeDisabled();

  release();
  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(1);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(promptButton).toBeEnabled();
  expect(createdIds, "사진을 고르며 만든 빈 대화").toHaveLength(1);

  await promptButton.click();
  await expect(page.getByTestId("user-message").last()).toContainText(PROMPT);
  await expect(page).toHaveURL(new RegExp(`/chat/${createdIds[0]}$`));
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
  await expect(page).toHaveURL(new RegExp(`/chat/${createdIds[0]}$`));
  expect(createdIds, "추천 질문을 보낸 뒤 만든 대화").toHaveLength(1);
});
