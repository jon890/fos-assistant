import { CONVERSATION_URL, expect, test } from "./fixtures.ts";
import type { Locator, Page } from "../../web/node_modules/@playwright/test/index.js";

/** 실제로 디코딩되는 1x1 PNG 다. 저장소에 이미지를 넣지 않으려고 바이트로 만들어 쓴다. */
const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

/** 입력칸이 이보다 좁아지면 한 줄에 몇 글자 들어가지 않는다. */
const MIN_TEXTAREA_WIDTH = 160;

function composer(page: Page): Locator {
  return page.getByRole("textbox", { name: "메시지" });
}

function picker(page: Page): Locator {
  return page.getByTestId("model-picker");
}

function dialog(page: Page): Locator {
  return page.getByRole("dialog", { name: "모델 고르기" });
}

function modelSelect(page: Page): Locator {
  return dialog(page).getByRole("combobox", { name: "모델", exact: true });
}

function effortSelect(page: Page): Locator {
  return dialog(page).getByRole("combobox", { name: "effort", exact: true });
}

async function sendAndWait(page: Page, text: string): Promise<void> {
  await composer(page).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByTestId("user-message").last()).toContainText(text);
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
}

async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow, "페이지가 가로로 밀린 폭(px)").toBeLessThanOrEqual(0);
}

test("모델과 effort 를 고르면 다음 보내기가 그 값을 싣고 다시 읽어도 단추에 남는다", async ({ page, hermes }, testInfo) => {
  await page.goto("/");
  await expect(picker(page)).toHaveText("기본");

  await picker(page).click();
  await expect(modelSelect(page).locator("option").first()).toHaveText("기본 (example-model)");
  await expect(modelSelect(page)).toBeEnabled();
  await modelSelect(page).selectOption({ label: "example-model" });
  await effortSelect(page).selectOption("high");
  await dialog(page).getByRole("button", { name: "적용" }).click();

  await expect(dialog(page)).toHaveCount(0);
  await expect(picker(page)).toHaveText("example-model · high");
  await sendAndWait(page, `모델 고르기 검사 ${testInfo.project.name}`);

  expect(await hermes.lastSubmittedRuntime(), "마지막 실행 요청의 모델 선택").toEqual({
    provider: "openai-codex",
    model: "example-model",
    reasoningEffort: "high",
  });

  await page.reload();
  await expect(picker(page)).toHaveText("example-model · high");
});

test("고르지 않고 보내면 실행 요청에 provider 와 model 이 빠진다", async ({ page, hermes }, testInfo) => {
  await page.goto("/");
  await expect(picker(page)).toHaveText("기본");

  await sendAndWait(page, `기본 모델 검사 ${testInfo.project.name}`);

  const runtime = await hermes.lastSubmittedRuntime();
  expect(runtime.provider, "기본값으로 보낸 실행의 provider").toBeUndefined();
  expect(runtime.model, "기본값으로 보낸 실행의 model").toBeUndefined();
  expect(runtime.reasoningEffort, "기본값으로 보낸 실행의 effort").toBeUndefined();
  await expect(picker(page)).toHaveText("기본");
});

test("effort 를 받지 않는 모델을 고르면 effort 를 고를 수 없다", async ({ page }) => {
  await page.goto("/");
  await picker(page).click();
  await expect(modelSelect(page)).toBeEnabled();
  await effortSelect(page).selectOption("high");

  await modelSelect(page).selectOption({ label: "example-model-mini" });

  await expect(effortSelect(page)).toBeDisabled();
  await expect(effortSelect(page)).toHaveValue("");
  await dialog(page).getByRole("button", { name: "적용" }).click();
  await expect(picker(page)).toHaveText("example-model-mini");
});

test("모델 목록을 읽지 못하면 창에 알리고 기본값으로는 보낼 수 있다", async ({ page, hermes }, testInfo) => {
  await page.route((url) => url.pathname === "/api/chat/model-options", (route) =>
    route.fulfill({ status: 502, json: { code: "HERMES_UNAVAILABLE", message: "모델 목록을 읽지 못했어요." } }));
  await page.goto("/");

  await picker(page).click();
  await expect(dialog(page)).toContainText("모델 목록을 불러오지 못했어요. 기본 모델로는 계속 보낼 수 있어요.");
  await expect(modelSelect(page).locator("option")).toHaveText(["기본"]);
  await expect(effortSelect(page)).toBeEnabled();
  await page.keyboard.press("Escape");
  await expect(dialog(page)).toHaveCount(0);

  await sendAndWait(page, `목록 실패 검사 ${testInfo.project.name}`);
  const runtime = await hermes.lastSubmittedRuntime();
  expect(runtime.model, "목록을 읽지 못한 뒤 보낸 실행의 model").toBeUndefined();
});

test("저장이 실패하면 단추가 이전 값으로 돌아가고 알린다", async ({ page }) => {
  await page.route((url) => /\/api\/chat\/conversations\/[^/]+\/model$/.test(url.pathname), (route) =>
    route.fulfill({ status: 500, json: { code: "INTERNAL_ERROR", message: "요청을 처리하지 못했어요." } }));
  await page.goto("/");

  await picker(page).click();
  await expect(modelSelect(page)).toBeEnabled();
  await effortSelect(page).selectOption("low");
  await dialog(page).getByRole("button", { name: "적용" }).click();

  await expect(page.getByTestId("model-picker-error")).toHaveText("모델을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.");
  await expect(picker(page)).toHaveText("기본");
});

test("새 대화에서 모델을 먼저 고르면 에이전트 카드가 잠기고 사진을 올려 보낼 수 있다", async ({ page }, testInfo) => {
  await page.goto("/");
  const browserCard = page.getByRole("radio", { name: "브라우저 비서" });
  await expect(browserCard).toHaveAttribute("aria-checked", "true");

  await picker(page).click();
  await expect(modelSelect(page)).toBeEnabled();
  await effortSelect(page).selectOption("medium");
  await dialog(page).getByRole("button", { name: "적용" }).click();

  // 빈 대화가 생겨 에이전트가 정해졌다. 메시지가 없는 동안은 새 대화 화면 모양 그대로다.
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(picker(page)).toHaveText("기본 · medium");
  await expect(page.getByRole("radio", { name: "흐름 비서" })).toBeDisabled();

  await page.getByTestId("attachment-input").setInputFiles([
    { name: "model-first.png", mimeType: "image/png", buffer: PNG_1X1 },
  ]);
  await expect(page.getByTestId("attachment-previews").locator("> div")).toHaveCount(1);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);

  await sendAndWait(page, `모델 먼저 고르기 검사 ${testInfo.project.name}`);
  await expect(page.getByTestId("user-message").last().getByTestId("message-attachment")).toHaveCount(1);
  await expect(page.getByTestId("user-message")).toHaveCount(1);
});

test("긴 모델 이름을 골라도 가로로 넘치지 않고 입력칸 폭이 남는다", async ({ page }) => {
  const longModel = `example-model-${"x".repeat(100)}`;
  await page.route((url) => url.pathname === "/api/chat/model-options", (route) => route.fulfill({
    status: 200,
    json: {
      defaultProvider: "openai-codex",
      defaultModel: "example-model",
      providers: [{
        provider: "openai-codex",
        name: "OpenAI Codex",
        models: ["example-model", longModel],
        reasoningCapable: { "example-model": true },
      }],
      reasoningEfforts: ["low", "medium", "high", "xhigh", "max"],
    },
  }));
  await page.goto("/");

  await picker(page).click();
  await expect(modelSelect(page)).toBeEnabled();
  await modelSelect(page).selectOption({ label: longModel });
  await effortSelect(page).selectOption("xhigh");
  await dialog(page).getByRole("button", { name: "적용" }).click();
  await expect(picker(page)).toHaveText(`${longModel} · xhigh`);
  await expect(page).toHaveURL(CONVERSATION_URL);

  await expectNoHorizontalScroll(page);
  const textarea = await composer(page).boundingBox();
  expect(textarea?.width ?? 0, "입력칸 폭(px)").toBeGreaterThanOrEqual(MIN_TEXTAREA_WIDTH);
  const button = await picker(page).boundingBox();
  const shell = await page.getByTestId("composer-shell").boundingBox();
  expect((button?.x ?? 0) + (button?.width ?? 0), "모델 단추의 오른쪽 끝(px)")
    .toBeLessThanOrEqual((shell?.x ?? 0) + (shell?.width ?? 0));
});
