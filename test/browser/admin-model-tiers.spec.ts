import { expect, setSession, test } from "./fixtures.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

/**
 * 관리자 「모델」 화면의 단계별 모델 칸이 그룹 목록에서만 고르는지 본다.
 *
 * 단계 정의와 목록은 응답을 바꿔 끼운다. 실제로 저장하면 다른 검사가 보는 그룹 단계가 바뀌므로 저장 요청도 가로채
 * 본문만 확인한다.
 */

type Tier = {
  tier: "FAST" | "BALANCED" | "DEEP";
  label: string;
  provider: string | null;
  model: string | null;
  reasoningEffort: string | null;
};

const TIERS: Tier[] = [
  { tier: "FAST", label: "빠르게", provider: "openai-codex", model: "listed-fast", reasoningEffort: "low" },
  { tier: "BALANCED", label: "균형", provider: "openai-codex", model: "retired-model", reasoningEffort: "medium" },
  { tier: "DEEP", label: "깊게", provider: null, model: null, reasoningEffort: null },
];

const CATALOG = {
  providers: [
    { provider: "openai-codex", name: "ChatGPT 구독", models: ["listed-fast", "listed-deep"], reasoning: {} },
    { provider: "anthropic", name: "Anthropic", models: ["other-model"], reasoning: {} },
  ],
  reasoningEfforts: ["low", "medium", "high", "xhigh", "max"],
};

/** 단계 정의와 그룹 목록을 바꿔 끼우고, 저장 요청의 본문을 모은다. */
async function stub(page: Page, catalog: typeof CATALOG | null): Promise<unknown[]> {
  const saved: unknown[] = [];
  await page.route(
    (url) => url.pathname === "/api/admin/model-tiers",
    (route) => route.fulfill({ json: { tiers: TIERS, groupDefaultTier: null } }),
  );
  await page.route(
    (url) => url.pathname === "/api/admin/model-catalog",
    (route) => route.fulfill({ json: { catalog } }),
  );
  await page.route(
    (url) => url.pathname === "/api/chat/model-tiers/group",
    (route) => {
      saved.push(route.request().postDataJSON());
      return route.fulfill({ status: 204 });
    },
  );
  return saved;
}

function tierFieldset(page: Page, label: string) {
  return page.getByRole("region", { name: "그룹 모델 설정" }).getByRole("group", { name: label });
}

test("단계별 모델은 그룹 목록에서 고르고 고른 provider 와 모델을 함께 저장한다", async ({ page }) => {
  const saved = await stub(page, CATALOG);
  await page.goto("/admin/models");

  const deep = tierFieldset(page, "깊게");
  const model = deep.getByLabel("모델");
  await expect(model).toHaveValue("");
  await expect(deep.getByLabel("강도")).toBeDisabled();
  // 입력칸이 아니라 고르는 칸이다. 목록에 없는 문자열을 쓸 자리가 없다.
  await expect(deep.getByRole("textbox")).toHaveCount(0);
  await expect(model.locator("optgroup")).toHaveCount(2);

  await model.selectOption({ label: "other-model" });
  await deep.getByLabel("강도").selectOption("high");
  await page.getByRole("region", { name: "그룹 모델 설정" }).getByRole("button", { name: "저장" }).click();

  await expect.poll(() => saved.length).toBe(1);
  const body = saved[0] as { tiers: Tier[] };
  expect(body.tiers.find((item) => item.tier === "DEEP")).toMatchObject({
    provider: "anthropic",
    model: "other-model",
    reasoningEffort: "high",
  });
});

test("저장된 모델이 목록에 없으면 그 값을 남겨 보이고 그대로 저장하거나 다른 모델로 바꿀 수 있다", async ({ page }) => {
  const saved = await stub(page, CATALOG);
  await page.goto("/admin/models");

  const balanced = tierFieldset(page, "균형");
  const model = balanced.getByLabel("모델");
  await expect(model.locator("option:checked")).toHaveText("ChatGPT 구독 · retired-model (목록에 없음)");
  await expect(balanced.getByText("저장된 모델이 지금 목록에 없어요.", { exact: false })).toBeVisible();
  // 목록에 있는 단계에는 안내가 없다.
  await expect(tierFieldset(page, "빠르게").getByText("목록에 없어요", { exact: false })).toHaveCount(0);

  await page.getByRole("region", { name: "그룹 모델 설정" }).getByRole("button", { name: "저장" }).click();
  await expect.poll(() => saved.length).toBe(1);
  expect((saved[0] as { tiers: Tier[] }).tiers.find((item) => item.tier === "BALANCED")).toMatchObject({
    provider: "openai-codex",
    model: "retired-model",
  });

  await model.selectOption({ label: "listed-deep" });
  await expect(balanced.getByText("저장된 모델이 지금 목록에 없어요.", { exact: false })).toHaveCount(0);
  await expect(model.locator("option", { hasText: "retired-model" })).toHaveCount(0);
});

test("목록을 읽지 못하면 저장된 값만 보이고 비울 수는 있다", async ({ page }) => {
  const saved = await stub(page, null);
  await page.goto("/admin/models");

  const section = page.getByRole("region", { name: "그룹 모델 설정" });
  await expect(section.getByRole("alert")).toContainText("모델 목록을 불러오지 못했어요.");

  const fast = tierFieldset(page, "빠르게");
  const model = fast.getByLabel("모델");
  await expect(model.locator("option:checked")).toHaveText("ChatGPT 구독 · listed-fast (목록에 없음)");
  // 비우기와 저장된 값 말고는 고를 것이 없다.
  await expect(model.locator("option")).toHaveCount(2);
  await expect(tierFieldset(page, "깊게").getByLabel("모델").locator("option")).toHaveCount(1);

  await model.selectOption("");
  await expect(fast.getByLabel("강도")).toBeDisabled();
  await section.getByRole("button", { name: "저장" }).click();
  await expect.poll(() => saved.length).toBe(1);
  expect((saved[0] as { tiers: Tier[] }).tiers.find((item) => item.tier === "FAST")).toMatchObject({
    provider: null,
    model: null,
    reasoningEffort: null,
  });
});

test("MEMBER 역할은 그룹 모델 목록을 받지 못한다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  const response = await page.request.get("/api/admin/model-catalog");
  expect(response.status()).toBe(403);
});

test("관리자는 실제 Control Plane 에서 그룹 모델 목록을 받는다", async ({ page }) => {
  await page.goto("/admin/models");
  const response = await page.request.get("/api/admin/model-catalog");
  expect(response.ok()).toBeTruthy();
  const body = (await response.json()) as { catalog: typeof CATALOG | null };
  expect(body.catalog, "가짜 Hermes 는 모든 profile 에 목록을 준다").not.toBeNull();
  expect(body.catalog?.reasoningEfforts).toContain("medium");
});
