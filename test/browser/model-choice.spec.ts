import { CONVERSATION_URL, expect, test } from "./fixtures.ts";
import type {
  Locator,
  Page,
} from "../../web/node_modules/@playwright/test/index.js";

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

function tierSettings(page: Page): Locator {
  return page.getByTestId("model-tier-settings");
}

test("에이전트가 없는 새 대화는 모델 조회 없이 기본 단계 표시를 유지한다", async ({
  page,
}) => {
  const modelRequests: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (
      url.pathname === "/api/chat/model-tiers" ||
      url.pathname === "/api/chat/model-options"
    ) {
      modelRequests.push(request.url());
    }
  });
  await page.route("**/api/agents", (route) => route.fulfill({ json: [] }));

  await page.goto("/");
  for (const [tier, label] of [
    ["fast", "빠르게"],
    ["balanced", "균형"],
    ["deep", "깊게"],
  ]) {
    const button = page.getByTestId(`model-tier-${tier}`);
    await expect(button).toHaveText(label);
    await expect(button).toBeDisabled();
  }
  await expect(tierSettings(page)).toBeDisabled();
  expect(modelRequests).toEqual([]);
});

async function openSettings(page: Page): Promise<void> {
  if ((await picker(page).count()) === 0) await tierSettings(page).click();
  await expect(picker(page)).toBeVisible();
}

async function openAdvancedPicker(page: Page): Promise<void> {
  await openSettings(page);
  await picker(page).click();
}

async function closeSettings(page: Page): Promise<void> {
  await expect(dialog(page)).toHaveCount(0);
  const settings = page.getByRole("dialog", {
    name: "모델 단계 설정",
    exact: true,
  });
  await settings
    .getByRole("button", { name: "모델 단계 설정 닫기", exact: true })
    .click();
  await expect(settings).toHaveCount(0);
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

const TIERS = {
  tiers: [
    {
      tier: "FAST",
      label: "빠르게",
      provider: null,
      model: "example-fast",
      reasoningEffort: "low",
    },
    {
      tier: "BALANCED",
      label: "균형",
      provider: null,
      model: "example-balanced",
      reasoningEffort: "medium",
    },
    {
      tier: "DEEP",
      label: "깊게",
      provider: null,
      model: "example-deep",
      reasoningEffort: "high",
    },
  ],
  userDefaultTier: null,
  groupDefaultTier: null,
  admin: false,
};

/**
 * 저장이 끝났는지 본다. 단추는 저장하는 동안 고른 값을 먼저 보이므로 글자만으로는 저장됐는지 알 수 없다.
 * 저장이 끝나 대화 목록의 그 줄이 바뀌어야 단추가 다시 눌린다.
 */
async function expectSaved(page: Page): Promise<void> {
  await openSettings(page);
  await expect(picker(page)).toBeEnabled();
  await expect(page.getByTestId("model-picker-error")).toHaveCount(0);
}

function isConversationList(url: URL): boolean {
  return url.pathname === "/api/chat/conversations";
}

/** 모델 선택을 적은 빈 대화를 API 로 만든다. 화면을 거치지 않아 목록 읽기가 검사 도중에 끼어들지 않는다. */
async function createConversationWithChoice(
  page: Page,
  choice: {
    provider: string | null;
    model: string | null;
    reasoningEffort: string | null;
  },
): Promise<string> {
  const created = await page.request.post("/api/chat/conversations", {
    data: { agentCode: "browser" },
  });
  expect(
    created.ok(),
    `빈 대화를 만들지 못했다: ${created.status()}`,
  ).toBeTruthy();
  const { conversationId } = (await created.json()) as {
    conversationId: string;
  };
  const chosen = await page.request.put(
    `/api/chat/conversations/${conversationId}/model`,
    { data: choice },
  );
  expect(chosen.ok(), `모델을 고르지 못했다: ${chosen.status()}`).toBeTruthy();
  return conversationId;
}

async function sendAndWait(page: Page, text: string): Promise<void> {
  await composer(page).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByTestId("user-message").last()).toContainText(text);
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({
    timeout: 30_000,
  });
  // 답이 끝난 뒤에 돌려준다. 답이 오는 동안 이어 보낸 글은 대기 메시지로 쌓인다.
  await expect(
    page.getByTestId("composer-shell").getByRole("button", { name: "중지" }),
  ).toHaveCount(0, { timeout: 30_000 });
}

async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(
    () =>
      document.documentElement.scrollWidth -
      document.documentElement.clientWidth,
  );
  expect(overflow, "페이지가 가로로 밀린 폭(px)").toBeLessThanOrEqual(0);
}

test("모델과 effort 를 고르면 다음 보내기가 그 값을 싣고 다시 읽어도 단추에 남는다", async ({
  page,
  hermes,
}, testInfo) => {
  await page.goto("/");
  await openSettings(page);
  await expect(picker(page)).toHaveText("기본");

  await openAdvancedPicker(page);
  await expect(modelSelect(page).locator("option").first()).toHaveText(
    "기본 (example-model)",
  );
  await expect(modelSelect(page)).toBeEnabled();
  await modelSelect(page).selectOption({ label: "example-model" });
  await effortSelect(page).selectOption("high");
  await dialog(page).getByRole("button", { name: "적용" }).click();

  await expect(dialog(page)).toHaveCount(0);
  await openSettings(page);
  await expect(picker(page)).toHaveText("example-model · high");
  await closeSettings(page);
  await sendAndWait(page, `모델 고르기 검사 ${testInfo.project.name}`);

  expect(
    await hermes.lastSubmittedRuntime(),
    "마지막 실행 요청의 모델 선택",
  ).toEqual({
    provider: "openai-codex",
    model: "example-model",
    reasoningEffort: "high",
  });

  await page.reload();
  await openSettings(page);
  await expect(picker(page)).toHaveText("example-model · high");
});

test("고르지 않고 보내면 실행 요청에 provider 와 model 이 빠진다", async ({
  page,
  hermes,
}, testInfo) => {
  await page.goto("/");
  await openSettings(page);
  await expect(picker(page)).toHaveText("기본");
  await page.keyboard.press("Escape");

  await sendAndWait(page, `기본 모델 검사 ${testInfo.project.name}`);

  const runtime = await hermes.lastSubmittedRuntime();
  expect(runtime.provider, "기본값으로 보낸 실행의 provider").toBeUndefined();
  expect(runtime.model, "기본값으로 보낸 실행의 model").toBeUndefined();
  expect(
    runtime.reasoningEffort,
    "기본값으로 보낸 실행의 effort",
  ).toBeUndefined();
  await openSettings(page);
  await expect(picker(page)).toHaveText("기본");
});

/** effort 칸의 선택지 글자다. 「끄기」 는 `none` 이다 */
const EFFORTS_WITH_NONE = [
  "기본",
  "끄기",
  "low",
  "medium",
  "high",
  "xhigh",
  "max",
];
const EFFORTS_WITHOUT_NONE = ["기본", "low", "medium", "high", "xhigh", "max"];

test("끄기는 끄기 지원이 확인된 모델에서만 effort 선택지에 있다", async ({
  page,
}) => {
  await page.goto("/");
  // 단계 단추 화면과 그 설정 창에는 지원 미확인 문구를 더하지 않는다.
  await expect(page.getByTestId("model-tier-picker")).toBeVisible();
  await expect(page.getByTestId("effort-support-unknown")).toHaveCount(0);
  await openSettings(page);
  await expect(page.getByTestId("effort-support-unknown")).toHaveCount(0);

  await picker(page).click();
  await expect(modelSelect(page)).toBeEnabled();
  // 「기본」 은 대역의 기본 모델(example-model)이고 끄기 지원이 확인된 모델이다.
  await expect(effortSelect(page).locator("option")).toHaveText(
    EFFORTS_WITH_NONE,
  );
  await modelSelect(page).selectOption({ label: "example-fast" });
  await expect(effortSelect(page).locator("option")).toHaveText(
    EFFORTS_WITHOUT_NONE,
  );
  await modelSelect(page).selectOption({ label: "example-deep" });
  await expect(effortSelect(page).locator("option")).toHaveText(
    EFFORTS_WITHOUT_NONE,
  );
  await modelSelect(page).selectOption({ label: "example-model" });
  await expect(effortSelect(page).locator("option")).toHaveText(
    EFFORTS_WITH_NONE,
  );
  await expect(page.getByTestId("effort-support-unknown")).toHaveCount(0);
});

test("끄기를 고른 채 끄기를 받지 않는 모델로 바꾸면 effort 가 기본으로 돌아가고 null 로 저장된다", async ({
  page,
}) => {
  await page.goto("/");
  await openAdvancedPicker(page);
  await expect(modelSelect(page)).toBeEnabled();
  await effortSelect(page).selectOption({ label: "끄기" });
  await expect(effortSelect(page)).toHaveValue("none");

  await modelSelect(page).selectOption({ label: "example-deep" });
  await expect(effortSelect(page)).toHaveValue("");
  await expect(effortSelect(page).locator("option:checked")).toHaveText("기본");
  await dialog(page).getByRole("button", { name: "적용" }).click();

  await expect(page).toHaveURL(CONVERSATION_URL);
  await expectSaved(page);
  await expect(picker(page)).toHaveText("example-deep");
  await page.reload();
  await openSettings(page);
  await expect(picker(page)).toHaveText("example-deep");
});

test("지원 미확인 모델은 effort 를 고르게 두고 알리며 지원하지 않는 모델은 막고 알리지 않는다", async ({
  page,
}) => {
  await page.goto("/");
  await openAdvancedPicker(page);
  await expect(modelSelect(page)).toBeEnabled();

  await modelSelect(page).selectOption({ label: "example-balanced" });
  await expect(effortSelect(page)).toBeEnabled();
  await expect(page.getByTestId("effort-support-unknown")).toHaveText(
    "이 모델의 effort 지원을 확인하지 못했어요. 골라도 모델이 무시할 수 있어요.",
  );
  // 지원 미확인이어도 끄기 지원이 확인되지 않았으니 끄기는 없다.
  await expect(effortSelect(page).locator("option")).toHaveText(
    EFFORTS_WITHOUT_NONE,
  );

  await modelSelect(page).selectOption({ label: "example-model-mini" });
  await expect(effortSelect(page)).toBeDisabled();
  await expect(page.getByTestId("effort-support-unknown")).toHaveCount(0);
});

test("끄기를 적용해 보내면 실행 요청에 none 이 실리고 기본으로 되돌리면 effort 가 빠진다", async ({
  page,
  hermes,
}, testInfo) => {
  await page.goto("/");
  await openAdvancedPicker(page);
  await expect(modelSelect(page)).toBeEnabled();
  await effortSelect(page).selectOption({ label: "끄기" });
  await dialog(page).getByRole("button", { name: "적용" }).click();
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expectSaved(page);
  // 단추는 대화에 적힌 값을 그대로 보인다.
  await expect(picker(page)).toHaveText("기본 · none");
  await closeSettings(page);
  await sendAndWait(page, `끄기 검사 ${testInfo.project.name}`);
  expect(
    (await hermes.lastSubmittedRuntime()).reasoningEffort,
    "끄기를 고른 실행의 effort",
  ).toBe("none");

  await openAdvancedPicker(page);
  await expect(modelSelect(page)).toBeEnabled();
  await effortSelect(page).selectOption({ label: "기본" });
  await dialog(page).getByRole("button", { name: "적용" }).click();
  await expectSaved(page);
  await expect(picker(page)).toHaveText("기본");
  await closeSettings(page);
  await sendAndWait(page, `기본 effort 검사 ${testInfo.project.name}`);
  expect(
    (await hermes.lastSubmittedRuntime()).reasoningEffort,
    "「기본」 으로 되돌린 실행의 effort",
  ).toBeUndefined();
});

test("저장된 끄기가 있는 대화는 선택지에 끄기가 하나만 있고 바꾸지 않고 적용해도 남는다", async ({
  page,
}) => {
  const conversationId = await createConversationWithChoice(page, {
    provider: "openai-codex",
    model: "example-model",
    reasoningEffort: "none",
  });
  const saves: string[] = [];
  page.on("request", (request) => {
    if (
      request.method() === "PUT" &&
      /\/api\/chat\/conversations\/[^/]+\/model$/.test(
        new URL(request.url()).pathname,
      )
    )
      saves.push(request.url());
  });
  await page.goto(`/chat/${conversationId}`);
  await openSettings(page);
  await expect(picker(page)).toHaveText("example-model · none");

  await picker(page).click();
  await expect(modelSelect(page)).toBeEnabled();
  await expect(effortSelect(page).locator("option")).toHaveText(
    EFFORTS_WITH_NONE,
  );
  await expect(effortSelect(page)).toHaveValue("none");
  await dialog(page).getByRole("button", { name: "적용" }).click();

  await expectSaved(page);
  await expect(picker(page)).toHaveText("example-model · none");
  expect(saves, "바꾸지 않고 적용했을 때 나간 저장 요청").toEqual([]);
  await page.reload();
  await openSettings(page);
  await expect(picker(page)).toHaveText("example-model · none");
});

test("단계를 고르면 빈 대화에 단계 선택을 저장하고 에이전트 기본값으로 돌아갈 수 있다", async ({
  page,
}) => {
  const savedBodies: unknown[] = [];
  let savedConversation: Record<string, unknown> | null = null;

  await page.route(
    (url) => isConversationList(url),
    async (route) => {
      if (route.request().method() !== "GET") {
        await route.fallback();
        return;
      }
      await route.fulfill({
        json: savedConversation === null ? [] : [savedConversation],
      });
    },
  );
  await page.route(
    (url) => url.pathname === "/api/chat/model-tiers",
    (route) => route.fulfill({ json: TIERS }),
  );
  await page.route(
    (url) =>
      /\/api\/chat\/conversations\/[^/]+\/model-tier$/.test(url.pathname),
    async (route) => {
      savedBodies.push(route.request().postDataJSON());
      savedConversation = {
        id: route.request().url().split("/").at(-2),
        title: "새 대화",
        agentCode: "browser",
        agentName: "브라우저 비서",
        updatedAt: new Date().toISOString(),
        provider: null,
        model: null,
        reasoningEffort: null,
        modelSelectionMode: savedBodies.length === 1 ? "TIER" : "DEFAULT",
        modelTier: savedBodies.length === 1 ? "DEEP" : null,
      };
      await route.fulfill({ json: savedConversation });
    },
  );

  await page.goto("/");
  await expect(page.getByTestId("model-tier-picker")).toContainText("빠르게");
  await expect(page.getByTestId("model-tier-picker")).toContainText("균형");
  await expect(page.getByTestId("model-tier-picker")).toContainText("깊게");
  await tierSettings(page).click();
  await expect(
    page.getByRole("button", { name: "그룹 모델 설정" }),
  ).toHaveCount(0);
  await page.keyboard.press("Escape");

  await page.getByTestId("model-tier-deep").click();
  await expect(page.getByTestId("model-tier-deep")).toHaveAttribute(
    "data-variant",
    "secondary",
  );
  await expect(page.getByTestId("model-tier-deep")).toBeEnabled();
  await tierSettings(page).click();
  await page.getByTestId("model-tier-profile-default").click();
  await expect(page.getByTestId("model-tier-deep")).toHaveAttribute(
    "data-variant",
    "ghost",
  );
  await expect(page.getByTestId("model-tier-profile-default")).toBeEnabled();

  await expect.poll(() => savedBodies.length).toBe(2);
  expect(savedBodies).toEqual([
    { mode: "TIER", tier: "DEEP" },
    { mode: "DEFAULT", tier: null },
  ]);
});

test("관리자도 대화 화면의 설정 창에서는 그룹 모델 설정을 보지 않는다", async ({
  page,
}) => {
  await page.route(
    (url) => url.pathname === "/api/chat/model-tiers",
    (route) =>
      route.fulfill({
        json: { ...TIERS, admin: true },
      }),
  );

  await page.goto("/");
  await tierSettings(page).click();
  await expect(picker(page)).toBeVisible();
  await expect(
    page.getByRole("button", { name: "그룹 모델 설정" }),
  ).toHaveCount(0);
});

test("그룹 모델 설정은 그룹 기본 단계를 단계별 모델보다 위에 보인다", async ({
  page,
}) => {
  await page.route(
    (url) =>
      url.pathname === "/api/chat/model-tiers" ||
      url.pathname === "/api/admin/model-tiers",
    (route) => route.fulfill({ json: { ...TIERS, admin: true } }),
  );

  await page.goto("/admin/models");

  const groupSection = page.getByRole("region", { name: "그룹 모델 설정" });
  const defaultHeading = groupSection.getByRole("heading", {
    name: "그룹 기본 단계",
  });
  const tiersHeading = groupSection.getByRole("heading", {
    name: "단계별 모델",
  });
  await expect(defaultHeading).toBeVisible();
  await expect(tiersHeading).toBeVisible();
  const defaultBox = await defaultHeading.boundingBox();
  const tiersBox = await tiersHeading.boundingBox();
  expect(defaultBox).not.toBeNull();
  expect(tiersBox).not.toBeNull();
  expect(defaultBox!.y).toBeLessThan(tiersBox!.y);
});

test("내 기본값 창은 정하지 않았을 때 도는 기준을 알린다", async ({ page }) => {
  await page.route(
    (url) => url.pathname === "/api/chat/model-tiers",
    (route) => route.fulfill({ json: { ...TIERS, groupDefaultTier: "DEEP" } }),
  );

  await page.goto("/");
  await tierSettings(page).click();
  await page.getByRole("button", { name: "내 기본값" }).click();
  await expect(page.getByRole("dialog", { name: "내 기본값" })).toContainText(
    "정하지 않으면 그룹 기본 단계(깊게)로 돌아요.",
  );
});

test("그룹 기본 단계가 없으면 내 기본값 창이 에이전트 기본 모델을 알린다", async ({
  page,
}) => {
  await page.route(
    (url) => url.pathname === "/api/chat/model-tiers",
    (route) => route.fulfill({ json: { ...TIERS, groupDefaultTier: null } }),
  );

  await page.goto("/");
  await tierSettings(page).click();
  await page.getByRole("button", { name: "내 기본값" }).click();
  await expect(page.getByRole("dialog", { name: "내 기본값" })).toContainText(
    "정하지 않으면 에이전트 기본 모델로 돌아요.",
  );
});

test("내 기본값 저장이 실패하면 창을 유지하고 다시 저장할 수 있다", async ({
  page,
}) => {
  let attempts = 0;
  await page.route(
    (url) => url.pathname === "/api/chat/model-tiers/default",
    (route) => {
      attempts += 1;
      return route.fulfill({ status: attempts === 1 ? 500 : 204 });
    },
  );

  await page.goto("/");
  await tierSettings(page).click();
  await page.getByRole("button", { name: "내 기본값" }).click();

  const defaultsDialog = page.getByRole("dialog", { name: "내 기본값" });
  await defaultsDialog.getByRole("button", { name: "빠르게" }).click();
  const failedAlert = defaultsDialog.getByRole("alert");
  await expect(failedAlert).toHaveText(
    "내 기본값을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  );
  await expect(defaultsDialog).toBeVisible();

  await defaultsDialog.getByRole("button", { name: "빠르게" }).click();
  await expect(defaultsDialog).toHaveCount(0);
  await expect(failedAlert).toHaveCount(0);
});

test("내 기본값을 저장하면 고르지 않은 대화의 단계 단추에 기본 표시가 생긴다", async ({
  page,
}) => {
  await page.route(
    (url) => url.pathname === "/api/chat/model-tiers",
    (route) => route.fulfill({ json: { ...TIERS, groupDefaultTier: "DEEP" } }),
  );
  await page.route(
    (url) => url.pathname === "/api/chat/model-tiers/default",
    (route) => route.fulfill({ status: 204 }),
  );

  await page.goto("/");
  // 내 기본값이 없으면 그룹 기본 단계가 적용된다.
  await expect(page.getByTestId("model-tier-deep")).toHaveAttribute(
    "data-inherited",
    "true",
  );
  await expect(page.getByTestId("model-tier-deep")).toContainText("기본");
  await expect(page.getByTestId("model-tier-deep")).toHaveAttribute(
    "aria-pressed",
    "false",
  );

  await tierSettings(page).click();
  await page.getByRole("button", { name: "내 기본값" }).click();
  await page
    .getByRole("dialog", { name: "내 기본값" })
    .getByRole("button", { name: "빠르게" })
    .click();
  await page.keyboard.press("Escape");

  await expect(page.getByTestId("model-tier-fast")).toHaveAttribute(
    "data-inherited",
    "true",
  );
  await expect(page.getByTestId("model-tier-deep")).not.toHaveAttribute(
    "data-inherited",
  );
});

test("그룹 단계 저장이 실패하면 입력값을 유지하고 다시 저장할 수 있다", async ({
  page,
}) => {
  let attempts = 0;
  await page.route(
    (url) =>
      url.pathname === "/api/chat/model-tiers" ||
      url.pathname === "/api/admin/model-tiers",
    (route) => route.fulfill({ json: { ...TIERS, admin: true } }),
  );
  await page.route(
    (url) => url.pathname === "/api/chat/model-tiers/group",
    (route) => {
      attempts += 1;
      return route.fulfill({ status: attempts === 1 ? 500 : 204 });
    },
  );

  await page.goto("/admin/models");

  const groupSection = page.getByRole("region", { name: "그룹 모델 설정" });
  const model = groupSection.getByLabel("모델", { exact: true }).first();
  await model.fill("example-retry");
  await groupSection.getByRole("button", { name: "저장" }).click();
  const failedAlert = groupSection.getByRole("alert");
  await expect(failedAlert).toHaveText(
    "그룹 모델 설정을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  );
  await expect(model).toHaveValue("example-retry");

  await groupSection.getByRole("button", { name: "저장" }).click();
  await expect(
    groupSection.getByText("저장했어요", { exact: true }),
  ).toBeVisible();
  await expect(failedAlert).toHaveCount(0);
});

test("단계 매핑이 비어 있으면 관리자 설정에서 기본값 실행을 안내한다", async ({
  page,
}) => {
  const savedBodies: unknown[] = [];
  await page.route(
    (url) =>
      url.pathname === "/api/chat/model-tiers" ||
      url.pathname === "/api/admin/model-tiers",
    (route) =>
      route.fulfill({
        json: {
          ...TIERS,
          admin: true,
          groupDefaultTier: "BALANCED",
          tiers: TIERS.tiers.map((item) => ({
            ...item,
            provider: null,
            model: null,
            reasoningEffort: null,
          })),
        },
      }),
  );
  await page.route(
    (url) => url.pathname === "/api/chat/model-tiers/group",
    (route) => {
      savedBodies.push(route.request().postDataJSON());
      return route.fulfill({ status: 204 });
    },
  );

  await page.goto("/");
  await expect(page.getByTestId("model-tier-fast")).toBeEnabled();
  await expect(page.getByTestId("model-tier-balanced")).toBeEnabled();
  await expect(page.getByTestId("model-tier-deep")).toBeEnabled();

  await tierSettings(page).click();
  const settingsDialog = page.getByRole("dialog", { name: "모델 단계 설정" });
  await expect(settingsDialog).toBeVisible();
  await expect(
    settingsDialog.getByText("단계별 모델을 아직 정하지 않았어요."),
  ).toHaveCount(0);

  await page.goto("/admin/models");
  const groupSection = page.getByRole("region", { name: "그룹 모델 설정" });
  await expect(
    groupSection.getByText("단계별 모델을 아직 정하지 않았어요."),
  ).toBeVisible();
  await expect(
    groupSection.getByLabel("모델", { exact: true }).first(),
  ).toHaveValue("");
  await expect(
    groupSection.getByLabel("강도", { exact: true }).first(),
  ).toHaveValue("");
  await expect(groupSection.getByLabel("그룹 기본 단계")).toHaveValue(
    "BALANCED",
  );
  await groupSection.getByRole("button", { name: "저장" }).click();

  await expect
    .poll(() => savedBodies)
    .toEqual([
      {
        tiers: [
          {
            tier: "FAST",
            label: "빠르게",
            provider: null,
            model: null,
            reasoningEffort: null,
          },
          {
            tier: "BALANCED",
            label: "균형",
            provider: null,
            model: null,
            reasoningEffort: null,
          },
          {
            tier: "DEEP",
            label: "깊게",
            provider: null,
            model: null,
            reasoningEffort: null,
          },
        ],
        defaultTier: "BALANCED",
      },
    ]);
});

test("effort 를 받지 않는 모델을 고르면 effort 를 고를 수 없다", async ({
  page,
}) => {
  await page.goto("/");
  await openAdvancedPicker(page);
  await expect(modelSelect(page)).toBeEnabled();
  await effortSelect(page).selectOption("high");

  await modelSelect(page).selectOption({ label: "example-model-mini" });

  await expect(effortSelect(page)).toBeDisabled();
  await expect(effortSelect(page)).toHaveValue("");
  await dialog(page).getByRole("button", { name: "적용" }).click();
  await expectSaved(page);
  await expect(picker(page)).toHaveText("example-model-mini");
  await page.reload();
  await openSettings(page);
  await expect(picker(page)).toHaveText("example-model-mini");
});

test("모델 목록을 읽지 못하면 창에 알리고 기본값으로는 보낼 수 있다", async ({
  page,
  hermes,
}, testInfo) => {
  await page.route(
    (url) => url.pathname === "/api/chat/model-options",
    (route) =>
      route.fulfill({
        status: 502,
        json: {
          code: "HERMES_UNAVAILABLE",
          message: "모델 목록을 읽지 못했어요.",
        },
      }),
  );
  await page.goto("/");

  await openAdvancedPicker(page);
  await expect(dialog(page)).toContainText(
    "모델 목록을 불러오지 못했어요. 기본 모델로는 계속 보낼 수 있어요.",
  );
  await expect(modelSelect(page).locator("option")).toHaveText(["기본"]);
  await expect(effortSelect(page)).toBeEnabled();
  await page.keyboard.press("Escape");
  await expect(dialog(page)).toHaveCount(0);
  await closeSettings(page);

  await sendAndWait(page, `목록 실패 검사 ${testInfo.project.name}`);
  const runtime = await hermes.lastSubmittedRuntime();
  expect(
    runtime.model,
    "목록을 읽지 못한 뒤 보낸 실행의 model",
  ).toBeUndefined();
});

test("저장이 실패하면 단추가 이전 값으로 돌아가고 알린다", async ({ page }) => {
  await page.route(
    (url) => /\/api\/chat\/conversations\/[^/]+\/model$/.test(url.pathname),
    (route) =>
      route.fulfill({
        status: 500,
        json: { code: "INTERNAL_ERROR", message: "요청을 처리하지 못했어요." },
      }),
  );
  await page.goto("/");

  await openAdvancedPicker(page);
  await expect(modelSelect(page)).toBeEnabled();
  await effortSelect(page).selectOption("low");
  await dialog(page).getByRole("button", { name: "적용" }).click();

  await openSettings(page);
  await expect(page.getByTestId("model-picker-error")).toHaveText(
    "모델을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  );
  await expect(page.getByTestId("model-picker-error")).toHaveAttribute(
    "role",
    "alert",
  );
  await expect(picker(page)).toHaveText("기본");

  // 다시 고르러 창을 열면 지난 실패 안내는 사라진다.
  await openAdvancedPicker(page);
  await expect(dialog(page)).toBeVisible();
  await expect(page.getByTestId("model-picker-error")).toHaveCount(0);
});

test("빈 대화를 만들지 못하면 입력창의 안내 하나만 보인다", async ({
  page,
}) => {
  await page.route(
    (url) => isConversationList(url),
    (route) =>
      route.request().method() === "POST"
        ? route.fulfill({
            status: 500,
            json: {
              code: "INTERNAL_ERROR",
              message: "요청을 처리하지 못했어요.",
            },
          })
        : route.fallback(),
  );
  await page.goto("/");

  await openAdvancedPicker(page);
  await expect(modelSelect(page)).toBeEnabled();
  await effortSelect(page).selectOption("low");
  await dialog(page).getByRole("button", { name: "적용" }).click();

  await expect(page.getByTestId("attachment-notice")).toHaveText(
    "대화를 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  );
  await openSettings(page);
  await expect(picker(page)).toBeEnabled();
  await expect(page.getByTestId("model-picker-error")).toHaveCount(0);
  await expect(picker(page)).toHaveText("기본");
});

test("새 대화에서 모델을 먼저 고르면 에이전트 카드가 잠기고 사진을 올려 보낼 수 있다", async ({
  page,
}, testInfo) => {
  // 슬롯 대기만 30초까지 허용된다. 응답 45초와 모델 선택·메시지 전송 시간을 따로 확보한다.
  test.setTimeout(90_000);
  await page.route("**/api/chat/conversations/*/attachments", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    const response = await route.fetch();
    // 기존 UI assertion의 기본 5초보다 늦게 실제 응답 본문을 전달한다.
    await new Promise((resolve) => setTimeout(resolve, 5_500));
    await route.fulfill({ response });
  });
  await page.goto("/");
  const browserCard = page.getByRole("radio", { name: "브라우저 비서" });
  await expect(browserCard).toHaveAttribute("aria-checked", "true");

  await openAdvancedPicker(page);
  await expect(modelSelect(page)).toBeEnabled();
  await effortSelect(page).selectOption("medium");
  await dialog(page).getByRole("button", { name: "적용" }).click();

  // 빈 대화가 생겨 에이전트가 정해졌다. 메시지가 없는 동안은 새 대화 화면 모양 그대로다.
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expectSaved(page);
  await expect(picker(page)).toHaveText("기본 · medium");
  await closeSettings(page);
  await expect(page.getByRole("radio", { name: "흐름 비서" })).toBeDisabled();

  const uploaded = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/chat\/conversations\/[^/]+\/attachments$/.test(response.url()),
    { timeout: 45_000 },
  );
  await page
    .getByTestId("attachment-input")
    .setInputFiles([
      { name: "model-first.png", mimeType: "image/png", buffer: PNG_1X1 },
    ]);
  await expect(
    page.getByTestId("attachment-previews").locator("> div"),
  ).toHaveCount(1);
  // 헤더 도착 뒤 본문까지 확인한다. 오류 응답도 uploading을 지우므로 완료 표시만으로 성공을 판단하지 않는다.
  const response = await uploaded;
  const attachment = (await response.json()) as { id?: number };
  expect(response.status(), "사진 업로드 응답 상태").toBe(200);
  expect(attachment.id, "사진 업로드 응답의 첨부 식별자").toBeGreaterThan(0);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews")).toHaveText("");

  await sendAndWait(page, `모델 먼저 고르기 검사 ${testInfo.project.name}`);
  await expect(
    page.getByTestId("user-message").last().getByTestId("message-attachment"),
  ).toHaveCount(1);
  await expect(page.getByTestId("user-message")).toHaveCount(1);
});

test("새 대화에서 모델을 먼저 고른 뒤 사진 업로드가 실패하면 오류를 알리고 사진을 빼고 보낸다", async ({
  page,
}, testInfo) => {
  test.setTimeout(90_000);
  await page.route("**/api/chat/conversations/*/attachments", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    await new Promise((resolve) => setTimeout(resolve, 5_500));
    await route.fulfill({
      status: 502,
      json: { code: "INTERNAL_ERROR", message: "사진을 올리지 못했어요." },
    });
  });
  await page.goto("/");
  await openAdvancedPicker(page);
  await expect(modelSelect(page)).toBeEnabled();
  await effortSelect(page).selectOption("medium");
  await dialog(page).getByRole("button", { name: "적용" }).click();
  await expectSaved(page);
  await closeSettings(page);

  const uploaded = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/chat\/conversations\/[^/]+\/attachments$/.test(response.url()),
    { timeout: 45_000 },
  );
  await page.getByTestId("attachment-input").setInputFiles([
    {
      name: "model-first-failed.png",
      mimeType: "image/png",
      buffer: PNG_1X1,
    },
  ]);
  await expect(page.getByTestId("attachment-uploading")).toBeVisible();
  const response = await uploaded;
  expect(response.status()).toBe(502);
  expect((await response.json()).code).toBe("INTERNAL_ERROR");
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews")).toContainText(
    "사진을 올리지 못했어요.",
  );
  await page.getByRole("button", { name: "사진 지우기" }).click();
  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);
  await sendAndWait(
    page,
    `모델 먼저 고르기 업로드 실패 검사 ${testInfo.project.name}`,
  );
  await expect(page.getByTestId("user-message")).toHaveCount(1);
  await expect(
    page.getByTestId("user-message").last().getByTestId("message-attachment"),
  ).toHaveCount(0);
});

test("긴 모델 이름을 골라도 가로로 넘치지 않고 입력칸 폭이 남는다", async ({
  page,
}) => {
  const longModel = `example-model-${"x".repeat(100)}`;
  await page.route(
    (url) => url.pathname === "/api/chat/model-options",
    (route) =>
      route.fulfill({
        status: 200,
        json: {
          defaultProvider: "openai-codex",
          defaultModel: "example-model",
          providers: [
            {
              provider: "openai-codex",
              name: "OpenAI Codex",
              models: ["example-model", longModel],
              reasoning: {
                "example-model": { support: "SUPPORTED", disable: "UNKNOWN" },
              },
            },
          ],
          reasoningEfforts: ["low", "medium", "high", "xhigh", "max"],
        },
      }),
  );
  await page.goto("/");

  await openAdvancedPicker(page);
  await expect(modelSelect(page)).toBeEnabled();
  await modelSelect(page).selectOption({ label: longModel });
  await effortSelect(page).selectOption("xhigh");
  await dialog(page).getByRole("button", { name: "적용" }).click();
  await expect(page).toHaveURL(CONVERSATION_URL);
  // 저장하는 동안에도 단추는 고른 값을 보인다. 저장이 끝난 뒤의 모양을 재야 저장된 값의 폭을 본다.
  await expectSaved(page);
  await expect(picker(page)).toHaveText(`${longModel} · xhigh`);
  await closeSettings(page);

  await expectNoHorizontalScroll(page);
  const textarea = await composer(page).boundingBox();
  expect(textarea?.width ?? 0, "입력칸 폭(px)").toBeGreaterThanOrEqual(
    MIN_TEXTAREA_WIDTH,
  );
});

test("목록을 읽지 못해도 대화에 적힌 모델이 남고 effort 만 바꿔도 모델이 그대로다", async ({
  page,
}) => {
  const conversationId = await createConversationWithChoice(page, {
    provider: "openai-codex",
    model: "example-model",
    reasoningEffort: "high",
  });
  await page.route(
    (url) => url.pathname === "/api/chat/model-options",
    (route) =>
      route.fulfill({
        status: 502,
        json: {
          code: "HERMES_UNAVAILABLE",
          message: "모델 목록을 읽지 못했어요.",
        },
      }),
  );
  await page.goto(`/chat/${conversationId}`);
  await openSettings(page);
  await expect(picker(page)).toHaveText("example-model · high");

  await openAdvancedPicker(page);
  await expect(page.getByTestId("model-options-failed")).toBeVisible();
  await expect(modelSelect(page).locator("option")).toHaveText([
    "기본",
    "example-model",
  ]);
  await expect(modelSelect(page).locator("option:checked")).toHaveText(
    "example-model",
  );
  await effortSelect(page).selectOption("low");
  await dialog(page).getByRole("button", { name: "적용" }).click();

  await expectSaved(page);
  await expect(picker(page)).toHaveText("example-model · low");
  await page.reload();
  await openSettings(page);
  await expect(picker(page)).toHaveText("example-model · low");
});

test("대화 목록이 오기 전에는 이미 있는 대화의 모델 단추를 막는다", async ({
  page,
}) => {
  const conversationId = await createConversationWithChoice(page, {
    provider: "openai-codex",
    model: "example-model",
    reasoningEffort: "high",
  });
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route(
    (url) => isConversationList(url),
    async (route) => {
      if (route.request().method() === "GET") await gate;
      await route.fallback();
    },
  );
  await page.goto(`/chat/${conversationId}`);

  // 에이전트 목록이 오기 전에는 다른 까닭으로도 막혀 있다. 사진 단추가 보이면 에이전트가 정해진 뒤다.
  await expect(page.getByTestId("attachment-input")).toBeAttached();
  // 적힌 값을 모르는 채 고르면 「기본」 으로 보고 저장해 적힌 모델을 지운다.
  await expect(tierSettings(page)).toBeDisabled();
  release();
  await expect(tierSettings(page)).toBeEnabled();
  await openSettings(page);
  await expect(picker(page)).toHaveText("example-model · high");
});

test("모델 저장 때문에 버린 대화 목록 응답은 한 번 더 읽는다", async ({
  page,
}) => {
  // 빈 대화를 만든 뒤에 나가는 목록 읽기 하나를 저장이 끝날 때까지 붙잡는다. 첫 화면의 목록 읽기는 붙잡지 않는다.
  let created = false;
  let taken = false;
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let markHeld!: () => void;
  const held = new Promise<void>((resolve) => {
    markHeld = resolve;
  });
  await page.route(
    (url) => isConversationList(url),
    async (route) => {
      const method = route.request().method();
      if (method === "POST") created = true;
      if (method === "GET" && created && !taken) {
        taken = true;
        markHeld();
        await gate;
      }
      await route.fallback();
    },
  );
  await page.goto("/");

  await openAdvancedPicker(page);
  await expect(modelSelect(page)).toBeEnabled();
  await effortSelect(page).selectOption("low");
  const saved = page.waitForResponse((response) =>
    /\/api\/chat\/conversations\/[^/]+\/model$/.test(
      new URL(response.url()).pathname,
    ),
  );
  await dialog(page).getByRole("button", { name: "적용" }).click();
  await held;
  expect((await saved).ok(), "모델 저장 응답").toBeTruthy();

  // 붙잡은 응답은 저장보다 먼저 나가 버려진다. 버린 채 두면 다른 줄의 제목과 순서가 옛 값으로 남는다.
  const again = page.waitForRequest(
    (request) =>
      request.method() === "GET" && isConversationList(new URL(request.url())),
  );
  release();
  await again;
  await expectSaved(page);
  await expect(picker(page)).toHaveText("기본 · low");
});
