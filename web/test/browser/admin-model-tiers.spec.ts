import { expect, setSession, test } from "./fixtures.ts";
import type { Page } from "../../node_modules/@playwright/test/index.js";

/**
 * 관리자 「모델」 화면의 단계별 모델 칸이 그룹 목록에서만 고르는지 본다.
 *
 * 단계 정의와 목록은 응답을 바꿔 끼운다. 실제로 저장하면 다른 검사가 보는 그룹 단계가 바뀌므로 저장 요청도 가로채
 * 본문만 확인한다.
 */

type Tier = {
  tier: "INSTANT" | "MEDIUM" | "HIGH" | "EXTRA_HIGH" | "PRO";
  label: string;
  provider: string | null;
  model: string | null;
  reasoningEffort: string | null;
};

const TIERS: Tier[] = [
  { tier: "INSTANT", label: "Instant", provider: "openai-codex", model: "listed-fast", reasoningEffort: "low" },
  { tier: "MEDIUM", label: "Medium", provider: "openai-codex", model: "retired-model", reasoningEffort: "medium" },
  { tier: "HIGH", label: "High", provider: null, model: null, reasoningEffort: null },
  { tier: "EXTRA_HIGH", label: "Extra High", provider: null, model: null, reasoningEffort: null },
  { tier: "PRO", label: "Pro", provider: null, model: null, reasoningEffort: null },
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
  return page.getByRole("region", { name: "그룹 모델 설정" }).getByRole("group", { name: label, exact: true });
}

test("관리자 화면은 다섯 칸을 정해진 차례대로 그린다", async ({ page }) => {
  await stub(page, CATALOG);
  await page.goto("/admin/models");
  const region = page.getByRole("region", { name: "그룹 모델 설정" });
  for (const label of ["Instant", "Medium", "High", "Extra High", "Pro"]) {
    await expect(region.getByRole("group", { name: label, exact: true })).toBeVisible();
  }
});

test("단계별 모델은 그룹 목록에서 고르고 고른 provider 와 모델을 함께 저장한다", async ({ page }) => {
  const saved = await stub(page, CATALOG);
  await page.goto("/admin/models");

  const deep = tierFieldset(page, "High");
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
  expect(body.tiers.find((item) => item.tier === "HIGH")).toMatchObject({
    provider: "anthropic",
    model: "other-model",
    reasoningEffort: "high",
  });
});

test("저장된 모델이 목록에 없으면 그 값을 남겨 보이고 그대로 저장하거나 다른 모델로 바꿀 수 있다", async ({ page }) => {
  const saved = await stub(page, CATALOG);
  await page.goto("/admin/models");

  const balanced = tierFieldset(page, "Medium");
  const model = balanced.getByLabel("모델");
  await expect(model.locator("option:checked")).toHaveText("ChatGPT 구독 · retired-model (목록에 없음)");
  await expect(balanced.getByText("저장된 모델이 지금 목록에 없어요.", { exact: false })).toBeVisible();
  // 목록에 있는 단계에는 안내가 없다.
  await expect(tierFieldset(page, "Instant").getByText("목록에 없어요", { exact: false })).toHaveCount(0);

  await page.getByRole("region", { name: "그룹 모델 설정" }).getByRole("button", { name: "저장" }).click();
  await expect.poll(() => saved.length).toBe(1);
  expect((saved[0] as { tiers: Tier[] }).tiers.find((item) => item.tier === "MEDIUM")).toMatchObject({
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

  const fast = tierFieldset(page, "Instant");
  const model = fast.getByLabel("모델");
  await expect(model.locator("option:checked")).toHaveText("ChatGPT 구독 · listed-fast (목록에 없음)");
  // 비우기와 저장된 값 말고는 고를 것이 없다.
  await expect(model.locator("option")).toHaveCount(2);
  await expect(tierFieldset(page, "High").getByLabel("모델").locator("option")).toHaveCount(1);

  await model.selectOption("");
  await expect(fast.getByLabel("강도")).toBeDisabled();
  await section.getByRole("button", { name: "저장" }).click();
  await expect.poll(() => saved.length).toBe(1);
  expect((saved[0] as { tiers: Tier[] }).tiers.find((item) => item.tier === "INSTANT")).toMatchObject({
    provider: null,
    model: null,
    reasoningEffort: null,
  });
});

const REFRESHED_CATALOG = {
  ...CATALOG,
  providers: [
    { provider: "openai-codex", name: "ChatGPT 구독", models: ["listed-fast", "listed-deep", "brand-new"], reasoning: {} },
    CATALOG.providers[1],
  ],
};

type RefreshAnswer = { status: "REFRESHED" | "REUSED" | "FAILED"; refreshedAt: string | null } | { httpStatus: number };

/**
 * 다시 읽기 경로를 대역으로 두고, 다시 읽은 뒤의 그룹 목록을 새 모델이 든 것으로 바꾼다. 그룹 목록을 몇 번 읽었는지
 * 돌려준다. `gate` 를 주면 그것이 풀릴 때까지 다시 읽기가 답하지 않는다.
 */
async function stubRefresh(page: Page, answer: RefreshAnswer, gate?: Promise<void>): Promise<{ catalogReads: number }> {
  const counts = { catalogReads: 0 };
  let refreshed = false;
  await page.route(
    (url) => url.pathname === "/api/admin/model-catalog",
    (route) => {
      counts.catalogReads += 1;
      return route.fulfill({ json: { catalog: refreshed ? REFRESHED_CATALOG : CATALOG } });
    },
  );
  await page.route(
    (url) => url.pathname === "/api/admin/model-catalog/refresh",
    async (route) => {
      if (gate) await gate;
      if ("httpStatus" in answer) return route.fulfill({ status: answer.httpStatus, json: { code: "INTERNAL_ERROR" } });
      refreshed = answer.status !== "FAILED";
      return route.fulfill({ json: answer });
    },
  );
  return counts;
}

function modelOptions(page: Page, label: string) {
  return tierFieldset(page, label).getByLabel("모델").locator("option");
}

test("목록 새로 읽기를 누르면 읽는 동안 단추가 막히고, 끝나면 새 모델이 선택지에 생기며 시각이 보인다", async ({ page }) => {
  await stub(page, CATALOG);
  let release = () => {};
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await stubRefresh(page, { status: "REFRESHED", refreshedAt: "2026-01-01T00:00:00Z" }, gate);
  await page.goto("/admin/models");
  await expect(modelOptions(page, "High").filter({ hasText: "brand-new" })).toHaveCount(0);
  // 저장 전에 고친 단계 선택은 다시 읽은 뒤에도 남아야 한다.
  await tierFieldset(page, "High").getByLabel("모델").selectOption({ label: "listed-deep" });

  const button = page.getByTestId("model-catalog-refresh");
  await button.click();
  await expect(button).toBeDisabled();
  await expect(button).toHaveText("읽는 중");
  release();

  const notice = page.getByTestId("model-catalog-refresh-notice");
  await expect(notice).toContainText("모델 목록을 다시 읽었어요.");
  await expect(page.getByTestId("model-catalog-refreshed-at")).toContainText("2026년 1월 1일");
  await expect(button).toBeEnabled();
  await expect(modelOptions(page, "High").filter({ hasText: "brand-new" })).toHaveCount(1);
  await expect(tierFieldset(page, "High").getByLabel("모델").locator("option:checked")).toHaveText("listed-deep");
});

for (const [name, answer] of [
  ["FAILED 로 답하면", { status: "FAILED", refreshedAt: null }],
  ["요청이 실패하면", { httpStatus: 500 }],
] as [string, RefreshAnswer][]) {
  test(`목록 새로 읽기가 ${name} 선택지를 그대로 두고 실패를 알리며 목록을 다시 읽지 않는다`, async ({ page }) => {
    await stub(page, CATALOG);
    const counts = await stubRefresh(page, answer);
    await page.goto("/admin/models");
    await expect(modelOptions(page, "High").filter({ hasText: "listed-deep" })).toHaveCount(1);
    const before = counts.catalogReads;

    await page.getByTestId("model-catalog-refresh").click();

    const notice = page.getByTestId("model-catalog-refresh-notice");
    await expect(notice.getByRole("alert")).toHaveText("모델 목록을 다시 읽지 못했어요. 잠시 뒤 다시 눌러 주세요.");
    await expect(page.getByTestId("model-catalog-refreshed-at")).toHaveCount(0);
    await expect(modelOptions(page, "High").filter({ hasText: "brand-new" })).toHaveCount(0);
    await expect(modelOptions(page, "High").filter({ hasText: "listed-deep" })).toHaveCount(1);
    expect(counts.catalogReads, "실패 뒤 그룹 목록을 읽은 횟수").toBe(before);
  });
}

test("30초 안에 다시 누르면 새로 묻지 않았다고 알리고 마지막으로 다시 읽은 시각을 보인다", async ({ page }) => {
  await stub(page, CATALOG);
  await stubRefresh(page, { status: "REUSED", refreshedAt: "2026-01-01T00:00:00Z" });
  await page.goto("/admin/models");

  await page.getByTestId("model-catalog-refresh").click();

  const notice = page.getByTestId("model-catalog-refresh-notice");
  await expect(notice).toContainText("30초 안에는 새로 묻지 않아요.");
  await expect(notice).not.toContainText("다시 읽었어요");
  await expect(page.getByTestId("model-catalog-refreshed-at")).toContainText("마지막으로 다시 읽은 시각 2026년 1월 1일");
});

test("다시 읽은 시각이 없으면 시각을 그리지 않는다", async ({ page }) => {
  await stub(page, CATALOG);
  await stubRefresh(page, { status: "REUSED", refreshedAt: null });
  await page.goto("/admin/models");

  await page.getByTestId("model-catalog-refresh").click();

  await expect(page.getByTestId("model-catalog-refresh-notice")).toContainText("30초 안에는 새로 묻지 않아요.");
  await expect(page.getByTestId("model-catalog-refreshed-at")).toHaveCount(0);
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
