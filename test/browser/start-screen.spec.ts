import { expect, setStarters, test } from "./fixtures.ts";
import type { Locator, Page } from "../../web/node_modules/@playwright/test/index.js";

/** 실제로 디코딩되는 1x1 PNG 다. 저장소에 이미지를 넣지 않으려고 바이트로 만들어 쓴다. */
const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

const TAGLINE = "브라우저 검사용 비서다";
const PROMPT = "첫 추천 질문이다";

/** `/api/agents` 한 줄의 모양이다. 화면이 읽는 칸을 모두 갖춘다. */
function agentRow(code: string, name: string, tagline: string | null, starterPrompts: string[]) {
  return {
    code,
    name,
    model: "example-model",
    visibility: "PRIVATE",
    acceptsAttachments: true,
    tagline,
    starterPrompts,
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

test.beforeEach(async () => {
  await setStarters("browser", TAGLINE, [PROMPT]);
});

test.afterAll(async () => {
  // 다른 검사가 에이전트 카드의 이름으로 고른다. 소개가 남으면 카드의 이름이 길어지므로 비운다.
  await setStarters("browser", null, []);
});

test("새 대화 화면에 인사와 첫 에이전트 카드와 추천 질문이 보인다", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByRole("heading", { level: 1, name: /님, 무엇을 도와줄까요$/ })).toBeVisible();
  const picker = page.getByRole("radiogroup", { name: "에이전트" });
  await expect(picker.getByRole("radio", { name: "브라우저 비서" })).toHaveAttribute("aria-checked", "true");
  await expect(picker.getByRole("radio", { name: "브라우저 비서" })).toContainText(TAGLINE);
  await expect(page.getByRole("button", { name: PROMPT, exact: true })).toBeVisible();
  await expect(composer(page)).toHaveAttribute("placeholder", "@ 로 에이전트를 부른다");
  await expectNoHorizontalScroll(page);
});

test("추천 질문을 누르면 그 글로 바로 보내고 입력창의 글은 그대로 둔다", async ({ page }) => {
  await page.goto("/");
  await composer(page).fill("쓰던 글");

  await page.getByRole("button", { name: PROMPT, exact: true }).click();

  await expect(page.getByTestId("user-message").last()).toContainText(PROMPT);
  await expect(page).toHaveURL(/\/c\/\d+$/);
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
  await expect(composer(page)).toHaveValue("쓰던 글");
  // 첫 메시지 뒤에는 새 대화 화면이 사라지고 입력창이 아래로 간다.
  await expect(page.getByRole("radiogroup", { name: "에이전트" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: PROMPT, exact: true })).toHaveCount(0);
});

test("다른 에이전트 카드를 누르면 그 에이전트의 추천 질문으로 바뀐다", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("button", { name: PROMPT, exact: true })).toBeVisible();

  await page.getByRole("radio", { name: "흐름 비서" }).click();

  await expect(page.getByRole("radio", { name: "흐름 비서" })).toHaveAttribute("aria-checked", "true");
  await expect(page.getByRole("radio", { name: "브라우저 비서" })).toHaveAttribute("aria-checked", "false");
  await expect(page.getByRole("button", { name: PROMPT, exact: true })).toHaveCount(0);
});

test("@ 뒤에 이름을 치고 Enter 를 누르면 그 에이전트를 고르고 보내지 않는다", async ({ page }) => {
  await page.goto("/");

  await composer(page).fill("@흐름");
  const list = page.getByRole("listbox", { name: "에이전트 고르기" });
  await expect(list.getByRole("option", { name: /흐름 비서/ })).toBeVisible();
  await expect(list.getByRole("option")).toHaveCount(1);
  await composer(page).press("Enter");

  await expect(list).toHaveCount(0);
  await expect(page.getByRole("radio", { name: "흐름 비서" })).toHaveAttribute("aria-checked", "true");
  await expect(composer(page)).toHaveValue("");
  await expect(page.getByTestId("user-message")).toHaveCount(0);
  await expect(page).toHaveURL(/\/$/);
});

test("@ 목록은 Esc 로 닫고 입력한 글은 남긴다", async ({ page }) => {
  await page.goto("/");

  await composer(page).fill("@");
  const list = page.getByRole("listbox", { name: "에이전트 고르기" });
  await expect(list).toBeVisible();
  await composer(page).press("Escape");

  await expect(list).toHaveCount(0);
  await expect(composer(page)).toHaveValue("@");
});

test("@ 뒤 글자에 맞는 에이전트가 없으면 목록에 알린다", async ({ page }) => {
  await page.goto("/");

  await composer(page).fill("@없는이름");

  await expect(page.getByRole("listbox", { name: "에이전트 고르기" })).toContainText("맞는 에이전트가 없다");
});

test("공백 없이 글자에 붙은 @ 에서는 목록이 뜨지 않는다", async ({ page }) => {
  await page.goto("/");

  await composer(page).fill("me@example.com");

  await expect(composer(page)).toHaveValue("me@example.com");
  await expect(page.getByRole("listbox", { name: "에이전트 고르기" })).toHaveCount(0);
});

test("쓸 수 있는 에이전트가 없으면 알리고 입력창을 잠근다", async ({ page }) => {
  await page.route("**/api/agents", async (route) => {
    await route.fulfill({ json: [] });
  });
  await page.goto("/");

  await expect(page.getByText("쓸 수 있는 에이전트가 없다. 관리자에게 등록을 요청한다.")).toBeVisible();
  await expect(composer(page)).toBeDisabled();
  await expect(page.getByRole("radiogroup", { name: "에이전트" })).toHaveCount(0);
});

test("에이전트가 하나면 카드 없이 그 이름과 소개를 보인다", async ({ page }) => {
  await page.route("**/api/agents", async (route) => {
    await route.fulfill({ json: [agentRow("browser", "하나뿐인 비서", "혼자 일하는 비서다", ["하나뿐인 질문이다"])] });
  });
  await page.goto("/");

  const main = page.getByRole("main");
  await expect(main.getByText("하나뿐인 비서", { exact: true })).toBeVisible();
  await expect(main.getByText("혼자 일하는 비서다")).toBeVisible();
  await expect(page.getByRole("button", { name: "하나뿐인 질문이다", exact: true })).toBeVisible();
  await expect(page.getByRole("radiogroup")).toHaveCount(0);
  await expectNoHorizontalScroll(page);
});

test("새 대화에서 사진을 먼저 올리면 카드와 @ 목록이 잠긴다", async ({ page }) => {
  await page.goto("/");
  const browserCard = page.getByRole("radio", { name: "브라우저 비서" });
  await expect(browserCard).toHaveAttribute("aria-checked", "true");

  await page.getByTestId("attachment-input").setInputFiles([
    { name: "lock.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(1);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);

  // 빈 대화가 생겨도 메시지가 없는 동안은 새 대화 화면 모양 그대로다.
  await expect(page).toHaveURL(/\/c\/\d+$/);
  await expect(page.getByRole("heading", { level: 1, name: /무엇을 도와줄까요$/ })).toBeVisible();
  const flowCard = page.getByRole("radio", { name: "흐름 비서" });
  await expect(flowCard).toBeDisabled();
  await flowCard.click({ force: true });
  await expect(browserCard).toHaveAttribute("aria-checked", "true");
  await expect(flowCard).toHaveAttribute("aria-checked", "false");

  await composer(page).fill("@");
  await expect(composer(page)).toHaveValue("@");
  await expect(page.getByRole("listbox", { name: "에이전트 고르기" })).toHaveCount(0);
});

test("새 대화 화면에서 사진과 글을 보내면 입력창이 아래로 가도 사진이 메시지에 남는다", async ({ page }, testInfo) => {
  await page.goto("/");
  await expect(page.getByRole("radiogroup", { name: "에이전트" })).toBeVisible();

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
  await expect(page.getByRole("radiogroup", { name: "에이전트" })).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);

  // 새로 읽어도 사진이 남아 있어야 한다. 입력창이 새로 만들어졌다면 정리가 그 사진을 서버에서 지운다.
  await page.reload();
  await expectImageLoaded(page.getByTestId("user-message").last().getByTestId("message-attachment"));
});

test("사진 때문에 빈 대화를 만드는 동안에는 추천 질문을 누를 수 없어 대화가 하나만 생긴다", async ({ page }) => {
  let release!: () => void;
  const released = new Promise<void>((resolve) => {
    release = resolve;
  });
  const createdIds: number[] = [];
  // route 는 goto 보다 먼저 건다. 대화 목록을 읽는 GET 은 그대로 보낸다.
  await page.route((url) => url.pathname === "/api/chat/conversations", async (route) => {
    if (route.request().method() !== "POST") {
      await route.fallback();
      return;
    }
    await released;
    const response = await route.fetch();
    const body = (await response.json()) as { conversationId: number };
    createdIds.push(body.conversationId);
    await route.fulfill({ response, json: body });
  });
  await page.goto("/");
  const promptButton = page.getByRole("button", { name: PROMPT, exact: true });
  await expect(promptButton).toBeEnabled();

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
  await expect(page).toHaveURL(new RegExp(`/c/${createdIds[0]}$`));
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
  await expect(page).toHaveURL(new RegExp(`/c/${createdIds[0]}$`));
  expect(createdIds, "추천 질문을 보낸 뒤 만든 대화").toHaveLength(1);
});
